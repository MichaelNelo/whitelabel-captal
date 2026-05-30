locals {
  name_prefix = "${var.project_name}-${var.environment}-portal-dispatcher"
}

# ─── Security group: egress to rqlite + AWS APIs ─────────────────────────────
resource "aws_security_group" "dispatcher" {
  name        = local.name_prefix
  vpc_id      = var.vpc_id
  description = "Captive portal dispatcher Lambda: egress to rqlite + AWS APIs"

  # Egress to rqlite HTTP API (port 4001 inside the cluster).
  egress {
    description     = "rqlite HTTP API"
    from_port       = 4001
    to_port         = 4001
    protocol        = "tcp"
    security_groups = [var.rqlite_security_group_id]
  }

  # Egress for CloudWatch Logs and any other AWS API calls. Using 0.0.0.0/0
  # because VPC interface endpoints for logs aren't provisioned here.
  egress {
    description = "AWS APIs (CloudWatch Logs, etc.)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = local.name_prefix
  }
}

# Reciprocal ingress on the rqlite SG so this Lambda can reach port 4001.
# Lives here (not in modules/rqlite) to avoid a module-level dependency cycle
# (rqlite needing dispatcher SG and vice versa). The rqlite module exposes its
# SG ID as a plain output, which we consume as a string variable here.
resource "aws_vpc_security_group_ingress_rule" "rqlite_from_dispatcher" {
  security_group_id            = var.rqlite_security_group_id
  referenced_security_group_id = aws_security_group.dispatcher.id
  from_port                    = 4001
  to_port                      = 4001
  ip_protocol                  = "tcp"
  description                  = "Portal dispatcher Lambda"
}

# ─── IAM role: Lambda VPC execution ──────────────────────────────────────────
resource "aws_iam_role" "dispatcher" {
  name = local.name_prefix

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dispatcher_vpc" {
  role       = aws_iam_role.dispatcher.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# ─── Lambda function ─────────────────────────────────────────────────────────
data "archive_file" "dispatcher" {
  type        = "zip"
  output_path = "${path.module}/dispatcher.zip"

  source {
    content  = file("${path.module}/dispatcher.py")
    filename = "dispatcher.py"
  }
}

resource "aws_lambda_function" "dispatcher" {
  function_name    = local.name_prefix
  role             = aws_iam_role.dispatcher.arn
  handler          = "dispatcher.handler"
  runtime          = "python3.12"
  filename         = data.archive_file.dispatcher.output_path
  source_code_hash = data.archive_file.dispatcher.output_base64sha256
  timeout          = 10
  memory_size      = 128

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = [aws_security_group.dispatcher.id]
  }

  environment {
    variables = {
      RQLITE_URL      = "http://rqlite.${var.service_discovery_namespace_name}:4001"
      CLOUDFRONT_HOST = var.cloudfront_host
    }
  }
}

# ─── ALB target group + Lambda invoke permission ─────────────────────────────
resource "aws_lb_target_group" "dispatcher" {
  name        = local.name_prefix
  target_type = "lambda"
}

resource "aws_lambda_permission" "alb" {
  statement_id  = "AllowALBInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.dispatcher.function_name
  principal     = "elasticloadbalancing.amazonaws.com"
  source_arn    = aws_lb_target_group.dispatcher.arn
}

resource "aws_lb_target_group_attachment" "dispatcher" {
  target_group_arn = aws_lb_target_group.dispatcher.arn
  target_id        = aws_lambda_function.dispatcher.arn
  depends_on       = [aws_lambda_permission.alb]
}
