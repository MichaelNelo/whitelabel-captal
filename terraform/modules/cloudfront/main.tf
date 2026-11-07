locals {
  bucket_name = "${var.project_name}-${var.environment}-assets"
  s3_origin   = "s3-assets"
  alb_origin  = "alb-api"

  # The backoffice's paths, at the portals' origin: its MCP endpoint, its OAuth metadata, and the
  # two posts its MCP App pages make. Under `/ui/` only those two: the rest of `/ui/` is the pages'
  # scripts, in S3.
  backoffice_paths = ["/mcp", "/mcp/*", "/.well-known/*", "/ui/runs/*", "/ui/unifi"]
}

# S3 bucket holding the bundle (`bundle/`) + per-location copies (`<slug>/`)
resource "aws_s3_bucket" "assets" {
  bucket = local.bucket_name

  tags = {
    Name = local.bucket_name
  }
}

resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# CloudFront → S3 secure origin
resource "aws_cloudfront_origin_access_control" "assets" {
  name                              = "${local.bucket_name}-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Allow only CloudFront to read the bucket. Includes s3:ListBucket so S3 returns 404
# (instead of 403) for missing keys — important for optional assets like custom-styles.css
# that locations may or may not provide; the SPA's `<link onerror=...>` only fires reliably
# on 404, and 403 leaks "key denied" semantics that aren't accurate for missing files.
data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "AllowCloudFrontReadObjects"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.assets.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }

  statement {
    sid       = "AllowCloudFrontListBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.assets.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "assets" {
  bucket = aws_s3_bucket.assets.id
  policy = data.aws_iam_policy_document.bucket.json
}

