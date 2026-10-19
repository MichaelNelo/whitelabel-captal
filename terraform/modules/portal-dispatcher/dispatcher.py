"""Captive portal dispatcher Lambda.

Triggered by ALB target group on /guest/s/* (UCG-redirected captive portal hits).
Looks up the location by AP MAC in rqlite, then returns a 302 to either:
  - the location's custom `unifi_redirect_url` if set (with all UCG query params appended),
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

RQLITE_URL = os.environ["RQLITE_URL"]
CLOUDFRONT_HOST = os.environ["CLOUDFRONT_HOST"]


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

    # Custom redirect URL wins over the default cloudfront/<slug>/ destination.
    # Append the original UCG query params with '?' or '&' depending on whether the
    # operator-provided URL already has a query string.
    if redirect_url:
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


def _error(status, msg):
    return {
        "statusCode": status,
        "statusDescription": f"{status} {msg}",
        "headers": {"Content-Type": "text/plain"},
        "body": msg,
        "isBase64Encoded": False,
    }
