package whitelabel.captal.mcp.apps

import scalatags.Text.all.*

/** The resource hints both MCP App pages put in their `<head>`.
  *
  * Shared because the two pages load the same entry shape from the same origin, and because the
  * reason each hint exists is a property of that arrangement rather than of either page.
  *
  * Both degrade to nothing. A page with no hints is correct — slower by a round trip, but correct —
  * and that is the direction this has to fail in: the names come from
  * [[AssetStore.scriptPreloads]], which reads the published entry and answers with nothing at all
  * if it cannot.
  */
object Hints:

  /** @param origin
    *   where the module is served from, for `preconnect`. Empty when unknown, which omits the tag.
    * @param preloads
    *   absolute URLs of the chunks the entry imports statically, for `modulepreload`.
    */
  def apply(origin: String, preloads: Seq[String]): Frag = frag(
    // `crossorigin` on both, and it is load-bearing rather than decorative: a module script is
    // fetched in CORS mode, so a hint made without it warms a connection — or fills a cache entry —
    // that the real request cannot use, and the browser fetches twice.
    //
    // No `fetchpriority="low"` here, unlike the portal's own preloads: that attribute is there to
    // keep the hint from competing with a stylesheet the first paint is waiting on, and these pages
    // carry their CSS inline. There is nothing for the module to arrive ahead of.
    Option
      .when(origin.nonEmpty)(link(rel := "preconnect", href := origin, attr("crossorigin") := ""))
      .getOrElse(frag()),
    frag(preloads.map(url => link(rel := "modulepreload", href := url, attr("crossorigin") := "")))
  )
end Hints
