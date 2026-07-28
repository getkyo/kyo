package kyo.internal

import kyo.*

/** Lifecycle bundle for an Aeron runtime: transport plus teardown.
  *
  * `close` tears down whatever this runtime owns: for an `embedded` runtime the Aeron client and
  * then the embedded media driver, in that order; for an `external` runtime only the client, since
  * the caller owns the driver. It is unsafe-tier; the caller bridges via `Sync.Unsafe.defer` and
  * `Sync.ensure`.
  */
private[kyo] trait AeronRuntime:
    def transport: AeronTransport
    def close()(using AllowUnsafe): Unit
end AeronRuntime

/** Per-platform Aeron runtime selectors, delegating to `AeronPlatformTransport`.
  *
  * `embedded(dir)` launches an embedded media driver and connects a client (JVM: Java client plus
  * `MediaDriver.launchEmbedded`; Native/JS: C client plus embedded C driver via the FFI shim).
  * `dir` must be a distinct directory per call (callers allocate via `Path.tempDir`), otherwise
  * concurrent embedded drivers collide on the same CnC file. The row is `< Async` because the
  * `@Ffi.blocking` `driverStart`/`clientConnect` downcalls surface through a `.safe.get` bridge;
  * the JVM body runs in the Async row too, for a uniform seam.
  *
  * `external(aeronDir)` connects to a driver already running at `aeronDir`. The caller owns that
  * driver, so the runtime's `close()` closes only the client. Both `Topic.run(aeronDir)` and
  * `AeronClient.connect` consume this primitive, so the in-band-failure mapping to
  * `Abort.fail(TopicTransportFailedException)` (JVM `AeronException`, FFI `FfiNullPointer`)
  * happens once, in the platform selectors.
  */
private[kyo] object AeronPlatform:
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        AeronPlatformTransport.embedded(dir)

    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        AeronPlatformTransport.external(aeronDir)
end AeronPlatform
