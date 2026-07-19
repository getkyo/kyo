package kyo.net.internal

import kyo.*
import kyo.net.NetConfig
import kyo.net.Transport

/** JS platform bootstrap. Lazily initializes a single-driver `JsTransport` (pool size 1, since JS is single-threaded). */
private[kyo] object NetPlatformTransport:
    /** Build a fresh JS transport with a custom config (single-driver, since JS is single-threaded). Caller owns its lifecycle. */
    def configured()(using AllowUnsafe, Frame): Transport =
        JsTransport.init(poolSize = 1)

    /** Process-lifetime variant, used to build the one shared [[kyo.net.NetPlatform.transport]]. JS has no Diagnostics-registering I/O
      * drivers (the stranded-op / fiber-leak gate has nothing to allowlist here), so no marker is needed: a plain build.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        configured()
end NetPlatformTransport
