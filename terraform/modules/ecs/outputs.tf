output "cluster_id" {
  description = "ECS cluster ID"
  value       = aws_ecs_cluster.main.id
}

output "cluster_arn" {
  description = "ECS cluster ARN"
  value       = aws_ecs_cluster.main.arn
}

output "cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

output "security_group_id" {
  description = "ECS tasks security group ID"
  value       = local.ecs_security_group_id
}

output "execution_role_arn" {
  description = "ECS task execution IAM role ARN. Used by every per-location service the backoffice creates, and by the backoffice's own task."
  value       = aws_iam_role.ecs_execution.arn
}

output "execution_role_name" {
  description = "The same role by name, so a policy can be attached to it — the backoffice's admin token is read from Secrets Manager by this identity, before its container starts."
  value       = aws_iam_role.ecs_execution.name
}

output "task_role_arn" {
  description = "ECS task IAM role ARN"
  value       = aws_iam_role.ecs_task.arn
}

output "task_role_name" {
  description = "Name of the ECS task role, for attaching policies from other modules"
  value       = aws_iam_role.ecs_task.name
}
