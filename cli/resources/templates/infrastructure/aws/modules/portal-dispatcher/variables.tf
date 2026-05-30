variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where the Lambda runs (same as rqlite)"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the Lambda VPC interfaces. Use public subnets so the Lambda can reach AWS APIs without NAT — the dispatcher's SG only allows 443 outbound + the rqlite SG."
  type        = list(string)
}

variable "rqlite_security_group_id" {
  description = "Security group ID of the rqlite ECS tasks. Used as the egress target for port 4001."
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "CloudMap namespace name (e.g. 'captal.local') — used to build rqlite's resolvable URL"
  type        = string
}

variable "cloudfront_host" {
  description = "CloudFront-fronted hostname devices are redirected to (e.g. 'staging.captal.centauroads.com'). The Lambda 302s here once the slug is resolved."
  type        = string
}
