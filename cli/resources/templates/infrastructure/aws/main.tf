locals {
  name_prefix                 = "${var.project_name}-${var.environment}"
  service_discovery_namespace = "${var.project_name}.local"
}

# ECS tasks security group (created here for shared reference by app and rqlite modules)
resource "aws_security_group" "ecs_tasks" {
  name        = "${local.name_prefix}-ecs-tasks"
  description = "Security group for ECS tasks"
  vpc_id      = module.vpc.vpc_id

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

# VPC
module "vpc" {
  source = "./modules/vpc"

  project_name       = var.project_name
  environment        = var.environment
  enable_nat_gateway = false
}

# ECR — 4 repos: 2 base (api, provision) + 2 derived (shared, locations)
module "ecr" {
  source = "./modules/ecr"

  environment = var.environment
}

# CLI releases bucket — `./mill cli.publishS3 --bucket <output> ...` uploads versioned
# CLI artifacts (captal.jar + bash/bat wrappers) here for operator consumption.
module "cli_releases" {
  source = "./modules/cli-releases"

  project_name = var.project_name
  environment  = var.environment
}

# ALB (initial setup without HTTPS)
module "alb" {
  source = "./modules/alb"

  project_name      = var.project_name
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
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
  vpc         = module.vpc.vpc_id
}

# ECS Cluster + IAM roles + log groups. Per-location services and task definitions
# are created by the CLI (`captal locations push <slug>`); shared task is created
# by `captal shared push`.
module "ecs" {
  source = "./modules/ecs"

  project_name          = var.project_name
  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
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
  vpc_id                           = module.vpc.vpc_id
  subnet_ids                       = module.vpc.public_subnet_ids
  ecs_cluster_id                   = module.ecs.cluster_id
  service_discovery_namespace_id   = aws_service_discovery_private_dns_namespace.main.id
  service_discovery_namespace_name = local.service_discovery_namespace
  app_security_group_id            = aws_security_group.ecs_tasks.id
  desired_count                    = var.rqlite_desired_count
  container_cpu                    = var.rqlite_cpu
  container_memory                 = var.rqlite_memory
  image_tag                        = var.rqlite_image_tag
  assign_public_ip                 = true
}

# Captive portal dispatcher — Lambda that looks up the slug by AP MAC in rqlite
# and 302-redirects the device to the correct CloudFront URL. Sits behind the
# ALB's HTTP listener (rule on /guest/s/*), fed by Global Accelerator's static IP.
module "portal_dispatcher" {
  source = "./modules/portal-dispatcher"
  count  = var.enable_global_accelerator ? 1 : 0

  project_name                     = var.project_name
  environment                      = var.environment
  vpc_id                           = module.vpc.vpc_id
  subnet_ids                       = module.vpc.public_subnet_ids
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
  name        = "${local.name_prefix}/tailscale/authkey"
  description = "Tailscale auth key for the ${local.name_prefix} proxy daemon (reusable + ephemeral, minted by TF)"
}

# Tailscale auth key minted via the provider. Reusable so each container restart
# can log in; ephemeral so the node auto-deletes from the tailnet when the task
# terminates; preauthorized to skip the manual approval step in admin. The
# OAuth client / API key behind the provider must be allowed to issue keys with
# tag:captal-proxy (configure in ACL → tagOwners).
resource "tailscale_tailnet_key" "captal_proxy" {
  reusable      = true
  ephemeral     = true
  preauthorized = true
  tags          = ["tag:captal-proxy"]
  expiry        = var.tailscale_authkey_expiry_seconds
  description   = "${local.name_prefix} proxy daemon"
}

resource "aws_secretsmanager_secret_version" "tailscale_authkey" {
  secret_id     = aws_secretsmanager_secret.tailscale_authkey.id
  secret_string = tailscale_tailnet_key.captal_proxy.key
}

# Tailscale proxy — HTTP CONNECT gateway that routes UniFi Controller traffic
# from API tasks → tailnet → subnet router VM → on-prem LAN → UCG.
module "tailscale_proxy" {
  source = "./modules/tailscale-proxy"

  project_name                     = var.project_name
  environment                      = var.environment
  aws_region                       = var.aws_region
  vpc_id                           = module.vpc.vpc_id
  subnet_ids                       = module.vpc.public_subnet_ids
  ecs_cluster_id                   = module.ecs.cluster_id
  service_discovery_namespace_id   = aws_service_discovery_private_dns_namespace.main.id
  service_discovery_namespace_name = local.service_discovery_namespace
  app_security_group_id            = aws_security_group.ecs_tasks.id
  ts_authkey_secret_arn            = aws_secretsmanager_secret.tailscale_authkey.arn
  # Passing the version_id into the container env makes the task definition
  # change whenever TF mints a new key (expiry rotation), which triggers an
  # automatic ECS rolling deploy that picks up the fresh secret value.
  ts_authkey_secret_version_id = aws_secretsmanager_secret_version.tailscale_authkey.version_id
  desired_count                = var.tailscale_proxy_desired_count
  container_cpu                = var.tailscale_proxy_cpu
  container_memory             = var.tailscale_proxy_memory
}

# Route 53 zone — production/staging → CloudFront (no apex)
module "route53" {
  source = "./modules/route53"

  domain_name         = var.domain_name
  env_prefix          = var.environment == "prod" ? "production" : "staging"
  cloudfront_dns_name = module.cloudfront.distribution_domain_name
  cloudfront_zone_id  = module.cloudfront.distribution_hosted_zone_id
}

# ACM certificates (regional for ALB + us-east-1 for CloudFront)
module "acm" {
  source = "./modules/acm"

  providers = {
    aws.us_east_1 = aws.us_east_1
  }

  domain_name = var.domain_name
  zone_id     = module.route53.zone_id
}

# HTTPS Listener — default_action returns 403 (all traffic now goes through CloudFront)
resource "aws_lb_listener" "https" {
  load_balancer_arn = module.alb.alb_arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = module.acm.certificate_arn_validated

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
  certificate_arn   = module.acm.cloudfront_certificate_arn_validated
}
