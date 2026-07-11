package kyo

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kyo.stats.Attributes
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.TraceExporter
import kyo.stats.internal.UnsafeTraceSpan

/** Service-loader fixture for the eager `ExporterFactory` scan hook in `object Stat`.
  *
  * Registered via `META-INF/services/kyo.stats.internal.ExporterFactory`, so the eager scan (forced by
  * `kyo.Stat`'s class-init) constructs it once per test-runner fork. The no-arg constructor records two
  * facts observed at construction time: the total construction count, and whether `Stat.kyoScope` was
  * already a non-null `Stat` when this factory was constructed. The latter is the class-init ordering
  * probe: because the scan hook is the last `val` in `object Stat`, a factory constructed inside `Stat`'s
  * class initializer must observe a fully-initialized `kyoScope`, never the JVM default-`null` of a
  * not-yet-initialized later field.
  *
  * `traceExporter()` returns a counting `TraceExporter` and increments `exporterConstructions` each time
  * one is built. `object Stat` shares ONE `TraceExporter` construction between the class-init scan and the
  * first trace use (the Local default reuses the scanned instance), so `exporterConstructions` must stay at
  * 1 across a clinit force followed by a `traceSpan`; a value of 2 is the double-construction defect.
  *
  * The counter and flags are the raw `java.util.concurrent.atomic` types: this factory is constructed by
  * `java.util.ServiceLoader` outside any effect and any `AllowUnsafe` scope, the companion-singleton case
  * where a raw atomic is the correct cross-construction cell.
  */
final class StatTestExporterFactory extends ExporterFactory:
    StatTestExporterFactory.constructions.incrementAndGet()
    StatTestExporterFactory.kyoScopeWasNonNull.set(Stat.kyoScope != null)
    StatTestExporterFactory.constructed.set(true)

    override def traceExporter()(implicit _au: AllowUnsafe): Option[TraceExporter] =
        StatTestExporterFactory.exporterConstructions.incrementAndGet()
        Some(new TraceExporter:
            def startSpan(
                scope: List[String],
                name: String,
                now: Instant,
                parent: Option[UnsafeTraceSpan] = None,
                attributes: Attributes = Attributes.empty
            )(implicit _au: AllowUnsafe): UnsafeTraceSpan = UnsafeTraceSpan.noop)
    end traceExporter
end StatTestExporterFactory

object StatTestExporterFactory:
    val constructions: AtomicInteger                           = new AtomicInteger(0)
    val exporterConstructions: AtomicInteger                   = new AtomicInteger(0)
    val constructed: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)
    val kyoScopeWasNonNull: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
end StatTestExporterFactory
