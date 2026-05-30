variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "cloudfront_domain" {
  description = "CloudFront domain name for this environment (e.g. staging.captal.centauroads.com)"
  type        = string
}

variable "alb_dns_name" {
  description = "ALB DNS name (origin for /*/api/*)"
  type        = string
}

variable "certificate_arn" {
  description = "ACM cert ARN (us-east-1) for the distribution"
  type        = string
}

variable "default_ttl_seconds" {
  description = "Default TTL for cached S3 assets"
  type        = number
  default     = 3600
}
