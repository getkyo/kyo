package kyo.internal

import kyo.*

private[kyo] object BrowserLauncherPlatform:

    /** Registers a runtime shutdown hook that forcibly kills the given Chrome process if it is still alive when the host runtime exits.
      * Safety net for exits that bypass the kyo scope finalizer chain (System.exit from outside kyo's effect tree, sbt abrupt termination).
      * SIGKILL bypasses shutdown hooks on both JVM and Scala Native, so it is not covered.
      */
    def registerShutdownHook(proc: Process)(using Frame): Unit < Sync =
        // Runtime.addShutdownHook(Thread) takes a raw java.lang.Thread, below the kyo
        // effect runtime; Sync.Unsafe.defer is the entry point for that ABI boundary.
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
