output "host" {
  description = "Where a location's UniFi settings point to reach it"
  value       = local.host
}

output "port" {
  description = "The port those settings name"
  value       = var.controller_port
}

output "records_url" {
  description = "Its record of calls, as the host reaches it"
  value       = "http://localhost:${var.records_port}"
}

output "service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.controller.name
}
