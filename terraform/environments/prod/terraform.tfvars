aws_region     = "us-east-1"
project_name   = "captal"
environment    = "prod"
container_port = 8080
domain_name    = "captal.centauroads.com"
# No `image_tag`, `mcp_image_tag` or `cdc_image_tag`: they are the API's, the backoffice's and the
# relay's versions, which `mill ci.run` passes. Applied by hand, pass the released ones:
# `-var=image_tag=1.3.0`.
cloudfront_domain = "captal.centauroads.com"

# RQLite (3-node Raft cluster on ephemeral storage with S3 auto-backup)
rqlite_desired_count = 3
rqlite_cpu           = 256
rqlite_memory        = 512

# The tag the build pushes rqlite under, read from this line, as in `local`. Changing it replaces
# every node: the data survives through the S3 backup alone, so check the backup bucket holds a
# recent version before applying a new one.
rqlite_image_tag = "1.0.8"
