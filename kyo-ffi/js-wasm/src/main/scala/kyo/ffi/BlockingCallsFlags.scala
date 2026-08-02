package kyo.ffi

import kyo.StaticFlag

/** JS/Wasm-only configuration for the `@Ffi.blocking` dispatch meter.
  *
  * On JS/Wasm every `@Ffi.blocking` downcall is dispatched through koffi's asynchronous path (a libuv worker), and koffi caps the number of
  * concurrently in-flight async calls at `max_async_calls` (default 256, hard max 4096); exceeding it throws "Too many asynchronous calls
  * are running" synchronously at dispatch. To keep that from ever throwing, `kyo.ffi.internal.BlockingBridge` meters concurrent dispatches to
  * [[maxBlockingCalls]] and configures koffi's pool a [[maxBlockingCallsFactor]] above it, so the meter (never koffi) is what applies
  * backpressure. Both resolve once at first koffi use and never change afterwards (see [[kyo.StaticFlag]]).
  *
  * These have no effect on JVM (Panama) or Native (`@extern`), which do not use koffi.
  */
private[kyo] object maxBlockingCalls extends StaticFlag[Int](256, n => Right(n max 1))

/** The multiple of [[maxBlockingCalls]] that koffi's `max_async_calls` pool is configured to (clamped to koffi's 4096 hard max). The headroom
  * above the meter bound absorbs koffi's C-to-JS callback path, which draws from the same pool but cannot be metered.
  */
private[kyo] object maxBlockingCallsFactor extends StaticFlag[Int](2, n => Right(n max 1))
