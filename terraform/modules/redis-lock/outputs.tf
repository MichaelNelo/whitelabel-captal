output "addresses" {
  description = "Every instance as `host:port`, comma separated, as the backoffice's REDIS_LOCK_ADDRESSES reads them"
  value = join(",", [
    for index in range(var.instances) :
    "redis-lock-${index}.${var.service_discovery_namespace_name}:${var.port}"
  ])
}

output "service_names" {
  description = "The instances' ECS services, comma separated, for the local release to keep their names resolving"
  value       = join(",", aws_ecs_service.redis[*].name)
}
