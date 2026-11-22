variable "project_name" {
  description = "Project name, used to build resource names"
  type        = string
}

variable "environment" {
  description = "Environment name; only one with Cloud Map off has this module"
  type        = string
}

variable "aws_region" {
  description = "Region the task's logs are written in"
  type        = string
}

# ─── Where it runs ──────────────────────────────────────────────────────────

variable "cluster_id" {
  description = "ECS cluster whose services it keeps the names of, and runs in"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets the task runs in"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group the task runs in; it calls the AWS API and nothing calls it"
  type        = string
}

variable "execution_role_arn" {
  description = "Role ECS itself assumes to pull the image and write the logs"
  type        = string
}

# ─── What it keeps ──────────────────────────────────────────────────────────

variable "zone_id" {
  description = "The private zone the names are kept in, the one Cloud Map would make"
  type        = string
}

variable "zone_name" {
  description = "That zone's name, which every name is under: `<DiscoveryName>.<zone_name>`"
  type        = string
}

variable "interval" {
  description = "Seconds between two rounds, and so at most how long a task that started goes unnamed"
  type        = number
  default     = 2
}

variable "image" {
  description = "The AWS CLI's image, which runs the registrar's script; the version the build's own CLI is"
  type        = string
  default     = "public.ecr.aws/aws-cli/aws-cli:2.34.24"
}
