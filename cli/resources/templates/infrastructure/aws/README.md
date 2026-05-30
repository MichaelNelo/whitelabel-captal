# Captal Infrastructure

Terraform configuration for deploying Captal to AWS.

## Architecture

- **S3**: Terraform state storage with native locking
- **ECR**: Container registry for Docker images
- **VPC**: Network with public subnets
- **ALB**: Application Load Balancer with target group
- **ECS Fargate**: Container orchestration
- **Route 53**: DNS hosted zone (captal.centauroads.com)

## Prerequisites

- AWS CLI configured with appropriate credentials
- Terraform >= 1.10

## Quick Start

```bash
# Initialize Terraform
terraform init

# Plan deployment (dev environment)
terraform plan -var-file=environments/dev/terraform.tfvars

# Apply deployment
terraform apply -var-file=environments/dev/terraform.tfvars
```

## Migrating State to S3

After the first successful apply (which creates the S3 bucket):

1. Copy `backend.tf.example` to `backend.tf`
2. Uncomment the backend configuration
3. Update bucket name if needed
4. Run `terraform init -migrate-state`

## Pushing Docker Images

```bash
# Get ECR login
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $(terraform output -raw ecr_repository_url | cut -d'/' -f1)

# Build and push
docker build -t captal .
docker tag captal:latest $(terraform output -raw ecr_repository_url):latest
docker push $(terraform output -raw ecr_repository_url):latest

# Force ECS to pull new image
aws ecs update-service --cluster captal-dev --service captal-dev --force-new-deployment
```

## Outputs

- `ecr_repository_url`: ECR repository URL for pushing images
- `alb_dns_name`: ALB DNS name
- `ecs_cluster_name`: ECS cluster name
- `ecs_service_name`: ECS service name
- `name_servers`: NS records to configure in parent domain
- `domain_name`: Domain name

## DNS Setup

After `terraform apply`, configure NS records in centauroads.com pointing to the name servers:

```bash
terraform output name_servers
```

Add these NS records in your centauroads.com DNS provider for the subdomain `captal`.
