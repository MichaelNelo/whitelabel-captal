package whitelabel.captal.mcp.apps

import scalatags.Text.all.*

/** The resource hints both MCP App pages put in their `<head>`.
  *
  * Both degrade to nothing — a page with no hints is correct, only slower by a round trip — which
  * is how [[AssetStore.scriptPreloads]] answers when it cannot read the published entry.
  */
object Hints:

  /** @param origin
    *   where the module is served from, for `preconnect`. Empty when unknown, which omits the tag.
    * @param preloads
    *   absolute URLs of the chunks the entry imports statically, for `modulepreload`.
    */
  def apply(origin: String, preloads: Seq[String]): Frag = frag(
    // `crossorigin` is required on both: a module script is fetched in CORS mode, so a hint made
    // without it warms a connection — or fills a cache entry — the real request cannot use.
    Option
      .when(origin.nonEmpty)(link(rel := "preconnect", href := origin, attr("crossorigin") := ""))
      .getOrElse(frag()),
    frag(preloads.map(url => link(rel := "modulepreload", href := url, attr("crossorigin") := "")))
  )
end Hints
