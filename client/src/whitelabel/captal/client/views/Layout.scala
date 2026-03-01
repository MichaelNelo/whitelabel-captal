package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*

object Layout:
  /** Standard layout with centered loading state that transitions to content.
    *
    * When isLoading is true:
    *   - Brand icon is centered in viewport with pulse animation
    *   - Content is hidden
    *
    * When isLoading becomes false:
    *   - Brand icon slides to top position
    *   - Content fades in with staggered animations
    */
  def apply(
      content: HtmlElement,
      footer: HtmlElement,
      isLoading: Signal[Boolean] = Signal.fromValue(false)
  ): HtmlElement =
    div(
      cls := "app-layout",
      div(
        cls := "layout-content",
        div(
          cls <-- isLoading.map:
            case true  => "layout-view loading-state"
            case false => "layout-view loaded-state"
          ,
          // Brand icon - centered during loading, slides to top when loaded
          div(
            cls <-- isLoading.map:
              case true  => "view-icon brand-pulse"
              case false => "view-icon"
            ,
            brandIcon
          ),
          // Content wrapper - hidden during loading, fades in when loaded
          div(
            cls <-- isLoading.map:
              case true  => "view-content hidden"
              case false => "view-content visible"
            ,
            content
          )
        )
      ),
      div(
        cls <-- isLoading.map:
          case true  => "layout-footer hidden"
          case false => "layout-footer visible"
        ,
        footer
      )
    )

  private def brandIcon: HtmlElement =
    img(
      src := "/brand-icon.svg",
      cls := "brand-icon",
      alt := "Captal"
    )
