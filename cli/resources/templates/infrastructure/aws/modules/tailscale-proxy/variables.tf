variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "aws_region" {
  description = "AWS region"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs for the proxy ECS task"
  type        = list(string)
}

variable "ecs_cluster_id" {
  description = "ECS cluster ID to run the proxy task in"
  type        = string
}

variable "service_discovery_namespace_id" {
  description = "CloudMap private DNS namespace ID"
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "CloudMap private DNS namespace name (e.g. captal.local)"
  type        = string
}

variable "app_security_group_id" {
  description = "App ECS tasks security group ID (allowed inbound to proxy port)"
  type        = string
}

variable "ts_authkey_secret_arn" {
  description = "AWS Secrets Manager ARN holding the Tailscale auth key (tskey-auth-...). Value injected at task start via secrets[]."
  type        = string
}

variable "ts_authkey_secret_version_id" {
  description = "Version ID of the secret. Surfaced as a cosmetic env var so a key rotation changes the task definition and triggers an ECS rolling deploy that re-reads the secret. Optional; pass null to disable rotation-triggered redeploys."
  type        = string
  default     = null
}

variable "desired_count" {
  description = "Desired number of proxy replicas. 1 is enough — the proxy is stateless and retries cover brief gaps."
  type        = number
  default     = 1
}

variable "container_cpu" {
  description = "CPU units for the proxy container (1024 = 1 vCPU)"
  type        = number
  default     = 256
}

variable "container_memory" {
  description = "Memory for the proxy container in MB"
  type        = number
  default     = 512
}

variable "assign_public_ip" {
  description = "Assign public IP to the task (needed when subnets have no NAT egress; Tailscale needs outbound HTTPS to controlplane)"
  type        = bool
  default     = true
}
