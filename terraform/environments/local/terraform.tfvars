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

# On here alone: a stand-in for a venue's UniFi controller, as `unifi.captal.local:8443`, which the
# end-to-end features point their locations at to check what access a guest was given. Its record of
# calls is on the host at `http://localhost:18080/calls`.
enable_unifi_fake = true

# On here alone too: the locations the backoffice deploys mount the API's dev endpoints, so the
# end-to-end features can make a server or a client error happen on demand.
enable_location_faults = true

# Never resolved locally: with `enable_dns = false` the distribution answers at its own generated
# domain. Present because the variable has no default, and the string is what the comment on the
# distribution is built from.
cloudfront_domain = "local.captal.invalid"
domain_name       = "captal.invalid"

container_port = 8080

# The tag the build pushes rqlite under, read from this line so what it pushes and what the task
# definitions pull cannot drift apart. It goes up with the apply, before the service that pulls it
# exists; bump it with the Dockerfile or the entrypoint. `image_tag` (the API), `mcp_image_tag` (the
# backoffice) and `cdc_image_tag` (the relay) are not set here: they are each module's version, which
# the build passes.
rqlite_image_tag = "1.0.8"

# Off 4001/4002, which the test suites' own rqlite containers sit beside. LocalStack publishes an
# ECS task's ports on the host, and the first node to start takes 5001: that is how `mill mcp.dev`
# migrates it and `mill api.dev` reaches it from the host. Its nodes and every task find it by
# service discovery inside LocalStack's own network.
rqlite_http_port = 5001
rqlite_raft_port = 5002

# Three rqlite tasks on purpose: a real Raft quorum, so leadership, the backup that only the leader
# takes, and a node coming back to an existing cluster are all exercisable locally instead of only
# in a deployed environment. LocalStack runs an ECS task as a real container, which is what makes
# that worth doing and also why the image has to be in ECR first.
#
# It is the database everything local uses: the backoffice and every deployed location's task reach
# it as `rqlite.captal.local`.
rqlite_desired_count = 3
rqlite_cpu           = 256
rqlite_memory        = 512

# The backoffice and the change data capture relay run here as tasks, from their published images,
# as they do in AWS: `mill mcp.dev` releases them with `ci.run`'s steps. rqlite reaches the relay,
# and the backoffice reaches rqlite and the relay, by service discovery.
mcp_desired_count = 1
cdc_desired_count = 1

# LocalStack serves the load balancer and the distribution on its gateway port, so the addresses the
# backoffice is given name it: `http://captal-local.elb.localhost.localstack.cloud:4567/mcp`, and
# `https://<id>.cloudfront.localhost.localstack.cloud:4567` for the assets.
public_port = 4567

# The bucket straight from the operator's browser, for the backoffice's pages' scripts and its
# uploads. Not the distribution: LocalStack's gateway refuses a request to one carrying an `Origin`
# it does not know, which a page in a host's frame always sends; the bucket answers with its CORS
# rule. `mill mcp.dev --tunnel` replaces it with LocalStack's tunnel.
assets_endpoint_url = "http://localhost:4567"

# The identity the suite runs as, and the reason `ENFORCE_IAM` can do anything here. LocalStack
# accepts any key pair and allows everything when enforcement is off, so a suite passing
# `test`/`test` tests its own calls and not `aws_iam_policy.mcp` -- it would pass against an empty
# policy. With this user's key, a permission missing from that policy fails here rather than in
# front of an operator.
mcp_create_test_user = true
