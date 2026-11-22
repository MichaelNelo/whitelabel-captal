variable "project_name" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment"
  type        = string
}

variable "aws_region" {
  description = "Region, for the log driver"
  type        = string
}

variable "cluster_id" {
  description = "ECS cluster the instances run in"
  type        = string
}

variable "vpc_id" {
  description = "VPC of the security group"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the tasks"
  type        = list(string)
}

variable "service_discovery_namespace_id" {
  description = "Cloud Map namespace each instance is named in. Empty where Cloud Map is off, and then each name is its service's `DiscoveryName` tag."
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "That namespace's name, which the instances' addresses end in"
  type        = string
}

variable "execution_role_arn" {
  description = "Task execution role, which pulls the image and writes the logs"
  type        = string
}

variable "caller_security_group_ids" {
  description = "Security groups of the services that take locks"
  type        = list(string)
}

variable "instances" {
  description = "How many independent instances the locks are taken on. Redlock holds a lock on a majority, so one instance down is survived from three on; with one, it is Redis's lock on a single instance."
  type        = number
  default     = 1
}

variable "image" {
  description = "Redis's image, from the public ECR gallery rather than Docker Hub, whose pull limits a task start would meet. A version, never `latest`."
  type        = string
  default     = "public.ecr.aws/docker/library/redis:7.4.1-alpine"
}

variable "port" {
  description = "Port Redis listens on"
  type        = number
  default     = 6379
}

variable "container_cpu" {
  description = "Fargate CPU units"
  type        = number
  default     = 256
}

variable "container_memory" {
  description = "Fargate memory in MiB: a lock is a key and a token"
  type        = number
  default     = 512
}
