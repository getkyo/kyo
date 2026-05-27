package kyo.internal

import kyo.*

private[kyo] object BrowserLauncherPlatform:

    /** No-op on Native: process lifecycle is managed by the native runtime; no JVM shutdown hooks are available. */
    def registerShutdownHook(proc: Process)(using Frame): Unit < Sync =
        Kyo.unit

end BrowserLauncherPlatform
