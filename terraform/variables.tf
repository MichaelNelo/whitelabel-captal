variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name used for resource naming"
  type        = string
  default     = "captal"
}

variable "environment" {
  description = "Environment (local, dev, prod). `local` is LocalStack; the others are AWS accounts."
  type        = string
  default     = "dev"
}

variable "aws_endpoint_url" {
  description = "Where the AWS APIs live. Empty against AWS, which selects the real resolver; LocalStack's endpoint for the `local` environment. This one variable is the whole difference between the two -- see the providers in versions.tf."
  type        = string
  default     = ""
}

variable "enable_dns" {
  description = "Route 53 zone, the ACM certificates validated against it, the ALB's HTTPS listener and CloudFront's custom alias. Off for `local`: nothing emulates DNS validation, so the certificate would never leave PENDING_VALIDATION and the apply would hang. CloudFront falls back to its own default certificate and its generated domain name."
  type        = bool
  default     = true
}

variable "mcp_image_tag" {
  description = "Tag of the captal-mcp image the backoffice runs. No default on purpose: a floating tag means the deployed version changes on the next build with nobody deciding it, which is how `docker/rqlite/Dockerfile` came to run a database engine two major versions from the one the tests use. Every environment names a version, and an environment that has never published an image has nothing to name yet -- which is the correct thing to be stopped by."
  type        = string
}

variable "mcp_desired_count" {
  description = "How many backoffice tasks. One in a deployed environment -- it holds no state of its own and a second would duplicate the scheduled-job rehydration at boot. Zero for `local`, where the suite runs the server in-process and only needs the resources it attaches to."
  type        = number
  default     = 1
}

variable "mcp_cpu" {
  description = "Fargate CPU units for the backoffice. Above a location's API because it holds the JVM plus the AWS SDK clients for ECS, ELBv2, S3 and CloudFront."
  type        = number
  default     = 512
}

variable "mcp_memory" {
  description = "Fargate memory in MiB for the backoffice"
  type        = number
  default     = 1024
}

variable "enable_tailscale" {
  description = "The Tailscale subnet-router daemon and the rqlite ingress rule that lets it through. Off for `local`, where reaching into the network from a laptop is what `docker exec` already does -- and where the provider would talk to Tailscale's real API."
  type        = bool
  default     = true
}

# `enable_nat_gateway` was here. It chose between private subnets with a NAT gateway and public
# subnets with per-task public IPs, and it defaulted to the latter with no environment overriding
# it -- so the private half was built and paid for by nobody. The default VPC has only public
# subnets, which is what the remaining topology uses; see `data "aws_vpc" "default"` in main.tf.

variable "cloudfront_domain" {
  description = "CloudFront domain name for this environment"
  type        = string
}

variable "container_port" {
  description = "Port the container listens on"
  type        = number
  default     = 8080
}

# `container_cpu`, `container_memory` and `desired_count` were here. Nothing read them: a location's
# portal has no ECS service in this configuration, because the backoffice registers one per location
# at deploy time and sizes it there. The `rqlite_*` and `mcp_*` equivalents below are real.

variable "image_tag" {
  description = "Tag of the captal-api image every location's task definition runs. No default, and `latest` is rejected: a floating tag means the deployed version changes on the next image build with nobody deciding it. That is not hypothetical here -- `docker/rqlite/Dockerfile` said `:latest` and the database engine drifted two major versions, which nothing would have reported."
  type        = string

  validation {
    condition     = var.image_tag != "latest"
    error_message = "image_tag must name a version. `latest` is how the rqlite engine moved from the 8 line to 10.6.1 unnoticed; pick the tag you mean, and change it deliberately."
  }
}

variable "domain_name" {
  description = "Domain name for the hosted zone"
  type        = string
  default     = "captal.centauroads.com"
}

variable "rqlite_desired_count" {
  description = "Desired number of rqlite nodes. 1 = EFS-backed single instance (dev). 3/5/etc = Raft cluster on ephemeral storage with S3 backups (prod)."
  type        = number
  default     = 1
}

variable "rqlite_cpu" {
  description = "CPU units for rqlite container (1024 = 1 vCPU)"
  type        = number
  default     = 256
}

variable "rqlite_memory" {
  description = "Memory for rqlite container in MB"
  type        = number
  default     = 512
}

variable "rqlite_image_tag" {
  description = "Tag of the custom rqlite image in ECR. Bump after each entrypoint/Dockerfile change so the ECS task definition changes and triggers a clean rolling deploy."
  type        = string
  default     = "1.0.0"
}

variable "tailscale_proxy_desired_count" {
  description = "Desired number of Tailscale proxy replicas. 1 is enough — stateless, retries cover gaps."
  type        = number
  default     = 1
}

variable "tailscale_proxy_cpu" {
  description = "CPU units for Tailscale proxy container (1024 = 1 vCPU)"
  type        = number
  default     = 256
}

variable "tailscale_proxy_memory" {
  description = "Memory for Tailscale proxy container in MB"
  type        = number
  default     = 512
}

# ── Tailscale auth key ─────────────────────────────────────────────────────
# Provider credentials (api_key + tailnet) are read directly from the env vars
# TAILSCALE_API_KEY and TAILSCALE_TAILNET — kept out of TF state.

variable "tailscale_authkey_expiry_seconds" {
  description = "Auth key lifetime in seconds. Max 7776000 (90 days). When elapsed, next `tofu apply` mints a new key and rolls the proxy task."
  type        = number
  default     = 7776000
}

variable "enable_global_accelerator" {
  description = "Provision Global Accelerator + portal dispatcher Lambda. Required for UniFi External Portal Server integration (UniFi only accepts IPv4 in that field). Costs ~$18/mo for the accelerator + per-request Lambda + traffic. Disable in environments not integrating with UniFi."
  type        = bool
  default     = true
}


variable "mcp_create_test_user" {
  description = "Create an IAM user carrying the backoffice's policy, with an access key, for running it outside ECS where the task role is not reachable. On for `local`, where it is what gives LocalStack's IAM enforcement an identity to evaluate; off everywhere else, because the key's secret is stored in state in plaintext. See the resource in modules/mcp."
  type        = bool
  default     = false
}
