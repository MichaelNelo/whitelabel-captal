package whitelabel.captal.mcp.apps

import java.time.Instant

import whitelabel.captal.core.application.queries.{RunReport, RunStatusQuery}
import whitelabel.captal.core.operation.Run
import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.tools.DeployTools.given
import whitelabel.captal.mcp.tools.{DeployTools, Queries}
import whitelabel.captal.platform.operations.RunRecords
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.http.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec as SchemaJsonCodec
import zio.stream.ZStream

/** A deploy run as Server-Sent Events, for the page in [[RunUi]].
  *
  * Read from the run's row, which is all the state a run has, so any replica can answer and a
  * reconnecting page picks up where the run is rather than where it was: once as the page connects,
  * and again each time the change feed says the row was written ([[RunFeed]]). Every change is one
  * `data:` event carrying the whole run; the heartbeat writes the row every ten seconds, so a
  * running run is never silent long enough for a proxy to cut it. The stream ends after the event
  * that shows the run finished.
  *
  * POST with the ticket in a urlencoded body, like [[UiRoutes]]: CORS-safelisted, so the sandboxed
  * frame needs no preflight, and the ticket stays out of the URL and out of every access log.
  */
object RunRoutes:
  val EventsPath: String = "/ui/runs/events"

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

  /** The run as it is, then again after each change the feed says may be its own, for as long as it
    * runs. Subscribed before the first read, so a write between the two is still heard.
    */
  private[mcp] def events(runId: String): ZStream[Queries.RunStatus & RunFeed, Nothing, String] =
    ZStream.unwrapScoped:
      ZIO
        .serviceWithZIO[RunFeed](_.subscribe)
        .map: signals =>
          ZStream
            .unfoldZIO(Option.empty[RunReport]): last =>
              ZIO.foreachDiscard(last)(nextChange(signals, runId, _)) *>
                read(runId).map(_.map(report => report -> Some(report)))
            .map(DeployTools.viewOf(_, alreadyGoing = false))
            .changes
            .takeUntil(_.status != RunRecords.Running)
            .map(view => s"data: ${encoder.encodeJson(view, None)}\n\n")

  private def read(runId: String): URIO[Queries.RunStatus, Option[RunReport]] = ZIO
    .serviceWithZIO[Queries.RunStatus](_.execute(RunStatusQuery.ById(runId)))
    .orDie
    .map(_.toOption)

  /** Until the feed says `runId` was written, or that anything may have been — or until the
    * heartbeat `last` read goes stale, which nothing writes: a runner that died writes no more, and
    * its run reads as interrupted only once its heartbeat is older than [[Run.StaleAfter]]. A
    * deadline rather than a schedule, a second past it so the read finds it passed.
    */
  private def nextChange(
      signals: Dequeue[RunFeed.Signal],
      runId: String,
      last: RunReport): UIO[Unit] =
    val written = signals
      .take
      .repeatUntil:
        case RunFeed.Signal.Changed(id) =>
          id == runId
        case RunFeed.Signal.Gap =>
          true
    val stale = Instant.parse(last.updatedAt).plus(Run.StaleAfter).plusSeconds(1)
    val staleness = Clock
      .instant
      .flatMap(now => ZIO.sleep(Duration.fromInterval(now, stale)).when(now.isBefore(stale)))
    written.unit.race(staleness.unit)

  private def stream(runId: String): ZIO[Queries.RunStatus & RunFeed, Nothing, Response] = ZIO
    .environment[Queries.RunStatus & RunFeed]
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

  def routes: Routes[QuillSqlite & Queries.RunStatus & RunFeed, Response] = Routes(
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
                    ZIO
                      .serviceWithZIO[Queries.RunStatus](_.execute(RunStatusQuery.ById(runId)))
                      .orDie
                      .flatMap:
                        case Left(_) =>
                          ZIO.succeed(text(Status.NotFound, "Ese run no existe."))
                        case Right(_) =>
                          stream(runId)
                ))
end RunRoutes
