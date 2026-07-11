package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.TraceExporter

/** Service-loader fixture for the eager `ExporterFactory` scan hook in `object Stat`.
  *
  * Registered via `META-INF/services/kyo.stats.internal.ExporterFactory`, so `TraceExporter.get` (forced by
  * `kyo.Stat`'s class-init scan hook) constructs it once per test-runner fork. The no-arg constructor records
  * two facts observed at construction time: the total construction count, and whether `Stat.kyoScope` was already
  * a non-null `Stat` when this factory was constructed. The latter is the class-init ordering probe: because the
  * scan hook is the last `val` in `object Stat`, a factory constructed inside `Stat`'s class initializer must
  * observe a fully-initialized `kyoScope`, never the JVM default-`null` of a not-yet-initialized later field.
  *
  * The counter and flags are the raw `java.util.concurrent.atomic` types: this factory is constructed by
  * `java.util.ServiceLoader` outside any effect and any `AllowUnsafe` scope, the companion-singleton case where
  * a raw atomic is the correct cross-construction cell.
  */
final class StatTestExporterFactory extends ExporterFactory:
    StatTestExporterFactory.constructions.incrementAndGet()
    StatTestExporterFactory.kyoScopeWasNonNull.set(Stat.kyoScope != null)
    StatTestExporterFactory.constructed.set(true)

    override def traceExporter()(implicit _au: AllowUnsafe): Option[TraceExporter] = None
end StatTestExporterFactory

object StatTestExporterFactory:
    val constructions: AtomicInteger                           = new AtomicInteger(0)
    val constructed: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)
    val kyoScopeWasNonNull: java.util.concurrent.atomic.AtomicBoolean =
        new java.util.concurrent.atomic.AtomicBoolean(false)
end StatTestExporterFactory
