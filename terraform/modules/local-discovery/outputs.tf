output "service_name" {
  description = "The registrar's ECS service"
  value       = aws_ecs_service.registrar.name
}

output "log_group_name" {
  description = "Where the registrar says which names it changed"
  value       = aws_cloudwatch_log_group.registrar.name
}
