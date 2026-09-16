# IAM permissions for the MCP backoffice container

The MCP server runs as an ECS task and authenticates to AWS through its **task role**. No
credentials are configured anywhere in the code or the environment: the SDK's default provider
chain picks the role up.

**This file has to grow with every operation added to the server.** A tool that needs a permission
missing here fails at runtime, in front of an operator, with an SDK error that names the API call
but not the fix. Adding the operation and forgetting the policy is the likely mistake, so treat
this document as part of the change rather than as follow-up.

## Current policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "LocationAssets",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:HeadObject"],
      "Resource": "arn:aws:s3:::<assets-bucket>/*"
    },
    {
      "Sid": "AssetCacheInvalidation",
      "Effect": "Allow",
      "Action": ["cloudfront:CreateInvalidation"],
      "Resource": "arn:aws:cloudfront::<account>:distribution/<distribution-id>"
    }
  ]
}
```

## What needs each one

| Permission | Needed by | Why |
|---|---|---|
| `s3:PutObject` | `set_location_styles`, `set_location_icon` | Writes `<slug>/custom-styles.css.gz` and `<slug>/brand-icon.svg` |
| `s3:GetObject` | `get_location_styles`, `get_location_icon` | Reads them back so an edit starts from what is deployed |
| `s3:PutObject` (presigned) | `create_advertiser_video` | The signature is issued by this role, so it must itself be allowed to write the key. The bytes go browser-to-S3 and never reach this server |
| `s3:HeadObject` | `confirm_video_upload` | Separates a finished upload from an abandoned one. The video row is written before the file exists, and stays inactive until this finds it |
| `cloudfront:CreateInvalidation` | both asset writes | Without it the edge serves the previous file until the cache expires, and the write looks applied while being invisible |

`s3:ListBucket` is deliberately absent. Nothing enumerates the bucket — every key is derived from a
slug the database already confirmed — and leaving it out means a bug in key construction fails
loudly on a missing object rather than quietly finding a neighbour's.

## Environment

| Variable | Purpose |
|---|---|
| `ASSETS_BUCKET` | Bucket holding the per-location assets |
| `CLOUDFRONT_DISTRIBUTION_ID` | Distribution to invalidate |
| `PUBLIC_BASE_URL` | Origin the portal serves assets from; stored in each video row as its playable URL |
| `DB_URL` | rqlite endpoint |
| `MCP_TOKENS` | Operator tokens, `secret\|name\|scope,scope;…` |
| `MCP_RESOURCE_URI` | The URI this deployment declares itself to be, for RFC 8707 audience binding |
| `MCP_PORT` | Defaults to 8081 |

Startup fails when any of these is missing rather than degrading: an unauthenticated server or one
writing to the wrong bucket is worse than one that does not start.

## Coming, per the migration plan

Deployment operations move to the server in Part 3. They will need considerably more than the
above, and the blast radius of a leaked token grows with each one — which is why tools carry
scopes. Recorded here as they are built, not before:

- **`deploy_location`** — ECS (register task definition, create/update service), ELBv2 (target
  group, listener rule), CloudWatch Logs (create log group), S3 (bundle sync), CloudFront
  (invalidation)
- **`deprovision_location`** — the delete counterpart of each of the above
