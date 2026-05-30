output "api_repository_url" {
  description = "ECR repo URL for captal-api base image"
  value       = aws_ecr_repository.repos["captal-api"].repository_url
}

output "provision_repository_url" {
  description = "ECR repo URL for captal-provision base image"
  value       = aws_ecr_repository.repos["captal-provision"].repository_url
}

output "shared_repository_url" {
  description = "ECR repo URL for captal-shared (derived images built by CLI shared push)"
  value       = aws_ecr_repository.repos["captal-shared"].repository_url
}

output "locations_repository_url" {
  description = "ECR repo URL for captal-locations (derived images built by CLI locations push)"
  value       = aws_ecr_repository.repos["captal-locations"].repository_url
}
