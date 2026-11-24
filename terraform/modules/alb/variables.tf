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

variable "http_port" {
  description = "Port of the HTTP listener: 80, or on LocalStack its gateway port, which it gives every listener whatever it is told. Declaring anything else there makes every apply move the listener back, and LocalStack stops routing while it rebuilds it."
  type        = number
  default     = 80
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

