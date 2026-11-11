variable "project_name" {
  description = "Project name, used to build resource names"
  type        = string
}

variable "environment" {
  description = "Environment name (local, prod)"
  type        = string
}

variable "aws_region" {
  description = "Region the task's logs are written in"
  type        = string
}

# ─── Where it runs ──────────────────────────────────────────────────────────

variable "cluster_id" {
  description = "ECS cluster the service runs in"
  type        = string
}

variable "vpc_id" {
  description = "VPC the relay's security group belongs to"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets the task runs in. Public, since the task carries its own address and reaches ECR through the internet gateway."
  type        = list(string)
}

variable "service_discovery_namespace_id" {
  description = "Private DNS namespace the relay registers in as `cdc`"
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "That namespace's name, which the addresses this module outputs are under"
  type        = string
}

variable "execution_role_arn" {
  description = "Role ECS itself assumes to pull the image"
  type        = string
}

# ─── Who reaches it ─────────────────────────────────────────────────────────

variable "rqlite_security_group_id" {
  description = "rqlite's group, admitted on the HTTP port its change data capture posts to"
  type        = string
}

variable "subscriber_security_group_id" {
  description = "The group of the tasks that subscribe, admitted on the gRPC port: the shared ECS tasks group, which the backoffice and every location's API run in"
  type        = string
}

# ─── The task ───────────────────────────────────────────────────────────────

variable "image" {
  description = "Image the relay runs, by its version tag"
  type        = string
}

variable "http_port" {
  description = "Port rqlite posts its change data capture to"
  type        = number
}

variable "grpc_port" {
  description = "Port subscribers follow the change feed on"
  type        = number
  default     = 9090
}

variable "buffer" {
  description = "How many change sets the relay keeps for subscribers that reconnect. One that missed more is told it has a gap and rebuilds from the database."
  type        = number
  default     = 1000
}

variable "container_cpu" {
  description = "Fargate CPU units"
  type        = number
}

variable "container_memory" {
  description = "Fargate memory in MiB"
  type        = number
}

variable "desired_count" {
  description = "How many relay tasks: one, or zero where it runs elsewhere"
  type        = number
}

variable "otlp_endpoint" {
  description = "Where the relay sends its traces over OTLP. Empty, and it keeps none."
  type        = string
  default     = ""
}
