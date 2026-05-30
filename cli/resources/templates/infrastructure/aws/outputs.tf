output "alb_dns_name" {
  description = "ALB DNS name"
  value       = module.alb.alb_dns_name
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

output "name_servers" {
  description = "Name servers to configure in parent domain"
  value       = module.route53.name_servers
}

output "domain_name" {
  description = "Domain name"
  value       = module.route53.domain_name
}

output "certificate_arn" {
  description = "Regional ACM certificate ARN (ALB)"
  value       = module.acm.certificate_arn_validated
}

output "rqlite_url" {
  description = "RQLite HTTP API URL"
  value       = module.rqlite.http_url
}

output "rqlite_ecr_repository_url" {
  description = "ECR repository URL for the custom rqlite image"
  value       = module.rqlite.ecr_repository_url
}

output "rqlite_service_name" {
  description = "RQLite ECS service name"
  value       = module.rqlite.service_name
}

# ─── Global Accelerator (UniFi External Portal Server static IPv4) ──────────

output "global_accelerator_ips" {
  description = "Two static IPv4 addresses for UniFi External Portal Server. Pick either. Empty when enable_global_accelerator = false."
  value       = var.enable_global_accelerator ? module.global_accelerator[0].static_ips : []
}

output "global_accelerator_dns_name" {
  description = "Global Accelerator DNS name (alternative when the client supports hostnames). Null when GA is disabled."
  value       = var.enable_global_accelerator ? module.global_accelerator[0].accelerator_dns_name : null
}

output "portal_dispatcher_function_name" {
  description = "Lambda function name for the captive portal dispatcher (for log tailing). Null when GA is disabled."
  value       = var.enable_global_accelerator ? module.portal_dispatcher[0].function_name : null
}

# ─── Tailscale proxy ────────────────────────────────────────────────────────

output "tailscale_proxy_url" {
  description = "URL to put in shared/captal.yaml unifi.proxyUrl (e.g. http://tailscale-proxy.captal.local:1055)"
  value       = module.tailscale_proxy.proxy_url
}

output "tailscale_proxy_service_name" {
  description = "Tailscale proxy ECS service name"
  value       = module.tailscale_proxy.service_name
}

output "tailscale_authkey_secret_arn" {
  description = "Secrets Manager ARN where the Tailscale auth key must be put with `aws secretsmanager put-secret-value`"
  value       = aws_secretsmanager_secret.tailscale_authkey.arn
}

# ─── ECR repos ──────────────────────────────────────────────────────────────

output "api_image_repository_url" {
  description = "ECR repo URL for captal-api base image"
  value       = module.ecr.api_repository_url
}

output "provision_image_repository_url" {
  description = "ECR repo URL for captal-provision base image"
  value       = module.ecr.provision_repository_url
}

output "shared_image_repository_url" {
  description = "ECR repo URL for captal-shared (CLI-built derivatives)"
  value       = module.ecr.shared_repository_url
}

output "locations_image_repository_url" {
  description = "ECR repo URL for captal-locations (CLI-built derivatives, tags <slug>-<ts>)"
  value       = module.ecr.locations_repository_url
}

# ─── CloudFront + S3 assets ─────────────────────────────────────────────────

output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID (used by CLI for invalidations)"
  value       = module.cloudfront.distribution_id
}

output "cloudfront_domain_name" {
  description = "CloudFront-assigned domain name"
  value       = module.cloudfront.distribution_domain_name
}

output "assets_bucket_name" {
  description = "S3 bucket name for client assets (bundle/ + per-location prefixes)"
  value       = module.cloudfront.assets_bucket_name
}

output "bundle_prefix" {
  description = "S3 key prefix where the project release flow uploads the master bundle"
  value       = "bundle/"
}

# ─── CLI releases ───────────────────────────────────────────────────────────

output "cli_releases_bucket_name" {
  description = "S3 bucket holding CLI release artifacts (./mill cli.publishS3 --bucket ...)"
  value       = module.cli_releases.bucket_name
}
