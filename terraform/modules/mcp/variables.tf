variable "project_name" {
  description = "Project name, used to build resource names"
  type        = string
}

variable "environment" {
  description = "Environment name (local, dev, prod)"
  type        = string
}

variable "aws_region" {
  description = "Region the task runs in, passed to the server so its AWS clients resolve"
  type        = string
}

variable "aws_endpoint_url" {
  description = "LocalStack endpoint, or empty against AWS. Empty means the variable is left out of the container definition entirely rather than set to an empty string -- the server reads it as an Option, and `Some(\"\")` would point every AWS client at nothing."
  type        = string
  default     = ""
}

# ─── Where it runs ──────────────────────────────────────────────────────────

variable "cluster_id" {
  description = "ECS cluster the service runs in"
  type        = string
}

variable "cluster_name" {
  description = "The same cluster by name, which is what the deploy tools name when they create a location's service"
  type        = string
}

variable "vpc_id" {
  description = "VPC the target group registers IPs in"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets the task runs in. Public, since the task carries its own address and reaches ECR through the internet gateway."
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group for the task. The shared ECS tasks group, which is what rqlite's own group allows ingress from -- so sharing it is what gives the server its database."
  type        = string
}

variable "execution_role_arn" {
  description = "Role ECS itself assumes to pull the image and read the admin token out of Secrets Manager"
  type        = string
}

variable "execution_role_name" {
  description = "The same role by name, so the policy granting it the secret can be attached"
  type        = string
}

# ─── The image and the service shape ───────────────────────────────────────

variable "image" {
  description = "Full image reference for the server, repository URL and tag"
  type        = string
}

variable "container_port" {
  description = "Port the server listens on"
  type        = number
  default     = 8080
}

variable "container_cpu" {
  description = "Fargate CPU units"
  type        = number
  default     = 512
}

variable "container_memory" {
  description = "Fargate memory in MiB"
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "How many tasks. One: the server holds no state of its own and a second would duplicate the scheduled-job rehydration at boot."
  type        = number
  default     = 1
}

# ─── How it is reached ──────────────────────────────────────────────────────

variable "alb_arn" {
  description = "Load balancer the listener rule hangs off"
  type        = string
}

variable "alb_dns_name" {
  description = "Load balancer's own name, for the DNS record pointing the hostname at it"
  type        = string
}

variable "alb_zone_id" {
  description = "Load balancer's hosted zone, for the same record"
  type        = string
}

variable "http_listener_arn" {
  description = "Port 80 listener. The rule attached here is what makes the server reachable locally, where there is no certificate and so no HTTPS listener."
  type        = string
}

variable "https_listener_arn" {
  description = "Port 443 listener, or empty when DNS is off and no certificate exists to present"
  type        = string
  default     = ""
}

variable "hostname" {
  description = "Public hostname, e.g. backoffice.captal.centauroads.com. Empty when DNS is off, and then the rule matches on path instead of host and no record is written."
  type        = string
  default     = ""
}

variable "zone_id" {
  description = "Route 53 zone the hostname's record goes in. Empty when DNS is off."
  type        = string
  default     = ""
}

variable "listener_rule_priority" {
  description = "Priority of the listener rule. Explicit because the load balancer evaluates rules in this order and the location rules the server creates pick their own numbers; keeping the backoffice low means it is matched before any of them."
  type        = number
  default     = 10
}

# ─── What it talks to ───────────────────────────────────────────────────────

variable "db_url" {
  description = "JDBC URL for rqlite, reached over service discovery inside the VPC"
  type        = string
}

variable "assets_bucket_name" {
  description = "Bucket holding per-location assets and the shared bundle"
  type        = string
}

variable "assets_bucket_arn" {
  description = "The same bucket, for the policy"
  type        = string
}

variable "cloudfront_distribution_id" {
  description = "Distribution the server invalidates after writing an asset"
  type        = string
}

variable "cloudfront_distribution_arn" {
  description = "The same distribution, for the policy"
  type        = string
}

variable "public_base_url" {
  description = "Where the portal's assets are served from, which is what gets stored as a video's URL"
  type        = string
}

# ─── What the deploy tools need to hand to a location ──────────────────────

variable "api_image" {
  description = "Image every location's task definition runs. One image for all of them: the slug arrives as an environment variable, not baked in."
  type        = string
}

variable "location_task_role_arn" {
  description = "Task role a location's API task runs as. Separate from the server's own, which carries the deploy permissions -- a location's API has no business creating services."
  type        = string
}

variable "create_test_user" {
  description = "An IAM user with the same policy, for running the server outside ECS where the task role is not reachable. Worth having locally: with LocalStack's IAM enforcement on, a permission missing here fails in the test suite rather than in front of an operator."
  type        = bool
  default     = false
}
