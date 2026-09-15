package kyo.internal

import kyo.*

private[kyo] object BrowserLauncherPlatform:

    /** No-op on JS: process lifecycle is managed by the JS runtime; no JVM shutdown hooks are available. */
    def registerShutdownHook(proc: Process)(using Frame): Unit < Sync =
        Kyo.unit

end BrowserLauncherPlatform
