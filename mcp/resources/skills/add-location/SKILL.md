---
name: add-location
description: Use this skill to onboard a location end-to-end — create it, give it translations, advertisers and videos, brand it, configure UniFi, and check what is still missing. Composes the smaller skills into one flow. Triggers on "agregar location", "add new location", "onboard location", "nuevo cliente", "new client", "create location", "configurar unifi", "configure unifi", "readiness", "qué falta".
version: 2.0.0
---

# Onboard a location end-to-end

A location is one portal instance: a URL prefix, an S3 asset prefix, its own session cookie, and the
content every other tool hangs off it.

There is nothing to initialize and no files to fill in. Every tool writes immediately, so there is
no commit step and stopping part way leaves nothing broken. **Call `get_location_readiness` between
steps instead of tracking state yourself** — it reports what is missing and ends with the next steps
in the order worth doing them. Everything below is just those steps with the detail the tool
schemas cannot carry.

## 1. Create it

`create_location` takes a slug and a display name, and creates nothing else.

The slug is permanent. It becomes the URL prefix (`/<slug>/api/...`), the S3 key prefix for the
location's assets, the suffix of its session cookie, and the AWS resource names at deploy time —
a rename would orphan the assets and break every live session. Kebab-case, at most 25 characters
(`captal-<slug>` is the ALB target group name and AWS caps those at 32), no leading, trailing or
doubled hyphen. The display name is free text and changeable.

`list_locations` gives you the slugs for everything else, plus `unifiConfigured` per location.

## 2. Identification surveys, if they are missing

The three surveys asked before a guest is identified — email, profiling, location — are
**deployment-wide, not per location**. Without the email one no guest is ever identified, so no
video is drawn and nothing past the first screen is reached. `get_location_readiness` reports them
as `missingIdentificationSurveys`; `create_identification_survey` creates one and fixes every
location at once. Needs `identity:write`. Read `read_skill('add-survey')` first.

## 3. Translations

Without them the portal renders raw key names to guests. `get_i18n_schema` for the keys and what
each is for, `set_translations` to write one locale at a time, `get_i18n_status` for the gaps. Read
`read_skill('edit-i18n')` for the procedure — `set_translations` refuses a key outside the schema.

## 4. Advertisers and videos

`create_advertiser` first (deployment-wide, like the identification surveys), then
`create_advertiser_video`, which registers the video together with the survey shown after it and
hands back a page to upload the file to. Do not ask the operator for the video's length: it is read
from the file at `confirm_video_upload`, which is also what puts the video into rotation. A video
whose file never arrived stays invisible to guests and costs nothing — `list_pending_uploads` finds
them and `resume_video_upload` opens a fresh page for one.

`create_promo_video` is the same flow for a video of the venue's own, with no advertiser and a survey
only if you pass questions. Read `read_skill('add-video')`.

## 5. Branding (optional)

`get_css_schema` lists every custom property the portal honours, with defaults — a property outside
that set is simply never read. Then `set_location_styles` (whole file, not a patch; call
`get_location_styles` first when editing) and `set_location_icon` (SVG source; no scripts or event
handlers). Both invalidate the CDN so the change is visible immediately. Read
`read_skill('style-reference')`.

## 6. UniFi

Until this is done the portal runs the whole flow and then strands every guest at Ready with no
network access. `get_unifi_config` reports what is stored, without the token.

`configure_unifi` takes only the slug and **opens a form**. It deliberately does not accept the API
token as an argument: every tool call is recorded by the host and replayed into the model's context
on later turns, and a token that authorises guest access on someone else's network must not go
there. The operator types it into the page and the browser posts it straight to the server.

What the form stores:

| Field | Required | Notes |
|---|---|---|
| `host` | yes | The UCG on the LAN, reachable through the Tailscale proxy on cloud deploys |
| `siteId` | yes | Site **UUID**, not the `default` name |
| `apiToken` | first time only | Left blank on a later edit, the stored one is kept |
| `port` | no | Defaults to 443 |
| `apMac` | no, but see below | |
| `redirectUrl` | no | When set, the dispatcher sends the device here instead of the portal |
| `durationMinutes` | no | Defaults to 1440 |

**`apMac` is what routes traffic to this location.** The portal dispatcher Lambda runs
`SELECT slug, unifi_redirect_url FROM locations WHERE LOWER(unifi_ap_mac) = ?` against rqlite; with
no unique AP MAC stored, a device redirected by the UCG gets a 404 instead of the portal. Find it in
the controller under Devices → the AP → Details → MAC Address.

Getting the credentials: the API key comes from **Settings → Control Plane → Integrations → Create
API Key** and is shown once. The site UUID:

```sh
curl https://<ucg-host>:443/proxy/network/integration/v1/sites -H "X-API-KEY:<token>" -k
```

Copy `data[].id`, not `internalReference` — the legacy `default` name does not work with the
Integration v1 endpoints. All UCG hardware uses port 443 and the same base path; standalone
(non-UCG) controllers are not supported.

## 7. Deploy

Content is live the moment a tool writes it. Standing up the portal service for a brand-new slug is
a separate, currently unimplemented step — see `read_skill('deploy-location')` before promising
anything here.

## 8. Verify with a test URL

The portal expects to be reached through a UniFi redirect carrying identifying params. Build one
with the location's AP MAC:

```
https://<domain>/<slug>/?id=<CLIENT_MAC>&ap=<AP_MAC>&ssid=<SSID>&url=<ORIGINAL_URL>&click_id=<CLICK_ID>
```

> **Agent instruction**: generate a fresh random `click_id` every single time — never reuse the
> previous one or a literal. Each real UniFi redirect carries a unique one and the API persists it on
> the session row for attribution. `printf 'test-%s\n' "$(openssl rand -hex 4)"` if you have a shell.

The SPA reads these on load (`parseCaptivePortalHeaders` in `client/Main.scala`) and forwards them as
headers on the first `/api/status` call.

| Param | Header | Required | Purpose |
|---|---|---|---|
| `id` | `X-Client-Mac` | yes | The device's MAC; identifies the guest across visits |
| `click_id` | `X-Click-Id` | yes | Per-redirect token, stored on the session for attribution |
| `ap` | `X-Ap-Mac` | no | Soft-validated against the location's stored AP MAC; a mismatch only warns |
| `ssid` | `X-Ssid` | no | Logged |
| `url` | `X-Redirect-Url` | no | Logged. The SPA does not follow it — the guest finishes on the portal |

Missing `id` or `click_id` on a session that does not exist yet gives `ApiError.SessionMissing` and
the error page. Once the session cookie is set they are ignored. Real traffic also carries `t`, which
the SPA ignores.

Then: open in a fresh incognito window, walk Welcome → identification → video → video survey →
Ready, and confirm a `captal_session_<slug>` cookie exists with `Path=/<slug>`. A second window with
a different `id=` should get its own session.

If anything fails, read `read_skill('troubleshoot-deployment')`.
