# Infrastructure

The OpenTofu configuration for every environment Captal runs in, including the one the test suite
applies against LocalStack.

This was a separate repository, `whitelabel-captal-infrastructure`, and is a subtree here with its
history. It moved because the local suite could not run without it: `mcp.test` applies this
configuration, so with the config one clone away, a checkout of the portal was not enough to test
the portal.

**OpenTofu, not Terraform.** `tofu` throughout, and `devenv.nix` provides it alongside `terraform`
because the two cannot share a working directory: a state file written by Terraform 1.15 is refused
by OpenTofu 1.12 for being from a newer version, and `.terraform.lock.hcl` records provider hashes
from whichever registry wrote it.

## Environments

| | state | applied by |
|---|---|---|
| `local` | `out/mcp/test/localStackInfra.dest/<container-id>/terraform.tfstate` | `mill mcp.test`, automatically |
| `dev` | S3, via `backend.tf` | by hand |
| `prod` | S3, via `backend.tf` | by hand |

`backend.tf` declares the S3 backend with `bucket = ""`; the real value comes from
`environments/<env>/backend.tfvars`, which is not committed. It is deliberately **not** staged for
the local run — its bucket is created by this same configuration, which is a chicken-and-egg
locally, and leaving the block out is what lets `tofu init` use a plain state file instead.

## Local

Nothing to run by hand. `mill mcp.test` applies this configuration against LocalStack before the
suite starts, and `environments/local/terraform.tfvars` explains what it turns off and why — DNS,
Tailscale and Global Accelerator, each for its own reason, and both services at zero tasks because
what the suite needs is the things a location deploy *attaches to*, not the services themselves.

Three operational facts, each of which has cost a debugging session:

- **The state and the container are born together.** The state directory is keyed by the LocalStack
  container id, because LocalStack holds the resources and the state file holds the record of them —
  either surviving without the other is a divergence `tofu` cannot repair. It plans a create for
  something that already exists and the apply fails with `AlreadyExists` on roles, buckets and
  CloudFront policies. `mill mcp.test.resetLocalStack` recreates the container, and the next run
  builds both together.
- **`mill clean` breaks it.** `localStackInfra` is a persistent task whose `dest` lives under
  `out/`, so a clean discards the state and leaves the container holding every resource — exactly
  that divergence. Run `resetLocalStack` after a clean.
- **Mill polices reads of this directory.** It did not while this was a sibling repository, which is
  why `mcp.test.tofuSources` exists: without that declaration the apply fails with
  `Reading from terraform not allowed`, a message that names the task and not the read.

The apply runs from a copy rather than from this directory, so `backend.tf` can be left out and the
provider cache kept separate. Editing files here is what re-triggers it — `tofuConfig` hashes every
`.tf` and `.tfvars` under this tree.

## dev and prod

```bash
cd terraform
tofu init
tofu plan  -var-file=environments/dev/terraform.tfvars
tofu apply -var-file=environments/dev/terraform.tfvars
```

Credentials for the AWS and Tailscale providers are read from the environment so they stay out of
state; `.envrc` is not committed, and nothing here is derivable from what is.

Editing an asset without applying leaves the deployed Lambda, CloudFront function and task
definitions behind the configuration, and nothing detects that for you — run `tofu plan` before
considering a change finished.

## Architecture

- **S3** — state storage with native locking, plus the assets bucket a portal is served from
- **ECR** — one repository per image: the portal, rqlite, the backoffice
- **VPC** — network with public subnets
- **ALB** — the listener; a location's target group and rule are created by the backoffice's deploy
  tool rather than here, which is why `Deploy.healthCheckPath` and not a variable decides the health
  check path of a per-location group
- **ECS Fargate** — the portal, rqlite and the MCP backoffice
- **CloudFront** — the portal's edge, with one function for SPA fallback and one that refuses the
  internal prefixes
- **Route 53 + ACM** — DNS and certificates, off in `local`
- **Global Accelerator + portal dispatcher** — two static IPv4 anycast addresses in front of the
  ALB, because UniFi's external portal accepts only IPv4, and a Lambda that turns an AP MAC into a
  location slug
- **Tailscale proxy** — a userspace daemon exposing an HTTP CONNECT proxy, so the API can reach an
  on-premise UniFi controller
