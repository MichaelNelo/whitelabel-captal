variable "project_name" {
  description = "Project name, the first part of every resource's name"
  type        = string
}

variable "environment" {
  description = "Environment name, the second part of every resource's name"
  type        = string
}

variable "aws_region" {
  description = "Region the log group is in, for the container's log driver"
  type        = string
}

variable "cluster_id" {
  description = "ECS cluster the service runs in"
  type        = string
}

variable "vpc_id" {
  description = "VPC the security group is in"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets the task runs in. Public, since the task carries its own address and pulls its image through the internet gateway."
  type        = list(string)
}

variable "service_discovery_namespace_id" {
  description = "Cloud Map namespace the service registers in, as `jaeger`"
  type        = string
}

variable "execution_role_arn" {
  description = "Role ECS itself assumes to pull the image and write the logs"
  type        = string
}

variable "sender_security_group_ids" {
  description = "Groups of the tasks that send traces: the services and every location's API"
  type        = list(string)
}

variable "ui_security_group_ids" {
  description = "Groups allowed to reach the UI: the Tailscale proxy's, when there is one. Empty, and nothing is."
  type        = list(string)
  default     = []
}

variable "image" {
  description = "Jaeger's image, from its own registry. A version, never `latest`."
  type        = string
  default     = "cr.jaegertracing.io/jaegertracing/jaeger:2.23.0"
}

variable "otlp_port" {
  description = "OTLP over gRPC, the port the services send to"
  type        = number
  default     = 4317
}

variable "ui_port" {
  description = "The UI and its query API"
  type        = number
  default     = 16686
}

variable "container_cpu" {
  description = "Fargate CPU units"
  type        = number
  default     = 512
}

variable "container_memory" {
  description = "Fargate memory in MiB: the traces are kept in it"
  type        = number
  default     = 2048
}
