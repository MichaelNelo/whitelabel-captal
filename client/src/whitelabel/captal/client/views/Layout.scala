package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.{AppState, BrandLoader}

object Layout:

  /** Standard layout with a centered loading state that transitions to content.
    *
    * While `isLoading` the brand icon is centered in the viewport and content is hidden; when it
    * turns false the icon slides to the top and content and footer fade in.
    */
  def apply(
      content: HtmlElement,
      footer: HtmlElement,
      isLoading: Signal[Boolean] = Signal.fromValue(false)): HtmlElement = div(
    cls := "app-layout",
    // Turns the nav-loader off when this view actually mounts to the DOM. Views that turn it on
    // with `AppState.setNavigating(true)` before a `syncWithPhase(...)` must not turn it off
    // themselves: the `.route-slot` may still be downloading the new view's chunk.
    onMountCallback(_ => AppState.setNavigating(false)),
    div(
      cls := "layout-content",
      div(
        cls <--
          isLoading.map:
            case true =>
              "layout-view loading-state"
            case false =>
              "layout-view loaded-state"
        ,
        // Driven by loading-state / loaded-state on the parent, so this stays one element across
        // the whole transition.
        div(cls := "view-icon", onMountCallback(ctx => adopt(ctx.thisNode.ref)), BrandLoader()),
        // Content wrapper - hidden during loading, fades in when loaded
        div(
          cls <--
            isLoading.map:
              case true =>
                "view-content hidden"
              case false =>
                "view-content visible"
          ,
          content)
      )
    ),
    div(
      cls <--
        isLoading.map:
          case true =>
            "layout-footer hidden"
          case false =>
            "layout-footer visible"
      ,
      footer)
  )

  /** Remove the static loader `index.html` painted before the bundle arrived. Tied to the mount
    * rather than a timer: until a Layout is up there is nothing to hand over to.
    */
  private def adopt(mounted: dom.Element): Unit =
    val _ = mounted
    Option(dom.document.querySelector(".initial-loader")).foreach(_.remove())

end Layout
