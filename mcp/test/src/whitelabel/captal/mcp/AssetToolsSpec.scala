package whitelabel.captal.mcp

import whitelabel.captal.mcp.tools.AssetTools
import zio.test.*

/** The SVG check is the only part of the asset tools with logic of its own; the rest is S3 calls
  * that a unit test cannot reach without a bucket.
  */
object AssetToolsSpec extends ZIOSpecDefault:
  private val plain =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle r="8"/></svg>"""

  private def refused(svg: String) = AssetTools.validateSvg(svg).either.map(_.isLeft)

  def spec =
    suite("brand icon validation")(
      test("a plain icon is accepted"):
        for result <- AssetTools.validateSvg(plain).either
        yield assertTrue(result.isRight)
      ,
      test("an XML declaration before the root is accepted"):
        for result <- AssetTools.validateSvg(s"""<?xml version="1.0"?>$plain""").either
        yield assertTrue(result.isRight)
      ,
      test("a script element is refused"):
        // Through an img element it would never run, but the object sits on the portal's own origin
        // and opening it directly renders it as a document.
        for
          lower <- refused(
            """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""")
          upper <- refused(
            """<svg xmlns="http://www.w3.org/2000/svg"><SCRIPT>alert(1)</SCRIPT></svg>""")
        yield assertTrue(lower, upper)
      ,
      test("an event handler attribute is refused"):
        for
          onload  <- refused("""<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>""")
          onclick <- refused(
            """<svg xmlns="http://www.w3.org/2000/svg"><circle onclick="x()"/></svg>""")
        yield assertTrue(onload, onclick)
      ,
      test("an attribute merely containing 'on' is not mistaken for a handler"):
        // `font-weight` and friends would match a sloppier pattern and refuse a legitimate icon.
        for result <-
            AssetTools
              .validateSvg(
                """<svg xmlns="http://www.w3.org/2000/svg"><text font-weight="700">x</text></svg>""")
              .either
        yield assertTrue(result.isRight)
      ,
      test("something that is not SVG at all is refused"):
        for
          html  <- refused("<html><body>nope</body></html>")
          empty <- refused("   ")
        yield assertTrue(html, empty)
    )
end AssetToolsSpec
