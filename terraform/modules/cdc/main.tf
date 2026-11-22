# The change data capture relay: rqlite's leader posts every change set to it, and it publishes
# each to the topic of every table it touches, which the tasks that follow a table read on queues of
# their own.
#
# Internal only. It has no target group and no listener rule: rqlite reaches it as
# `cdc.<namespace>`, and its security group admits nothing else.

data "aws_caller_identity" "current" {}

locals {
  name = "${var.project_name}-${var.environment}-cdc"

  # A table's topic's ARN but for the table's name, which the relay and every subscriber append.
  topic_prefix = "arn:aws:sns:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${local.name}-"
}

resource "aws_cloudwatch_log_group" "cdc" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

# One standard topic per table, which every change set touching that table goes to, whole. A table
# rather than a filter policy on one topic, because a task subscribes on every start, and a new
# filter policy takes up to 15 minutes to apply, delivering nobody says what meanwhile. Standard
# rather than FIFO: no subscriber needs the order or the deduplication, and a FIFO topic admits 100
# subscriptions, too few for one per task.
resource "aws_sns_topic" "table" {
  for_each = toset(var.tables)

  name = "${local.name}-${each.key}"
}

# What the relay runs as: publishing to its topics, and nothing else.
resource "aws_iam_role" "task" {
  name = "${local.name}-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

data "aws_iam_policy_document" "publish" {
  statement {
    sid       = "PublishChanges"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [for topic in aws_sns_topic.table : topic.arn]
  }
}

resource "aws_iam_role_policy" "publish" {
  name   = "${local.name}-publish"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.publish.json
}

# HTTP from rqlite, and nothing else: the subscribers read the topics, never the relay. Inline,
# since rqlite's group does not name this one.
resource "aws_security_group" "cdc" {
  name        = local.name
  description = "Change data capture relay"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Change data capture from rqlite"
    from_port       = var.http_port
    to_port         = var.http_port
    protocol        = "tcp"
    security_groups = [var.rqlite_security_group_id]
  }

  # Pulling its image from ECR, and publishing to SNS.
  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name}-sg"
  }
}

resource "aws_ecs_task_definition" "cdc" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "cdc"
    image     = var.image
    essential = true

    portMappings = [
      { containerPort = var.http_port, protocol = "tcp" },
    ]

    # Healthy once it answers, which is what a deploy waits for before it stops the task this one
    # replaces. The image is the JRE's, which carries curl.
    healthCheck = {
      command     = ["CMD-SHELL", "curl -sf http://localhost:${var.http_port}/health || exit 1"]
      interval    = 10
      timeout     = 5
      retries     = 3
      startPeriod = 30
    }

    environment = concat([
      { name = "CDC_PORT", value = tostring(var.http_port) },
      { name = "CDC_TOPIC_PREFIX", value = local.topic_prefix },
      ], var.otlp_endpoint == "" ? [] : [
      { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = var.otlp_endpoint },
      { name = "OTEL_SERVICE_NAME", value = "captal-cdc" },
    ])

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.cdc.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "cdc"
      }
    }
  }])
}

# None where Cloud Map is off: then `modules/local-discovery` keeps the name, the service's
# `DiscoveryName` tag.
resource "aws_service_discovery_service" "cdc" {
  count = var.service_discovery_namespace_id == "" ? 0 : 1
  name  = "cdc"

  dns_config {
    namespace_id = var.service_discovery_namespace_id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  # No `health_check_custom_config`: the task has no health check to report through it, and ECS
  # deregisters a task that stops. LocalStack also drops an empty one, which would replace this
  # resource on every apply.
}

# One task, which is enough: the relay keeps nothing, and while it is replaced or gone rqlite retries
# its posts, for about six minutes before it drops one.
resource "aws_ecs_service" "cdc" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.cdc.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.cdc.id]
    assign_public_ip = true
  }

  dynamic "service_registries" {
    for_each = aws_service_discovery_service.cdc[*].arn

    content {
      registry_arn = service_registries.value
    }
  }

  tags = {
    DiscoveryName = "cdc"
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Overlapped: the new task starts, and the old one stops once the new one is healthy. Both behind
  # `cdc.<namespace>` meanwhile is harmless, since either publishes whatever rqlite posts to it, and
  # a post cut short as the old one stops is posted again. The old one gets the default 30 seconds
  # between SIGTERM and SIGKILL, far more than a post takes.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
}
