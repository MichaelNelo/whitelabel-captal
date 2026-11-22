package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{
  PublishTermsVersionCommand,
  RemoveTermsTranslationCommand,
  SetTermsTranslationCommand
}
import whitelabel.captal.core.application.queries.{TermsInForceQuery, TermsReport}
import whitelabel.captal.core.terms.TermsTranslation
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.OperatorContext
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs}
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

  private def viewOf(report: TermsReport): TermsView = TermsView(
    current = report
      .current
      .map(version =>
        VersionView(
          version.version,
          version.createdAt,
          version.translations.map(t => Translation(t.locale, t.text)))),
    earlier = report.earlier
  )

  /** The terms as the tools answer them, read after any change. */
  private val view: ZIO[Queries.TermsInForce, String, TermsView] = ZIO
    .serviceWithZIO[Queries.TermsInForce](Commands.ask(_, TermsInForceQuery())())
    .map(viewOf)

  val get: McpToolHandlerR[Queries.TermsInForce] =
    McpTool("get_terms_of_service")
      .description(
        "The terms of service every portal asks a guest to accept before the flow begins: the version in force with its text in each locale, and the numbers of the ones before it. They are the same for every location.")
      .readOnly
      .reads((_: GetRequest) => ZIO.succeed(TermsInForceQuery()))((_, report: TermsReport) =>
        ZIO.succeed(viewOf(report)))

  val create
      : McpToolHandlerR[Queries.TermsInForce & OperatorContext & ContentFlows.PublishTermsVersion] =
    McpTool("create_terms_version")
      .description(
        "Publish a new version of the terms of service, in force at once for every location: each device is asked to accept it on its next welcome, even one that accepted the previous version. It needs a text in every locale the version in force has, and may add more. To fix or add a single translation without asking anyone again, use set_terms_translation instead.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: CreateRequest) =>
        for
          // As every id the domain makes: ZIO's `Random` repeats its ids from one run to the next
          // under test, where the database outlives the run.
          id  <- ZIO.succeed(java.util.UUID.randomUUID())
          now <- Clock.instant
        yield PublishTermsVersionCommand(
          id.toString,
          in.translations.map(t => TermsTranslation(t.locale, t.text)),
          now))((_, _: Int) => view)

  val setTranslation
      : McpToolHandlerR[Queries.TermsInForce & OperatorContext & ContentFlows.SetTermsTranslation] =
    McpTool("set_terms_translation")
      .description(
        "Add a locale to the version of the terms of service in force, or replace its text in one. Not a new version: nobody who accepted it is asked again, so use it for a translation, not for a change to what the terms say — that is create_terms_version. A locale added here is offered by every portal from its next visit.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: SetTranslationRequest) =>
        Clock.instant.map(SetTermsTranslationCommand(TermsTranslation(in.locale, in.text), _)))(
        (_, _: Unit) => view)

  val removeTranslation: McpToolHandlerR[
    Queries.TermsInForce & OperatorContext & ContentFlows.RemoveTermsTranslation] =
    McpTool("remove_terms_translation")
      .description(
        "Take a locale out of the version of the terms of service in force. Not a new version. Portals stop offering that locale from their next visit, and a guest who had picked it reads the terms in another. The last locale cannot be removed.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: RemoveTranslationRequest) =>
        Clock.instant.map(RemoveTermsTranslationCommand(in.locale, _)))((_, _: Unit) => view)

  val all: List[McpToolHandlerR[
    Queries.TermsInForce & OperatorContext & ContentFlows.PublishTermsVersion &
      ContentFlows.SetTermsTranslation & ContentFlows.RemoveTermsTranslation]] = List(
    get,
    create,
    setTranslation,
    removeTranslation)
end TermsTools
