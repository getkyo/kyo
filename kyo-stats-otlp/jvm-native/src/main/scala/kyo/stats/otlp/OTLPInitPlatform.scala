package kyo.stats.otlp

import kyo.*

/** JVM/Native auto-init: starts background fibers for metrics export
  * and trace flushing on kyo's existing scheduler.
  *
  * Triggered from OTLPTraceExporter constructor, which runs when
  * ExporterFactory creates the exporter.
  */
private[otlp] object OTLPInitPlatform:

    /** Starts background fibers for periodic trace flushing, signal-driven flushing, and metric export. Idempotent via `started` CAS. */
    def triggerStart(exporter: OTLPTraceExporter)(using AllowUnsafe): Unit =
        val config = exporter.config
        if exporter.started.compareAndSet(false, true) then
            given Frame = Frame.internal
            val _ = Sync.Unsafe.evalOrThrow {
                OTLPMetricsExporter.run(config).andThen {
                    Clock.repeatWithDelay(config.bspScheduleDelay) {
                        Abort.recover[Throwable](err => Log.error("OTLP scheduled trace flush failed", err)) {
                            exporter.flush(config)
                        }
                    }.andThen {
                        Fiber.initUnscoped {
                            Loop.forever {
                                Abort.recover[Throwable](err => Log.error("OTLP signal trace flush failed", err)) {
                                    exporter.flushSignal.safe.take.andThen {
                                        exporter.flush(config)
                                    }
                                }.andThen(Loop.continue)
                            }
                        }
                    }
                }
            }
end OTLPInitPlatform
