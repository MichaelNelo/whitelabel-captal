---
name: change-terraform
description: Use when changing the OpenTofu configuration under terraform/ — adding a module, threading a variable, or editing a resource. Covers the two environments, how a variable reaches a module, the for_each plan-time rule, formatting, and the drift rule. Triggers on "terraform", "tofu", "add a module", "infra change", "cambiar terraform", "infraestructura".
version: 1.0.0
---

# Change the terraform configuration

`terraform/`, in OpenTofu — not Terraform. Both are in the devenv shell; the state a Terraform 1.15
binary writes is refused by OpenTofu 1.12 for being from a newer version, so always run `tofu`.

## Two environments

| Environment | State | Applied by |
|---|---|---|
| `local` | a plain state file under `out/localstack/<container-id>/` | `mill mcp.test`, `mill mcp.dev`, and `mill ci.run` pointed at LocalStack |
| `prod` | S3, via `backend.tf` and `environments/prod/backend.tfvars` | `mill ci.run` in CodeBuild, or by hand |

There is no `dev`. It was destroyed and its directory removed — a reference to `environments/dev/`
is a leftover.

One variable decides which cloud the configuration talks to:

```hcl
local_aws = var.aws_endpoint_url != ""
```

Everything else derives from it — credentials, endpoint overrides, skipped validations — so there is
no second place to keep in step. If you add a provider, derive its local behaviour from
`local.local_aws` the same way.

## Threading a variable takes four edits

A value an operator sets has to travel: tfvars → root variable → module call → module variable.
Miss one and it silently keeps the default.

1. `terraform/modules/<m>/variables.tf` — declare it, with a default that is correct for AWS.
2. `terraform/modules/<m>/main.tf` — consume it. If the module has a `locals` block that hardcodes
   the value, point the local at `var.<name>` rather than adding a second source of truth.
3. `terraform/variables.tf` — declare the root-level one, prefixed by area (`rqlite_http_port`,
   `mcp_desired_count`).
4. `terraform/main.tf` — pass it in the `module "<m>"` block.
5. `terraform/environments/<env>/terraform.tfvars` — override it only where the default is wrong.

Grep for the literal before you finish. A port or a name is often hardcoded in more than one module:
`rqlite_http_port` had to reach both `modules/rqlite` and `modules/portal-dispatcher`, which built
the database's URL independently.

## `for_each` keys must be known at plan time

A `for_each` key becomes part of the resource address, so the whole key set has to be known before
anything runs. This is the trap that has actually bitten:

```hcl
# Wrong: the keys are ids that do not exist until apply.
for_each = toset(var.subnet_ids)

# Right: keys from the configuration, apply-time values in the value.
for_each = { for index, id in var.subnet_ids : tostring(index) => id }
subnet_id = each.value
```

The error is `Invalid for_each argument … values derived from resource attributes that cannot be
determined until apply`. An unknown value *inside* a resource is fine — only an unknown **key**
breaks. It fails on a destroy and on the very first apply, so a resource keyed this way may never
have been creatable.

Changing a key re-addresses existing instances. Add `moved` blocks, or make the change while nothing
is deployed.

## Before you finish

```bash
cd terraform
tofu fmt -recursive          # a misaligned `=` fails CI-style checks and pollutes the next diff
tofu plan -var-file=environments/prod/terraform.tfvars
```

`tofu validate` needs an `init` and will not catch a `for_each` problem — that is a plan-time error.
The build never inits in `terraform/` itself: it copies `*.tf` (minus `backend.tf`), `modules/` and
`environments/` to a staging directory so the configuration stays read-only. A new module or a new
top-level `.tf` is picked up by that copy with no build change.

**Run the plan even if you only edited an asset.** Files that end up *inside* a resource —
`modules/portal-dispatcher/dispatcher.py`, `modules/cloudfront/function/cdn.js`,
`docker/rqlite-entrypoint.sh` — change the resource's hash, but nothing reaches AWS until an apply.
Hours have been lost to a Lambda serving old code that had been fixed on disk in an earlier session.

## What the build reads back

`mill mcp.dev` and `mill mcp.test` consume these root outputs to configure the backoffice:
`ecs_cluster_name`, `vpc_id`, `subnet_ids`, `ecs_tasks_security_group_id`, `alb_listener_arn`,
`task_execution_role_arn`, `task_role_arn`, `assets_bucket_name`, `cloudfront_distribution_id`.

Renaming or removing one of those breaks the local environment with a message naming the missing
output. Adding a resource the deploy tools need means adding its output too.

## Anti-patterns

- ❌ Running `terraform` instead of `tofu`.
- ❌ Keying a `for_each` on ids that come from another resource.
- ❌ Adding a variable to a module and stopping there, without threading it to the root.
- ❌ Hardcoding a port or name in a module when another module derives the same thing.
- ❌ Editing an asset that lives inside a resource and closing the session without a plan.
- ❌ Expecting `environments/dev/` to exist.
- ❌ Committing without `tofu fmt -recursive`.
