package kyo

import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.TraceExporter

/** A deliberately-throwing `ExporterFactory` registered alongside `StatTestExporterFactory` in the test
  * `META-INF/services` resource. Its constructor throws, so the eager `ExporterFactory` scan at `object
  * Stat`'s class-init must isolate this failure per factory: it skips this provider and still constructs
  * the sibling `StatTestExporterFactory`, rather than letting one bad provider turn `object Stat`'s class
  * initializer into a permanent `NoClassDefFoundError` for every later Stat use.
  *
  * `constructionAttempted` records that discovery reached this provider (proving the isolation is
  * exercised, not that the provider was silently absent from discovery).
  */
final class StatTestThrowingFactory extends ExporterFactory:
    StatTestThrowingFactory.constructionAttempted.set(true)
    throw new RuntimeException("StatTestThrowingFactory always throws at construction")

    override def traceExporter()(implicit _au: AllowUnsafe): Option[TraceExporter] = None
end StatTestThrowingFactory

object StatTestThrowingFactory:
    val constructionAttempted: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
end StatTestThrowingFactory
