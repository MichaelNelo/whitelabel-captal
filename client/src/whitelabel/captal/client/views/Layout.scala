package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.{AppState, BrandLoader}

object Layout:

  /** Standard layout with centered loading state that transitions to content.
    *
    * When isLoading is true:
    *   - Brand icon is centered in viewport, with the waiting ring spinning around it
    *   - Content is hidden
    *
    * When isLoading becomes false:
    *   - The ring fades out and the brand icon slides to top position
    *   - Content fades in with staggered animations
    */
  def apply(
      content: HtmlElement,
      footer: HtmlElement,
      isLoading: Signal[Boolean] = Signal.fromValue(false)): HtmlElement = div(
    cls := "app-layout",
    // Turns the nav-loader off when this view actually mounts to the DOM. Views that turn it on
    // with `AppState.setNavigating(true)` before a `syncWithPhase(...)` must not turn it off
    // themselves: the `.route-slot` may still be downloading the new view's chunk, leaving a gap
    // with no loader. Delegating that to Layout's mount guarantees the overlay covers everything
    // until the destination view is visible.
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
        // No class of its own per state any more: the ring is driven by loading-state /
        // loaded-state on the parent, so this stays one element across the whole transition.
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

  /** Remove the static loader `index.html` painted before the bundle arrived. Continuity across the
    * handoff is trivial now that the loader animation is `breathe` (symmetric — 0% and 100% are the
    * same state), so the freshly-mounted icon can start its cycle at t=0 without any visible jump
    * against the outgoing one. Tied to the mount rather than a timer because until a Layout is up
    * there is nothing to hand over to.
    */
  private def adopt(mounted: dom.Element): Unit =
    val _ = mounted
    Option(dom.document.querySelector(".initial-loader")).foreach(_.remove())

end Layout
