locals {
  name_prefix = "${var.project_name}-${var.environment}-rqlite"
  http_port   = 4001
  raft_port   = 4002
  dns_name    = "rqlite.${var.service_discovery_namespace_name}"

  # Single-instance branch: EFS-backed persistence, stop-then-start, FARGATE.
  # Multi-instance branch: Raft quorum on ephemeral storage, rolling deploy, FARGATE_SPOT.
  single_node = var.desired_count == 1

  # Raft quorum = ⌊N/2⌋ + 1. Drives the deployment policy so rolling deploys
  # never drop the cluster below quorum — ECS keeps at least `raft_quorum` tasks
  # healthy during the rollout.
  raft_quorum = floor(var.desired_count / 2) + 1

  # Single-node: stop-then-start (0/100) because EFS + SQLite cannot tolerate
  # two writers. Multi-node: derived so ceil(N × min%) = quorum (no quorum loss)
  # and floor(N × max%) = N+1 (surge by one task at a time).
  rqlite_min_healthy_percent = local.single_node ? 0 : ceil(local.raft_quorum * 100 / var.desired_count)
  rqlite_max_percent         = local.single_node ? 100 : ceil((var.desired_count + 1) * 100 / var.desired_count)
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "rqlite" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = 30
}

# Service Discovery Service (registers A records in CloudMap)
resource "aws_service_discovery_service" "rqlite" {
  name = "rqlite"

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

# Security Group
resource "aws_security_group" "rqlite" {
  name        = "${local.name_prefix}-sg"
  description = "Security group for rqlite ECS tasks"
  vpc_id      = var.vpc_id

  # HTTP API from app tasks
  ingress {
    description     = "rqlite HTTP API from app"
    from_port       = local.http_port
    to_port         = local.http_port
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  # HTTP API between rqlite nodes (multi-node Raft cluster only)
  ingress {
    description = "rqlite HTTP API from peers"
    from_port   = local.http_port
    to_port     = local.http_port
    protocol    = "tcp"
    self        = true
  }

  # Raft consensus between rqlite nodes (multi-node Raft cluster only)
  ingress {
    description = "rqlite Raft from peers"
    from_port   = local.raft_port
    to_port     = local.raft_port
    protocol    = "tcp"
    self        = true
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-sg"
  }
}

# ─── EFS for persistent rqlite data (single-node branch only) ───────────────
# When desired_count = 1, rqlite mounts /rqlite/file from EFS so data survives
# task replacement and Fargate restarts. SQLite must NEVER be opened by two
# writers concurrently, which is why this branch deploys stop-then-start.
# Multi-node branch (3+) keeps ephemeral storage with Raft consensus + S3
# auto-backup.

resource "aws_efs_file_system" "rqlite" {
  count = local.single_node ? 1 : 0

  creation_token = "${local.name_prefix}-data"
  encrypted      = true

  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"

  lifecycle_policy {
    transition_to_ia = "AFTER_30_DAYS"
  }

  tags = {
    Name = "${local.name_prefix}-data"
  }
}

resource "aws_security_group" "efs" {
  count = local.single_node ? 1 : 0

  name        = "${local.name_prefix}-efs-sg"
  description = "EFS mount targets for rqlite"
  vpc_id      = var.vpc_id

  ingress {
    description     = "NFS from rqlite tasks"
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.rqlite.id]
  }

  tags = {
    Name = "${local.name_prefix}-efs-sg"
  }
}

resource "aws_efs_mount_target" "rqlite" {
  for_each = local.single_node ? toset(var.subnet_ids) : toset([])

  file_system_id  = aws_efs_file_system.rqlite[0].id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs[0].id]
}

resource "aws_efs_access_point" "rqlite" {
  count = local.single_node ? 1 : 0

  file_system_id = aws_efs_file_system.rqlite[0].id

  posix_user {
    uid = 0
    gid = 0
  }

  root_directory {
    path = "/rqlite"

    creation_info {
      owner_uid   = 0
      owner_gid   = 0
      permissions = "0755"
    }
  }

  tags = {
    Name = "${local.name_prefix}-ap"
  }
}

# ECR Repository for custom rqlite image
resource "aws_ecr_repository" "rqlite" {
  name                 = "${var.project_name}-rqlite"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "rqlite" {
  repository = aws_ecr_repository.rqlite.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 5 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 5
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# IAM Execution Role
resource "aws_iam_role" "rqlite_execution" {
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

resource "aws_iam_role_policy_attachment" "rqlite_execution" {
  role       = aws_iam_role.rqlite_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# EFS mount permissions on the task role (single-node branch only).
# Fargate uses the task role for IAM-authenticated EFS mounts.
resource "aws_iam_role_policy" "rqlite_task_efs" {
  count = local.single_node ? 1 : 0

  name = "${local.name_prefix}-efs"
  role = aws_iam_role.rqlite_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "elasticfilesystem:ClientMount",
          "elasticfilesystem:ClientWrite",
          "elasticfilesystem:ClientRootAccess"
        ]
        Resource = aws_efs_file_system.rqlite[0].arn
      }
    ]
  })
}

