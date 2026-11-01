# A stand-in for a venue's UniFi controller, for the local configuration only (`enable_unifi_fake`):
# what a location's API calls to grant and revoke a guest's access, answered and recorded, so the
# end-to-end features can check what a guest got. `controller.py` is the whole of it, run by the
# public Python image: there is nothing to build.
#
# Reached by the locations' tasks as `unifi.<namespace>` on `controller_port`, over HTTPS as a
# controller answers, with a self-signed certificate the API's client accepts as it accepts a real
# one's. Its record is on `records_port`, which LocalStack publishes on the host.

locals {
  name = "${var.project_name}-${var.environment}-unifi-fake"
  host = "unifi.${var.service_discovery_namespace_name}"
}

resource "tls_private_key" "controller" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_self_signed_cert" "controller" {
  private_key_pem       = tls_private_key.controller.private_key_pem
  validity_period_hours = 87600
  dns_names             = [local.host]
  allowed_uses          = ["digital_signature", "key_encipherment", "server_auth"]

  subject {
    common_name = local.host
  }
}

resource "aws_cloudwatch_log_group" "controller" {
  name              = "/ecs/${local.name}"
  retention_in_days = 7
}

resource "aws_security_group" "controller" {
  name        = local.name
  description = "UniFi stand-in, local only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Location APIs"
    from_port       = var.controller_port
    to_port         = var.controller_port
    protocol        = "tcp"
    security_groups = [var.caller_security_group_id]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_ecs_task_definition" "controller" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([{
    name      = "unifi"
    image     = var.image
    essential = true
    command   = ["python", "-u", "-c", "import os; exec(os.environ['CONTROLLER'])"]

    portMappings = [
      { containerPort = var.controller_port, hostPort = var.controller_port, protocol = "tcp" },
      { containerPort = var.records_port, hostPort = var.records_port, protocol = "tcp" },
    ]

    environment = [
      { name = "CONTROLLER", value = file("${path.module}/controller.py") },
      { name = "CONTROLLER_PORT", value = tostring(var.controller_port) },
      { name = "RECORDS_PORT", value = tostring(var.records_port) },
      { name = "TLS_CERT", value = tls_self_signed_cert.controller.cert_pem },
      { name = "TLS_KEY", value = tls_private_key.controller.private_key_pem },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.controller.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "unifi"
      }
    }
  }])
}

resource "aws_service_discovery_service" "controller" {
  name = "unifi"

  dns_config {
    namespace_id = var.service_discovery_namespace_id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }
}

resource "aws_ecs_service" "controller" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.controller.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.controller.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.controller.arn
  }

  # Its record lives in memory, and on a port of the host: one task, and the next only once the
  # last is gone.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
