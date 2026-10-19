# LocalStack. The same configuration the AWS environments use, with the pieces that cannot be
# emulated turned off and the API endpoint pointed at the container.
#
# The point of this file is that the local fixture stops being a reimplementation. Everything a
# location deploy attaches to -- the cluster, the load balancer and its listener, the security
# groups, the task roles, the assets bucket, the distribution -- comes from the configuration that
# also builds the deployed environments, rather than from a pile of `awslocal` calls in the Mill
# build that happen to agree with it.

aws_region   = "us-east-1"
project_name = "captal"
environment  = "local"

# This single variable selects LocalStack over AWS; see the providers in versions.tf.
aws_endpoint_url = "http://localhost:4566"

# Off, each for its own reason:
#
#   - dns: nothing emulates DNS validation, so an ACM certificate would sit in
#     PENDING_VALIDATION and the apply would wait forever. CloudFront falls back to its own
#     certificate and its generated domain name, which is all a local run addresses.
#   - tailscale: the provider talks to Tailscale's real API, and reaching into the network from a
#     laptop is what `docker exec` already does here.
#   - global accelerator: not emulated. It also brings the portal dispatcher Lambda with it, which
#     only exists to turn an AP MAC into a slug for real captive-portal hardware.
enable_dns                = false
enable_tailscale          = false
enable_global_accelerator = false

# Never resolved locally: with `enable_dns = false` the distribution answers at its own generated
# domain. Present because the variable has no default, and the string is what the comment on the
# distribution is built from.
cloudfront_domain = "local.captal.invalid"
domain_name       = "captal.invalid"

container_port   = 8080
container_cpu    = 256
container_memory = 512
desired_count    = 1

# Matched to what the Mill build already pulls, so LocalStack -- which runs an ECS task as a real
# container -- is never waiting on an image nobody can fetch.
image_tag        = "1.0.1"
rqlite_image_tag = "1.0.4"

# Zero tasks for both services. What the suite needs from this configuration is the things a
# location deploy *attaches to* -- the cluster, the listener, the groups, the roles, the bucket,
# the distribution -- not the services themselves. The suite runs the backoffice in-process, and
# rqlite as a plain container, because the database has to be migrated before any of this exists.
#
# The resources are still created, which is the point: a target group the server registers against
# is a real target group, and the task definitions are real revisions.
rqlite_desired_count = 0
rqlite_cpu           = 256
rqlite_memory        = 512

mcp_desired_count = 0

# Named because the variable has no default, not because it is pulled: with zero tasks nothing
# fetches it. `mill mcp.imagePush` is what would put a real tag in this registry.
mcp_image_tag = "latest"

# The identity the suite runs as, and the reason `ENFORCE_IAM` can do anything here. LocalStack
# accepts any key pair and allows everything when enforcement is off, so a suite passing
# `test`/`test` tests its own calls and not `aws_iam_policy.mcp` -- it would pass against an empty
# policy. With this user's key, a permission missing from that policy fails here rather than in
# front of an operator.
mcp_create_test_user = true
