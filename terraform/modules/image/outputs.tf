output "reference" {
  description = "The image as `<repository>@<digest>`: what a task definition names"
  value       = "${var.repository_url}@${data.aws_ecr_image.pushed.image_digest}"
}
