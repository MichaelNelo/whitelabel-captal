output "target_group_arn" {
  description = "ARN of the Lambda target group. Wire this into the ALB module so the HTTP listener routes /guest/s/* here."
  value       = aws_lb_target_group.dispatcher.arn
}

output "security_group_id" {
  description = "Security group ID of the dispatcher Lambda. Wire this into the rqlite module to allow ingress on port 4001."
  value       = aws_security_group.dispatcher.id
}

output "function_name" {
  description = "Lambda function name (for log tailing and direct invoke debugging)"
  value       = aws_lambda_function.dispatcher.function_name
}
