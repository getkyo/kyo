package kyo.stats.otlp

import kyo.AllowUnsafe
import kyo.stats.internal.*

/** Service-loader factory that creates an [[OTLPTraceExporter]] when the `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable is set.
  *
  * Registered via `META-INF/services/kyo.stats.internal.ExporterFactory` and discovered automatically at startup.
  */
class OTLPExporterFactory extends ExporterFactory:
    override def traceExporter()(using AllowUnsafe): Option[TraceExporter] =
        OTLPConfig.loadIfEnabled().map(cfg => OTLPTraceExporter.init(cfg))
end OTLPExporterFactory
