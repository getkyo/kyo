package kyo.stats.internal

import java.time.Instant
import kyo.AllowUnsafe
import kyo.stats.Attributes
import scala.util.control.NonFatal

/** Creates and manages trace spans for distributed tracing.
  *
  * Implementations handle span creation, ID generation, and export to a backend. The `noop` instance discards all spans. The `all`
  * combinator fans out to multiple exporters and propagates trace context from the first `Propagatable` span.
  */
abstract class TraceExporter extends Serializable {
    def startSpan(
        scope: List[String],
        name: String,
        now: Instant,
        parent: Option[UnsafeTraceSpan] = None,
        attributes: Attributes = Attributes.empty
    )(implicit _au: AllowUnsafe): UnsafeTraceSpan
}

object TraceExporter {

    /** Loads all `TraceExporter` instances via service-loader discovery and composes them. */
    def get(implicit _au: AllowUnsafe): TraceExporter = {
        import scala.jdk.CollectionConverters.*
        val factories = java.util.ServiceLoader.load(classOf[ExporterFactory]).iterator().asScala.toList
        val exporters = factories.flatMap(_.traceExporter())
        compose(exporters)
    }

    /** Service-loader discovery with per-factory isolation: a factory whose construction or
      * `traceExporter()` call throws is skipped and its exporters are omitted, so one failing provider
      * cannot take down discovery for the rest. The lazy service-loader iterator constructs each provider
      * on `next()`, so the guard wraps both the construction and the `traceExporter()` call for each entry.
      * Used where discovery runs at a boundary that turns a throw into an unrecoverable failure (a class
      * initializer), so a bad third-party provider degrades to skipped instead of bricking every later use.
      */
    def getIsolated(implicit _au: AllowUnsafe): TraceExporter = {
        val it = java.util.ServiceLoader.load(classOf[ExporterFactory]).iterator()
        // hasNext and next() both run provider code lazily and can throw; a hasNext throw ends discovery,
        // a next()/traceExporter() throw skips that one provider. Recursion accumulates the survivors.
        @scala.annotation.tailrec
        def loop(acc: List[TraceExporter]): List[TraceExporter] = {
            val hasNext =
                try it.hasNext
                catch { case ex: Throwable if NonFatal(ex) => false }
            if (!hasNext) acc.reverse
            else {
                val next =
                    try it.next().traceExporter().toList
                    catch { case ex: Throwable if NonFatal(ex) => Nil }
                loop(next reverse_::: acc)
            }
        }
        compose(loop(Nil))
    }

    private def compose(exporters: List[TraceExporter]): TraceExporter =
        exporters match {
            case Nil      => noop
            case h :: Nil => h
            case l        => all(l)
        }

    val noop: TraceExporter =
        new TraceExporter {
            def startSpan(
                scope: List[String],
                name: String,
                now: Instant,
                parent: Option[UnsafeTraceSpan] = None,
                attributes: Attributes = Attributes.empty
            )(implicit _au: AllowUnsafe): UnsafeTraceSpan =
                UnsafeTraceSpan.noop
        }

    def all(exporters: List[TraceExporter]): TraceExporter =
        new TraceExporter {
            def startSpan(
                scope: List[String],
                name: String,
                now: Instant,
                parent: Option[UnsafeTraceSpan] = None,
                attributes: Attributes = Attributes.empty
            )(implicit _au: AllowUnsafe): UnsafeTraceSpan = {
                val spans        = exporters.map(_.startSpan(scope, name, now, parent, attributes))
                val propagatable = spans.collectFirst { case p: UnsafeTraceSpan.Propagatable => p }
                propagatable match {
                    case Some(p) =>
                        new UnsafeTraceSpan with UnsafeTraceSpan.Propagatable {
                            def traceId                                                                  = p.traceId
                            def spanId                                                                   = p.spanId
                            def end(now: Instant)(implicit _au: AllowUnsafe)                             = spans.foreach(_.end(now))
                            def event(n: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe) = spans.foreach(_.event(n, a, now))
                            def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe) = spans.foreach(_.setStatus(status))
                        }
                    case None =>
                        new UnsafeTraceSpan {
                            def end(now: Instant)(implicit _au: AllowUnsafe)                             = spans.foreach(_.end(now))
                            def event(n: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe) = spans.foreach(_.event(n, a, now))
                            def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe) = spans.foreach(_.setStatus(status))
                        }
                }
            }
        }
}
