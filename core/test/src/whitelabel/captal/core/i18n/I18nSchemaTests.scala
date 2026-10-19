package whitelabel.captal.core.i18n

import utest.*

object I18nSchemaTests extends TestSuite:

  /** The key set the portal and the `localized_texts` rows actually use. Must stay spelled out
    * rather than derived: a self-consistent derivation can still be wrong.
    */
  private val KeysBeforeDerivation: List[String] = List(
    "welcome.title",
    "welcome.subtitle",
    "welcome.steps.step1",
    "welcome.steps.step2",
    "welcome.steps.step3",
    "welcome.button.start",
    "welcome.button.connecting",
    "welcome.selectLanguage",
    "welcome.authorized.title",
    "welcome.authorized.remaining",
    "ready.title",
    "ready.subtitle",
    "ready.resetButton",
    "loading.message",
    "error.title",
    "error.retry",
    "error.generic",
    "question.submit",
    "question.next",
    "question.required",
    "question.invalidEmail",
    "question.invalidUrl",
    "question.invalidPattern",
    "question.minLength",
    "question.maxLength",
    "question.minSelections",
    "question.maxSelections",
    "question.invalidOption",
    "question.ratingOutOfRange",
    "question.numericOutOfRange",
    "question.dateOutOfRange",
    "question.invalidAnswer",
    "video.pageTitle",
    "video.continueIn",
    "video.watchComplete",
    "video.markWatched",
    "video.loading",
    "video.noVideoAvailable",
    "video.payAttention"
  )

  /** Every key mapped to itself, so a misrouted field is visible as a wrong path. */
  private val identityMessages: Map[String, String] = KeysBeforeDerivation.map(k => k -> k).toMap

  val tests = Tests:

    test("keys"):
      test("match the hand-written enumeration exactly, in declaration order"):
        assert(I18nSchema.keys[I18n] == KeysBeforeDerivation)

      test("are unique"):
        val keys = I18nSchema.keys[I18n]
        assert(keys.distinct.size == keys.size)

    test("decode"):
      test("routes each key to its own field"):
        val i18n = I18nSchema.decode[I18n](identityMessages)
        // Nested cases are the ones a wrong prefix would break.
        assert(i18n.welcome.steps.step2 == "welcome.steps.step2")
        assert(i18n.welcome.button.connecting == "welcome.button.connecting")
        assert(i18n.welcome.authorized.remaining == "welcome.authorized.remaining")
        // A leaf on the root product, and a field whose name repeats across products.
        assert(i18n.welcome.selectLanguage == "welcome.selectLanguage")
        assert(i18n.loading.message == "loading.message")
        assert(i18n.video.loading == "video.loading")
        assert(i18n.error.title == "error.title")
        assert(i18n.ready.title == "ready.title")

      test("falls back to the visible [key] placeholder when a message is absent"):
        val i18n = I18nSchema.decode[I18n](Map.empty)
        assert(i18n.welcome.title == "[welcome.title]")
        assert(i18n.question.invalidAnswer == "[question.invalidAnswer]")

      test("keeps present keys when only some are missing"):
        val partial = Map("welcome.title" -> "Bienvenido")
        val i18n = I18nSchema.decode[I18n](partial)
        assert(i18n.welcome.title == "Bienvenido")
        assert(i18n.welcome.subtitle == "[welcome.subtitle]")

    test("missing"):
      test("reports nothing when every key is covered"):
        assert(I18nSchema.missing[I18n](identityMessages).isEmpty)

      test("reports every key for an empty map"):
        assert(I18nSchema.missing[I18n](Map.empty) == KeysBeforeDerivation)

      test("ignores keys that are not part of the schema"):
        val extra = identityMessages + ("welcome.nonexistent" -> "x")
        assert(I18nSchema.missing[I18n](extra).isEmpty)

    test("descriptions"):
      test("cover every key, by construction"):
        // The compiler already rejects a missing field; this states the invariant executably.
        val described = I18nSchema.flatten(I18n.descriptions)
        assert(described.keySet == KeysBeforeDerivation.toSet)

      test("are not blank or placeholder text"):
        val described = I18nSchema.flatten(I18n.descriptions)
        // `[key]` is the derivation's own fallback: one here means a description was never written.
        val bad = described.filter((k, v) => v.isBlank || v == s"[$k]" || v.length < 10)
        assert(bad.isEmpty)

      test("document the placeholders a translator must keep"):
        val described = I18nSchema.flatten(I18n.descriptions)
        // These interpolate runtime values; a translation that drops the token renders a literal.
        assert(described("question.minLength").contains("{min}"))
        assert(described("question.ratingOutOfRange").contains("{min}"))
        assert(described("question.ratingOutOfRange").contains("{max}"))
        assert(described("video.continueIn").contains("{seconds}"))
        assert(described("welcome.authorized.remaining").contains("{seconds}"))

    test("flatten"):
      test("round-trips through decode"):
        val source = KeysBeforeDerivation.map(k => k -> s"value for $k").toMap
        assert(I18nSchema.flatten(I18nSchema.decode[I18n](source)) == source)
end I18nSchemaTests
