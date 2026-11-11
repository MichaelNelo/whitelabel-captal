# Jaeger: receives the traces every service sends over OTLP, and shows them.
#
# Internal only. It has no target group and no listener rule: the services send to
# `jaeger.<namespace>`, and the UI is reached through the Tailscale proxy, which advertises the
# VPC's routes to the tailnet. Its security group admits nothing else: the UI has no authentication
# of its own, and a trace carries every path a guest asked for.
#
# One task, in-memory storage: Jaeger's default configuration, which keeps the most recent traces
# up to its `max_traces` and loses them when the task is replaced. Enough to follow what happened;
# not a record.

locals {
  name = "${var.project_name}-${var.environment}-jaeger"
}

resource "aws_cloudwatch_log_group" "jaeger" {
  name              = "/ecs/${local.name}"
  retention_in_days = 14
}

resource "aws_security_group" "jaeger" {
  name        = local.name
  description = "Jaeger: OTLP from the services, the UI from the Tailscale proxy"
  vpc_id      = var.vpc_id

  ingress {
    description     = "OTLP over gRPC from the services that trace"
    from_port       = var.otlp_port
    to_port         = var.otlp_port
    protocol        = "tcp"
    security_groups = var.sender_security_group_ids
  }

  dynamic "ingress" {
    for_each = length(var.ui_security_group_ids) > 0 ? [1] : []
    content {
      description     = "The UI, from the tailnet through the Tailscale proxy"
      from_port       = var.ui_port
      to_port         = var.ui_port
      protocol        = "tcp"
      security_groups = var.ui_security_group_ids
    }
  }

  # Pulling its image from its public registry, and nothing else it starts.
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

resource "aws_ecs_task_definition" "jaeger" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([{
    name      = "jaeger"
    image     = var.image
    essential = true

    portMappings = [
      { containerPort = var.otlp_port, protocol = "tcp" },
      { containerPort = var.ui_port, protocol = "tcp" },
    ]

    # Its default configuration listens on `localhost` unless told otherwise.
    environment = [
      { name = "JAEGER_LISTEN_HOST", value = "0.0.0.0" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.jaeger.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "jaeger"
      }
    }
  }])
}

resource "aws_service_discovery_service" "jaeger" {
  name = "jaeger"

  dns_config {
    namespace_id = var.service_discovery_namespace_id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  # No `health_check_custom_config`, as for the relay: ECS deregisters a task that stops, and
  # LocalStack drops an empty one, which would replace this resource on every apply.
}

resource "aws_ecs_service" "jaeger" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.jaeger.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.jaeger.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.jaeger.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Stop-then-start: its storage is in memory, so a second task would only split the traces.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
