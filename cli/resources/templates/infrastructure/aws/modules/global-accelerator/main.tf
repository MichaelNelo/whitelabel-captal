locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

# Standard Global Accelerator — IPv4 anycast static IPs (2) routed to AWS
# endpoints. The IPs survive ALB recreation and the underlying endpoint can be
# swapped without changing what UniFi sees. Required because UniFi External
# Portal Server validates the field as IPv4 only (no hostnames).
resource "aws_globalaccelerator_accelerator" "main" {
  name            = local.name_prefix
  ip_address_type = "IPV4"
  enabled         = true
}

# TCP/80 listener — captive portal redirect traffic from UCG is always HTTP
# (devices don't trust certs for literal IPs). The 302 to HTTPS happens at the
# next hop (Lambda → CloudFront).
resource "aws_globalaccelerator_listener" "http" {
  accelerator_arn = aws_globalaccelerator_accelerator.main.id
  protocol        = "TCP"
  client_affinity = "NONE"

  port_range {
    from_port = 80
    to_port   = 80
  }
}

# Endpoint group routing 100% of traffic to the existing ALB. Health check
# expects HTTP 200-399 on `/` port 80 — our ALB listener responds 301 to that,
# which counts as healthy.
resource "aws_globalaccelerator_endpoint_group" "alb" {
  listener_arn                  = aws_globalaccelerator_listener.http.id
  endpoint_group_region         = var.aws_region
  health_check_protocol         = "HTTP"
  health_check_port             = 80
  health_check_path             = "/"
  health_check_interval_seconds = 30
  threshold_count               = 3

  endpoint_configuration {
    endpoint_id = var.alb_arn
    weight      = 100
  }
}
