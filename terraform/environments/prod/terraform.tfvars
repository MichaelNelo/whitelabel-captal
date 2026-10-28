aws_region     = "us-east-1"
project_name   = "captal"
environment    = "prod"
container_port = 8080
domain_name    = "captal.centauroads.com"
# No `image_tag` or `mcp_image_tag`: they are the API's and the backoffice's versions, which
# `mill ci.run` passes. Applied by hand, pass the released ones: `-var=image_tag=1.3.0`.
cloudfront_domain = "captal.centauroads.com"

# RQLite (3-node Raft cluster on ephemeral storage with S3 auto-backup)
rqlite_desired_count = 3
rqlite_cpu           = 256
rqlite_memory        = 512
