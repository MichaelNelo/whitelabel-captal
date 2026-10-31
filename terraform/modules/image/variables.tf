variable "repository_url" {
  description = "Registry the image goes into"
  type        = string
}

variable "tag" {
  description = "Tag it goes up under: its version. The push runs when this changes."
  type        = string
}

variable "local_image" {
  description = "The image the build made, `captal-<name>:latest`"
  type        = string
}

variable "aws_region" {
  description = "Region of the registry"
  type        = string
}

variable "aws_endpoint_url" {
  description = "LocalStack's endpoint, or empty against AWS. LocalStack's registry takes a push with no login, and holds no release a push could overwrite."
  type        = string
  default     = ""
}
