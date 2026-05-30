locals {
  base_repos    = ["captal-api", "captal-provision"]
  derived_repos = ["captal-shared", "captal-locations"]
  all_repos     = concat(local.base_repos, local.derived_repos)
}

resource "aws_ecr_repository" "repos" {
  for_each = toset(local.all_repos)

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

# Derived images (created by CLI on each push): retain last 20, delete rest.
resource "aws_ecr_lifecycle_policy" "derived" {
  for_each = toset(local.derived_repos)

  repository = aws_ecr_repository.repos[each.value].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 20 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}
