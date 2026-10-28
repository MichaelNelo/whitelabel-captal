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

# This single variable selects LocalStack over AWS; see the providers in versions.tf. The build passes
# its own value with -var, from the port it started the container on, so this one only matters for a
# plan run by hand -- keep it on the same port.
aws_endpoint_url = "http://localhost:4567"

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

container_port = 8080

# The tags the build pushes into this environment's own ECR, read from these lines rather than
# carried by the build, so what it pushes and what the task definitions pull cannot drift apart.
# `rqlite_image_tag` goes up with the apply, before the service that pulls it exists; bump it with
# the Dockerfile or the entrypoint. `image_tag` (the API every deployed location runs) and
# `mcp_image_tag` (the backoffice) go up on every `mill mcp.dev`.
image_tag        = "1.0.1"
rqlite_image_tag = "1.0.5"

# Off 4001/4002, which the test suites' own rqlite containers sit beside. LocalStack publishes an
# ECS task's ports on the host, and the first node to start takes 5001: that is how `mill mcp.dev`
# and `mill api.dev` reach the cluster from the host. Its nodes and the locations' tasks find it by
# service discovery inside LocalStack's own network.
rqlite_http_port = 5001
rqlite_raft_port = 5002

# Three rqlite tasks on purpose: a real Raft quorum, so leadership, the backup that only the leader
# takes, and a node coming back to an existing cluster are all exercisable locally instead of only
# in a deployed environment. LocalStack runs an ECS task as a real container, which is what makes
# that worth doing and also why the image has to be in ECR first.
#
# It is the database everything local uses: the backoffice on the host through port 5001, and every
# deployed location's task as `rqlite.captal.local`.
rqlite_desired_count = 3
rqlite_cpu           = 256
rqlite_memory        = 512

# The service is declared and its image published, but it runs no task: the backoffice runs on the
# host, with `mill mcp.dev`. Raising this runs the published image here instead.
mcp_desired_count = 0

mcp_image_tag = "latest"

# The identity the suite runs as, and the reason `ENFORCE_IAM` can do anything here. LocalStack
# accepts any key pair and allows everything when enforcement is off, so a suite passing
# `test`/`test` tests its own calls and not `aws_iam_policy.mcp` -- it would pass against an empty
# policy. With this user's key, a permission missing from that policy fails here rather than in
# front of an operator.
mcp_create_test_user = true
