package whitelabel.captal.mcp

import scala.annotation.nowarn

/** Puts back the `Predef.ArrowAssoc.->` call shape Quill's assignment parser matches on, which
  * Scala 3.10 replaced with an inline extension method producing a bare tuple.
  *
  * Imported per file (`import QuillArrow.->`) wherever a Quill `update`, `insert` or
  * `onConflictUpdate` names columns; it shadows `Predef.->` for the whole file, which is what makes
  * it take effect. Delete it when Quill parses a bare `Tuple2`.
  */
object QuillArrow:
  /** Both parameters must stay `inline`, mirroring 3.10's own `->`: a by-value parameter of an
    * inline method is bound to a `val` at the call site, which turns the lambda body into a block,
    * and Quill's extractor requires the body to *be* the arrow.
    */
  extension [A](inline self: A)
    @nowarn("cat=deprecation")
    inline def ->[B](inline that: B): (A, B) = Predef.ArrowAssoc(self).->(that)
