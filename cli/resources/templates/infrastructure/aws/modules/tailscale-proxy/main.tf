locals {
  name_prefix = "${var.project_name}-${var.environment}-tailscale-proxy"
  proxy_port  = 1055
  dns_name    = "tailscale-proxy.${var.service_discovery_namespace_name}"
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "tailscale_proxy" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = 30
}

# Service Discovery Service — registra `tailscale-proxy.<namespace>` (A record)
resource "aws_service_discovery_service" "tailscale_proxy" {
  name = "tailscale-proxy"

  dns_config {
    namespace_id = var.service_discovery_namespace_id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# Security Group — ingress sólo desde app tasks, egress libre (Tailscale necesita
# salida a controlplane.tailscale.com:443 + UDP/3478 STUN + DERP relays HTTPS).
resource "aws_security_group" "tailscale_proxy" {
  name        = "${local.name_prefix}-sg"
  description = "HTTP CONNECT proxy for UniFi authorization (Tailscale userspace mode)"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP CONNECT proxy from app tasks"
    from_port       = local.proxy_port
    to_port         = local.proxy_port
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  egress {
    description = "Allow all outbound traffic (Tailscale controlplane + DERP + LAN via tailnet)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-sg"
  }
}

# IAM Execution Role — pull image + read secret
resource "aws_iam_role" "execution" {
  name = "${local.name_prefix}-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "${local.name_prefix}-secrets"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = var.ts_authkey_secret_arn
      }
    ]
  })
}

# IAM Task Role — vacío, sin permisos AWS extra (Tailscale corre userspace)
resource "aws_iam_role" "task" {
  name = "${local.name_prefix}-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

# Task Definition
resource "aws_ecs_task_definition" "tailscale_proxy" {
  family                   = local.name_prefix
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "tailscale"
      image     = "tailscale/tailscale:stable"
      essential = true

      portMappings = [
        {
          containerPort = local.proxy_port
          hostPort      = local.proxy_port
          protocol      = "tcp"
        }
      ]

      environment = concat(
        [
          # Userspace mode — required: Fargate does not allow tun/tap.
          { name = "TS_USERSPACE", value = "true" },
          # Expose HTTP CONNECT proxy on the container port (consumed by the API).
          { name = "TS_OUTBOUND_HTTP_PROXY_LISTEN", value = "0.0.0.0:${local.proxy_port}" },
          # Hostname shown in Tailscale admin.
          { name = "TS_HOSTNAME", value = "${var.project_name}-${var.environment}-proxy" },
          # --accept-routes: pick up subnet advertisements from the on-prem VM router.
          { name = "TS_EXTRA_ARGS", value = "--accept-routes" },
          # Fargate filesystem is read-only outside /tmp; state must live there.
          { name = "TS_STATE_DIR", value = "/tmp/tailscale" }
        ],
        # Cosmetic — changes when TF rotates the auth key, forcing ECS to roll.
        var.ts_authkey_secret_version_id == null ? [] : [
          { name = "TS_AUTHKEY_SECRET_VERSION", value = var.ts_authkey_secret_version_id }
        ]
      )

      secrets = [
        { name = "TS_AUTHKEY", valueFrom = var.ts_authkey_secret_arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.tailscale_proxy.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "tailscale"
        }
      }
    }
  ])
}

# ECS Service
resource "aws_ecs_service" "tailscale_proxy" {
  name            = local.name_prefix
  cluster         = var.ecs_cluster_id
  task_definition = aws_ecs_task_definition.tailscale_proxy.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.tailscale_proxy.id]
    assign_public_ip = var.assign_public_ip
  }

  service_registries {
    registry_arn = aws_service_discovery_service.tailscale_proxy.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_controller {
    type = "ECS"
  }

  # Stop-then-start: el ephemeral Tailscale auth key implica que sólo un nodo a la
  # vez debería estar logueado. 0/100 cumple; el gap de ~60s lo absorben los
  # retries del UnifiAuthorizationHandler (phase queda en Ready hasta nuevo /api/finish).
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
