variable "domain_name" {
  description = "Hosted zone name (apex)"
  type        = string
}

variable "env_prefix" {
  description = "Environment prefix for the CloudFront subdomain (production, staging)"
  type        = string
}

variable "cloudfront_dns_name" {
  description = "CloudFront distribution domain name (alias for the subdomain)"
  type        = string
}

variable "cloudfront_zone_id" {
  description = "CloudFront hosted zone ID (alias for the subdomain)"
  type        = string
}

