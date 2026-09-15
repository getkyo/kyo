package kyo.scheduler

/** JS/Wasm runs a single-threaded scheduler with no worker pool, so there is no worker state to render and no
  * scheduler dumper to register. This no-op mirrors the JVM/Native [[SchedulerDiagnostics]] so the shared
  * [[IOTask]] first-touch hook resolves on every platform.
  */
private[kyo] object SchedulerDiagnostics:
    def init(): Unit = ()
end SchedulerDiagnostics
