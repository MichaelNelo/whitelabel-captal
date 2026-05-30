import base64
import mimetypes
import os

import boto3

s3 = boto3.client("s3")
BUCKET = os.environ["BUCKET"]
MAX_PROXY_SIZE = 900_000


def has_extension(path):
    return "." in path.rsplit("/", 1)[-1]


def serve(key):
    try:
        head = s3.head_object(Bucket=BUCKET, Key=key)
    except s3.exceptions.ClientError:
        return None

    if head["ContentLength"] > MAX_PROXY_SIZE:
        url = s3.generate_presigned_url("get_object", Params={"Bucket": BUCKET, "Key": key}, ExpiresIn=300)
        return {"statusCode": 302, "headers": {"Location": url}}

    obj = s3.get_object(Bucket=BUCKET, Key=key)
    body = obj["Body"].read()
    content_type = head.get("ContentType", mimetypes.guess_type(key)[0] or "application/octet-stream")

    # .gz files: serve as binary with Content-Encoding: gzip and underlying Content-Type
    if key.endswith(".gz"):
        underlying_type = mimetypes.guess_type(key[:-3])[0] or "application/octet-stream"
        return {
            "statusCode": 200,
            "headers": {"Content-Type": underlying_type, "Content-Encoding": "gzip"},
            "body": base64.b64encode(body).decode("utf-8"),
            "isBase64Encoded": True,
        }

    is_text = content_type.startswith("text/") or content_type in ("application/json", "application/javascript", "image/svg+xml")

    if is_text:
        return {"statusCode": 200, "headers": {"Content-Type": content_type}, "body": body.decode("utf-8"), "isBase64Encoded": False}

    return {"statusCode": 200, "headers": {"Content-Type": content_type}, "body": base64.b64encode(body).decode("utf-8"), "isBase64Encoded": True}


def handler(event, context):
    path = event.get("path", "/")
    key = path.lstrip("/")

    if not key:
        key = "index.html"

    response = serve(key)
    if response:
        return response

    # Not found: if path has no extension, it's a SPA route → serve index.html
    if not has_extension(path):
        return serve("index.html") or {"statusCode": 404, "body": "Not Found"}

    return {"statusCode": 404, "body": "Not Found"}
