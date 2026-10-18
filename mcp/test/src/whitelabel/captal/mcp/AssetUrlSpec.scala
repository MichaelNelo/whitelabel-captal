package whitelabel.captal.mcp

import whitelabel.captal.mcp.aws.AssetStore
import zio.test.*

/** `publicUrl` and `keyOf` as a pair, against both addressing styles.
  *
  * They are inverses, and nothing checked that. The asymmetry was invisible because a wrong key is
  * still a valid key: the upload went to it, the existence check found it there, the video was
  * confirmed, and the only thing that failed was the portal's GET — long after the operator had
  * gone. These run without S3 because the question is about strings.
  */
object AssetUrlSpec extends ZIOSpecDefault:

  /** The two shapes `PUBLIC_BASE_URL` takes. CloudFront serves the bucket at its root, so the path
    * is the key; a single local endpoint addresses path-style, so the bucket is the first segment.
    */
  private def cdn = AssetStore(
    bucket = "captal-assets",
    distributionId = "E123",
    publicBaseUrl = "https://d111.cloudfront.net",
    s3 = null,
    cdn = null,
    presigner = null)

  private def localstack = AssetStore(
    bucket = "captal-assets-dev",
    distributionId = "E000",
    publicBaseUrl = "http://localhost:4566/captal-assets-dev",
    s3 = null,
    cdn = null,
    presigner = null)

  private val key = "cafe-centro/videos/263f7bf0"

  def spec =
    suite("asset URLs")(
      test("a URL this configuration built is undone exactly"):
        assertTrue(
          cdn.keyOf(cdn.publicUrl(key)) == key,
          localstack.keyOf(localstack.publicUrl(key)) == key)
      ,
      // The regression: against LocalStack the bucket sits in the path, and reading the key off the
      // path alone returned `captal-assets-dev/cafe-centro/videos/…`. A 513 MB object went there.
      test("the bucket never ends up inside the key"):
        assertTrue(
          !localstack.keyOf(localstack.publicUrl(key)).startsWith("captal-assets-dev"),
          localstack.publicUrl(key) ==
            "http://localhost:4566/captal-assets-dev/cafe-centro/videos/263f7bf0"
        )
      ,
      // What the path fallback is for: a row written when the portal answered on another domain.
      // Neither base matches, so this is the branch that parses.
      test("a URL from an older domain still resolves"):
        assertTrue(
          cdn.keyOf(s"https://old.example.com/$key") == key,
          localstack.keyOf(s"http://127.0.0.1:4566/captal-assets-dev/$key") == key)
      ,
      // And the trap the repair could have introduced: a location whose slug matches the bucket
      // name would lose its first segment to a fallback that strips too eagerly. The exact base is
      // tried first precisely so this case never reaches the parser.
      test("a slug that matches the bucket name is not truncated"):
        val collided = "captal-assets-dev/videos/abc"
        assertTrue(localstack.keyOf(localstack.publicUrl(collided)) == collided)
      ,
      test("something unparseable comes back untouched rather than throwing"):
        assertTrue(cdn.keyOf("not a url at all") == "not a url at all")
    )
end AssetUrlSpec
