---
name: configure-aws
description: Use this skill when configuring captal.yaml, setting up AWS credentials, or asking about AWS infrastructure parameters. Triggers on "configure aws", "setup aws", "captal.yaml", "aws credentials", "ecs cluster", "alb listener", "s3 bucket".
version: 1.0.0
---

# Configure captal.yaml with AWS CLI

This skill guides you through configuring `shared/captal.yaml` using AWS CLI commands to automatically discover infrastructure parameters.

## Prerequisites

- AWS CLI installed and configured (`aws configure`)
- Appropriate IAM permissions to describe resources

## Quick Setup Script

Run this to gather most parameters automatically:

```bash
# Set your region
REGION=$(aws configure get region)
echo "Region: $REGION"

# Get default VPC
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=is-default,Values=true" --query "Vpcs[0].VpcId" --output text)
echo "VPC: $VPC_ID"

# Get subnets in the VPC
aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" --query "Subnets[*].[SubnetId,AvailabilityZone]" --output table

# Get security groups
aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC_ID" --query "SecurityGroups[*].[GroupId,GroupName]" --output table

# List ECS clusters
aws ecs list-clusters --query "clusterArns[*]" --output table

# List ALB listeners
aws elbv2 describe-load-balancers --query "LoadBalancers[*].[LoadBalancerArn,LoadBalancerName]" --output table

# Get ECS execution role
aws iam list-roles --query "Roles[?contains(RoleName, 'ecsTaskExecution')].[Arn,RoleName]" --output table

# List S3 buckets
aws s3 ls

# List ECR repositories
aws ecr describe-repositories --query "repositories[*].[repositoryUri,repositoryName]" --output table
```

## Parameter Reference

### aws.region
```bash
aws configure get region
```
Default region for all AWS operations.

### image (ECR URI)
```bash
# List ECR repositories
aws ecr describe-repositories --query "repositories[*].repositoryUri" --output text

# Get latest image tag
REPO_NAME="captal"
aws ecr describe-images --repository-name $REPO_NAME --query "sort_by(imageDetails,&imagePushedAt)[-1].imageTags[0]" --output text
```
Format: `<account>.dkr.ecr.<region>.amazonaws.com/<repo>:<tag>`

### s3.bucket
```bash
# List buckets
aws s3 ls

# Create a new bucket if needed
aws s3 mb s3://captal-assets-<account-id>
```
Used for storing client assets (JS, CSS, videos).

### ecs.cluster
```bash
# List clusters
aws ecs list-clusters --output text

# Create cluster if needed
aws ecs create-cluster --cluster-name captal
```

### ecs.subnets
```bash
# Get VPC ID first
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=is-default,Values=true" --query "Vpcs[0].VpcId" --output text)

# List subnets with public IP auto-assign (required for Fargate)
aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query "Subnets[?MapPublicIpOnLaunch==\`true\`].[SubnetId,AvailabilityZone]" \
  --output table
```
Use at least 2 subnets in different AZs for high availability.

### ecs.securityGroups
```bash
# List security groups
aws ec2 describe-security-groups \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query "SecurityGroups[*].[GroupId,GroupName,Description]" \
  --output table

# Create a security group for captal if needed
aws ec2 create-security-group \
  --group-name captal-ecs \
  --description "Security group for captal ECS tasks" \
  --vpc-id $VPC_ID

# Allow inbound HTTP from ALB
aws ec2 authorize-security-group-ingress \
  --group-name captal-ecs \
  --protocol tcp \
  --port 8080 \
  --source-group <alb-security-group-id>
```

### ecs.executionRoleArn
```bash
# Find existing ECS execution role
aws iam list-roles \
  --query "Roles[?contains(RoleName, 'ecsTaskExecution')].[Arn,RoleName]" \
  --output table

# Or get the AWS managed role
aws iam get-role --role-name ecsTaskExecutionRole --query "Role.Arn" --output text
```
If no role exists, create one with `AmazonECSTaskExecutionRolePolicy` attached.

### alb.listenerArn
```bash
# List load balancers
aws elbv2 describe-load-balancers \
  --query "LoadBalancers[*].[LoadBalancerArn,LoadBalancerName,DNSName]" \
  --output table

# Get listeners for a specific ALB
ALB_ARN="<your-alb-arn>"
aws elbv2 describe-listeners \
  --load-balancer-arn $ALB_ARN \
  --query "Listeners[*].[ListenerArn,Port,Protocol]" \
  --output table
```
Use the HTTPS (443) listener ARN.

### alb.vpcId
```bash
# Get default VPC
aws ec2 describe-vpcs --filters "Name=is-default,Values=true" --query "Vpcs[0].VpcId" --output text

# Or list all VPCs
aws ec2 describe-vpcs --query "Vpcs[*].[VpcId,CidrBlock,Tags[?Key=='Name'].Value|[0]]" --output table
```

### database.url
This depends on your database setup. Common formats:

```yaml
# RQLite (recommended for development)
database:
  url: "jdbc:rqlite:http://rqlite.example.com:4001"

# PostgreSQL RDS
database:
  url: "jdbc:postgresql://<rds-endpoint>:5432/captal?user=admin&password=xxx"

# SQLite (local only)
database:
  url: "jdbc:sqlite:/path/to/captal.db"
```

For RDS:
```bash
aws rds describe-db-instances \
  --query "DBInstances[*].[DBInstanceIdentifier,Endpoint.Address,Endpoint.Port]" \
  --output table
```

## Example Complete Configuration

```yaml
aws:
  region: us-east-1

image: "123456789.dkr.ecr.us-east-1.amazonaws.com/captal:latest"

s3:
  bucket: "captal-assets-123456789"

ecs:
  cluster: "captal"
  cpu: "256"
  memory: "512"
  desiredCount: 1
  subnets:
    - "subnet-abc123"
    - "subnet-def456"
  securityGroups:
    - "sg-xyz789"
  executionRoleArn: "arn:aws:iam::123456789:role/ecsTaskExecutionRole"

alb:
  listenerArn: "arn:aws:elasticloadbalancing:us-east-1:123456789:listener/app/captal-alb/abc/def"
  vpcId: "vpc-123abc"
  domain: captal.app
  healthCheckPath: /health

database:
  url: "jdbc:rqlite:http://rqlite.internal:4001"

server:
  devMode: false
  devEndpoints: false
```

## Validation

After configuring, validate your setup:

```bash
# Test AWS credentials
aws sts get-caller-identity

# Test ECR access
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR_URI

# Test S3 access
aws s3 ls s3://$BUCKET

# Test ECS cluster
aws ecs describe-clusters --clusters $CLUSTER
```
