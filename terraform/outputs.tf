output "alb_dns_name" {
  description = "ALB DNS name"
  value       = module.alb.alb_dns_name
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

# ─── What a location deploy attaches to ─────────────────────────────────────
#
# The backoffice creates a location's service, target group and listener rule at run time, so it has
# to be told what to attach them to rather than discovering it. These six are the rest of that
# contract -- the other eleven values it needs were already outputs here.
#
# They exist for the test fixture as much as for a deployment. `mcp.test` built the same cluster,
# network, roles and listener by hand with about two hundred lines of `awslocal` calls, which is a
# reimplementation of this file that happened to agree with it. Three bugs this week were a decision
# living in two places where one moved; these outputs are what lets the fixture read the
# configuration instead of copying it.

output "vpc_id" {
  description = "VPC a location's target group registers addresses in"
  value       = local.vpc_id
}

output "subnet_ids" {
  description = "Subnets a location's task runs in, comma-separated as the server reads them. Public: every task carries its own address and reaches ECR through the internet gateway."
  value       = join(",", local.workload_subnet_ids)
}

output "ecs_tasks_security_group_id" {
  description = "Security group every task runs with. Ingress on the container port from the load balancer's group and nothing else, which is the whole of their isolation."
  value       = aws_security_group.ecs_tasks.id
}

output "alb_listener_arn" {
  description = "Listener a location's rule hangs off. The HTTPS one where there is a certificate to present, the HTTP one where there is not -- the same condition that decides the backoffice's own rule."
  value       = var.enable_dns ? aws_lb_listener.https[0].arn : module.alb.http_listener_arn
}

output "task_execution_role_arn" {
  description = "Role ECS assumes to pull an image and read secrets, named in every task definition the backoffice registers"
  value       = module.ecs.execution_role_arn
}

output "task_role_arn" {
  description = "Role a location's API task runs as. Not the backoffice's own, which carries the deploy permissions -- a location's API has no business creating services."
  value       = module.ecs.task_role_arn
}

output "name_servers" {
  description = "Name servers to configure in parent domain"
  value       = one(module.route53[*].name_servers)
}

output "domain_name" {
  description = "Domain name"
  value       = one(module.route53[*].domain_name)
}

output "certificate_arn" {
  description = "Regional ACM certificate ARN (ALB)"
  value       = one(module.acm[*].certificate_arn_validated)
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
  value       = one(module.tailscale_proxy[*].proxy_url)
}

output "tailscale_proxy_service_name" {
  description = "Tailscale proxy ECS service name"
  value       = one(module.tailscale_proxy[*].service_name)
}

output "tailscale_authkey_secret_arn" {
  description = "Secrets Manager ARN where the Tailscale auth key must be put with `aws secretsmanager put-secret-value`"
  value       = one(aws_secretsmanager_secret.tailscale_authkey[*].arn)
}

# ─── ECR repos ──────────────────────────────────────────────────────────────

output "api_image_repository_url" {
  description = "ECR repo URL for captal-api, the image every location's task definition runs"
  value       = module.ecr.api_repository_url
}

output "mcp_image_repository_url" {
  description = "ECR repo URL for captal-mcp, the backoffice server"
  value       = module.ecr.mcp_repository_url
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

# ─── MCP backoffice ─────────────────────────────────────────────────────────

output "mcp_url" {
  description = "Where the MCP endpoint is reached. This is also what the server advertises as its resource URI, so a client is configured with exactly this."
  value       = module.mcp.url
}

output "mcp_service_name" {
  description = "ECS service name, for `aws ecs update-service --force-new-deployment` after pushing a new image"
  value       = module.mcp.service_name
}

output "mcp_admin_token_secret_arn" {
  description = "Secrets Manager ARN holding the admin token. Read it with `aws secretsmanager get-secret-value`; it is not an output here, because an output is stored in state and printed by `tofu output`."
  value       = module.mcp.admin_token_secret_arn
}

output "mcp_log_group_name" {
  description = "Where the backoffice's own output lands. First place to look when a tool reports resources created and nothing serving."
  value       = module.mcp.log_group_name
}

# The local suite's credentials. Null in every AWS environment, because `mcp_create_test_user` is
# only ever true for `local` -- see the resource in modules/mcp for why the secret is safe to
# produce there and is absent everywhere else.

output "mcp_test_user_access_key_id" {
  description = "Access key the local suite runs as, so IAM enforcement has an identity to evaluate instead of a dummy pair. Null unless `mcp_create_test_user`."
  value       = module.mcp.test_user_access_key_id
}

output "mcp_test_user_secret_access_key" {
  description = "Secret for the key above. Null unless `mcp_create_test_user`."
  value       = module.mcp.test_user_secret_access_key
  sensitive   = true
}

