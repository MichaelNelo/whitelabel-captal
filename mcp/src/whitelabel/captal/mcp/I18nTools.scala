package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{LocalizedTextRow, LocationRow}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given

/** Translation tools: the first mutations the backoffice exposes.
  *
  * i18n goes first deliberately. `localized_texts` is a leaf table with no nested children, the
  * required key set is derived from the type so writes can be validated instead of trusted, and the
  * failure it addresses is real: a missing key renders `[welcome.title]` to a guest with no error
  * anywhere.
  */
object I18nTools:
  private val Category = "frontend"

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

  final case class Translation(
      @description(
        "A required key, dotted — 'welcome.title'. Read i18n://required-keys for the full set and what each one is for; a key outside it is refused.")
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

  given Schema[LocaleStatus] = DeriveSchema.gen
  given Schema[I18nStatusIn] = DeriveSchema.gen
  given Schema[I18nStatusOut] = DeriveSchema.gen
  given Schema[Translation] = DeriveSchema.gen
  given Schema[SetTranslationsRequest] = DeriveSchema.gen
  given Schema[SetTranslationsResponse] = DeriveSchema.gen
  given Schema[RemoveTranslationRequest] = DeriveSchema.gen
  given Schema[RemoveTranslationResponse] = DeriveSchema.gen

  private inline def locationBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private inline def activeTextsQuery = quote: (locationIdParam: Option[String]) =>
    query[LocalizedTextRow].filter(r =>
      r.locationId == locationIdParam && r.category == "frontend" && r.isActive == 1)

  /** Deliberately ignores `is_active`: a write has to find a previously retired row so it can bring
    * it back, rather than inserting a duplicate that the natural-key unique index would reject.
    */
  private inline def byNaturalKeyQuery = quote:
    (entityIdParam: String, localeParam: String, locationIdParam: Option[String]) =>
      query[LocalizedTextRow].filter(r =>
        r.entityId == entityIdParam && r.locale == localeParam && r.locationId == locationIdParam)

  private def resolveSlug(slug: String): ZIO[QuillSqlite, String, String] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(locationBySlugQuery(lift(slug)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No location with slug '$slug'. Check `resources/list` for what exists.")
      .map(_.id)

  private def storedFor(locationId: String): ZIO[QuillSqlite, Nothing, List[LocalizedTextRow]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(activeTextsQuery(lift(Option(locationId)))).orDie

  private def missingFor(
      locationId: String,
      locale: String): ZIO[QuillSqlite, Nothing, List[String]] = storedFor(locationId).map: rows =>
    val present = rows.filter(_.locale == locale).map(r => r.entityId -> r.value).toMap
    I18nSchema.missing[I18n](present)

  /** Writes one row, upserting on the NATURAL key `(entity_id, locale, location_id)`.
    *
    * Not `onConflictUpdate(_.id)`, which is what `EntityWriter` does: that resolves conflicts on
    * the primary key and only ever worked because `IdGenerator` derived the id from these same
    * three fields. With surrogate ids a blind insert collides with the unique index from V12 and
    * fails outright instead of updating.
    */
  private def upsert(
      locationId: String,
      locale: String,
      key: String,
      value: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.*
    for
      now      <- Clock.instant.map(_.toString)
      existing <- run(byNaturalKeyQuery(lift(key), lift(locale), lift(Option(locationId))))
        .orDie
        .map(_.headOption)
      affected <-
        existing match
          case Some(row) =>
            run(
              query[LocalizedTextRow]
                .filter(_.id == lift(row.id))
                // isActive -> 1 so writing a translation undoes an earlier removal.
                .update(_.value -> lift(value), _.updatedAt -> lift(now), _.isActive -> 1)).orDie
          case None =>
            val row = LocalizedTextRow(
              id = UUID.randomUUID.toString,
              entityId = key,
              locale = locale,
              value = value,
              category = Category,
              createdAt = now,
              updatedAt = now,
              locationId = Some(locationId),
              isActive = 1
            )
            run(query[LocalizedTextRow].insertValue(lift(row))).orDie
      // The guard lives in Writes because it applies to every write in this server, not just here.
      _ <- Writes.expectOne(affected, s"Write for '$key' ($locale)")
    yield ()
    end for

  val status: McpToolHandlerR[QuillSqlite] = McpTool("get_i18n_status")
    .description(
      "Translation coverage for a location: which required keys each locale is missing, and which stored keys are not part of the schema. Covers the portal interface only, not survey question wording. See skill://edit-i18n/SKILL.md.")
    .handle: (in: I18nStatusIn) =>
      for
        locationId <- resolveSlug(in.slug)
        rows       <- storedFor(locationId)
        required    = I18nSchema.keys[I18n]
        requiredSet = required.toSet
        locales     = rows
          .groupBy(_.locale)
          .toList
          .sortBy(_._1)
          .map: (locale, localeRows) =>
            val present = localeRows.map(r => r.entityId -> r.value).toMap
            LocaleStatus(
              locale = locale,
              present = present.size,
              missing = I18nSchema.missing[I18n](present),
              // Keys stored but no longer in the schema: renamed or removed upstream, dead weight.
              unknown = present.keys.filterNot(requiredSet.contains).toList.sorted
            )
      yield I18nStatusOut(in.slug, required.size, locales)

  val setTranslations: McpToolHandlerR[QuillSqlite] = McpTool("set_translations")
    .description(
      "Create or update translations for one locale of a location. Keys must belong to the i18n schema; read i18n://required-keys for the list and skill://edit-i18n/SKILL.md for the procedure. Reports which keys are still missing afterwards.")
    .requireScopes(Auth.I18nWrite)
    .handle: (in: SetTranslationsRequest) =>
      val requiredSet = I18nSchema.keys[I18n].toSet
      val unknown = in.entries.map(_.key).filterNot(requiredSet.contains).distinct.sorted
      for
        // Rejected as a whole rather than partially applied: a typo'd key would otherwise be
        // written and silently never rendered.
        _ <-
          ZIO.when(unknown.nonEmpty)(
            ZIO.fail(
              s"Not part of the i18n schema: ${unknown.mkString(
                  ", ")}. Read i18n://required-keys."))
        _          <- ZIO.when(in.entries.isEmpty)(ZIO.fail("No entries given."))
        locationId <- resolveSlug(in.slug)
        _ <- ZIO.foreachDiscard(in.entries)(e => upsert(locationId, in.locale, e.key, e.value))
        missing <- missingFor(locationId, in.locale)
      yield SetTranslationsResponse(in.entries.size, missing)

  val removeTranslation: McpToolHandlerR[QuillSqlite] = McpTool("remove_translation")
    .description(
      "Retire one translation (soft delete). The portal will render the literal [key] for that locale until it is set again.")
    .requireScopes(Auth.I18nWrite)
    .handle: (in: RemoveTranslationRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          locationId <- resolveSlug(in.slug)
          now        <- Clock.instant.map(_.toString)
          affected   <-
            run(
              query[LocalizedTextRow]
                .filter(r =>
                  r.entityId == lift(in.key) && r.locale == lift(in.locale) &&
                    r.locationId == lift(Option(locationId)) && r.isActive == 1)
                .update(_.isActive -> 0, _.updatedAt -> lift(now))).orDie
          missing <- missingFor(locationId, in.locale)
        yield RemoveTranslationResponse(affected > 0, missing)

  val all: List[McpToolHandlerR[QuillSqlite]] = List(status, setTranslations, removeTranslation)
end I18nTools
