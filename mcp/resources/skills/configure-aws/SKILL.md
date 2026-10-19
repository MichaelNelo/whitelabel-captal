---
name: configure-aws
description: Use this skill for the backoffice deployment itself — its environment variables, its AWS permissions, where the infrastructure lives, and the operator tokens it issues. Triggers on "configure aws", "setup aws", "env vars", "variables de entorno", "MCP_ADMIN_TOKEN", "assets bucket", "cloudfront distribution", "iam permissions", "terraform", "tofu", "infra", "create token", "nuevo operador", "dar acceso", "revoke token", "revocar token".
version: 2.0.0
---

# Configure the backoffice deployment

There is no `captal.yaml` and no `captal` CLI. Nothing reads configuration out of an operator's
workspace: content is written by this server's tools straight to rqlite and S3, and the deployment
itself is configured through environment variables and the configuration under `terraform/`.

## Where the infrastructure lives

`terraform/`, in OpenTofu. It was a separate repository and is a subtree here now. Modules under
`modules/`, per-environment values under `environments/{dev,prod}/`. Clone it when you need it.

It is deliberately **not** reachable as an MCP tool. This server runs inside the VPC that repo
creates, so it cannot stand up its own prerequisites, and what Terraform contributes here is the
state file and the plan.

```sh
tofu init -backend-config=environments/dev/backend.tfvars
tofu plan  -var-file=environments/dev/terraform.tfvars
```

`tofu plan` is the single source of truth for what is deployed — believe it over any document,
including this one. Run it before closing a session in which you edited that repo: an edit without
an apply leaves the Lambda, the distribution and the task definitions behind the code.

## Environment this server reads

Startup fails outright when any of the first five is missing, which is deliberate — each one is
something a tool would otherwise discover halfway through a write.

| Variable | Value | Notes |
|---|---|---|
| `DB_URL` | `tofu output rqlite_url` | JDBC rqlite URL, read through `application.conf` |
| `ASSETS_BUCKET` | `tofu output assets_bucket_name` | Per-location assets and videos, all under `<slug>/` |
| `CLOUDFRONT_DISTRIBUTION_ID` | `tofu output cloudfront_distribution_id` | Invalidated on every asset write |
| `PUBLIC_BASE_URL` | `https://` + `tofu output cloudfront_domain_name` | The origin the portal fetches assets from, stored in each video row as its playable URL. A wrong value produces well-formed rows whose URL answers nothing, and the failure shows up in the player |
| `MCP_ADMIN_TOKEN` | chosen by whoever deploys | Seeds the admin row. See below |
| `MCP_RESOURCE_URI` | the address clients reach this server at | RFC 8707 audience binding, and the origin the MCP App pages post to and declare in their CSP. Behind an ALB this is the public address, not the bound port |
| `MCP_PORT` | optional, default `8081` | |
| `AWS_ENDPOINT_URL` | optional | Points S3 at LocalStack for local work; brings path-style addressing with it |

Locally, `./mill mcp.dev` supplies all of them against a dev rqlite container and LocalStack, with
`dev-token` as the admin token.

## AWS credentials

None are configured anywhere. The task runs as its ECS task role and the SDK's default provider
chain picks it up — no key in the image, the task definition or the environment.

The policy is `aws_iam_policy.mcp` in `terraform/modules/mcp`; what needs each permission is
`docs/MCP_IAM.md` in this one. **They have to move together**: a tool that needs a permission
missing from the module fails at runtime, in front of an operator, with an SDK error that names the
API call and not the fix. `s3:ListBucket` is deliberately absent — nothing enumerates the bucket.

To run the server outside ECS, set `create_test_user = true` in that repo; it creates an identity
carrying the same policy but no access key, because `aws_iam_access_key` would write the secret into
Terraform state. Make one out of band and delete it when the testing is done:

```sh
aws iam create-access-key --user-name captal-dev-mcp-local
```

## Operator tokens

`MCP_ADMIN_TOKEN` seeds exactly one row in `mcp_tokens` — the admin, holding every scope. Nothing
else reads the variable, so **rotating it is a restart**: the row is rewritten in place at a fixed
id, so the tokens pointing at it through `created_by` do not move. It is also the recovery path for
a database known to lose data, which is why it is supplied rather than generated.

Every other operator gets their own token from a tool:

- **`create_token`** — a name and a list of scopes (`i18n_write`, `content_write`, `identity_write`,
  `tokens_write`). The token is shown once and stored only as a SHA-256 hash, so a lost one is
  replaced, never recovered. Hand it over privately: repeating it in the conversation puts it in the
  transcript the host keeps.
- **`list_tokens`** — who holds what, who issued it, whether it is revoked. Never the token itself.
  Revoked tokens stay listed, because they are what the tokens they issued point at.
- **`revoke_token`** — takes effect on the next call, and invalidates any configuration page that
  token had open. You may revoke what you issued; the admin may revoke anything. You cannot revoke
  your own — ask another operator — and the admin token cannot be revoked at all.

Delegation only ever attenuates: a token may grant only scopes it already holds. That is what makes
handing someone `tokens_write` safe — they can extend the chain, never widen it.

| Scope | Unlocks |
|---|---|
| `i18n_write` | `set_translations`, `remove_translation` |
| `content_write` | locations, advertisers, videos, styles and icon, advertiser surveys and their questions, `configure_unifi` |
| `identity_write` | the three deployment-wide identification surveys and their questions |
| `tokens_write` | `create_token`, `revoke_token` |

Read-only tools need no scope, only a valid token. `create_location` sits under `content_write`
today because that is the only write scope this server has, so a translator holding it can stand up
a portal — a known rough edge rather than an intention.

## Verify

```sh
aws sts get-caller-identity
aws s3 ls s3://$ASSETS_BUCKET/
```

Then call `list_tokens` — the cheapest proof that the server is up, authenticated and reaching the
database.
