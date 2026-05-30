locals {
  bucket_name = "${var.project_name}-cli-releases-${var.environment}"
}

# S3 bucket holding versioned CLI release artifacts (jar + cross-platform wrappers).
# Layout produced by `./mill cli.publishS3 --bucket <name> --version <v>`:
#   v<v>/captal.jar, v<v>/captal, v<v>/captal.bat   (immutable per version)
#   latest/captal.jar, latest/captal, latest/captal.bat   (overwritten on each release)
resource "aws_s3_bucket" "releases" {
  bucket = local.bucket_name

  tags = {
    Name = local.bucket_name
  }
}

# Versioning so overwriting `latest/*` doesn't lose history; older releases stay retrievable.
resource "aws_s3_bucket_versioning" "releases" {
  bucket = aws_s3_bucket.releases.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "releases" {
  bucket = aws_s3_bucket.releases.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Allow public read of release artifacts so `captal update` (and `curl` bootstrap) can fetch
# without AWS credentials. ACLs stay blocked — public access is granted only via the bucket
# policy below, restricted to the release prefixes.
resource "aws_s3_bucket_public_access_block" "releases" {
  bucket = aws_s3_bucket.releases.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "releases_public" {
  statement {
    sid     = "PublicReadReleaseArtifacts"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.releases.arn}/latest/*",
      "${aws_s3_bucket.releases.arn}/v*/*"
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "releases_public" {
  bucket     = aws_s3_bucket.releases.id
  policy     = data.aws_iam_policy_document.releases_public.json
  depends_on = [aws_s3_bucket_public_access_block.releases]
}

# Cleanup: noncurrent versions of `latest/*` are deleted after N days.
# Versioned releases under v<version>/ are immutable so this only affects
# overwritten objects in latest/ and any historical object versions.
resource "aws_s3_bucket_lifecycle_configuration" "releases" {
  bucket = aws_s3_bucket.releases.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }
  }
}
