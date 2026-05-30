output "bucket_name" {
  description = "S3 bucket name holding CLI release artifacts (consumed by `./mill cli.publishS3 --bucket <name>`)"
  value       = aws_s3_bucket.releases.id
}

output "bucket_arn" {
  description = "S3 bucket ARN"
  value       = aws_s3_bucket.releases.arn
}

output "bucket_url" {
  description = "Convenience: s3://<bucket-name> URI"
  value       = "s3://${aws_s3_bucket.releases.id}"
}
