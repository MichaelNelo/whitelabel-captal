package whitelabel.captal.mcp.tools

import java.time.Instant
import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.Writes
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{Terms, TermsRow, TermsTranslationRow}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** The terms of service every portal asks a guest to accept before the flow begins: one series of
  * versions for every location, the highest in force. A change to the terms is a new version, with
  * a text in at least every locale the one before had; adding or removing a translation of the
  * version in force is not a new version, and asks nobody again. A portal offers only the locales
  * the version in force has, and `deploy_location` refuses while there is none.
  */
object TermsTools:

  final case class Translation(
      @description("A locale code, such as es or en.")
      locale: String,
      @description("The terms in that locale, as the dialog shows them. Line breaks are kept.")
      text: String)

  final case class VersionView(
      @description("This version's number. The highest is the one in force.")
      version: Int,
      @description("When it was created, ISO-8601.")
      createdAt: String,
      @description("Its texts, by locale: the locales the portals offer while it is in force.")
      translations: List[Translation])

  final case class TermsView(
      @description(
        "The version in force, which a device accepts once and is asked again only for a newer one. Absent before the first is created, and until then deploy_location refuses.")
      current: Option[VersionView],
      @description("The numbers of the versions before it, newest first.")
      earlier: List[Int])

  final case class GetRequest()

  final case class CreateRequest(
      @description(
        "The new version's text in each locale. It has to include every locale the version in force has, and may add more.")
      translations: List[Translation])

  final case class SetTranslationRequest(
      @description("The locale to add to the version in force, or to replace in it.")
      locale: String,
      @description("The terms in that locale.")
      text: String)

  final case class RemoveTranslationRequest(
      @description("The locale to take out of the version in force.")
      locale: String)

  given Schema[Translation] = DeriveSchema.gen
  given Schema[VersionView] = DeriveSchema.gen
  given Schema[TermsView] = DeriveSchema.gen
  given Schema[GetRequest] = DeriveSchema.gen
  given Schema[CreateRequest] = DeriveSchema.gen
  given Schema[SetTranslationRequest] = DeriveSchema.gen
  given Schema[RemoveTranslationRequest] = DeriveSchema.gen

  private def view(quill: QuillSqlite): UIO[TermsView] =
    import quill.*
    for
      current  <- Terms.current(quill).orDie
      versions <- run(query[TermsRow].sortBy(_.version)(using Ord.desc).map(_.version)).orDie
      texts    <- ZIO.foreach(current)(terms => Terms.translations(quill, terms.id).orDie)
    yield TermsView(
      current = current.map(terms =>
        VersionView(
          terms.version,
          terms.createdAt,
          texts.toList.flatten.map(t => Translation(t.locale, t.body)))),
      earlier = versions.drop(1)
    )

  private def inForce(quill: QuillSqlite): IO[String, TermsRow] = Terms
    .current(quill)
    .orDie
    .someOrFail(
      "No terms of service exist yet. Create the first version with create_terms_version.")

  /** What is wrong with a version's texts, before anything is written. */
  private def problems(translations: List[Translation], previous: List[String]): List[String] =
    val locales = translations.map(_.locale.trim)
    val repeated = locales.diff(locales.distinct).distinct
    val blank = translations.filter(_.text.trim.isEmpty).map(_.locale)
    val dropped = previous.filterNot(locales.contains)
    List(
      Option.when(translations.isEmpty)("Give the new version at least one translation."),
      Option.when(locales.exists(_.isEmpty))("Every translation needs a locale."),
      Option.when(repeated.nonEmpty)(s"${repeated.mkString(", ")} appear more than once."),
      Option.when(blank.nonEmpty)(s"The text for ${blank.mkString(", ")} is empty."),
      Option.when(dropped.nonEmpty)(
        s"The version in force is written in ${dropped.mkString(
            ", ")} too: a new version needs a text in every locale the previous one had.")
    ).flatten

  val get: McpToolHandlerR[QuillSqlite] = McpTool("get_terms_of_service")
    .description(
      "The terms of service every portal asks a guest to accept before the flow begins: the version in force with its text in each locale, and the numbers of the ones before it. They are the same for every location.")
    .readOnly
    .handle: (_: GetRequest) =>
      ZIO.serviceWithZIO[QuillSqlite](view)

  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_terms_version")
    .description(
      "Publish a new version of the terms of service, in force at once for every location: each device is asked to accept it on its next welcome, even one that accepted the previous version. It needs a text in every locale the version in force has, and may add more. To fix or add a single translation without asking anyone again, use set_terms_translation instead.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          current  <- Terms.current(quill).orDie
          previous <- ZIO.foreach(current)(terms => Terms.translations(quill, terms.id).orDie)
          _        <-
            problems(in.translations, previous.toList.flatten.map(_.locale)) match
              case Nil =>
                ZIO.unit
              case found =>
                ZIO.fail(found.mkString(" "))
          now   = Instant.now.toString
          row   = TermsRow(UUID.randomUUID.toString, current.fold(1)(_.version + 1), now)
          texts = in.translations.map(t => TermsTranslationRow(row.id, t.locale.trim, t.text, now))
          _ <-
            transaction(
              run(query[TermsRow].insertValue(lift(row))) *>
                run(liftQuery(texts).foreach(text => query[TermsTranslationRow].insertValue(text))))
              .orDie
          result <- view(quill)
        yield result

  val setTranslation: McpToolHandlerR[QuillSqlite] = McpTool("set_terms_translation")
    .description(
      "Add a locale to the version of the terms of service in force, or replace its text in one. Not a new version: nobody who accepted it is asked again, so use it for a translation, not for a change to what the terms say — that is create_terms_version. A locale added here is offered by every portal from its next visit.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetTranslationRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          terms <- inForce(quill)
          _     <-
            ZIO.when(in.locale.trim.isEmpty || in.text.trim.isEmpty)(
              ZIO.fail("Both the locale and its text are needed."))
          row = TermsTranslationRow(terms.id, in.locale.trim, in.text, Instant.now.toString)
          affected <- Terms.saveTranslation(quill, row).orDie
          _        <- Writes.expectOne(affected, s"Saving the terms' '${row.locale}' translation")
          result   <- view(quill)
        yield result

  val removeTranslation: McpToolHandlerR[QuillSqlite] = McpTool("remove_terms_translation")
    .description(
      "Take a locale out of the version of the terms of service in force. Not a new version. Portals stop offering that locale from their next visit, and a guest who had picked it reads the terms in another. The last locale cannot be removed.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: RemoveTranslationRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        val locale = in.locale.trim
        for
          terms <- inForce(quill)
          texts <- Terms.translations(quill, terms.id).orDie
          _     <-
            ZIO.when(!texts.exists(_.locale == locale))(
              ZIO.fail(
                s"The version in force has no '$locale' text. It has ${texts
                    .map(_.locale)
                    .mkString(", ")}."))
          _ <-
            ZIO.when(texts.sizeIs == 1)(
              ZIO.fail(
                "That is the version's only text: without one, no portal could show the terms."))
          affected <-
            run(
              query[TermsTranslationRow]
                .filter(t => t.termsId == lift(terms.id) && t.locale == lift(locale))
                .delete).orDie
          _      <- Writes.expectOne(affected, s"Removing the terms' '$locale' translation")
          result <- view(quill)
        yield result
        end for

  val all: List[McpToolHandlerR[QuillSqlite]] = List(get, create, setTranslation, removeTranslation)
end TermsTools
