# The images the services run, pushed into their registries by the apply itself, and each one's
# reference by digest for the task definitions.
#
# The build makes the images (`mill <module>.image`, as `captal-<name>:latest`); this pushes one only
# when its tag changes, which is what `triggers_replace` keeps in the state: the version the build
# computed, a new one on every release that moves it. A task definition names the digest the push
# left, read after it, so no service is pointed at an image before it is in its registry, and a tag
# pushed over later cannot change what a service runs.
#
# Pushing needs Docker and the built image wherever the apply runs; against AWS also the `aws` CLI,
# to log in to ECR and to tell a published tag from a new one.

locals {
  # `<account>.dkr.ecr.<region>.<domain>/<name>` -> `<name>`.
  repository_names = { for name, image in var.images : name => element(split("/", image.repository_url), 1) }
}

resource "terraform_data" "push" {
  for_each = var.images

  triggers_replace = {
    repository = each.value.repository_url
    tag        = each.value.tag
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -euo pipefail
      remote="$REPOSITORY:$TAG"
      # Against AWS a tag already in its registry is that release, and is left as it is.
      if [ -z "$ENDPOINT" ]; then
        if aws ecr describe-images --region "$REGION" --repository-name "$NAME" \
             --image-ids imageTag="$TAG" > /dev/null 2>&1; then
          echo "[image] $remote is already published"
          exit 0
        fi
        aws ecr get-login-password --region "$REGION" |
          docker login --username AWS --password-stdin "$${REPOSITORY%%/*}"
      fi
      docker tag "$LOCAL" "$remote"
      docker push --quiet "$remote"
      echo "[image] pushed $remote"
    EOT
    environment = {
      REPOSITORY = each.value.repository_url
      NAME       = local.repository_names[each.key]
      TAG        = each.value.tag
      LOCAL      = each.value.local
      REGION     = var.aws_region
      ENDPOINT   = var.aws_endpoint_url
    }
  }
}

# Read after the push, so the digest is the one it left.
data "aws_ecr_image" "pushed" {
  for_each = var.images

  repository_name = local.repository_names[each.key]
  image_tag       = each.value.tag

  depends_on = [terraform_data.push]
}
