locals {
  name_prefix                 = "${var.project_name}-${var.environment}"
  service_discovery_namespace = "${var.project_name}.local"
}

# ECS tasks security group (created here for shared reference by app and rqlite modules)
resource "aws_security_group" "ecs_tasks" {
  name        = "${local.name_prefix}-ecs-tasks"
  description = "Security group for ECS tasks"
  vpc_id      = local.vpc_id

  ingress {
    description     = "Allow traffic from ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [module.alb.security_group_id]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-ecs-tasks-sg"
  }
}

# S3 Backend for Terraform state
module "s3_backend" {
  source = "./modules/s3-backend"

  project_name = var.project_name
  environment  = var.environment
}

# The account's default VPC, rather than one of our own.
#
# Every ECS task here runs in a public subnet with an address of its own and is isolated by
# security group alone: ingress on the container port from the load balancer's group, and nothing
# else. That was already the topology -- `enable_nat_gateway` defaulted to false and no environment
# set it -- so the VPC module was building private subnets, route tables, an EIP and a NAT gateway
# that nothing used, at about USD 32 a month per availability zone.
#
# Default subnets are public: they route to the account's internet gateway and auto-assign public
# IPs, which is exactly what a task needs to reach ECR without a NAT. LocalStack provides the same
# shape (172.31.0.0/16 with one subnet per AZ), so local and deployed share this part rather than
# diverging.
#
# What is given up: the private-subnet option, which was the second layer behind the security
# groups. The groups are the only control now, so a rule with a CIDR source rather than a group
# source is exposed the moment it applies -- see `aws_security_group.ecs_tasks` above, which allows
# the load balancer's group and nothing wider.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  # One per availability zone, which is what makes them the *default* subnets rather than anything
  # else that may have been created in this VPC by hand.
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

locals {
  vpc_id              = data.aws_vpc.default.id
  vpc_cidr            = data.aws_vpc.default.cidr_block
  public_subnet_ids   = data.aws_subnets.default.ids
  workload_subnet_ids = data.aws_subnets.default.ids
}

# ECR — two repos: the API image every location runs, and the backoffice server's own.
module "ecr" {
  source = "./modules/ecr"

  environment = var.environment
}

# `cli_releases` was here: a public bucket holding versioned `captal` CLI artifacts for operators to
# download. The CLI was deleted and the backoffice replaced it, so there is nothing to publish.
#
# `modules/cdn` went with it -- a Lambda@Edge CDN that `modules/cloudfront` superseded, and that had
# already stopped being referenced from this file.

# ALB (initial setup without HTTPS)
module "alb" {
  source = "./modules/alb"

  project_name      = var.project_name
  environment       = var.environment
  vpc_id            = local.vpc_id
  public_subnet_ids = local.public_subnet_ids
  container_port    = var.container_port
  # Wire the captive portal dispatcher Lambda target group into the HTTP listener.
  # enable_portal_dispatcher_rule is a static bool so the resource count can be
  # evaluated at plan time (must NOT use a module attribute for count gating).
  enable_portal_dispatcher_rule      = var.enable_global_accelerator
  portal_dispatcher_target_group_arn = var.enable_global_accelerator ? module.portal_dispatcher[0].target_group_arn : ""
}

# CloudMap namespace for service discovery
resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = local.service_discovery_namespace
  description = "Service discovery namespace for ${var.project_name}"
  vpc         = local.vpc_id
}

# ECS Cluster + IAM roles + log groups. Per-location services and task definitions
# are created by the CLI (`captal locations push <slug>`); shared task is created
# by `captal shared push`.
module "ecs" {
  source = "./modules/ecs"

  project_name          = var.project_name
  environment           = var.environment
  vpc_id                = local.vpc_id
  alb_security_group_id = module.alb.security_group_id
  container_port        = var.container_port

  create_security_group = false
  ecs_security_group_id = aws_security_group.ecs_tasks.id
}

# RQLite cluster (service discovery via CloudMap DNS)
module "rqlite" {
  source = "./modules/rqlite"

  project_name                     = var.project_name
  environment                      = var.environment
  aws_region                       = var.aws_region
  vpc_id                           = local.vpc_id
  subnet_ids                       = local.workload_subnet_ids
  ecs_cluster_id                   = module.ecs.cluster_id
  service_discovery_namespace_id   = aws_service_discovery_private_dns_namespace.main.id
  service_discovery_namespace_name = local.service_discovery_namespace
  app_security_group_id            = aws_security_group.ecs_tasks.id
  desired_count                    = var.rqlite_desired_count
  container_cpu                    = var.rqlite_cpu
  container_memory                 = var.rqlite_memory
  image_tag                        = var.rqlite_image_tag
  # Public subnets and no NAT, so a task without an address of its own cannot reach ECR. See the
  # `data "aws_vpc" "default"` block for why the private option is gone.
  assign_public_ip = true
}

