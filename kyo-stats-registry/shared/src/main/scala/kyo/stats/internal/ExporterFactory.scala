package kyo.stats.internal

import kyo.AllowUnsafe

/** SPI for providing trace exporters, discovered via `java.util.ServiceLoader`.
  *
  * Implementations return `Some(exporter)` to activate export, or `None` to remain inactive.
  */
abstract class ExporterFactory extends Serializable {
    def traceExporter()(implicit _au: AllowUnsafe): Option[TraceExporter] = None
}
