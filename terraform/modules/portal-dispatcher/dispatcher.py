"""Captive portal dispatcher Lambda.

Triggered by ALB target group on /guest/s/* (UCG-redirected captive portal hits).
Looks up the locations that have an access point with that MAC in rqlite (`access_points`) and
takes the newest that is not paused — or, when every one is, the newest, whose page is then the
maintenance page. Then returns a 302 to:
  - its custom `unifi_redirect_url` if set and it is not paused (with all UCG query params
    appended),
  - or the default `https://<cloudfront_host>/<slug>/?<params>` otherwise.

UCG-generated URL example:
  /guest/s/default/?ap=94:2a:6f:d0:30:57&id=1c:71:25:63:e4:24
                   &t=1742398732&url=http%3A%2F%2Fexample.com&ssid=dph-guest

We extract `ap`, lookup `(slug, unifi_redirect_url)` in rqlite, and 302 to:
  - if redirect_url is non-empty: <redirect_url>?ap=...&id=...&t=...&url=...&ssid=...
  - otherwise:                    https://<cloudfront_host>/<slug>/?ap=...&id=...&t=...&...
"""

import json
import os
import urllib.parse
import urllib.request

import boto3
from botocore.config import Config

RQLITE_URL = os.environ["RQLITE_URL"]
CLOUDFRONT_HOST = os.environ["CLOUDFRONT_HOST"]
ASSETS_BUCKET = os.environ["ASSETS_BUCKET"]

# Short and tried once: a guest is waiting on this redirect, and an S3 that does not answer is no
# reason to keep them waiting.
S3 = boto3.client(
    "s3",
    config=Config(connect_timeout=2, read_timeout=2, retries={"max_attempts": 1}),
)


def handler(event, context):
    qsp = event.get("queryStringParameters") or {}
    ap_mac = (qsp.get("ap") or "").lower()

    if not ap_mac:
        return _error(400, "Missing 'ap' query parameter")

    # As `access_points.mac` is written: lowercase, with colons.
    mac = _normalized(ap_mac)
    # Escape single quotes (defense in depth — mac is bounded to MAC format).
    escaped = mac.replace("'", "''")
    # Newest first: where several running locations share an AP, the newest takes its guests.
    query = (
        "SELECT l.slug, l.unifi_redirect_url FROM access_points a "
        "JOIN locations l ON l.id = a.location_id "
        f"WHERE a.mac = '{escaped}' ORDER BY l.created_at DESC"
    )
    # Where the migration that brings `access_points` has not run yet: the AP each location had.
    legacy = (
        "SELECT slug, unifi_redirect_url FROM locations "
        f"WHERE LOWER(unifi_ap_mac) = '{escaped}' ORDER BY created_at DESC"
    )

    try:
        result = _query(query)
        if "error" in result:
            print(f"access_points unreadable ({result['error']}), reading locations' own AP")
            result = _query(legacy)
    except Exception as e:
        return _error(503, f"rqlite unreachable: {e}")

    rows = result.get("values") or []
    if not rows:
        return _error(404, f"No location registered for AP {ap_mac}")

    candidates = [(row[0], row[1]) for row in rows]
    # A paused location takes no guests: they go to a running one with the same AP. S3 is asked
    # only when there is a choice to make or a redirect URL to skip; otherwise every answer leads
    # to the same place.
    paused = _paused_slugs() if len(candidates) > 1 or candidates[0][1] else set()
    running = [c for c in candidates if c[0] not in paused]
    if len(running) > 1:
        print(
            f"AP {ap_mac} matches several running locations, "
            f"{', '.join(slug for slug, _ in running)}: sending its guests to the newest"
        )
    # With every one paused, the newest: its page is the maintenance page.
    slug, redirect_url = (running or candidates)[0]
    qs = urllib.parse.urlencode(qsp, doseq=True)

    # Custom redirect URL wins over the default cloudfront/<slug>/ destination, unless the location
    # is paused: then its guests go to its own page, which the pause made the maintenance page.
    # Append the original UCG query params with '?' or '&' depending on whether the
    # operator-provided URL already has a query string.
    if redirect_url and slug not in paused:
        sep = "&" if "?" in redirect_url else "?"
        location = f"{redirect_url}{sep}{qs}"
    else:
        location = f"https://{CLOUDFRONT_HOST}/{slug}/?{qs}"

    return {
        "statusCode": 302,
        "statusDescription": "302 Found",
        "headers": {
            "Location": location,
            "Cache-Control": "no-store",
        },
        "body": "",
        "isBase64Encoded": False,
    }


def _normalized(mac):
    """`mac` lowercase with colons, whatever separators it came with; unchanged if it is not a MAC."""
    digits = mac.replace(":", "").replace("-", "")
    if len(digits) != 12 or any(c not in "0123456789abcdef" for c in digits):
        return mac
    return ":".join(digits[i : i + 2] for i in range(0, 12, 2))


def _query(sql):
    """The first result of `sql` against rqlite: its `values`, or the `error` rqlite answered."""
    url = f"{RQLITE_URL}/db/query?level=none&q={urllib.parse.quote(sql)}"
    with urllib.request.urlopen(url, timeout=5) as resp:
        data = json.loads(resp.read())
    return (data.get("results", [{}])[0] or {})


def _paused_slugs():
    """The locations that are paused, in one listing.

    A pause keeps the page it replaced at `_paused/<slug>/site.html`, and a resume or a deploy
    takes it away, so the object is there exactly while the location is paused. Failing to tell is
    logged and reads as none paused, which redirects as before pauses were looked at.
    """
    try:
        listed = S3.list_objects_v2(Bucket=ASSETS_BUCKET, Prefix="_paused/")
    except Exception as e:
        print(f"Could not tell which locations are paused, so none is taken as paused: {e}")
        return set()
    return {
        key.split("/")[1]
        for key in (item["Key"] for item in listed.get("Contents", []))
        if key.endswith("/site.html")
    }


def _error(status, msg):
    return {
        "statusCode": status,
        "statusDescription": f"{status} {msg}",
        "headers": {"Content-Type": "text/plain"},
        "body": msg,
        "isBase64Encoded": False,
    }
