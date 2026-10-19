output "url" {
  description = "Where the MCP endpoint is reached — the value the server also advertises as its resource URI"
  value       = local.dns_enabled ? "https://${var.hostname}/mcp" : "http://${var.alb_dns_name}/mcp"
}

output "service_name" {
  description = "ECS service name, for `aws ecs update-service --force-new-deployment` after an image push"
  value       = aws_ecs_service.mcp.name
}

output "task_role_arn" {
  description = "Role the server runs as. Its own, so a location's API task does not inherit the deploy permissions."
  value       = aws_iam_role.task.arn
}

output "log_group_name" {
  description = "Where the server's own output lands"
  value       = aws_cloudwatch_log_group.mcp.name
}

output "admin_token_secret_arn" {
  description = "Secrets Manager ARN of the admin token. Read it with `aws secretsmanager get-secret-value`; it is not an output of this configuration because that would put it in state as plaintext in the plan."
  value       = aws_secretsmanager_secret.admin_token.arn
}

output "test_user_name" {
  description = "IAM user carrying the same policy, for running the server outside ECS. Null unless asked for."
  value       = var.create_test_user ? aws_iam_user.test[0].name : null
}

output "test_user_access_key_id" {
  description = "Access key for the user above, so a local suite can be subject to the policy rather than bypassing it. Null unless asked for."
  value       = var.create_test_user ? aws_iam_access_key.test[0].id : null
}

output "test_user_secret_access_key" {
  description = "Secret for the key above. Only ever produced for `local`, where the account is emulated; see the resource for why it is safe there and absent everywhere else."
  value       = var.create_test_user ? aws_iam_access_key.test[0].secret : null
  sensitive   = true
}
