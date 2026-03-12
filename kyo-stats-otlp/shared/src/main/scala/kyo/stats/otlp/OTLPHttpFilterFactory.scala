package kyo.stats.otlp

import kyo.*

/** Service-loader factory that provides W3C Trace Context HTTP filters when OTLP export is enabled.
  *
  * Registered via `META-INF/services/kyo.HttpFilterFactory` and discovered automatically by the HTTP server and client. Returns `None` for
  * both filters when `OTEL_EXPORTER_OTLP_ENDPOINT` is not set.
  */
class OTLPHttpFilterFactory extends HttpFilterFactory:
    override def serverFilter(using Frame, AllowUnsafe): Option[HttpFilter.Passthrough[Nothing]] =
        OTLPConfig.loadIfEnabled().map(_ => OTLPTraceContextFilter.server)
    override def clientFilter(using Frame, AllowUnsafe): Option[HttpFilter.Passthrough[Nothing]] =
        OTLPConfig.loadIfEnabled().map(_ => OTLPTraceContextFilter.client)
end OTLPHttpFilterFactory
