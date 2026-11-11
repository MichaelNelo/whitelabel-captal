"""Captive portal dispatcher Lambda.

Triggered by ALB target group on /guest/s/* (UCG-redirected captive portal hits).
Looks up the location by AP MAC in rqlite, then returns a 302 to either:
  - the location's custom `unifi_redirect_url` if set and the location is not paused (with all
    UCG query params appended),
  - or the default `https://<cloudfront_host>/<slug>/?<params>` otherwise, which a paused
    location's page answers with the maintenance page.

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

    # Escape single quotes (defense in depth — ap is bounded to MAC format).
    escaped = ap_mac.replace("'", "''")
    query = (
        "SELECT slug, unifi_redirect_url FROM locations "
        f"WHERE LOWER(unifi_ap_mac) = '{escaped}'"
    )
    url = f"{RQLITE_URL}/db/query?level=none&q={urllib.parse.quote(query)}"

    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            data = json.loads(resp.read())
    except Exception as e:
        return _error(503, f"rqlite unreachable: {e}")

    rows = (data.get("results", [{}])[0] or {}).get("values") or []
    if not rows:
        return _error(404, f"No location registered for AP {ap_mac}")

    slug, redirect_url = rows[0][0], rows[0][1]
    qs = urllib.parse.urlencode(qsp, doseq=True)

    # Custom redirect URL wins over the default cloudfront/<slug>/ destination, unless the location
    # is paused: then its guests go to its own page, which the pause made the maintenance page.
    # Asked only where there is a redirect URL; otherwise both go to the same place.
    # Append the original UCG query params with '?' or '&' depending on whether the
    # operator-provided URL already has a query string.
    if redirect_url and not _paused(slug):
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


def _paused(slug):
    """Whether the location is paused.

    A pause keeps the page it replaced at `_paused/<slug>/site.html`, and a resume or a deploy
    takes it away, so the object is there exactly while the location is paused. Failing to tell is
    logged and read as running, which redirects as before pauses were looked at.
    """
    try:
        listed = S3.list_objects_v2(
            Bucket=ASSETS_BUCKET, Prefix=f"_paused/{slug}/site.html", MaxKeys=1
        )
    except Exception as e:
        print(f"Could not tell whether '{slug}' is paused, so it is taken as running: {e}")
        return False
    return listed.get("KeyCount", 0) > 0


def _error(status, msg):
    return {
        "statusCode": status,
        "statusDescription": f"{status} {msg}",
        "headers": {"Content-Type": "text/plain"},
        "body": msg,
        "isBase64Encoded": False,
    }
