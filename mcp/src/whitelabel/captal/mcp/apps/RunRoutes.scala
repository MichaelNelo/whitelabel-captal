package whitelabel.captal.mcp.apps

import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.aws.Runs
import whitelabel.captal.mcp.tools.DeployTools
import whitelabel.captal.mcp.tools.DeployTools.given
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.http.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec as SchemaJsonCodec
import zio.stream.ZStream

/** A deploy run as Server-Sent Events, for the page in [[RunUi]].
  *
  * Read from the run's row, which is all the state a run has, so any replica can answer and a
  * reconnecting page picks up where the run is rather than where it was. Every change is one
  * `data:` event carrying the whole run; the heartbeat changes the row every ten seconds, so a
  * running run is never silent long enough for a proxy to cut it. The stream ends after the event
  * that shows the run finished.
  *
  * POST with the ticket in a urlencoded body, like [[UiRoutes]]: CORS-safelisted, so the sandboxed
  * frame needs no preflight, and the ticket stays out of the URL and out of every access log.
  */
object RunRoutes:
  val EventsPath: String = "/ui/runs/events"

  private val PollEvery: Duration = 1.second

  private val encoder = SchemaJsonCodec.jsonEncoder(summon[Schema[DeployTools.RunView]])

  private def text(status: Status, body: String): Response = Response(
    status = status,
    headers =
      Headers(Header.ContentType(MediaType.text.plain)) ++
        Headers(Header.AccessControlAllowOrigin.All),
    body = Body.fromString(body))

  /** Finds the key the ticket was signed with: the calling token's stored hash, refused once that
    * token is revoked.
    */
  private def runIdFor(ticket: String): ZIO[QuillSqlite, UiTicket.Rejected, String] =
    RunTicket.tokenIdOf(ticket) match
      case None =>
        ZIO.fail(UiTicket.Rejected.Malformed)
      case Some(tokenId) =>
        Tokens
          .byIdentifier(tokenId)
          .flatMap:
            case Some(row) if row.revokedAt.isEmpty =>
              ZIO.fromEither(RunTicket.verify(row.tokenSha256, ticket))
            case _ =>
              ZIO.fail(UiTicket.Rejected.UnknownToken)

  private def events(runId: String): ZStream[QuillSqlite, Nothing, String] = ZStream
    .repeatZIOWithSchedule(
      Runs.get(runId).flatMap(ZIO.foreach(_)(DeployTools.viewOf(_, alreadyGoing = false))),
      Schedule.spaced(PollEvery))
    .collectSome
    .changes
    .takeUntil(_.status != Runs.Running)
    .map(view => s"data: ${encoder.encodeJson(view, None)}\n\n")

  private def stream(runId: String): ZIO[QuillSqlite, Nothing, Response] = ZIO
    .environment[QuillSqlite]
    .map: env =>
      Response(
        status = Status.Ok,
        headers = Headers(
          Header.ContentType(MediaType.text.`event-stream`),
          Header.CacheControl.NoCache,
          Header.AccessControlAllowOrigin.All,
          Header.Custom("X-Accel-Buffering", "no")
        ),
        body = Body.fromCharSequenceStreamChunked(events(runId).provideEnvironment(env))
      )

  def routes: Routes[QuillSqlite, Response] = Routes(
    Method.POST / "ui" / "runs" / "events" ->
      handler: (request: Request) =>
        request
          .body
          .asURLEncodedForm
          .orElseSucceed(Form.empty)
          .flatMap: form =>
            form.get("ticket").flatMap(_.stringValue).map(_.trim).filter(_.nonEmpty) match
              case None =>
                ZIO.succeed(text(Status.Forbidden, "Falta el ticket."))
              case Some(ticket) =>
                runIdFor(ticket).foldZIO(
                  _ => ZIO.succeed(text(Status.Forbidden, "Este ticket no es válido.")),
                  runId =>
                    Runs
                      .get(runId)
                      .flatMap:
                        case None =>
                          ZIO.succeed(text(Status.NotFound, "Ese run no existe."))
                        case Some(_) =>
                          stream(runId)
                ))
end RunRoutes
