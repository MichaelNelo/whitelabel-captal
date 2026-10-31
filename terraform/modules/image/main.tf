# One image a service runs, pushed into its registry by the apply itself, and its reference by
# digest for the task definition.
#
# The build makes the image (`mill <module>.image`, as `captal-<name>:latest`); this pushes it only
# when its tag changes, which is what `triggers_replace` keeps in the state: the version the build
# computed, a new one on every release that moves it. A task definition names the digest the push
# left, read after it, so no service is pointed at an image before it is in its registry, and a tag
# pushed over later cannot change what a service runs.
#
# One module call per image rather than one with `for_each`: terraform tracks a dependency on a
# resource block, not on one of its instances, so a single block would make every image's digest
# wait for any push, and replace every task definition, rqlite's nodes included, whenever one image
# changed.
#
# Pushing needs Docker and the built image wherever the apply runs; against AWS also the `aws` CLI,
# to log in to ECR and to tell a published tag from a new one.

locals {
  # `<account>.dkr.ecr.<region>.<domain>/<name>` -> `<name>`.
  repository_name = element(split("/", var.repository_url), 1)
}

resource "terraform_data" "push" {
  triggers_replace = {
    repository = var.repository_url
    tag        = var.tag
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
      REPOSITORY = var.repository_url
      NAME       = local.repository_name
      TAG        = var.tag
      LOCAL      = var.local_image
      REGION     = var.aws_region
      ENDPOINT   = var.aws_endpoint_url
    }
  }
}

# Read after the push, so the digest is the one it left.
data "aws_ecr_image" "pushed" {
  repository_name = local.repository_name
  image_tag       = var.tag

  depends_on = [terraform_data.push]
}
