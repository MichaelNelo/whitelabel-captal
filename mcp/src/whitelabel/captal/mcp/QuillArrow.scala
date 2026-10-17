package whitelabel.captal.mcp

import scala.annotation.nowarn

/** Puts back the tree shape Quill's assignment parser matches on.
  *
  * Scala 3.10 made `->` an inline extension method (scala/scala3#24728), and its own migration
  * notes name this exact casualty: *"Quote patterns such as `case '{ ($a: String) -> ($b: Any) }`
  * no longer match, because the tree is no longer an ArrowAssoc conversion."* Quill's parser is one
  * of those patterns — `Extractors.ArrowFunction` matches `($prop: Any).->[v]($value)`, a call to
  * `Predef.ArrowAssoc.->` — so on 3.10 every `update(_.column -> lift(value))` in this module
  * failed with *"The assignment statement … is invalid"*. Both of Quill's accepted shapes go
  * through that one extractor, so `insert(_.c -> v)` and `onConflictUpdate` break the same way, and
  * there is no alternative syntax to move to: a bare tuple is what the compiler now produces and
  * what Quill rejects.
  *
  * `ArrowAssoc` survives as a non-implicit class whose `->` is deprecated but still a plain method,
  * and the same PR added a non-implicit `ArrowAssoc[A]` constructor for direct calls like this one.
  * An inline extension expanding to it restores the call before Quill's macro runs, which leaves
  * every call site ordinary Quill.
  *
  * Imported where it is needed rather than put on the package, because it shadows `Predef.->` for
  * the whole file. That shadowing is the half of the migration note that makes this work — *"A `->`
  * defined as an extension method still takes precedence"* — and it is also why the import is worth
  * seeing at the top of the file.
  *
  * Delete it when Quill parses a bare `Tuple2`. Quill 4.8.6 is the last release and predates 3.10,
  * so this is the whole of the module's 3.10 support, not a stopgap for one version.
  */
object QuillArrow:
  /** Both parameters are `inline`, mirroring 3.10's own `->`, and that is load-bearing rather than
    * tidiness: a by-value parameter of an inline method is bound to a `val that$proxy1` at the call
    * site, which turns the lambda body into a block. Quill's extractor is
    * `Lambda1(ident, tpe, ArrowFunction(prop, value))` — the body has to *be* the arrow, so a block
    * around it is rejected just as a bare tuple is, with the identical error message.
    */
  extension [A](inline self: A)
    @nowarn("cat=deprecation")
    inline def ->[B](inline that: B): (A, B) = Predef.ArrowAssoc(self).->(that)
