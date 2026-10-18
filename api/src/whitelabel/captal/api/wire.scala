package whitelabel.captal.api

import whitelabel.captal.core.application.Phase
import whitelabel.captal.messages.{ApiError, StatusResponse}
import zio.schema.{DeriveSchema, Schema}

/** The server's half of the wire format, for the routes that have left tapir.
  *
  * Its opposite number is `schemas`, which holds the tapir `Schema`s still in use. The two are not
  * the same kind of thing despite the shared name, and that is the whole reason this file exists: a
  * tapir `Schema` describes a shape circe has already decided, so a type nobody documents can take
  * `Schema.anyObject` and cost nothing — eight of them do. A zio-schema `Schema` *is* the codec, so
  * there is no such thing as an undocumented type here. Every entry below is load-bearing, and
  * entries move from `schemas` to this file as routes convert, one route at a time.
  *
  * JVM-only on purpose. These instances were briefly declared beside the types they describe, in
  * `core`, which cross-compiles — so a serialisation library the browser has no use for landed on
  * the Scala.js classpath for types the browser decodes with circe. Nothing linked it, but the
  * right place for a `Schema` the server needs is the server.
  */
object wire:

  /** The same seven strings `Phase` has always gone over the wire as.
    *
    * Hand-written rather than derived, because a derived enum schema writes the variant name in an
    * object — `{"Welcome":{}}`, which is what `ApiError` now does — and the browser reads `Phase`
    * with `Decoder.decodeString.map(fromDbString)`. `ApiError` could change shape because both
    * sides of it are derived together; `Phase` cannot, so the `Schema` matches the circe instance
    * instead. `fromDbString` is total — an unknown string is `Welcome` — which is why this is
    * `transform` and not `transformOrFail`.
    */
  given Schema[Phase] = Schema[String].transform(Phase.fromDbString, Phase.toDbString)

  /** Derived, unlike `Phase`, because both sides of `ApiError` are derived — its circe pair was
    * hand-written until the two were checked against each other and the written bytes turned out to
    * match. `WireContractSuite` is what holds that; see its header for what the hand-written pair
    * had been getting wrong.
    */
  given Schema[ApiError] = DeriveSchema.gen[ApiError]

  given Schema[StatusResponse] = DeriveSchema.gen[StatusResponse]

end wire
