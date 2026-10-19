output "static_ips" {
  description = "Two static anycast IPv4 addresses assigned by Global Accelerator. Use either one in UniFi External Portal Server."
  value       = aws_globalaccelerator_accelerator.main.ip_sets[0].ip_addresses
}

output "accelerator_dns_name" {
  description = "DNS name of the GA accelerator (alternative to static IPs for clients that accept hostnames)"
  value       = aws_globalaccelerator_accelerator.main.dns_name
}
