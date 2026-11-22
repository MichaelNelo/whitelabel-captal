output "ingest_url" {
  description = "Where rqlite posts its change data capture"
  value       = "http://cdc.${var.service_discovery_namespace_name}:${var.http_port}/cdc"
}

output "topic_arns" {
  description = "Each table's topic, by the table's name"
  value       = { for table, topic in aws_sns_topic.table : table => topic.arn }
}

output "topic_prefix" {
  description = "A table's topic's ARN but for the table's name: the subscribers' CDC_TOPIC_PREFIX"
  value       = local.topic_prefix
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
