output "ingest_url" {
  description = "Where rqlite posts its change data capture"
  value       = "http://cdc.${var.service_discovery_namespace_name}:${var.http_port}/cdc"
}

output "feed_address" {
  description = "`host:port` of the change feed, which subscribers are given"
  value       = "cdc.${var.service_discovery_namespace_name}:${var.grpc_port}"
}

output "service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.cdc.name
}

output "log_group_name" {
  description = "Where the relay's output lands"
  value       = aws_cloudwatch_log_group.cdc.name
}

output "security_group_id" {
  description = "The relay's security group, which Jaeger admits its traces from"
  value       = aws_security_group.cdc.id
}
