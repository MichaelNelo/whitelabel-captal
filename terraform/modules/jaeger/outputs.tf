output "service_name" {
  description = "The ECS service, to find its task's private address for the UI"
  value       = aws_ecs_service.jaeger.name
}

output "log_group_name" {
  description = "Where Jaeger logs"
  value       = aws_cloudwatch_log_group.jaeger.name
}

output "security_group_id" {
  description = "Jaeger's security group"
  value       = aws_security_group.jaeger.id
}
