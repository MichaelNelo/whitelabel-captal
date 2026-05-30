resource "aws_route53_zone" "main" {
  name = var.domain_name

  tags = {
    Name = var.domain_name
  }
}

moved {
  from = aws_route53_record.production
  to   = aws_route53_record.cloudfront
}

# CloudFront — production or staging depending on environment
resource "aws_route53_record" "cloudfront" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "${var.env_prefix}.${var.domain_name}"
  type    = "A"

  alias {
    name                   = var.cloudfront_dns_name
    zone_id                = var.cloudfront_zone_id
    evaluate_target_health = false
  }
}
