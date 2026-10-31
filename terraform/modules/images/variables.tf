variable "images" {
  description = "Each image by name: the registry it goes into, the tag it goes up under (its version), and the local image the build made."
  type = map(object({
    repository_url = string
    tag            = string
    local          = string
  }))
}

variable "aws_region" {
  description = "Region of the registries"
  type        = string
}

variable "aws_endpoint_url" {
  description = "LocalStack's endpoint, or empty against AWS. LocalStack's registry takes a push with no login, and holds no release a push could overwrite."
  type        = string
  default     = ""
}
