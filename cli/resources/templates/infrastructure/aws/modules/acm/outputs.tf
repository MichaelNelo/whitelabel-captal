output "certificate_arn" {
  description = "Regional ACM certificate ARN (ALB)"
  value       = aws_acm_certificate.main.arn
}

output "certificate_arn_validated" {
  description = "Regional ACM certificate ARN, after validation (ALB)"
  value       = aws_acm_certificate_validation.main.certificate_arn
}

output "cloudfront_certificate_arn" {
  description = "us-east-1 ACM certificate ARN (CloudFront)"
  value       = aws_acm_certificate.cloudfront.arn
}

output "cloudfront_certificate_arn_validated" {
  description = "us-east-1 ACM certificate ARN, after validation (CloudFront)"
  value       = aws_acm_certificate_validation.cloudfront.certificate_arn
}
