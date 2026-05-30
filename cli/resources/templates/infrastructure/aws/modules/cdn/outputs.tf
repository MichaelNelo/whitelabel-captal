output "lambda_target_group_arn" {
  description = "Lambda target group ARN (used as default_action of the HTTPS listener)"
  value       = aws_lb_target_group.lambda.arn
}
