package kyo.stats.internal

import kyo.AllowUnsafe

/** SPI for providing trace and stats exporters, discovered via `java.util.ServiceLoader`.
  *
  * Implementations return `Some(exporter)` to activate export, or `None` to remain inactive.
  */
abstract class ExporterFactory extends Serializable:
    def traceExporter()(using AllowUnsafe): Option[TraceExporter] = None
    def statsExporter()(using AllowUnsafe): Option[StatsExporter] = None
end ExporterFactory
