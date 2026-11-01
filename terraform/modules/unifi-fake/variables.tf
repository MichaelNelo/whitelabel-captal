variable "project_name" {
  description = "Project name, used to build resource names"
  type        = string
}

variable "environment" {
  description = "Environment name; only `local` runs this"
  type        = string
}

variable "aws_region" {
  description = "Region the task's logs are written in"
  type        = string
}

variable "cluster_id" {
  description = "ECS cluster the service runs in"
  type        = string
}

variable "vpc_id" {
  description = "VPC its security group belongs to"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets the task runs in"
  type        = list(string)
}

variable "service_discovery_namespace_id" {
  description = "Private DNS namespace it registers in as `unifi`"
  type        = string
}

variable "service_discovery_namespace_name" {
  description = "That namespace's name, which its address is under"
  type        = string
}

variable "execution_role_arn" {
  description = "Role ECS itself assumes to pull the image"
  type        = string
}

variable "caller_security_group_id" {
  description = "The group of the tasks that call it: the shared ECS tasks group the locations' APIs run in"
  type        = string
}

variable "image" {
  description = "The Python image `controller.py` runs on, at a fixed version"
  type        = string
  default     = "python:3.12.8-alpine3.21"
}

variable "controller_port" {
  description = "Port the locations' APIs reach it on, over HTTPS, as they reach a controller"
  type        = number
  default     = 8443
}

variable "records_port" {
  description = "Port its record of calls is read on, over HTTP; LocalStack publishes it on the host"
  type        = number
  default     = 18080
}
