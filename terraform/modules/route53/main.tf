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

# The name CloudFront answers for, which is its alias: the zone's apex when they are the same.
resource "aws_route53_record" "cloudfront" {
  zone_id = aws_route53_zone.main.zone_id
  name    = var.record_name
  type    = "A"

  alias {
    name                   = var.cloudfront_dns_name
    zone_id                = var.cloudfront_zone_id
    evaluate_target_health = false
  }
}
