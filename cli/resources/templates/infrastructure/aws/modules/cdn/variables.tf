variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "https_listener_arn" {
  description = "HTTPS listener ARN that routes default traffic to this Lambda target group"
  type        = string
}

variable "bucket_name" {
  description = "S3 bucket name (managed by the cloudfront module) that the Lambda reads from"
  type        = string
}

variable "bucket_arn" {
  description = "S3 bucket ARN (managed by the cloudfront module) used in the IAM policy resource"
  type        = string
}
