package whitelabel.captal.mcp.tools

import java.time.Instant

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{
  RemoveTranslationCommand,
  SetTranslationsCommand
}
import whitelabel.captal.core.application.queries.TranslationCoverageQuery
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.i18n.{Error as I18nError, I18n, I18nSchema, TranslationEntry}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.queries.TranslationCoverage
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** Translation tools: a location's portal texts, through `core`'s `SetTranslationsCommand`,
  * `RemoveTranslationCommand` and `TranslationCoverageQuery`.
  *
  * The required key set is derived from the type, so writes are validated instead of trusted: a
  * missing key renders `[welcome.title]` to a guest with no error anywhere.
  */
object I18nTools:

  final case class LocaleStatus(
      @description("BCP 47 tag, as stored — 'es', 'en'.")
      locale: String,
      @description("How many of the required keys this locale has a value for.")
      present: Int,
      @description(
        "Required keys with no value in this locale. The portal renders each one as its literal name to guests, so this list is the user-visible defect.")
      missing: List[String],
      @description(
        "Stored keys the portal never reads. Usually a typo or a key that outlived the screen it belonged to; harmless but worth removing.")
      unknown: List[String])
  final case class I18nStatusIn(
      @description("Which location to report on.")
      slug: String)
  final case class I18nStatusOut(
      @description("The location reported on.")
      slug: String,
      @description(
        "How many keys the portal needs, derived from the I18n type rather than a list kept by hand. Every locale is measured against this number.")
      required: Int,
      @description(
        "One entry per locale that has any translation at all. A locale absent here has none, which is why a fresh location reports an empty list.")
      locales: List[LocaleStatus])

  final case class SchemaRequest()

  final case class RequiredKey(
      @description("The key, dotted — 'welcome.title'.")
      key: String,
      @description(
        "What the string is for: where it appears and what it has to say. Written for whoever is composing the translation, who cannot see the screen.")
      purpose: String)

  final case class SchemaResponse(
      @description("Every key the portal reads, in declaration order.")
      keys: List[RequiredKey],
      @description("How many there are. A locale missing any of them renders its literal name.")
      total: Int)

  final case class Translation(
      @description(
        "A required key, dotted — 'welcome.title'. Call get_i18n_schema for the full set and what each one is for; a key outside it is refused.")
      key: String,
      @description("The text guests see, in this locale.")
      value: String)
  final case class SetTranslationsRequest(
      @description("Which location's translations to write.")
      slug: String,
      @description("BCP 47 tag. Writing a locale the location does not serve yet creates it.")
      locale: String,
      @description(
        "The keys to write. Existing keys are overwritten and keys left out are untouched, so this can be a partial update.")
      entries: List[Translation])
  final case class SetTranslationsResponse(
      @description("How many keys were written.")
      written: Int,
      @description(
        "Required keys this locale still has no value for. Empty means the locale is complete and the portal will render no literal key names.")
      stillMissing: List[String])

  final case class RemoveTranslationRequest(
      @description("Which location's translation to remove.")
      slug: String,
      @description("Which locale to remove it from. Other locales keep theirs.")
      locale: String,
      @description(
        "The key to remove. Removing a required key puts the locale back into a state where the portal shows the key name, so this is for unknown keys rather than tidying.")
      key: String)
  final case class RemoveTranslationResponse(
      @description("False when there was nothing stored under that key to begin with.")
      removed: Boolean,
      @description("Required keys this locale has no value for, after the removal.")
      stillMissing: List[String])

  given Schema[SchemaRequest] = DeriveSchema.gen
  given Schema[RequiredKey] = DeriveSchema.gen
  given Schema[SchemaResponse] = DeriveSchema.gen
  given Schema[LocaleStatus] = DeriveSchema.gen
  given Schema[I18nStatusIn] = DeriveSchema.gen
  given Schema[I18nStatusOut] = DeriveSchema.gen
  given Schema[Translation] = DeriveSchema.gen
  given Schema[SetTranslationsRequest] = DeriveSchema.gen
  given Schema[SetTranslationsResponse] = DeriveSchema.gen
  given Schema[RemoveTranslationRequest] = DeriveSchema.gen
  given Schema[RemoveTranslationResponse] = DeriveSchema.gen

  /** What the operator reads for each refusal of these tools. */
  private val describe: PartialFunction[AppError, List[String]] =
    case AppError.Location(found) =>
      found.toList.map(LocationCommands.describe)
    case AppError.I18n(found) =>
      found
        .toList
        .map:
          case I18nError.UnknownKeys(keys) =>
            s"Not part of the i18n schema: ${keys.mkString(
                ", ")}. Call get_i18n_schema for the keys that are."
          case I18nError.NoEntries =>
            "No entries given."

  private def slugOf(raw: String): IO[String, Slug] = ZIO
    .fromEither(Slug.parse(raw.trim))
    .mapError(LocationCommands.describe)

  /** Runs `command` through `flow` as the calling operator. */
  private def changed[C, R](ctx: McpToolContext, command: C)(
      flow: Flow.Aux[Task, C, R]): ZIO[OperatorContext, String, R] =
    for
      caller <- Commands.callerOf(ctx)
      result <- Commands.run(Operator(caller, None), command)(flow)(describe)
    yield result

  /** The key set itself, which `get_i18n_status` reports coverage against.
    *
    * Derived from the `I18n` type and from an instance the compiler forces to be total, so neither
    * the list nor the explanations can drift from what the portal reads.
    */
  val schema: McpToolHandlerR[Any] = McpTool("get_i18n_schema")
    .description(
      "Every translation key the portal requires and what each one is for. Read this before writing translations: set_translations refuses a key outside the set, and a key inside it that nobody writes renders its own name to guests.")
    .readOnly
    .handle: (_: SchemaRequest) =>
      val described = I18nSchema.flatten(I18n.descriptions)
      val keys = I18nSchema.keys[I18n].map(key => RequiredKey(key, described.getOrElse(key, "")))
      ZIO.succeed(SchemaResponse(keys, keys.size))

  val status: McpToolHandlerR[QuillSqlite] = McpTool("get_i18n_status")
    .description(
      "Translation coverage for a location: which required keys each locale is missing, and which stored keys are not part of the schema. Covers the portal interface only, not survey question wording. Call read_skill('edit-i18n') first.")
    .readOnly
    .handle: (in: I18nStatusIn) =>
      for
        slug   <- slugOf(in.slug)
        quill  <- ZIO.service[QuillSqlite]
        report <- Commands.ask(TranslationCoverage(quill), TranslationCoverageQuery(slug))(describe)
      yield I18nStatusOut(
        report.slug.asString,
        report.required,
        report
          .locales
          .map(locale =>
            LocaleStatus(locale.locale, locale.present, locale.missing, locale.unknown)))

  val setTranslations: McpToolHandlerR[OperatorContext & ContentFlows.SetTranslations] = McpTool(
    "set_translations")
    .description(
      "Create or update translations for one locale of a location. Keys must belong to the i18n schema; call get_i18n_schema for the list and read_skill('edit-i18n') for the procedure. Reports which keys are still missing afterwards.")
    .requireScopes(Auth.I18nWrite)
    .handleWithContext: (in: SetTranslationsRequest, ctx: McpToolContext) =>
      for
        slug    <- slugOf(in.slug)
        flow    <- ZIO.service[ContentFlows.SetTranslations]
        missing <-
          changed(
            ctx,
            SetTranslationsCommand(
              slug,
              in.locale,
              in.entries.map(e => TranslationEntry(e.key, e.value)),
              Instant.now))(flow)
      yield SetTranslationsResponse(in.entries.size, missing)

  val removeTranslation: McpToolHandlerR[OperatorContext & ContentFlows.RemoveTranslation] =
    McpTool("remove_translation")
      .description(
        "Retire one translation (soft delete). The portal will render the literal [key] for that locale until it is set again.")
      .requireScopes(Auth.I18nWrite)
      .handleWithContext: (in: RemoveTranslationRequest, ctx: McpToolContext) =>
        for
          slug    <- slugOf(in.slug)
          flow    <- ZIO.service[ContentFlows.RemoveTranslation]
          removal <-
            changed(ctx, RemoveTranslationCommand(slug, in.locale, in.key, Instant.now))(flow)
        yield RemoveTranslationResponse(removal.removed, removal.stillMissing)

  val all: List[McpToolHandlerR[
    QuillSqlite & OperatorContext & ContentFlows.SetTranslations &
      ContentFlows.RemoveTranslation]] = List(schema, status, setTranslations, removeTranslation)
end I18nTools
