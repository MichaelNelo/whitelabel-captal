output "security_group_id" {
  description = "Tailscale proxy security group ID"
  value       = aws_security_group.tailscale_proxy.id
}

output "service_name" {
  description = "Tailscale proxy ECS service name"
  value       = aws_ecs_service.tailscale_proxy.name
}

output "discovery_dns_name" {
  description = "DNS name for proxy service discovery (e.g. tailscale-proxy.captal.local)"
  value       = local.dns_name
}

output "proxy_url" {
  description = "URL to put in shared/captal.yaml unifi.proxyUrl"
  value       = "http://${local.dns_name}:${local.proxy_port}"
}
