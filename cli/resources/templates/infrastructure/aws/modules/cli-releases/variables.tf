variable "project_name" {
  description = "Project name (used to construct bucket name)"
  type        = string
}

variable "environment" {
  description = "Environment (suffix)"
  type        = string
}

variable "noncurrent_version_retention_days" {
  description = "Days to retain noncurrent versions of release artifacts before deletion (older releases that have been overwritten under v<version>/ or latest/)"
  type        = number
  default     = 90
}
