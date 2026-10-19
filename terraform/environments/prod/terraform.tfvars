aws_region     = "us-east-1"
project_name   = "captal"
environment    = "prod"
container_port = 8080
domain_name    = "captal.centauroads.com"
# `latest` was here, and `image_tag` now refuses it: a floating tag changes the deployed version on
# the next build with nobody deciding it. Name the tag you mean. This environment has nothing
# running, so being stopped here costs nothing and is the point.
image_tag         = "latest"
cloudfront_domain = "captal.centauroads.com"

# RQLite (3-node Raft cluster on ephemeral storage with S3 auto-backup)
rqlite_desired_count = 3
rqlite_cpu           = 256
rqlite_memory        = 512

# The backoffice's image. `mcp_image_tag` has no default for the same reason `image_tag` refuses
# `latest`: the version a deployment runs is a decision, not an accident. Nothing has published this
# image yet, so there is no version to name -- which is the correct thing to be stopped by.
mcp_image_tag = "latest"
