variable "project_name" {
  description = "Project name (used to build the accelerator's display name)"
  type        = string
}

variable "environment" {
  description = "Environment (dev, staging, prod)"
  type        = string
}

variable "aws_region" {
  description = "AWS region where the ALB endpoint lives (Global Accelerator is global but endpoint groups are regional)"
  type        = string
}

variable "alb_arn" {
  description = "ALB ARN to route Global Accelerator TCP/80 traffic to"
  type        = string
}
