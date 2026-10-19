output "ecr_repository_url" {
  description = "ECR repository URL for the custom rqlite image"
  value       = aws_ecr_repository.rqlite.repository_url
}

output "security_group_id" {
  description = "RQLite security group ID"
  value       = aws_security_group.rqlite.id
}

output "service_name" {
  description = "RQLite ECS service name"
  value       = aws_ecs_service.rqlite.name
}

output "discovery_dns_name" {
  description = "DNS name for rqlite service discovery (e.g. rqlite.captal.local)"
  value       = "rqlite.${var.service_discovery_namespace_name}"
}

output "http_url" {
  description = "RQLite HTTP API URL for application connection"
  value       = "jdbc:rqlite:http://rqlite.${var.service_discovery_namespace_name}:${local.http_port}"
}