# Debug ingress: let the Tailscale proxy (acting as VPC subnet router) reach
# rqlite's HTTP API on 4001. Approved tailnet devices with --accept-routes can
# then query rqlite directly from a developer laptop. Lives in root (not in the
# rqlite module) because it bridges two sibling modules and the dependency only
# exists when Tailscale is part of the deployment.
resource "aws_vpc_security_group_ingress_rule" "rqlite_from_tailscale_proxy" {
  count = var.enable_tailscale ? 1 : 0

  security_group_id            = module.rqlite.security_group_id
  description                  = "rqlite HTTP from Tailscale proxy (subnet router for debug)"
  ip_protocol                  = "tcp"
  from_port                    = 4001
  to_port                      = 4001
  referenced_security_group_id = module.tailscale_proxy[0].security_group_id
}

# Captive portal dispatcher — Lambda that looks up the slug by AP MAC in rqlite
# and 302-redirects the device to the correct CloudFront URL. Sits behind the
# ALB's HTTP listener (rule on /guest/s/*), fed by Global Accelerator's static IP.
module "portal_dispatcher" {
  source = "./modules/portal-dispatcher"
  count  = var.enable_global_accelerator ? 1 : 0

  project_name                     = var.project_name
  environment                      = var.environment
  vpc_id                           = local.vpc_id
  subnet_ids                       = local.workload_subnet_ids
  rqlite_security_group_id         = module.rqlite.security_group_id
  service_discovery_namespace_name = local.service_discovery_namespace
  cloudfront_host                  = var.cloudfront_domain
}

# Global Accelerator — static IPv4 in front of the ALB so UniFi External Portal
# Server (which validates the field as IPv4 only) has something stable to point
# at. Wraps the ALB at TCP/80; HTTPS keeps going directly to CloudFront.
module "global_accelerator" {
  source = "./modules/global-accelerator"
  count  = var.enable_global_accelerator ? 1 : 0

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region
  alb_arn      = module.alb.alb_arn
}

# Secret container for the Tailscale auth key. The VALUE is set by the
# aws_secretsmanager_secret_version below, which reads from the
# tailscale_tailnet_key resource — full automation, no manual put-secret-value.
resource "aws_secretsmanager_secret" "tailscale_authkey" {
  count = var.enable_tailscale ? 1 : 0

  name        = "${local.name_prefix}/tailscale/authkey"
  description = "Tailscale auth key for the ${local.name_prefix} proxy daemon (reusable + ephemeral, minted by TF)"
}

# Tailscale auth key minted via the provider. Reusable so each container restart
# can log in; ephemeral so the node auto-deletes from the tailnet when the task
# terminates; preauthorized to skip the manual approval step in admin. The
# OAuth client / API key behind the provider must be allowed to issue keys with
# tag:captal-proxy (configure in ACL → tagOwners).
resource "tailscale_tailnet_key" "captal_proxy" {
  count = var.enable_tailscale ? 1 : 0

  reusable      = true
  ephemeral     = true
  preauthorized = true
  tags          = ["tag:captal-proxy"]
  expiry        = var.tailscale_authkey_expiry_seconds
  description   = "${local.name_prefix} proxy daemon"
}

resource "aws_secretsmanager_secret_version" "tailscale_authkey" {
  count = var.enable_tailscale ? 1 : 0

  secret_id     = aws_secretsmanager_secret.tailscale_authkey[0].id
  secret_string = tailscale_tailnet_key.captal_proxy[0].key
}

# Tailscale proxy — HTTP CONNECT gateway that routes UniFi Controller traffic
# from API tasks → tailnet → subnet router VM → on-prem LAN → UCG.
module "tailscale_proxy" {
  source = "./modules/tailscale-proxy"
  count  = var.enable_tailscale ? 1 : 0

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region
  vpc_id       = local.vpc_id
  vpc_cidr     = local.vpc_cidr
  subnet_ids   = local.workload_subnet_ids
  # Public subnets and no NAT, so a task without an address of its own cannot reach ECR. See the
  # `data "aws_vpc" "default"` block for why the private option is gone.
  assign_public_ip                 = true
  ecs_cluster_id                   = module.ecs.cluster_id
  service_discovery_namespace_id   = aws_service_discovery_private_dns_namespace.main.id
  service_discovery_namespace_name = local.service_discovery_namespace
  app_security_group_id            = aws_security_group.ecs_tasks.id
  ts_authkey_secret_arn            = aws_secretsmanager_secret.tailscale_authkey[0].arn
  # Passing the version_id into the container env makes the task definition
  # change whenever TF mints a new key (expiry rotation), which triggers an
  # automatic ECS rolling deploy that picks up the fresh secret value.
  ts_authkey_secret_version_id = aws_secretsmanager_secret_version.tailscale_authkey[0].version_id
  desired_count                = var.tailscale_proxy_desired_count
  container_cpu                = var.tailscale_proxy_cpu
  container_memory             = var.tailscale_proxy_memory
}

