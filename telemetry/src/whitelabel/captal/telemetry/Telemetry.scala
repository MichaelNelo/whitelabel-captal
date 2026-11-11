package whitelabel.captal.telemetry

import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import zio.*

/** Tracing for a deployable, and the shorthands for the fiber's [[Tracing]]. */
object Telemetry:

  /** Tracing for `scope`, exported over OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT`, with the rest of the
    * SDK's standard `OTEL_*` settings (`OTEL_SERVICE_NAME`, the sampler). Records nothing when that
    * variable is unset: a process with nowhere to send its spans keeps none. Closing the layer
    * flushes what is still buffered.
    */
  def layer(scope: String): TaskLayer[Tracing] = ZLayer.scoped:
    for
      openTelemetry <- ZIO
        .succeed(sys.env.get("OTEL_EXPORTER_OTLP_ENDPOINT").filter(_.trim.nonEmpty))
        .flatMap:
          case None =>
            ZIO.succeed(api.OpenTelemetry.noop())
          case Some(_) =>
            ZIO.fromAutoCloseable(
              ZIO.attempt(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk()))
      tracing <- make(openTelemetry, scope)
    yield tracing

  /** Tracing that records nothing, for tests and for wiring that needs a `Tracing` to exist. */
  val noop: ULayer[Tracing] = ZLayer.scoped(make(api.OpenTelemetry.noop(), "noop"))

  private def make(openTelemetry: api.OpenTelemetry, scope: String) = FiberRef
    .make(Context.root())
    .map(current =>
      Tracing(openTelemetry.getTracer(scope), current, W3CTraceContextPropagator.getInstance()))

  def span[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty())(effect: ZIO[R, E, A]): ZIO[R & Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.span(name, kind, attributes)(effect))

  def continuing[R, E, A](
      headers: Map[String, String],
      name: String,
      kind: SpanKind,
      attributes: Attributes = Attributes.empty())(effect: ZIO[R, E, A]): ZIO[R & Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.continuing(headers, name, kind, attributes)(effect))

  def context: URIO[Tracing, Map[String, String]] = ZIO.serviceWithZIO[Tracing](_.context)

  def setAttribute(name: String, value: Long): URIO[Tracing, Unit] = ZIO.serviceWithZIO[Tracing](
    _.setAttribute(name, value))
end Telemetry
