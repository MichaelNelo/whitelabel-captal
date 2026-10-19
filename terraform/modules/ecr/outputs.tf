output "api_repository_url" {
  description = "ECR repo URL for captal-api, the image every location's task definition runs"
  value       = aws_ecr_repository.repos["captal-api"].repository_url
}

output "mcp_repository_url" {
  description = "ECR repo URL for captal-mcp, the backoffice server"
  value       = aws_ecr_repository.repos["captal-mcp"].repository_url
}