# Route 53 zone — production/staging → CloudFront (no apex)
module "route53" {
  source = "./modules/route53"
  count  = var.enable_dns ? 1 : 0

  domain_name         = var.domain_name
  env_prefix          = var.environment == "prod" ? "production" : "staging"
  cloudfront_dns_name = module.cloudfront.distribution_domain_name
  cloudfront_zone_id  = module.cloudfront.distribution_hosted_zone_id
}

# ACM certificates (regional for ALB + us-east-1 for CloudFront)
#
# Travels with the zone rather than having a flag of its own: the certificates are validated by
# DNS records written into it, so a certificate without the zone never leaves
# PENDING_VALIDATION and the apply waits for something that cannot happen.
module "acm" {
  source = "./modules/acm"
  count  = var.enable_dns ? 1 : 0

  providers = {
    aws.us_east_1 = aws.us_east_1
  }

  domain_name = var.domain_name
  zone_id     = module.route53[0].zone_id
}

# HTTPS Listener — default_action returns 403 (all traffic now goes through CloudFront)
#
# Counted with the certificate it presents. A listener on 443 with no `certificate_arn` is
# rejected by the API, so there is no degraded form of this to keep.
resource "aws_lb_listener" "https" {
  count = var.enable_dns ? 1 : 0

  load_balancer_arn = module.alb.alb_arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = module.acm[0].certificate_arn_validated

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      status_code  = "403"
    }
  }
}

# CloudFront — sirve assets (S3) + API (ALB) con SPA fallback
module "cloudfront" {
  source = "./modules/cloudfront"

  project_name      = var.project_name
  environment       = var.environment
  cloudfront_domain = var.cloudfront_domain
  alb_dns_name      = module.alb.alb_dns_name
  # Empty when there is no zone to validate against, which the module reads as "use CloudFront's
  # own certificate and its generated domain name, and claim no alias".
  certificate_arn = var.enable_dns ? module.acm[0].cloudfront_certificate_arn_validated : ""
}

# The MCP backoffice server, and the permissions that are part of deploying it.
#
# `modules/mcp-iam` was here and was all this server had: a policy granting four S3 and CloudFront
# actions, attached to the shared ECS task role. The server itself -- its image, its service, its
# seventeen environment variables, the hostname it answers on -- existed nowhere in this
# configuration.
module "mcp" {
  source = "./modules/mcp"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region
  # Passed through so the server's own AWS clients resolve where this configuration does.
  aws_endpoint_url = var.aws_endpoint_url

  cluster_id          = module.ecs.cluster_id
  cluster_name        = module.ecs.cluster_name
  vpc_id              = local.vpc_id
  subnet_ids          = local.workload_subnet_ids
  security_group_id   = aws_security_group.ecs_tasks.id
  execution_role_arn  = module.ecs.execution_role_arn
  execution_role_name = module.ecs.execution_role_name

  image            = "${module.ecr.mcp_repository_url}:${var.mcp_image_tag}"
  container_port   = var.container_port
  container_cpu    = var.mcp_cpu
  container_memory = var.mcp_memory
  desired_count    = var.mcp_desired_count

  alb_arn            = module.alb.alb_arn
  alb_dns_name       = module.alb.alb_dns_name
  alb_zone_id        = module.alb.alb_zone_id
  http_listener_arn  = module.alb.http_listener_arn
  https_listener_arn = var.enable_dns ? aws_lb_listener.https[0].arn : ""
  hostname           = var.enable_dns ? "backoffice.${var.domain_name}" : ""
  zone_id            = var.enable_dns ? module.route53[0].zone_id : ""

  db_url                      = module.rqlite.http_url
  assets_bucket_name          = module.cloudfront.assets_bucket_name
  assets_bucket_arn           = module.cloudfront.assets_bucket_arn
  cloudfront_distribution_id  = module.cloudfront.distribution_id
  cloudfront_distribution_arn = module.cloudfront.distribution_arn
  public_base_url             = var.enable_dns ? "https://${var.cloudfront_domain}" : "https://${module.cloudfront.distribution_domain_name}"

  # What the deploy tools hand to a location: one image for all of them, and the task role a
  # location's API runs as -- which is not the server's own.
  api_image              = "${module.ecr.api_repository_url}:${var.image_tag}"
  location_task_role_arn = module.ecs.task_role_arn

  create_test_user = var.mcp_create_test_user
}
