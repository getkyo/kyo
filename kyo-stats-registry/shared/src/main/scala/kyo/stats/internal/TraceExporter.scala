package kyo.stats.internal

import kyo.AllowUnsafe
import kyo.stats.Attributes
import java.time.Instant

/** Creates and manages trace spans for distributed tracing.
  *
  * Implementations handle span creation, ID generation, and export to a backend. The `noop` instance discards all spans. The `all`
  * combinator fans out to multiple exporters and propagates trace context from the first `Propagatable` span.
  */
abstract class TraceExporter extends Serializable:
    def startSpan(
        scope: List[String],
        name: String,
        now: Instant,
        parent: Option[UnsafeTraceSpan] = None,
        attributes: Attributes = Attributes.empty
    )(using AllowUnsafe): UnsafeTraceSpan

object TraceExporter:

    /** Loads all `TraceExporter` instances via service-loader discovery and composes them. */
    def get(using AllowUnsafe): TraceExporter =
        import scala.jdk.CollectionConverters.*
        val factories = java.util.ServiceLoader.load(classOf[ExporterFactory]).iterator().asScala.toList
        val exporters = factories.flatMap(_.traceExporter())
        exporters match
            case Nil      => noop
            case h :: Nil => h
            case l        => all(l)
    end get

    val noop: TraceExporter =
        new TraceExporter:
            def startSpan(
                scope: List[String],
                name: String,
                now: Instant,
                parent: Option[UnsafeTraceSpan] = None,
                attributes: Attributes = Attributes.empty
            )(using AllowUnsafe): UnsafeTraceSpan =
                UnsafeTraceSpan.noop

    def all(exporters: List[TraceExporter]): TraceExporter =
        new TraceExporter:
            def startSpan(
                scope: List[String],
                name: String,
                now: Instant,
                parent: Option[UnsafeTraceSpan] = None,
                attributes: Attributes = Attributes.empty
            )(using AllowUnsafe): UnsafeTraceSpan =
                val spans = exporters.map(_.startSpan(scope, name, now, parent, attributes))
                val propagatable = spans.collectFirst { case p: UnsafeTraceSpan.Propagatable => p }
                propagatable match
                    case Some(p) =>
                        new UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
                            def traceId = p.traceId
                            def spanId  = p.spanId
                            def end(now: Instant)(using AllowUnsafe) = spans.foreach(_.end(now))
                            def event(n: String, a: Attributes, now: Instant)(using AllowUnsafe) = spans.foreach(_.event(n, a, now))
                            def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe) = spans.foreach(_.setStatus(status))
                    case None =>
                        new UnsafeTraceSpan:
                            def end(now: Instant)(using AllowUnsafe) = spans.foreach(_.end(now))
                            def event(n: String, a: Attributes, now: Instant)(using AllowUnsafe) = spans.foreach(_.event(n, a, now))
                            def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe) = spans.foreach(_.setStatus(status))
end TraceExporter
