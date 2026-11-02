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
  description = "Tag of the captal-mcp image the backoffice runs: the backoffice's version, which the build passes from its `mcp/v*` git tags and is set in no tfvars. No default on purpose: a floating tag means the deployed version changes on the next build with nobody deciding it, which is how `docker/rqlite.Dockerfile` came to run a database engine two major versions from the one the tests use."
  type        = string
}

variable "mcp_public_origin" {
  description = "Scheme, host and port clients reach the backoffice at, when not `backoffice.<domain_name>` or the load balancer: a tunnel to a local one, which `mill mcp.dev --tunnel` passes. Empty derives it."
  type        = string
  default     = ""
}

variable "public_port" {
  description = "Port the load balancer and the distribution answer on from outside, when not the scheme's own. LocalStack serves both on its gateway port; 0 everywhere else."
  type        = number
  default     = 0
}

variable "assets_endpoint_url" {
  description = "Where an operator's browser reaches the assets bucket directly rather than through the distribution: LocalStack's gateway, or a tunnel to it. The backoffice's pages load their scripts from it and upload to it. Empty in AWS."
  type        = string
  default     = ""
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
  description = "Tag of the captal-api image every location's task definition runs: the API's version, which the build passes from its `api/v*` git tags and is set in no tfvars. No default, and `latest` is rejected: a floating tag means the deployed version changes on the next image build with nobody deciding it. That is not hypothetical here -- `docker/rqlite.Dockerfile` said `:latest` and the database engine drifted two major versions, which nothing would have reported."
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

variable "rqlite_http_port" {
  description = "Port rqlite serves its HTTP API on. Leave it alone outside `local`: under awsvpc each task has its own address and nothing collides. LocalStack publishes a task's ports on the host, where 4001 is already the plain rqlite container the dev commands run."
  type        = number
  default     = 4001
}

variable "rqlite_raft_port" {
  description = "Port rqlite's Raft transport listens on. Moves with rqlite_http_port."
  type        = number
  default     = 4002
}

variable "rqlite_image_tag" {
  description = "Tag of the custom rqlite image in ECR, which every environment's tfvars sets. Bump it with each entrypoint or Dockerfile change: the apply pushes the image when it changes, and the new digest replaces every node."
  type        = string
}

variable "rqlite_cdc_endpoint" {
  description = "Where rqlite posts its change data capture. Empty means the relay by its service discovery name, which is right wherever it runs as a task beside rqlite."
  type        = string
  default     = ""
}

variable "rqlite_cdc_table_filter" {
  description = "The tables whose changes rqlite posts to the relay, as a regular expression: `video_playbacks`, which the backoffice follows to collect replaced video files, and `errors`, which a location's API follows to shorten a recovery wait when an error recurs. Changing it changes rqlite's task definition, which replaces every node."
  type        = string
  default     = "^(video_playbacks|errors)$"
}

variable "cdc_image_tag" {
  description = "Tag of the captal-cdc image the change data capture relay runs: the relay's version, which the build passes from its `cdc/v*` git tags and is set in no tfvars. No default, as for the other images: a floating tag changes the deployed version with nobody deciding it."
  type        = string
}

variable "enable_location_faults" {
  description = "Whether the locations the backoffice deploys mount the API's dev endpoints, which make a server or a client error happen on demand for the end-to-end features to recover from: `local` only. Never in an AWS environment, where a guest could reach them."
  type        = bool
  default     = false
}

variable "enable_unifi_fake" {
  description = "A stand-in for a venue's UniFi controller, which locations can be pointed at to grant and revoke access with no controller: `local` only, for the end-to-end features. Never in an AWS environment, where a location's access is a real controller's."
  type        = bool
  default     = false
}

variable "cdc_desired_count" {
  description = "How many relay tasks: one in a deployed environment, since rqlite posts to one address and every subscriber has to follow that same relay. Zero for `local`, where `mill mcp.dev` runs it on the host."
  type        = number
  default     = 1
}

variable "cdc_cpu" {
  description = "Fargate CPU units for the relay"
  type        = number
  default     = 256
}

variable "cdc_memory" {
  description = "Fargate memory in MiB for the relay: the JVM, and the change sets it keeps for subscribers that reconnect"
  type        = number
  default     = 512
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
