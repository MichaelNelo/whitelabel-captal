package whitelabel.captal.api

import io.circe.Decoder
import whitelabel.captal.messages.ApiError
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.SessionContext
import zio.*
import zio.http.Route
import zio.http.endpoint.{AuthType, Endpoint}
import zio.schema.Schema
import zio.schema.codec.JsonCodec

/** Whether the browser can read a response, asked before it is sent.
  *
  * The server writes with zio-schema and the browser reads with circe, and the browser's decoders
  * are `messages`, which compiles for the JVM too: so the server can read its own answer the way
  * the browser will. One it cannot is a defect of ours that the server saw. It is recorded, and
  * answered with its id, so the guest leaves through it at once, rather than through a client
  * report the server has no reason to believe.
  *
  * Only whether it decodes, not whether it decodes to an equal value: that is the browser's own
  * test, and a stricter one would turn a harmless difference into a guest sent out of the flow.
  *
  * Only routes that read the session: an error recorded with none is one nobody can leave through.
  */
object WireCheck:
  /** The server's own, as `HttpApp` applies it to every route. */
  private val config = JsonCodec.Config(ignoreEmptyCollections =
    HttpApp.codecConfig.ignoreEmptyCollections)

  final class UnreadableResponse(what: String, reason: String)
      extends Exception(s"The browser cannot read this $what: $reason")

  /** `value` as the server writes it to the browser. */
  def json[A: Schema](value: A): String =
    JsonCodec.jsonEncoder(config)(summon[Schema[A]]).encodeJson(value, None).toString

  def readable[A: Schema: Decoder](value: A): ZIO[QuillSqlite & SessionContext, ApiError, A] =
    io.circe.parser.decode[A](json(value)) match
      case Right(_) =>
        ZIO.succeed(value)
      case Left(e) =>
        ApiErrors.failWith(UnreadableResponse(value.getClass.getSimpleName, e.getMessage))

  extension [P, I, O, Auth <: AuthType](endpoint: Endpoint[P, I, ApiError, O, Auth])
    /** `implement`, with the answer checked [[readable]] before it is sent. */
    def implementReadable[Env](f: I => ZIO[Env, ApiError, O])(using
        Schema[O],
        Decoder[O],
        Trace): Route[Env & QuillSqlite & SessionContext, Nothing] = endpoint.implement(input =>
      f(input).flatMap(readable(_)))
end WireCheck
