package kyo.internal

import kyo.Instant

/** The platform clock for [[kyo.Instant]] on Scala.js, linked as JS or as WasmGC. */
private[kyo] object InstantPlatformSpecific:

    /** The current time in milliseconds, the resolution `java.time.Instant.now()` offers on this platform, read without it. */
    def now(): Instant = Instant.fromEpochMilli(java.lang.System.currentTimeMillis())
end InstantPlatformSpecific
