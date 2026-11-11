package whitelabel.captal.cdc

import java.util.concurrent.TimeUnit

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import scalapb.zio_grpc.ZBindableService
import whitelabel.captal.telemetry.Telemetry
import zio.*
import zio.http.Server

/** The relay: rqlite posts to it over HTTP, subscribers follow it over gRPC.
  *
  * `CDC_PORT` (8090 unless set) is the HTTP port, `CDC_GRPC_PORT` (9090) the gRPC one, and
  * `CDC_BUFFER` (1000) how many change sets it keeps for subscribers that reconnect.
  */
object Main extends ZIOAppDefault:
  def run =
    for
      httpPort <- System.envOrElse("CDC_PORT", "8090").map(_.toInt)
      grpcPort <- System.envOrElse("CDC_GRPC_PORT", "9090").map(_.toInt)
      capacity <- System.envOrElse("CDC_BUFFER", "1000").map(_.toInt)
      relay    <- Relay.make(capacity)
      instance = relay.instance
      _ <- ZIO.logInfo(
        s"Relaying change data capture from :$httpPort to :$grpcPort as $instance, keeping $capacity")
      _ <-
        Server
          .serve(RelayRoutes(relay))
          .provide(Server.defaultWithPort(httpPort), Telemetry.layer("captal-cdc")) <&>
          ZIO.scoped(serve(grpcPort, relay) *> ZIO.never)
    yield ()

  /** The feed served on `port`, for as long as the scope lasts; the port it took, for `0`.
    *
    * Not zio-grpc's `ScopedServer`, whose release waits for every call to end: a subscription never
    * ends on its own, so the relay would never stop. This one ends them, and the subscribers
    * reconnect to whatever relay comes next.
    */
  def serve(port: Int, relay: Relay): ZIO[Scope, Throwable, Int] =
    for
      definition <- ZBindableService.serviceDefinition(ChangeFeedService(relay))
      server     <-
        ZIO.acquireRelease(ZIO.attempt(grpc(port).addService(definition).build().start()))(server =>
          ZIO.attempt(server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)).ignore)
    yield server.getPort

  /** Subscribers ping a quiet stream to find a relay that is gone; this lets them, where the
    * default would answer their pings by closing the connection.
    */
  private def grpc(port: Int): NettyServerBuilder = NettyServerBuilder
    .forPort(port)
    .permitKeepAliveTime(10, TimeUnit.SECONDS)
    .permitKeepAliveWithoutCalls(true)
end Main