# The upload page PUTs the video straight to this bucket with a presigned URL. Its `Content-Type`,
# `video/mp4`, is not CORS-safelisted, so the browser asks with a preflight first, and a bucket with
# no CORS configuration refuses it: the upload fails before it starts. Any origin, because the page
# runs in a host's sandboxed frame whose origin is opaque or host-generated; the signature on the
# URL is what authorises the write.
resource "aws_s3_bucket_cors_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id

  cors_rule {
    allowed_origins = ["*"]
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# CloudFront Function: rewrite extensionless URIs to <slug>/index.html (SPA fallback per slug).
# Applied only to the S3 default behavior; the ALB behavior never sees it.
resource "aws_cloudfront_function" "spa_fallback" {
  name    = "${var.project_name}-${var.environment}-spa-fallback"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite extensionless paths to <slug>/index.html for SPA fallback"
  publish = true
  code    = file("${path.module}/function/spa-fallback.js")
}

# Our own policies rather than AWS's managed ones.
#
# These were `data` lookups of `Managed-CachingOptimized`, `Managed-CachingDisabled` and
# `Managed-AllViewer`. Those exist only in a real account, so the configuration could not be
# planned against LocalStack at all -- which is the whole reason the local fixture had to be a
# reimplementation instead of this.
#
# Defining them also says what is cached, which a managed policy's name does not.
resource "aws_cloudfront_cache_policy" "assets" {
  name        = "${local.bucket_name}-assets"
  comment     = "Static assets: cache on path alone, compressed"
  default_ttl = 86400
  min_ttl     = 1
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    # Nothing but the path takes part in the key. Every asset under a slug is addressed by name and
    # varies by nothing else, so a header or a query string in the key would only split the cache.
    headers_config {
      header_behavior = "none"
    }

    cookies_config {
      cookie_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

resource "aws_cloudfront_cache_policy" "api" {
  name        = "${local.bucket_name}-api"
  comment     = "API: never cached"
  default_ttl = 0
  min_ttl     = 0
  max_ttl     = 0

  # No `Accept-Encoding` normalization: CloudFront refuses it on a policy that caches nothing
  # ("EnableAcceptEncodingGzip is invalid for policy with caching disabled"), which LocalStack does
  # not check. The header still reaches the origin through the origin request policy, which
  # forwards every viewer header, so compressing is the origin's to do.
  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = false
    enable_accept_encoding_brotli = false

    headers_config {
      header_behavior = "none"
    }

    cookies_config {
      cookie_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# Everything forwarded to the ALB, which the API needs rather than merely likes: the portal's
# session resolver reads `X-Client-Mac`, `X-Ap-Mac`, `X-Fingerprint` and `X-Ssid`, and the captive
# portal's query string is where they come from.
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${local.bucket_name}-api-origin"
  comment = "Forward every header, cookie and query string to the ALB"

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"
    headers {
      items = ["CloudFront-Viewer-Address"]
    }
  }

  cookies_config {
    cookie_behavior = "all"
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

# The MCP App pages load their module scripts from `ui/` through this distribution, and a module
# script is fetched in CORS mode: without `Access-Control-Allow-Origin` the browser blocks it and
# the page renders blank. S3 only answers CORS to a request whose `Origin` reaches it, which these
# cache policies do not forward, so the header is set at the edge instead — outside the cache key,
# so nothing about caching changes.
resource "aws_cloudfront_response_headers_policy" "ui" {
  name    = "${local.bucket_name}-ui"
  comment = "MCP App scripts: readable from any origin"

  cors_config {
    access_control_allow_credentials = false
    access_control_max_age_sec       = 3000
    origin_override                  = true

    access_control_allow_headers {
      items = ["*"]
    }

    access_control_allow_methods {
      items = ["GET", "HEAD", "OPTIONS"]
    }

    access_control_allow_origins {
      items = ["*"]
    }
  }
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "Captal ${var.environment} — ${var.cloudfront_domain}"
  default_root_object = ""
  # No certificate means no alias: CloudFront rejects an alias it cannot present a matching
  # certificate for. The distribution is then reached at its own generated domain name, which is
  # all a local run needs.
  aliases      = var.certificate_arn == "" ? [] : [var.cloudfront_domain]
  price_class  = "PriceClass_100"
  http_version = "http2"

  origin {
    origin_id                = local.s3_origin
    domain_name              = aws_s3_bucket.assets.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.assets.id
  }

  origin {
    origin_id   = local.alb_origin
    domain_name = var.alb_dns_name

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # Default behavior: S3 (assets), with SPA fallback function
  default_cache_behavior {
    target_origin_id       = local.s3_origin
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id = aws_cloudfront_cache_policy.assets.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }

  # The backoffice: to the ALB, never cached, every header forwarded — `Authorization` among them,
  # which CloudFront removes unless a policy forwards all viewer headers. Before `/ui/*`: the first
  # behavior that matches wins. Not compressed, as the API is not, so a stream of events reaches the
  # page as the server writes it.
  dynamic "ordered_cache_behavior" {
    for_each = local.backoffice_paths
    content {
      path_pattern           = ordered_cache_behavior.value
      target_origin_id       = local.alb_origin
      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods         = ["GET", "HEAD"]
      compress               = false

      cache_policy_id          = aws_cloudfront_cache_policy.api.id
      origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
    }
  }

  # MCP App scripts: the assets behavior, without the SPA fallback, plus the CORS header above.
  ordered_cache_behavior {
    path_pattern           = "/ui/*"
    target_origin_id       = local.s3_origin
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id            = aws_cloudfront_cache_policy.assets.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.ui.id
  }

  # API behavior: forward to ALB, no caching, preserve all headers/cookies. Not compressed at the
  # edge: CloudFront compresses only under a cache policy that normalizes `Accept-Encoding`, which
  # `api` cannot.
  ordered_cache_behavior {
    path_pattern           = "/*/api/*"
    target_origin_id       = local.alb_origin
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    compress               = false

    cache_policy_id          = aws_cloudfront_cache_policy.api.id
    origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
  }

  # `cloudfront_default_certificate` and `acm_certificate_arn` are mutually exclusive, so each
  # field is null in the arrangement it does not belong to.
  viewer_certificate {
    cloudfront_default_certificate = var.certificate_arn == ""
    acm_certificate_arn            = var.certificate_arn == "" ? null : var.certificate_arn
    ssl_support_method             = var.certificate_arn == "" ? null : "sni-only"
    minimum_protocol_version       = var.certificate_arn == "" ? null : "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }
}
