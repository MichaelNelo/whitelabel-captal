package whitelabel.captal.client

import com.raquo.laminar.api.L.*

/** The brand icon as an `<img>`. Animated by `breathe` in styles.css; under `.loaded-state` the
  * breathing gives way to a glow flash.
  */
object BrandLoader:

  def apply(): HtmlElement = div(
    cls := "brand-ring",
    img(src := "brand-icon.svg", cls := "brand-icon", alt := "Captal"))
