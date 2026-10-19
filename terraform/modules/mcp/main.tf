# The MCP backoffice server: the thing that owns every location's deploy.
#
# It had no module. `modules/mcp-iam` existed and granted it four S3 and CloudFront actions, which
# was the whole of its presence here -- so the server that creates services, target groups and
# listener rules was itself deployed by hand, with the seventeen environment variables it requires
# supplied from nowhere.
#
# Folded the IAM in rather than leaving it beside: the policy and the role it attaches to are part
# of what deploying this server means, and keeping them in a separate module is how they came to
# describe a different set of permissions than the server actually calls.

locals {
  name = "${var.project_name}-${var.environment}-mcp"

  dns_enabled = var.hostname != "" && var.zone_id != ""

  # Left out entirely when empty rather than set to "". The server reads it with `System.env`, so
  # an empty string is `Some("")`, which becomes `endpointOverride(URI.create(""))` and points
  # every AWS client at nothing -- a failure with no message that names the cause.
  endpoint_env = var.aws_endpoint_url == "" ? [] : [{
    name  = "AWS_ENDPOINT_URL"
    value = var.aws_endpoint_url
  }]

  environment = concat([
    { name = "MCP_PORT", value = tostring(var.container_port) },
    { name = "MCP_RESOURCE_URI", value = "${local.dns_enabled ? "https://${var.hostname}" : "http://${var.alb_dns_name}"}/mcp" },

    { name = "AWS_REGION", value = var.aws_region },
    { name = "DB_URL", value = var.db_url },

    # Assets and the edge in front of them.
    { name = "ASSETS_BUCKET", value = var.assets_bucket_name },
    { name = "CLOUDFRONT_DISTRIBUTION_ID", value = var.cloudfront_distribution_id },
    { name = "PUBLIC_BASE_URL", value = var.public_base_url },

    # What `deploy_location` attaches a location to. The server reads these rather than discovering
    # them, because a wrong guess here creates resources nothing routes to.
    { name = "ECS_CLUSTER", value = var.cluster_name },
    { name = "VPC_ID", value = var.vpc_id },
    { name = "SUBNET_IDS", value = join(",", var.subnet_ids) },
    { name = "SECURITY_GROUP_ID", value = var.security_group_id },
    { name = "ALB_LISTENER_ARN", value = local.dns_enabled ? var.https_listener_arn : var.http_listener_arn },
    { name = "TASK_EXECUTION_ROLE_ARN", value = var.execution_role_arn },
    { name = "TASK_ROLE_ARN", value = var.location_task_role_arn },
    { name = "API_IMAGE", value = var.api_image },
  ], local.endpoint_env)
}

# ─── The admin token ────────────────────────────────────────────────────────

# Seeds the row that holds every scope; every other token is issued from it with `create_token`.
#
# In Secrets Manager rather than in the container definition's `environment`, because that list is
# stored in the task definition and shows in `describe-task-definition` to anyone who can call it.
# The `secrets` block below injects it as the same environment variable, so the server is unchanged.
#
# The generated value does reach the state file, which is encrypted in S3 and no better than the
# bucket's own access control. `ignore_changes` is what makes rotating it out of band stick: change
# it in the console or with `put-secret-value` and this will not put the original back.
resource "random_password" "admin_token" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "admin_token" {
  name        = "${local.name}-admin-token"
  description = "MCP backoffice admin token: seeds the row holding every scope"
}

resource "aws_secretsmanager_secret_version" "admin_token" {
  secret_id     = aws_secretsmanager_secret.admin_token.id
  secret_string = random_password.admin_token.result

  lifecycle {
    ignore_changes = [secret_string]
  }
}

# ECS, not the server, reads the secret: the execution role is the identity that fetches it before
# the container starts. Granting it to the task role instead would leave the task unable to start
# with an error about the secret rather than about the permission.
data "aws_iam_policy_document" "read_secret" {
  statement {
    sid       = "ReadAdminToken"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.admin_token.arn]
  }
}

