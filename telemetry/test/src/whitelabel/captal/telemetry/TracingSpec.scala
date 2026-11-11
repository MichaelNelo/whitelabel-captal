package whitelabel.captal.telemetry

import scala.jdk.CollectionConverters.*

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio.*
import zio.test.*

/** Spans nest by the fiber, cross a fork, and continue in another process from the headers. */
object TracingSpec extends ZIOSpecDefault:

  /** A tracer whose spans land in `exporter` as each one ends. */
  private def tracing(exporter: InMemorySpanExporter) = FiberRef
    .make(Context.root())
    .map: current =>
      val provider = SdkTracerProvider
        .builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
      Tracing(provider.get("test"), current, W3CTraceContextPropagator.getInstance())

  private def finished(exporter: InMemorySpanExporter): Map[String, SpanData] =
    exporter.getFinishedSpanItems.asScala.map(span => span.getName -> span).toMap

  def spec =
    suite("Tracing")(
      test("a span opened inside another is its child, in the same trace"):
        ZIO.scoped:
          val exporter = InMemorySpanExporter.create()
          for
            tracing <- tracing(exporter)
            _       <- tracing.span("outer")(tracing.span("inner")(ZIO.unit))
            spans = finished(exporter)
          yield assertTrue(
            spans("inner").getParentSpanId == spans("outer").getSpanId,
            spans("inner").getTraceId == spans("outer").getTraceId)
      ,
      test("a forked fiber's span is the child of the span it was forked in"):
        ZIO.scoped:
          val exporter = InMemorySpanExporter.create()
          for
            tracing <- tracing(exporter)
            _       <- tracing.span("parent")(tracing.span("forked")(ZIO.unit).fork.flatMap(_.join))
            spans = finished(exporter)
          yield assertTrue(spans("forked").getParentSpanId == spans("parent").getSpanId)
      ,
      test("the context a span hands on is continued as the same trace, under that span"):
        ZIO.scoped:
          val exporter = InMemorySpanExporter.create()
          for
            tracing <- tracing(exporter)
            headers <- tracing.span("sender")(tracing.context)
            _       <- tracing.continuing(headers, "receiver", SpanKind.CONSUMER)(ZIO.unit)
            spans = finished(exporter)
          yield assertTrue(
            headers.contains("traceparent"),
            spans("receiver").getParentSpanId == spans("sender").getSpanId,
            spans("receiver").getTraceId == spans("sender").getTraceId,
            spans("receiver").getKind == SpanKind.CONSUMER
          )
      ,
      test("headers that carry no trace start a new one"):
        ZIO.scoped:
          val exporter = InMemorySpanExporter.create()
          for
            tracing <- tracing(exporter)
            _       <- tracing.continuing(Map.empty, "alone", SpanKind.SERVER)(ZIO.unit)
            spans = finished(exporter)
          yield assertTrue(!spans("alone").getParentSpanContext.isValid)
      ,
      test("a span that fails with a Throwable is an error; one that answers a typed value is not"):
        ZIO.scoped:
          val exporter = InMemorySpanExporter.create()
          for
            tracing <- tracing(exporter)
            _       <- tracing.span("broken")(ZIO.fail(new RuntimeException("boom"))).ignore
            _       <- tracing.span("answered")(ZIO.fail("404")).ignore
            spans = finished(exporter)
          yield assertTrue(
            spans("broken").getStatus.getStatusCode == StatusCode.ERROR,
            spans("answered").getStatus.getStatusCode == StatusCode.UNSET)
    )
end TracingSpec
