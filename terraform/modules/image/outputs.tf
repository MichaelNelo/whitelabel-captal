output "references" {
  description = "Each image by name, as `<repository>@<digest>`: what a task definition names"
  value = {
    for name, image in var.images :
    name => "${image.repository_url}@${data.aws_ecr_image.pushed[name].image_digest}"
  }
}