# S3 bucket for rqlite auto-backup
resource "aws_s3_bucket" "rqlite_backup" {
  bucket = "${local.name_prefix}-backup"

  tags = {
    Name = "${local.name_prefix}-backup"
  }
}

resource "aws_s3_bucket_versioning" "rqlite_backup" {
  bucket = aws_s3_bucket.rqlite_backup.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "rqlite_backup" {
  bucket = aws_s3_bucket.rqlite_backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "rqlite_backup" {
  bucket                  = aws_s3_bucket.rqlite_backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# IAM Task Role
resource "aws_iam_role" "rqlite_task" {
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

# S3 access for auto-backup/restore
resource "aws_iam_role_policy" "rqlite_task_s3" {
  name = "${local.name_prefix}-s3-backup"
  role = aws_iam_role.rqlite_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject"
        ]
        Resource = "${aws_s3_bucket.rqlite_backup.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.rqlite_backup.arn
      }
    ]
  })
}

# Task Definition
resource "aws_ecs_task_definition" "rqlite" {
  family                   = local.name_prefix
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = aws_iam_role.rqlite_execution.arn
  task_role_arn            = aws_iam_role.rqlite_task.arn

  dynamic "volume" {
    for_each = local.single_node ? [1] : []

    content {
      name = "rqlite-data"

      efs_volume_configuration {
        file_system_id     = aws_efs_file_system.rqlite[0].id
        transit_encryption = "ENABLED"

        authorization_config {
          access_point_id = aws_efs_access_point.rqlite[0].id
          iam             = "ENABLED"
        }
      }
    }
  }

  container_definitions = jsonencode([
    {
      name      = "rqlite"
      image     = "${aws_ecr_repository.rqlite.repository_url}:${var.image_tag}"
      essential = true

      mountPoints = local.single_node ? [
        {
          sourceVolume  = "rqlite-data"
          containerPath = "/rqlite/file"
          readOnly      = false
        }
      ] : []

      environment = [
        {
          name  = "RQLITE_HTTP_PORT"
          value = tostring(local.http_port)
        },
        {
          name  = "RQLITE_RAFT_PORT"
          value = tostring(local.raft_port)
        },
        {
          name  = "RQLITE_DISCO_DNS_NAME"
          value = local.dns_name
        },
        {
          name  = "RQLITE_BOOTSTRAP_EXPECT"
          value = tostring(var.desired_count)
        },
        {
          name  = "RQLITE_REAP_TIMEOUT"
          value = "120s"
        },
        {
          name  = "RQLITE_BACKUP_BUCKET"
          value = aws_s3_bucket.rqlite_backup.id
        },
        {
          name  = "RQLITE_BACKUP_REGION"
          value = var.aws_region
        },
        {
          name  = "RQLITE_BACKUP_INTERVAL"
          value = var.backup_interval
        }
      ]

      portMappings = [
        {
          containerPort = local.http_port
          hostPort      = local.http_port
          protocol      = "tcp"
        },
        {
          containerPort = local.raft_port
          hostPort      = local.raft_port
          protocol      = "tcp"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.rqlite.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "rqlite"
        }
      }

      healthCheck = {
        # ?noleader: pass as long as the node is up and the SQLite DB is open.
        # Strict /readyz also requires leader visibility, which can flap during
        # snapshots / peers.json recovery / brief Raft state transitions. For a
        # single-node cluster, a healthy node either has itself as leader or is
        # in transient recovery — both fine from ECS's perspective; serving
        # traffic still requires a leader, which the API surfaces as 5xx, not
        # as a stuck task.
        command  = ["CMD-SHELL", "wget -qO- http://localhost:${local.http_port}/readyz?noleader || exit 1"]
        interval = 20
        timeout  = 10
        retries  = 5
        # peers.json recovery + leader election + extension loading on warm EFS
        # state can take well over the default 60s.
        startPeriod = 180
      }
    }
  ])
}

# ECS Service
resource "aws_ecs_service" "rqlite" {
  name            = local.name_prefix
  cluster         = var.ecs_cluster_id
  task_definition = aws_ecs_task_definition.rqlite.arn
  desired_count   = var.desired_count

  # Single-node uses on-demand FARGATE (no eviction). Multi-node tolerates spot
  # eviction because Raft + S3 backup recover from individual node loss.
  capacity_provider_strategy {
    capacity_provider = local.single_node ? "FARGATE" : "FARGATE_SPOT"
    weight            = 1
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.rqlite.id]
    assign_public_ip = var.assign_public_ip
  }

  service_registries {
    registry_arn = aws_service_discovery_service.rqlite.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_controller {
    type = "ECS"
  }

  # Derived from desired_count via locals so the policy scales with cluster size
  # without manual tuning. Single-node: stop-then-start (0/100) — SQLite cannot
  # tolerate two writers on the same EFS path. Multi-node: rolling deploy that
  # always preserves Raft quorum (min% = ⌈quorum/N⌉, max% = ⌈(N+1)/N⌉).
  deployment_minimum_healthy_percent = local.rqlite_min_healthy_percent
  deployment_maximum_percent         = local.rqlite_max_percent

  depends_on = [aws_efs_mount_target.rqlite]
}
