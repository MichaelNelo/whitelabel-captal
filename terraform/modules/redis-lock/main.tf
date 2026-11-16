# Redis, for the locks taken as Redis describes Redlock, on a majority of independent instances: the
# one that keeps each location to one operation at a time, which the backoffice takes, and the one
# that keeps the migrations to one migrator, which the backoffice and every location's API take as
# they start.
#
# Each instance is its own service with its own name, `redis-lock-<n>.<namespace>`, since Redlock
# asks for instances that fail apart, not a replicated cluster. Internal only: its security group
# admits the services that take locks and nothing else.
#
# In memory only, and nothing evicted. A lock is worth nothing past its time, so there is nothing to
# keep across a restart; an instance that restarts comes back empty, and its locks are held, for as
# long as they last, by the instances that did not. A Fargate task takes longer to start than a lock
# lasts, which is the delay Redlock asks of an instance that restarts without persistence.

locals {
  name = "${var.project_name}-${var.environment}-redis-lock"
}

resource "aws_cloudwatch_log_group" "redis" {
  name              = "/ecs/${local.name}"
  retention_in_days = 14
}

resource "aws_security_group" "redis" {
  name        = local.name
  # AWS takes no apostrophe here: letters, digits, spaces and `._-:/()#,@[]+=&;{}!$*` only.
  description = "Redis for the locks, from the services that take them"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from the services that take locks"
    from_port       = var.port
    to_port         = var.port
    protocol        = "tcp"
    security_groups = var.caller_security_group_ids
  }

  # Pulling its image from the public registry, and nothing else it starts.
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

resource "aws_ecs_task_definition" "redis" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([{
    name      = "redis"
    image     = var.image
    essential = true

    # No snapshot and no append-only file: see the head of this file.
    command = [
      "redis-server",
      "--port", tostring(var.port),
      "--save", "",
      "--appendonly", "no",
      "--maxmemory-policy", "noeviction",
    ]

    # `hostPort` as rqlite's: the same port under awsvpc, and in LocalStack the one the host reaches
    # the instance on, where `api.dev` takes the migrations' lock.
    portMappings = [{ containerPort = var.port, hostPort = var.port, protocol = "tcp" }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.redis.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "redis"
      }
    }
  }])
}

resource "aws_service_discovery_service" "redis" {
  count = var.instances
  name  = "redis-lock-${count.index}"

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

resource "aws_ecs_service" "redis" {
  count           = var.instances
  name            = "${local.name}-${count.index}"
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.redis.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.redis.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.redis[count.index].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Stop-then-start: two tasks behind one name would be two instances answering for one vote.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
