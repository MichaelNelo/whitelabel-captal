# What ECS has Cloud Map do, where Cloud Map is off -- LocalStack offers it only on its Ultimate
# licence: a task that keeps each named service's record in the discovery zone listing the addresses
# of its running tasks, every one of them and them alone. A service is named by its `DiscoveryName`
# tag, the name it would have in Cloud Map, so a client reaches `rqlite.<namespace>` either way.
#
# Not a load balancer's alias: rqlite's nodes find each other by their own addresses, and Redis and
# Raft are not HTTP. Not a record written here: a task's address exists only once ECS starts it, and
# changes every time one is replaced.

locals {
  name = "${var.project_name}-${var.environment}-local-discovery"
}

resource "aws_cloudwatch_log_group" "registrar" {
  name              = "/ecs/${local.name}"
  retention_in_days = 7
}

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

data "aws_iam_policy_document" "registrar" {
  # The cluster's services, their tags and their tasks' addresses.
  statement {
    sid    = "ReadServicesAndTasks"
    effect = "Allow"
    actions = [
      "ecs:ListServices",
      "ecs:DescribeServices",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
    ]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.cluster_id]
    }
  }

  statement {
    sid    = "KeepNames"
    effect = "Allow"
    actions = [
      "route53:ListResourceRecordSets",
      "route53:ChangeResourceRecordSets",
    ]
    resources = ["arn:aws:route53:::hostedzone/${var.zone_id}"]
  }
}

resource "aws_iam_role_policy" "registrar" {
  name   = local.name
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.registrar.json
}

resource "aws_ecs_task_definition" "registrar" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "registrar"
    image     = var.image
    essential = true

    # The script is the command, so a change to it is a new revision.
    entryPoint = ["bash", "-c"]
    command    = [file("${path.module}/registrar.sh")]

    environment = [
      { name = "CLUSTER", value = var.cluster_id },
      { name = "ZONE_ID", value = var.zone_id },
      { name = "ZONE_NAME", value = var.zone_name },
      { name = "INTERVAL", value = tostring(var.interval) },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.registrar.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "registrar"
      }
    }
  }])
}

# One task, stop-then-start: two would write the same records, harmlessly but for nothing.
resource "aws_ecs_service" "registrar" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.registrar.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = true
  }

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
}
