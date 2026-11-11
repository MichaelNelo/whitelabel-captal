package whitelabel.captal.telemetry

import scala.jdk.CollectionConverters.*

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanKind, StatusCode, Tracer}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{TextMapGetter, TextMapPropagator, TextMapSetter}
import zio.*

/** Spans over OpenTelemetry's Java API, with the current one kept in the fiber.
  *
  * Not zio-opentelemetry: the backoffice's MCP library pins its 4.0 release candidate, which has no
  * public way to continue a trace another process started, and one version is all a process can
  * load. The model is the same — every span opened explicitly in ZIO, its context in a `FiberRef`
  * rather than a thread-local, since a fiber moves between threads and a forked one inherits it.
  */
final class Tracing private[telemetry] (
    tracer: Tracer,
    current: FiberRef[Context],
    propagator: TextMapPropagator):

  /** `effect` in a span named `name`, the child of the fiber's current one. */
  def span[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty())(effect: ZIO[R, E, A]): ZIO[R, E, A] = current
    .get
    .flatMap(within(_, name, kind, attributes)(effect))

  /** `effect` in a span continuing the trace `headers` carry as W3C trace context, or starting one
    * when they carry none.
    */
  def continuing[R, E, A](
      headers: Map[String, String],
      name: String,
      kind: SpanKind,
      attributes: Attributes = Attributes.empty())(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    within(propagator.extract(Context.root(), headers, Tracing.Getter), name, kind, attributes)(
      effect)

  /** The fiber's current span as W3C trace context headers (`traceparent`, `tracestate`): what a
    * message carries so that whoever handles it continues this trace.
    */
  def context: UIO[Map[String, String]] = current
    .get
    .map: context =>
      val headers = scala.collection.mutable.Map.empty[String, String]
      propagator.inject(context, headers, Tracing.Setter)
      headers.toMap

  def setAttribute(name: String, value: Long): UIO[Unit] =
    current.get.map(Span.fromContext(_).setAttribute(name, value)).unit

  /** Ends the span with the effect, an error when it failed with a `Throwable` or died: a typed
    * failure that is an answer — an HTTP response, say — is not the span's error.
    */
  private def within[R, E, A](
      parent: Context,
      name: String,
      kind: SpanKind,
      attributes: Attributes)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseExitWith(
      ZIO.succeed(
        tracer
          .spanBuilder(name)
          .setParent(parent)
          .setSpanKind(kind)
          .setAllAttributes(attributes)
          .startSpan()))((span, exit: Exit[E, A]) =>
      ZIO.succeed:
        exit match
          case Exit.Failure(cause)
              if cause.isDie || cause.failureOption.exists(_.isInstanceOf[Throwable]) =>
            span.setStatus(StatusCode.ERROR, cause.prettyPrint.take(500))
          case _ =>
            ()
        span.end())(span => current.locally(parent.`with`(span))(effect))
end Tracing

object Tracing:
  private val Getter: TextMapGetter[Map[String, String]] =
    new TextMapGetter[Map[String, String]]:
      def keys(carrier: Map[String, String]): java.lang.Iterable[String] = carrier.keys.asJava
      def get(carrier: Map[String, String], key: String): String =
        carrier.get(key).orElse(carrier.get(key.toLowerCase)).orNull

  private val Setter: TextMapSetter[scala.collection.mutable.Map[String, String]] =
    (carrier, key, value) =>
      if carrier != null then
        carrier.update(key, value)
end Tracing
