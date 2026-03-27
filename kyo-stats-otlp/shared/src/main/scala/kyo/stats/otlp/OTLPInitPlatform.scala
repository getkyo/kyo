package kyo.stats.otlp

import kyo.*

/** Starts background fibers for metrics export and trace flushing.
  *
  * Triggered from OTLPTraceExporter constructor, which runs when ExporterFactory creates the exporter.
  */
private[otlp] object OTLPInitPlatform:

    /** Starts background fibers for periodic trace flushing, signal-driven flushing, and metric export. Idempotent via `started` CAS. */
    def triggerStart(exporter: OTLPTraceExporter)(using AllowUnsafe) =
        val config = exporter.config
        if exporter.started.compareAndSet(false, true) then
            // ExporterFactory (kyo-stats-registry) can't have Frame
            given Frame = Frame.internal
            val _ = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Scope.run {
                        OTLPMetricsExporter.run(config).andThen {
                            OTLPClient.startExportLoop(
                                config.bspScheduleDelay,
                                config.bspExportTimeout,
                                "traces",
                                exporter.trigger
                            )(exporter.flush(config))
                        }.andThen {
                            // Keep scope alive forever so fibers aren't interrupted
                            Async.never
                        }
                    }
                }
            }
        end if
    end triggerStart
end OTLPInitPlatform
