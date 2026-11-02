package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** The brand icon as an `<img>`. Animated by `breathe` in styles.css; under `.loaded-state` the
  * breathing gives way to a glow flash.
  */
object BrandLoader:

  def apply(): HtmlElement = div(
    cls := "brand-ring",
    img(src := icon, cls := "brand-icon", alt := "Captal"))

  /** The location's icon, beside the page, or by the absolute address a preview's block names in
    * `data-icon`: a preview's document has no address to resolve a relative one against. Read off
    * the DOM rather than through [[Preview]], which would bring the portal's whole first chunk into
    * the maintenance page, which shows this icon too.
    */
  private def icon: String = Option(dom.document.getElementById("captal-preview"))
    .flatMap(block => Option(block.getAttribute("data-icon")))
    .getOrElse("brand-icon.svg")
