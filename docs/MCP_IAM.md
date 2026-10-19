# IAM permissions for the MCP backoffice container

The MCP server runs as an ECS task and authenticates to AWS through its **task role**. No
credentials are configured anywhere in the code or the environment: the SDK's default provider
chain picks the role up.

**This file has to grow with every operation added to the server.** A tool that needs a permission
missing here fails at runtime, in front of an operator, with an SDK error that names the API call
but not the fix. Adding the operation and forgetting the policy is the likely mistake, so treat
this document as part of the change rather than as follow-up.

## Current policy

Defined at `terraform/modules/mcp-iam`, attached to the ECS task role. The two
have to move together: a permission added here and not there fails at runtime.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "LocationAssets",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
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
| `s3:GetObject` | `confirm_video_upload` | Also authorises HeadObject — IAM has no separate action for it. That call is what separates a finished upload from an abandoned one: the video row is written before the file exists and stays inactive until it is found |
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
| `MCP_ADMIN_TOKEN` | Seeds the admin row on startup, which holds every scope. Every other token is issued from it with `create_token` and stored hashed in `mcp_tokens`; nothing else reads this. Rotating it and restarting replaces the credential in place. |
| `MCP_RESOURCE_URI` | The URI this deployment declares itself to be, for RFC 8707 audience binding |
| `MCP_PORT` | Defaults to 8081 |

Startup fails when any of these is missing rather than degrading: an unauthenticated server or one
writing to the wrong bucket is worse than one that does not start.

## CORS, which is not an IAM permission and fails the same way

Two browser-to-AWS paths need a header that no role grants, and both fail mute — the operator sees
a page that renders and does nothing. Neither is configured by this server; both live in the
configuration under `terraform/`.

**The upload PUT, browser to S3 directly.** The presigned URL points at the bucket, and the page
sends the file's own `Content-Type`. `video/mp4` is not one of the three CORS-safelisted types, so
the browser sends a preflight `OPTIONS` first, and a bucket with no CORS configuration answers that
with a 403. The PUT is never attempted, and the failure arrives at the moment the operator picks a
file. The bucket needs:

```json
{"CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["PUT","GET","HEAD"],
  "AllowedHeaders":["*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3000}]}
```

`ensureLocalStack` applies exactly this for dev. Production does not get it from there.

**The MCP Apps' modules, browser to CloudFront.** Since the pages stopped inlining their script,
each loads `<script type="module" src="<PUBLIC_BASE_URL>/ui/upload.js">`. A module script is fetched
in CORS mode where a classic one is not, so without `Access-Control-Allow-Origin` the module is
blocked and the page is inert.

This one is **not** solved by the bucket's CORS. These objects are served through CloudFront, and S3
only emits CORS headers when the request carries an `Origin` it can match — which CloudFront does
not forward by default. Two ways, and they are not equivalent:

- A **Response Headers Policy** on the distribution with `Access-Control-Allow-Origin: *` for the
  `ui/*` behaviour. Independent of the cache key, so nothing about caching changes.
- Forwarding `Origin` through an **Origin Request Policy** and letting S3 answer. This adds `Origin`
  to the cache key, so the same file is cached once per requesting origin — and the sandbox origin is
  derived per server URL, so that is churn for no gain.

The policy is the one to use. `*` rather than the sandbox origin: three public JavaScript files with
no credentials and no data, and the sandbox origin is a hash of our own server URL, so pinning it
adds a way to break without adding protection.

## Coming, per the migration plan

Deployment operations move to the server in Part 3. They will need considerably more than the
above, and the blast radius of a leaked token grows with each one — which is why tools carry
scopes. Recorded here as they are built, not before:

- **`deploy_location`** — ECS (register task definition, create/update service), ELBv2 (target
  group, listener rule), CloudWatch Logs (create log group), S3 (bundle sync), CloudFront
  (invalidation)
- **`deprovision_location`** — the delete counterpart of each of the above
