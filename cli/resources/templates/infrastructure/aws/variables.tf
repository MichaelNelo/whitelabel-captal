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
  description = "Environment (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "cloudfront_domain" {
  description = "CloudFront domain name for this environment"
  type        = string
}

variable "container_port" {
  description = "Port the container listens on"
  type        = number
  default     = 8080
}

variable "container_cpu" {
  description = "CPU units for the container (1024 = 1 vCPU)"
  type        = number
  default     = 256
}

variable "container_memory" {
  description = "Memory for the container in MB"
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Desired number of ECS tasks"
  type        = number
  default     = 1
}

variable "image_tag" {
  description = "Docker image tag for the application container"
  type        = string
  default     = "latest"
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

