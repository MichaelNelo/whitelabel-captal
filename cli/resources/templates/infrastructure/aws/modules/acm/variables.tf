variable "domain_name" {
  description = "Domain name for the certificate"
  type        = string
}

variable "zone_id" {
  description = "Route 53 zone ID for DNS validation"
  type        = string
}
