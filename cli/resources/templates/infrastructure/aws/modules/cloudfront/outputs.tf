output "distribution_id" {
  description = "CloudFront distribution ID"
  value       = aws_cloudfront_distribution.main.id
}

output "distribution_arn" {
  description = "CloudFront distribution ARN"
  value       = aws_cloudfront_distribution.main.arn
}

output "distribution_domain_name" {
  description = "CloudFront-assigned domain name (xxxxx.cloudfront.net)"
  value       = aws_cloudfront_distribution.main.domain_name
}

output "distribution_hosted_zone_id" {
  description = "CloudFront hosted zone ID (always Z2FDTNDATAQYW2 in current AWS)"
  value       = aws_cloudfront_distribution.main.hosted_zone_id
}

output "assets_bucket_name" {
  description = "S3 bucket holding bundle/ and per-location prefixes"
  value       = aws_s3_bucket.assets.id
}

output "assets_bucket_arn" {
  description = "S3 bucket ARN"
  value       = aws_s3_bucket.assets.arn
}