resource "aws_iam_role_policy" "read_secret" {
  name   = "${local.name}-read-secret"
  role   = var.execution_role_name
  policy = data.aws_iam_policy_document.read_secret.json
}

# ─── What the server is allowed to do ───────────────────────────────────────

# Its own role, not the one a location's API task runs as.
#
# `modules/mcp-iam` attached this policy to the shared ECS task role, which is the role
# `deploy_location` also hands to every location's task definition -- so every location's API could
# write any location's assets and invalidate the distribution. Nothing needed that; it was a
# consequence of there being one role.
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

data "aws_iam_policy_document" "mcp" {
  # Per-location stylesheets, brand icons and advertiser videos, all under <slug>/.
  #
  # PutObject also authorises the presigned upload URLs the server hands to a browser: a signature
  # is only as permitted as the identity that signed it. GetObject also covers HeadObject, which is
  # what separates a finished upload from an abandoned one.
  statement {
    sid    = "LocationAssetObjects"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      # `destroy_location` removes everything under a slug's prefix, and the paused page it saved.
      "s3:DeleteObject",
    ]
    resources = ["${var.assets_bucket_arn}/*"]
  }

  # Deliberate, and it used to be deliberately absent: the comment said nothing enumerates the
  # bucket because every key is derived from a slug the database confirmed. That stopped being
  # true. `copyBundle` lists `bundle/` to know what to copy, and `destroy_location` lists a slug's
  # prefix to know what to delete and to report how many objects went.
  statement {
    sid       = "LocationAssetListing"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [var.assets_bucket_arn]
  }

  # Both halves, and the second is easy to miss. `CreateInvalidation` starts it; `GetInvalidation`
  # is what `invalidateAndWait` polls until it reports Completed -- and `pause_location` depends on
  # that wait, because the redirect is only safe once the edge is serving the maintenance page.
  # With only the first, pause creates the invalidation and then fails, having already swapped the
  # page.
  statement {
    sid    = "AssetCacheInvalidation"
    effect = "Allow"
    actions = [
      "cloudfront:CreateInvalidation",
      "cloudfront:GetInvalidation",
    ]
    resources = [var.cloudfront_distribution_arn]
  }

  # Registering a task definition is not resource-scoped by the API: there is no ARN to name before
  # the revision exists. The service calls are scoped to this cluster.
  statement {
    sid    = "TaskDefinitions"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DeregisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:ListTaskDefinitions",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "LocationServices"
    effect = "Allow"
    actions = [
      "ecs:CreateService",
      "ecs:UpdateService",
      "ecs:DescribeServices",
      "ecs:DeleteService",
      "ecs:ListServices",
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

  # Without this a task definition naming either role is refused, and the error names the role
  # rather than the missing permission. Scoped to the two roles a location's task actually runs as,
  # and to being passed to ECS specifically.
  statement {
    sid       = "PassTaskRoles"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [var.execution_role_arn, var.location_task_role_arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # A location's target group and the listener rule that forwards to it. The describes are not
  # resource-scoped by the API; the mutations are, but the target group does not exist yet when
  # `CreateTargetGroup` is called, so that one cannot be either.
  statement {
    sid    = "LocationRouting"
    effect = "Allow"
    actions = [
      "elasticloadbalancing:CreateTargetGroup",
      "elasticloadbalancing:DeleteTargetGroup",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeTargetHealth",
      "elasticloadbalancing:CreateRule",
      "elasticloadbalancing:DeleteRule",
      "elasticloadbalancing:ModifyRule",
      "elasticloadbalancing:DescribeRules",
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeLoadBalancers",
      "elasticloadbalancing:AddTags",
    ]
    resources = ["*"]
  }

  # The log group a location's task writes to, created before the service so the container's first
  # lines have somewhere to land.
  statement {
    sid    = "LocationLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:DeleteLogGroup",
      "logs:DescribeLogGroups",
      "logs:PutRetentionPolicy",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "mcp" {
  name        = local.name
  description = "MCP backoffice: per-location assets, cache invalidation, and the deploy of a location's service"
  policy      = data.aws_iam_policy_document.mcp.json
}

resource "aws_iam_role_policy_attachment" "task" {
  role       = aws_iam_role.task.name
  policy_arn = aws_iam_policy.mcp.arn
}

# Local path: the same policy on an identity usable from a developer machine, where the task role
# is not reachable.
resource "aws_iam_user" "test" {
  count = var.create_test_user ? 1 : 0
  name  = "${local.name}-local"

  tags = {
    Purpose = "Running the MCP backoffice outside ECS, where the task role is not reachable"
  }
}

resource "aws_iam_user_policy_attachment" "test" {
  count      = var.create_test_user ? 1 : 0
  user       = aws_iam_user.test[0].name
  policy_arn = aws_iam_policy.mcp.arn
}

# The credentials that make the policy above do any work.
#
# Without IAM enforcement LocalStack accepts any key pair and allows everything, so a suite passing
# `test`/`test` exercises its own calls and not the policy -- it would pass against an empty one.
# This is the pair `ENFORCE_IAM` evaluates, and what lets a permission missing from
# `aws_iam_policy.mcp` fail in a test rather than in front of an operator.
#
# The secret lands in state in plaintext, which is why this is tied to `create_test_user` and why
# that is only ever true for `local`: there the state is a file under the build's `out/` on one
# machine, describing an emulated account whose id is twelve zeros. An AWS environment creates
# neither the user nor the key, and its workloads reach the policy through the task role.
resource "aws_iam_access_key" "test" {
  count = var.create_test_user ? 1 : 0
  user  = aws_iam_user.test[0].name
}

# ─── The service ────────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "mcp" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

resource "aws_ecs_task_definition" "mcp" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.container_cpu
  memory                   = var.container_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "mcp"
    image     = var.image
    essential = true

    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]

    environment = local.environment

    secrets = [{
      name      = "MCP_ADMIN_TOKEN"
      valueFrom = aws_secretsmanager_secret.admin_token.arn
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.mcp.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "mcp"
      }
    }
  }])
}

