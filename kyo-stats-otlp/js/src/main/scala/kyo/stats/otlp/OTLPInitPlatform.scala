package kyo.stats.otlp

import kyo.*

/** JS platform stub — no background fibers are started. Export is deferred to the first span activity. */
private[otlp] object OTLPInitPlatform:
    /** No-op on JS. */
    def triggerStart(exporter: OTLPTraceExporter)(using AllowUnsafe): Unit = ()
end OTLPInitPlatform
