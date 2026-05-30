variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for ALB"
  type        = list(string)
}

variable "container_port" {
  description = "Container port"
  type        = number
  default     = 8080
}

variable "health_check_path" {
  description = "Health check path"
  type        = string
  default     = "/api/health"
}

variable "enable_deletion_protection" {
  description = "Enable deletion protection for ALB"
  type        = bool
  default     = false
}

variable "enable_portal_dispatcher_rule" {
  description = "When true, add a high-priority HTTP listener rule routing /guest/s/* to the dispatcher Lambda. Must be a static bool (NOT derived from a module attribute) so count evaluates at plan time."
  type        = bool
  default     = false
}

variable "portal_dispatcher_target_group_arn" {
  description = "Target group ARN for the captive portal dispatcher Lambda. Only consumed when enable_portal_dispatcher_rule = true. Empty string is fine when the rule isn't being created."
  type        = string
  default     = ""
}
