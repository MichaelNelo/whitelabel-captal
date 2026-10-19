aws_region        = "us-east-1"
project_name      = "captal"
environment       = "dev"
container_port    = 8080
container_cpu     = 256
container_memory  = 512
desired_count     = 1
domain_name       = "captal.centauroads.com"
image_tag         = "1.0.1"
rqlite_image_tag  = "1.0.4"
cloudfront_domain = "staging.captal.centauroads.com"

# RQLite (single-instance with EFS persistence)
rqlite_desired_count = 1
rqlite_cpu           = 256
rqlite_memory        = 512
mcp_create_test_user = true

# The backoffice's image. `mcp_image_tag` has no default for the same reason `image_tag` refuses
# `latest`: the version a deployment runs is a decision, not an accident. Nothing has published this
# image yet, so there is no version to name -- which is the correct thing to be stopped by.
mcp_image_tag = "latest"
