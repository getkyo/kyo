package kyo.net.internal

import kyo.*
import kyo.net.Transport

/** JS platform bootstrap. Lazily initializes a single-driver `JsTransport` (pool size 1, since JS is single-threaded). */
private[kyo] object NetPlatformTransport:
    /** Build the one process-lifetime [[kyo.net.NetPlatform.transport]] (single-driver, since JS is single-threaded). JS has no
      * Diagnostics-registering I/O drivers (the stranded-op / fiber-leak gate has nothing to allowlist here), so no marker is needed.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        JsTransport.init(poolSize = 1)
end NetPlatformTransport
