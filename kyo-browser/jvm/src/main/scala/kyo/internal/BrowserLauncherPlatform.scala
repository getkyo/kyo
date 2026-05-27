package kyo.internal

import kyo.*

private[kyo] object BrowserLauncherPlatform:

    /** Registers a JVM shutdown hook that forcibly kills the given Chrome process if it is still alive when the JVM exits. This is the
      * safety net for JVM exits that bypass the kyo scope finalizer chain (SIGKILL, System.exit from outside kyo's effect tree, sbt abrupt
      * termination).
      */
    def registerShutdownHook(proc: Process)(using Frame): Unit < Sync =
        // `Runtime.getRuntime.addShutdownHook(Thread)` requires a raw java.lang.Thread,
        // which is below the kyo effect runtime; Sync.Unsafe.defer is the entry point
        // for that JVM-ABI boundary.
        Sync.Unsafe.defer {
            val unsafeProc = proc.unsafe
            val hook = new Thread(
                () =>
                    try if unsafeProc.isAlive() then unsafeProc.destroyForcibly()
                    catch case _: Throwable => () // best effort during shutdown
                ,
                "kyo-browser-shutdown-killer"
            )
            hook.setDaemon(false) // shutdown hooks must NOT be daemon threads
            Runtime.getRuntime.addShutdownHook(hook)
        }

end BrowserLauncherPlatform
