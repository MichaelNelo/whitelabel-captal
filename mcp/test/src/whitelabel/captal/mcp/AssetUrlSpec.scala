package whitelabel.captal.mcp

import whitelabel.captal.mcp.aws.AssetStore
import zio.test.*

/** `publicUrl` and `keyOf` as inverses, against both addressing styles. Needs no S3. */
object AssetUrlSpec extends ZIOSpecDefault:

  /** The two shapes `PUBLIC_BASE_URL` takes. CloudFront serves the bucket at its root, so the path
    * is the key; a single local endpoint addresses path-style, so the bucket is the first segment.
    */
  private def cdn = AssetStore(
    bucket = "captal-assets",
    distributionId = "E123",
    publicBaseUrl = "https://d111.cloudfront.net",
    scriptBaseUrl = "https://d111.cloudfront.net",
    s3 = null,
    cdn = null,
    presigner = null
  )

  private def localstack = AssetStore(
    bucket = "captal-assets-dev",
    distributionId = "E000",
    publicBaseUrl = "http://localhost:4566/captal-assets-dev",
    scriptBaseUrl = "http://localhost:4566/captal-assets-dev",
    s3 = null,
    cdn = null,
    presigner = null
  )

  /** The same bucket with the browser reaching it through a tunnel, as `mcp.dev --tunnel` runs it.
    */
  private def tunnelled = localstack.copy(scriptBaseUrl =
    "https://some-words.trycloudflare.com/captal-assets-dev")

  private val key = "cafe-centro/videos/263f7bf0"

  def spec =
    suite("asset URLs")(
      test("a URL this configuration built is undone exactly"):
        assertTrue(
          cdn.keyOf(cdn.publicUrl(key)) == key,
          localstack.keyOf(localstack.publicUrl(key)) == key)
      ,
      test("the bucket never ends up inside the key"):
        assertTrue(
          !localstack.keyOf(localstack.publicUrl(key)).startsWith("captal-assets-dev"),
          localstack.publicUrl(key) ==
            "http://localhost:4566/captal-assets-dev/cafe-centro/videos/263f7bf0"
        )
      ,
      // Neither configured base matches, so this exercises the path fallback.
      test("a URL from an older domain still resolves"):
        assertTrue(
          cdn.keyOf(s"https://old.example.com/$key") == key,
          localstack.keyOf(s"http://127.0.0.1:4566/captal-assets-dev/$key") == key)
      ,
      // The exact base is tried before the fallback, so this never reaches the eager strip.
      test("a slug that matches the bucket name is not truncated"):
        val collided = "captal-assets-dev/videos/abc"
        assertTrue(localstack.keyOf(localstack.publicUrl(collided)) == collided)
      ,
      test("something unparseable comes back untouched rather than throwing"):
        assertTrue(cdn.keyOf("not a url at all") == "not a url at all")
      ,
      // A video row outlives the tunnel, so its URL must not name it.
      test("the scripts follow their own base and stored URLs do not"):
        assertTrue(
          tunnelled.scriptUrl("upload.js") ==
            "https://some-words.trycloudflare.com/captal-assets-dev/ui/upload.js",
          tunnelled.scriptOrigin == "https://some-words.trycloudflare.com",
          tunnelled.publicUrl(key) == localstack.publicUrl(key)
        )
    )
end AssetUrlSpec
