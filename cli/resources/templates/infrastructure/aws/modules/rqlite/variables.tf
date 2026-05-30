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
  description = "Subnet IDs for ECS tasks"
  type        = list(string)
}

variable "ecs_cluster_id" {
  description = "ECS cluster ID to run rqlite tasks in"
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
  description = "App ECS tasks security group ID (to allow HTTP API access)"
  type        = string
}

variable "desired_count" {
  description = "Desired number of rqlite nodes. desired_count=1 turns on EFS persistence + stop-then-start deploys. desired_count >= 3 (odd) uses Raft quorum + ephemeral storage and S3 auto-backup."
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count == 1 || (var.desired_count % 2 == 1 && var.desired_count >= 3)
    error_message = "rqlite desired_count must be 1 (EFS-backed) or an odd number >= 3 (Raft cluster, ephemeral)."
  }
}

variable "container_cpu" {
  description = "CPU units for rqlite container"
  type        = number
  default     = 256
}

variable "container_memory" {
  description = "Memory for rqlite container in MB"
  type        = number
  default     = 512
}

variable "assign_public_ip" {
  description = "Assign public IP to tasks (needed for public subnets without NAT)"
  type        = bool
  default     = true
}

variable "backup_interval" {
  description = "rqlite auto-backup interval (rqlite duration string, e.g. \"1m\", \"30s\", \"5m\"). Lower values reduce the data-loss window for multi-node deploys/quorum loss but increase S3 PUT cost."
  type        = string
  default     = "1m"
}

variable "image_tag" {
  description = "Tag of the rqlite image to deploy from the project ECR repo. Bump when shipping a new entrypoint/Dockerfile change so the task definition revision changes and ECS rolls cleanly. Avoid 'latest' — it caches in surprising ways across redeploys."
  type        = string
  default     = "1.0.0"
}

