locals {
  bucket_name = "${var.project_name}-${var.environment}-assets"
  s3_origin   = "s3-assets"
  alb_origin  = "alb-api"
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
# (instead of 403) for missing keys — important for optional assets like custom-styles.css.gz
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

# CloudFront Function: rewrite extensionless URIs to <slug>/index.html (SPA fallback per slug).
# Applied only to the S3 default behavior; the ALB behavior never sees it.
resource "aws_cloudfront_function" "spa_fallback" {
  name    = "${var.project_name}-${var.environment}-spa-fallback"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite extensionless paths to <slug>/index.html for SPA fallback"
  publish = true
  code    = file("${path.module}/function/spa-fallback.js")
}

# AWS managed cache & origin-request policies (referenced by ID)
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "Captal ${var.environment} — ${var.cloudfront_domain}"
  default_root_object = ""
  aliases             = [var.cloudfront_domain]
  price_class         = "PriceClass_100"
  http_version        = "http2"

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

    cache_policy_id = data.aws_cloudfront_cache_policy.caching_optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }

  # API behavior: forward to ALB, no caching, preserve all headers/cookies
  ordered_cache_behavior {
    path_pattern           = "/*/api/*"
    target_origin_id       = local.alb_origin
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }
}
