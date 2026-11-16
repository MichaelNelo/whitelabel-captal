package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.queries.{LocationPreview, PreviewLocationQuery}
import whitelabel.captal.mcp.apps.PreviewUi
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.reads
import McpSchema.given
import ToolSchema.{readOnly, withUi}

/** How a location's portal looks, before it is deployed or after: one screen a guest goes through,
  * drawn by the portal itself with the location's stylesheet, icon and texts. The page is the
  * portal, inline (`PreviewUi`); this answers what it draws with.
  */
object PreviewTools:

  /** The screens, by the name the tool takes, with what a guest is doing on each. */
  private val Screens = List(
    "welcome"  -> "the welcome, before starting",
    "question" -> "an identification question",
    "video"    -> "the advertiser video, as it plays",
    "survey"   -> "the question after the video",
    "ready"    -> "connecting, once the flow is done",
    "access"   -> "the access granted, counting down",
    "error"    -> "the portal's error view"
  )

  final case class PreviewRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "Which screen: welcome, question, video, survey, ready, access or error. welcome when absent.")
      screen: Option[String] = None,
      @description("The language the screen is drawn in, es or en. es when absent.")
      locale: Option[String] = None)

  final case class PreviewResponse(
      @description("The location shown.")
      slug: String,
      @description("Its display name.")
      name: String,
      @description("The screen drawn.")
      screen: String,
      @description("What a guest is doing on it.")
      label: String,
      @description("The language it is drawn in.")
      locale: String,
      @description(
        "The location's own stylesheet. Absent when it has none and uses the base theme.")
      stylesheet: Option[String],
      @description("The location's icon, or the portal's when it has none.")
      icon: String,
      @description(
        "The location's texts in this language, by key, as its portal reads them. A key it has not translated reads [key].")
      texts: Map[String, String],
      @description("The languages the portal offers.")
      locales: List[String],
      @description("What the screen cannot show as a guest would see it, and why.")
      notes: List[String])

  given Schema[PreviewRequest] = DeriveSchema.gen
  given Schema[PreviewResponse] = DeriveSchema.gen

  private val Locales = Set("es", "en")

  val preview: McpToolHandlerR[Queries.PreviewLocation] = McpTool("preview_location")
    .description(
      "Show how a location's portal looks, before it is deployed or after: one screen a guest goes through (welcome, question, video, survey, ready, access or error), drawn by the portal itself with the location's stylesheet, icon and texts, and sample questions in place of its own. Call it once per screen to see more. Nothing is recorded and no session is made, and the screen is for looking, not for clicking. A stylesheet, icon or translation changed since shows at once.")
    .readOnly
    .reads((in: PreviewRequest) =>
      for
        _ <- labelOf(in)
        _ <-
          ZIO.when(!Locales.contains(localeOf(in)))(
            ZIO.fail(s"No preview in '${localeOf(in)}'. The portal is drawn in es or en."))
        slug <- Refusals.slugOf(in.slug)
      yield PreviewLocationQuery(slug, localeOf(in))): (in, preview: LocationPreview) =>
      labelOf(in).map: label =>
        PreviewResponse(
          slug = preview.slug,
          name = preview.name,
          screen = screenOf(in),
          label = label,
          locale = preview.locale,
          stylesheet = preview.stylesheet,
          icon = preview.icon,
          texts = preview.texts,
          locales = preview.locales,
          notes =
            List(
              Option.when(preview.stylesheet.isEmpty)(
                "La location no tiene hoja de estilos propia: la pantalla usa el tema base."),
              Option.when(preview.untranslated > 0)(
                s"${preview.untranslated} textos sin traducir en '${preview
                    .locale}' se muestran como [clave]."),
              Option.when(screenOf(in) == "video")(
                "El video es uno genérico hecho por la vista previa, no uno de la location.")
            ).flatten
        )
    .withUi(PreviewUi.AppUri)

  private def screenOf(in: PreviewRequest) = in.screen.map(_.trim.toLowerCase).getOrElse("welcome")
  private def localeOf(in: PreviewRequest) = in.locale.map(_.trim.toLowerCase).getOrElse("es")

  /** What a guest is doing on the screen asked for, or which screens there are. */
  private def labelOf(in: PreviewRequest): IO[String, String] = ZIO
    .fromOption(Screens.toMap.get(screenOf(in)))
    .orElseFail(
      s"No screen '${screenOf(in)}'. The screens are ${Screens.map(_._1).mkString(", ")}.")

  val all: List[McpToolHandlerR[Queries.PreviewLocation]] = List(preview)
end PreviewTools
