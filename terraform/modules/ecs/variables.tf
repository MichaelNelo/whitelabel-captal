variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID (used by the ECS tasks security group)"
  type        = string
}

variable "container_port" {
  description = "Container port (used by the ECS tasks security group ingress rule)"
  type        = number
  default     = 8080
}

variable "alb_security_group_id" {
  description = "ALB security group ID (allowed source for the ECS tasks SG)"
  type        = string
}

variable "ecs_security_group_id" {
  description = "External ECS tasks security group ID (required if create_security_group is false)"
  type        = string
  default     = ""
}

variable "create_security_group" {
  description = "Whether to create the ECS tasks security group (set to false if providing external)"
  type        = bool
  default     = true
}
