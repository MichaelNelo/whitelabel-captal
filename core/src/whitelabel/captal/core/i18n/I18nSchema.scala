package whitelabel.captal.core.i18n

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

/** Derives the flat i18n key set from the [[I18n]] type itself.
  *
  * The keys used to live in three places at once: the types in [[I18n]], a hand-written enumeration
  * in `LocaleService.buildI18n`, and the prose of the `i18n-reference` skill. Keeping them in sync
  * was manual, and the failure mode is silent: a missing key renders as a literal `[welcome.title]`
  * in the portal rather than failing anywhere a test would catch.
  *
  * Deriving every direction from the schema makes the type the single source of truth. Adding a
  * field to a case class adds it to the required key set, to the decoder, to the documented
  * descriptions, and to whatever the backoffice reports as missing, all at once.
  *
  * Implemented with `Mirror` + `inline` instead of `scala.quoted` macros: the whole thing is a fold
  * over `MirroredElemLabels` and `MirroredElemTypes`, so quotes buy nothing. It also has to compile
  * for Scala.js, since `core-js` shares `core.sources`.
  */
trait I18nSchema[A]:
  /** Every key this schema expects, prefixed. The prefix is empty at the root. */
  def keys(prefix: String): List[String]

  /** Rebuilds the value from a flat map, one lookup per leaf. */
  def fromMap(prefix: String, messages: Map[String, String]): A

  /** Flattens a value back to key → leaf. The inverse of [[fromMap]]. */
  def toMap(prefix: String, value: A): Map[String, String]

object I18nSchema:
  /** The keys `A` requires, in declaration order. */
  def keys[A](using schema: I18nSchema[A]): List[String] = schema.keys("")

  /** Which of `A`'s keys the given map does not cover. */
  def missing[A](messages: Map[String, String])(using schema: I18nSchema[A]): List[String] = keys[A]
    .filterNot(messages.contains)

  def decode[A](messages: Map[String, String])(using schema: I18nSchema[A]): A = schema.fromMap(
    "",
    messages)

  def flatten[A](value: A)(using schema: I18nSchema[A]): Map[String, String] = schema.toMap(
    "",
    value)

  /** A leaf.
    *
    * The `[key]` placeholder is deliberate and predates this typeclass: it renders visibly in the
    * portal instead of failing at startup, which is how a missing translation gets noticed at all.
    * Kept identical so replacing `buildI18n` is behaviour-preserving.
    */
  given I18nSchema[String] with
    def keys(prefix: String): List[String] = List(prefix)
    def fromMap(prefix: String, messages: Map[String, String]): String = messages.getOrElse(
      prefix,
      s"[$prefix]")
    def toMap(prefix: String, value: String): Map[String, String] = Map(prefix -> value)

  /** Named rather than an anonymous `new I18nSchema[A]` inside [[derived]]: an anonymous class in
    * an inline body is re-emitted at every inline site, which the compiler warns about and which
    * would multiply classes in the Scala.js bundle the captive portal downloads. Public because
    * inline expansion lands at the call site, which may be any package.
    */
  final class Derived[A](
      keysOf: String => List[String],
      build: (String, Map[String, String]) => A,
      flattenOf: (String, A) => Map[String, String])
      extends I18nSchema[A]:
    def keys(prefix: String): List[String] = keysOf(prefix)
    def fromMap(prefix: String, messages: Map[String, String]): A = build(prefix, messages)
    def toMap(prefix: String, value: A): Map[String, String] = flattenOf(prefix, value)

  inline given derived[A <: Product](using m: Mirror.ProductOf[A]): I18nSchema[A] = Derived[A](
    prefix => collectKeys[m.MirroredElemLabels, m.MirroredElemTypes](prefix),
    (prefix, messages) =>
      val values = collectValues[m.MirroredElemLabels, m.MirroredElemTypes](prefix, messages)
      m.fromProduct(Tuple.fromArray(values.toArray))
    ,
    (prefix, value) =>
      collectEntries[m.MirroredElemLabels, m.MirroredElemTypes](
        prefix,
        value.productIterator.toIndexedSeq,
        0)
  )

  private def path(prefix: String, label: String): String =
    if prefix.isEmpty then
      label
    else
      s"$prefix.$label"

  /** Walks labels and element types in lockstep. The two `erasedValue` matches stay nested rather
    * than zipped because `Tuple.Zip` does not reduce inside an `inline match`.
    */
  private inline def collectKeys[Labels <: Tuple, Elems <: Tuple](prefix: String): List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple =>
        Nil
      case _: (label *: labels) =>
        inline erasedValue[Elems] match
          case _: EmptyTuple =>
            Nil
          case _: (elem *: elems) =>
            summonInline[I18nSchema[elem]].keys(
              path(prefix, constValue[label].asInstanceOf[String])) :::
              collectKeys[labels, elems](prefix)

  private inline def collectValues[Labels <: Tuple, Elems <: Tuple](
      prefix: String,
      messages: Map[String, String]): List[Any] =
    inline erasedValue[Labels] match
      case _: EmptyTuple =>
        Nil
      case _: (label *: labels) =>
        inline erasedValue[Elems] match
          case _: EmptyTuple =>
            Nil
          case _: (elem *: elems) =>
            summonInline[I18nSchema[elem]].fromMap(
              path(prefix, constValue[label].asInstanceOf[String]),
              messages) :: collectValues[labels, elems](prefix, messages)

  /** Same lockstep walk, but reading the runtime field values positionally. `index` tracks the
    * product arity already consumed, which stays aligned with the type-level fold.
    */
  private inline def collectEntries[Labels <: Tuple, Elems <: Tuple](
      prefix: String,
      values: IndexedSeq[Any],
      index: Int): Map[String, String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple =>
        Map.empty
      case _: (label *: labels) =>
        inline erasedValue[Elems] match
          case _: EmptyTuple =>
            Map.empty
          case _: (elem *: elems) =>
            summonInline[I18nSchema[elem]].toMap(
              path(prefix, constValue[label].asInstanceOf[String]),
              values(index).asInstanceOf[elem]) ++
              collectEntries[labels, elems](prefix, values, index + 1)
end I18nSchema