# `ip` and not `instance`: an `awsvpc` task registers by address, and `instance` would be rejected.
resource "aws_lb_target_group" "mcp" {
  name        = substr("${local.name}-tg", 0, 32)
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# On the HTTPS listener where there is a certificate to present, on HTTP where there is not. Only
# one of the two exists at a time, so the server's `MCP_RESOURCE_URI` and `ALB_LISTENER_ARN` are
# derived from the same condition.
resource "aws_lb_listener_rule" "mcp_https" {
  count = local.dns_enabled ? 1 : 0

  listener_arn = var.https_listener_arn
  priority     = var.listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.mcp.arn
  }

  condition {
    host_header {
      values = [var.hostname]
    }
  }
}

# Matches on path rather than host: locally the load balancer is reached at its own name, and
# there is no hostname to match on.
resource "aws_lb_listener_rule" "mcp_http" {
  count = local.dns_enabled ? 0 : 1

  listener_arn = var.http_listener_arn
  priority     = var.listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.mcp.arn
  }

  condition {
    path_pattern {
      values = ["/mcp", "/mcp/*", "/.well-known/*", "/ui/*", "/health"]
    }
  }
}

resource "aws_ecs_service" "mcp" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.mcp.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Matches the target group's own health check, so ECS does not replace a container that is still
  # starting its JVM.
  health_check_grace_period_seconds = 180

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.mcp.arn
    container_name   = "mcp"
    container_port   = var.container_port
  }

  # `createService` with a `loadBalancers` block fails with "no associated load balancer" when the
  # target group is not yet reachable through a listener. The same ordering `deploy_location` has
  # to respect, recorded there as a historical bug.
  depends_on = [aws_lb_listener_rule.mcp_https, aws_lb_listener_rule.mcp_http]
}

# ─── The hostname ───────────────────────────────────────────────────────────

resource "aws_route53_record" "mcp" {
  count = local.dns_enabled ? 1 : 0

  zone_id = var.zone_id
  name    = var.hostname
  type    = "A"

  alias {
    name                   = var.alb_dns_name
    zone_id                = var.alb_zone_id
    evaluate_target_health = false
  }
}
