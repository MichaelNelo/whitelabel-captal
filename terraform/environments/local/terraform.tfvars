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

container_port = 8080

# `rqlite_image_tag` is the tag `mill rqlite.image` builds and the apply pushes into this
# environment's own ECR before the service that pulls it exists. Bump it with the Dockerfile or the
# entrypoint, and the build follows -- it reads this line rather than carrying a tag of its own.
#
# `image_tag` has no such task yet: nothing pushes `captal-api` here, and with no portal service in
# this configuration nothing pulls it either. The backoffice stamps its own image into the task
# definition it registers, and the suite points that at an image already on the machine.
image_tag        = "1.0.1"
rqlite_image_tag = "1.0.5"

# Off 4001/4002, which is where `mill api.dev` and `mill mcp.dev` run the plain rqlite container the
# backoffice and the migrations talk to. LocalStack publishes an ECS task's ports on the host, so the
# first cluster node to start would otherwise take that port and whichever of the two came second
# would fail -- in either order, depending on nothing. The cluster does not need a host port at all:
# its nodes find each other by service discovery inside LocalStack's own network.
rqlite_http_port = 5001
rqlite_raft_port = 5002

# Three rqlite tasks on purpose: a real Raft quorum, so leadership, the backup that only the leader
# takes, and a node coming back to an existing cluster are all exercisable locally instead of only
# in a deployed environment. LocalStack runs an ECS task as a real container, which is what makes
# that worth doing and also why the image has to be in ECR first.
#
# The backoffice still runs in-process against the plain rqlite container, not against this cluster:
# the cluster is only addressable as `rqlite.captal.local` through service discovery, and the schema
# has to be migrated before any of this exists.
rqlite_desired_count = 3
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
