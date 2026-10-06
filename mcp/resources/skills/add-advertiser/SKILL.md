---
name: add-advertiser
description: Use this skill when adding or changing advertisers through the backoffice MCP server. Triggers on "add advertiser", "new advertiser", "advertiser priority", "retire advertiser".
version: 2.0.0
---

# Add an advertiser

Advertisers are created with tools, not files.

## Order of operations

1. `list_advertisers` — names must be unique, and this is what tells you what exists.
2. `create_advertiser` — name and priority.
3. Add videos for the advertiser in each location that should run them.

## Priority is a weight, not an order

Earlier versions of this skill said higher priority means shown first. That is wrong.
`VideoRepositoryQuill` draws videos with weighted random sampling: each advertiser's priority is
divided by the sum across all active advertisers, and the draw is `-log(random) / weight`. Priority
sets **how often** an advertiser is drawn, not **when**.

So a priority of 20 against a 10 means roughly twice as often, not first. A priority of zero means
never drawn — allowed, and occasionally what you want, but rarely what you meant.

## Retiring

`set_advertiser_active` with `active: false`. The video query joins on the advertiser being active,
so retiring one also takes every one of its videos out of rotation. The tool reports how many, since
that reach is otherwise invisible until someone opens the portal.

There is no delete. Videos and surveys reference advertisers.

## Uniqueness is enforced by the server, not the database

The `advertisers` table has no unique constraint of any kind. `create_advertiser` refuses a name
already in use, and `update_advertiser` refuses to rename onto one. Writing to the table by any
other route would happily create a duplicate, and nothing would complain.

## Related

- `skill://add-video/SKILL.md` — videos belong to an advertiser
- `skill://add-survey/SKILL.md` — advertiser surveys attach to a video
