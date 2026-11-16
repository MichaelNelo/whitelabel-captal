package whitelabel.captal.mcp.tools

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.queries.PreviewLocationQuery
import whitelabel.captal.core.location.Slug
import whitelabel.captal.mcp.apps.PreviewUi
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.queries.Locations
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

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

  val preview: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("preview_location")
    .description(
      "Show how a location's portal looks, before it is deployed or after: one screen a guest goes through (welcome, question, video, survey, ready, access or error), drawn by the portal itself with the location's stylesheet, icon and texts, and sample questions in place of its own. Call it once per screen to see more. Nothing is recorded and no session is made, and the screen is for looking, not for clicking. A stylesheet, icon or translation changed since shows at once.")
    .readOnly
    .handle: (in: PreviewRequest) =>
      val screen = in.screen.map(_.trim.toLowerCase).getOrElse("welcome")
      val locale = in.locale.map(_.trim.toLowerCase).getOrElse("es")
      for
        label <- ZIO
          .fromOption(Screens.toMap.get(screen))
          .orElseFail(s"No screen '$screen'. The screens are ${Screens.map(_._1).mkString(", ")}.")
        _ <-
          ZIO.when(!Locales.contains(locale))(
            ZIO.fail(s"No preview in '$locale'. The portal is drawn in es or en."))
        slug    <- ZIO.fromEither(Slug.parse(in.slug)).mapError(LocationCommands.describe)
        quill   <- ZIO.service[QuillSqlite]
        store   <- ZIO.service[AssetStore]
        preview <-
          Commands.ask(Locations.preview(quill, store), PreviewLocationQuery(slug, locale)) {
            case AppError.Location(found) =>
              found.toList.map(LocationCommands.describe)
          }
      yield PreviewResponse(
        slug = preview.slug,
        name = preview.name,
        screen = screen,
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
              s"${preview
                  .untranslated} textos sin traducir en '$locale' se muestran como [clave]."),
            Option.when(screen == "video")(
              "El video es uno genérico hecho por la vista previa, no uno de la location.")
          ).flatten
      )
      end for
    .withUi(PreviewUi.AppUri)

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(preview)
end PreviewTools
