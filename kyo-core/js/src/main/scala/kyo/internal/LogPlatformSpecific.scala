package kyo.internal

import kyo.*

private[kyo] trait LogPlatformSpecific extends LogShared:

    /** Writes the event synchronously on the calling fiber; no daemon is ever created.
      * `kyo.Log.asyncLogging` has no effect on JS/Wasm, which are single-threaded.
      */
    private[kyo] def emit(event: Log.Event)(using AllowUnsafe, Frame): Unit =
        write(event)

    /** No daemon to await; writes are already synchronous on JS/Wasm. */
    private[kyo] def flushDaemon(using Frame): Unit < Async = ()

    /** No daemon is ever created on JS/Wasm; the counter is always 0. */
    private[kyo] def daemonInitCount(using AllowUnsafe): Int = 0

end LogPlatformSpecific
