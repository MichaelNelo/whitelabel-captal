# The change data capture relay: rqlite's leader posts every change set to it, and the services
# that follow the database subscribe to it over gRPC.
#
# Internal only. It has no target group and no listener rule: rqlite and the subscribers reach it
# as `cdc.<namespace>`, and its security group admits nothing else.

locals {
  name = "${var.project_name}-${var.environment}-cdc"
}

resource "aws_cloudwatch_log_group" "cdc" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

# HTTP from rqlite, gRPC from the tasks that subscribe: the backoffice and every location's API,
# which share the ECS tasks group. Both inline: neither group names this one, so there is no cycle.
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

  ingress {
    description     = "Change feed subscriptions"
    from_port       = var.grpc_port
    to_port         = var.grpc_port
    protocol        = "tcp"
    security_groups = [var.subscriber_security_group_id]
  }

  # Pulling its image from ECR, and nothing else it starts.
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

  container_definitions = jsonencode([{
    name      = "cdc"
    image     = var.image
    essential = true

    portMappings = [
      { containerPort = var.http_port, protocol = "tcp" },
      { containerPort = var.grpc_port, protocol = "tcp" },
    ]

    environment = [
      { name = "CDC_PORT", value = tostring(var.http_port) },
      { name = "CDC_GRPC_PORT", value = tostring(var.grpc_port) },
      { name = "CDC_BUFFER", value = tostring(var.buffer) },
    ]

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

resource "aws_service_discovery_service" "cdc" {
  name = "cdc"

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

# One task. A second would split rqlite's posts from the subscriptions: the leader posts to
# whichever address the name resolves to, and a subscriber follows whichever it resolved.
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

  service_registries {
    registry_arn = aws_service_discovery_service.cdc.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Stop-then-start. A rolling deploy would leave both tasks behind `cdc.<namespace>` for a while:
  # a subscriber that connected to the new one while rqlite still posted to the old would miss
  # those change sets with no gap to tell it. With no overlap, rqlite retries its posts while the
  # new task starts, and every subscriber reconnects to it and gets a gap, since its instance is new.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
