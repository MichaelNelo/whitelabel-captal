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
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront and CloudFront-attached ACM certs MUST live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

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
provider "tailscale" {}
