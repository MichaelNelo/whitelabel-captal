# The images this build releases besides rqlite's, which its own module holds: the API every
# location runs, the backoffice, and the change data capture relay.
#
# Removing one from this list asks AWS to delete the repository, which fails while it still holds
# images. Empty it first, or set `force_delete` for one apply.
locals {
  base_repos = ["captal-api", "captal-mcp", "captal-cdc"]
}

resource "aws_ecr_repository" "repos" {
  for_each = toset(local.base_repos)

  name                 = "${each.value}-${var.environment}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Base images: keep last 10 tagged with "v" prefix; delete untagged older than 7 days.
resource "aws_ecr_lifecycle_policy" "base" {
  for_each = toset(local.base_repos)

  repository = aws_ecr_repository.repos[each.value].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 10 versioned images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["v"]
          countType     = "imageCountMoreThan"
          countNumber   = 10
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Delete untagged images older than 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      }
    ]
  })
}

# The derived-image lifecycle policy was here, keeping the last 20 of whatever `captal locations
# push` had built. There is nothing left for it to apply to.
