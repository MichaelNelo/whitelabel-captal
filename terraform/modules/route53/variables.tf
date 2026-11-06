variable "domain_name" {
  description = "Hosted zone name (apex)"
  type        = string
}

variable "record_name" {
  description = "The name pointed at CloudFront, which must be the distribution's alias: the zone's apex or a name under it"
  type        = string
}

variable "cloudfront_dns_name" {
  description = "CloudFront distribution domain name, which the record is an alias for"
  type        = string
}

variable "cloudfront_zone_id" {
  description = "CloudFront hosted zone ID, which the record is an alias for"
  type        = string
}

