terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tailscale = {
      source  = "tailscale/tailscale"
      version = "~> 0.18"
    }
    # Generates the backoffice admin token that seeds the row holding every scope.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

locals {
  # One variable decides whether this configuration talks to AWS or to LocalStack. Everything
  # below is derived from it, so there is no second place to keep in step.
  local_aws = var.aws_endpoint_url != ""
}

provider "aws" {
  region = var.aws_region

  # LocalStack verifies no signature, but the SDK's signer still needs a pair to exist, and the
  # three skips avoid calls to metadata and STS endpoints that are not emulated.
  access_key                  = local.local_aws ? "test" : null
  secret_key                  = local.local_aws ? "test" : null
  skip_credentials_validation = local.local_aws
  skip_requesting_account_id  = local.local_aws
  skip_metadata_api_check     = local.local_aws

  # The default puts the bucket in the hostname, which works against AWS and not against a single
  # local endpoint, where `bucket.localhost` resolves to nothing.
  s3_use_path_style = local.local_aws

  # A `dynamic` block rather than assigning `var.aws_endpoint_url` to each field. The provider does
  # treat an empty endpoint as unset, so the simpler form would also work -- but it would rest on
  # that implicit behaviour, where this way the block simply does not exist against AWS.
  dynamic "endpoints" {
    for_each = local.local_aws ? [1] : []

    content {
      acm              = var.aws_endpoint_url
      cloudfront       = var.aws_endpoint_url
      cloudwatch       = var.aws_endpoint_url
      ec2              = var.aws_endpoint_url
      ecr              = var.aws_endpoint_url
      ecs              = var.aws_endpoint_url
      efs              = var.aws_endpoint_url
      elbv2            = var.aws_endpoint_url
      iam              = var.aws_endpoint_url
      lambda           = var.aws_endpoint_url
      logs             = var.aws_endpoint_url
      route53          = var.aws_endpoint_url
      s3               = var.aws_endpoint_url
      secretsmanager   = var.aws_endpoint_url
      servicediscovery = var.aws_endpoint_url
      sts              = var.aws_endpoint_url
    }
  }

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront and CloudFront-attached ACM certs MUST live in us-east-1.
#
# Carries the same overrides: an alias is a separate provider instance and inherits nothing from
# the default one, so leaving them off here would send every CloudFront and ACM call to AWS while
# the rest went to LocalStack.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  access_key                  = local.local_aws ? "test" : null
  secret_key                  = local.local_aws ? "test" : null
  skip_credentials_validation = local.local_aws
  skip_requesting_account_id  = local.local_aws
  skip_metadata_api_check     = local.local_aws
  s3_use_path_style           = local.local_aws

  dynamic "endpoints" {
    for_each = local.local_aws ? [1] : []

    content {
      acm              = var.aws_endpoint_url
      cloudfront       = var.aws_endpoint_url
      cloudwatch       = var.aws_endpoint_url
      ec2              = var.aws_endpoint_url
      ecr              = var.aws_endpoint_url
      ecs              = var.aws_endpoint_url
      efs              = var.aws_endpoint_url
      elbv2            = var.aws_endpoint_url
      iam              = var.aws_endpoint_url
      lambda           = var.aws_endpoint_url
      logs             = var.aws_endpoint_url
      route53          = var.aws_endpoint_url
      s3               = var.aws_endpoint_url
      secretsmanager   = var.aws_endpoint_url
      servicediscovery = var.aws_endpoint_url
      sts              = var.aws_endpoint_url
    }
  }

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# Tailscale — issues the auth key consumed by the tailscale-proxy ECS daemon.
# Credentials are read from the environment (NOT TF variables), same pattern as
# the AWS provider:
#   export TAILSCALE_API_KEY='tskey-api-...'
#   export TAILSCALE_TAILNET='your-org.ts.net'   # or '-' for default
# Generate the API token at https://login.tailscale.com/admin/settings/keys
# (tab "API access tokens"). Keeping the secret out of TF state.
provider "tailscale" {
  # A provider is configured whether or not any resource uses it, so `count = 0` on the proxy is
  # not enough to keep this quiet -- the plan fails with "credentials are empty" before it reaches
  # a single resource. A placeholder satisfies the check locally; nothing calls the API, because
  # the resources that would are not instantiated.
  api_key = local.local_aws ? "tskey-api-unused-locally" : null
  tailnet = local.local_aws ? "-" : null
}
