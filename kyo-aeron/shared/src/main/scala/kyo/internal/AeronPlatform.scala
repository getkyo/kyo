package kyo.internal

import kyo.*

/** Lifecycle bundle for an Aeron runtime: transport + teardown.
  *
  * `AeronRuntime` is the result of `AeronPlatform.embedded(dir)` (which owns both
  * the client and the embedded driver) or `AeronPlatform.external(aeronDir)` (which
  * owns only the client; the caller owns the external driver). The transport is the
  * platform-specific `AeronTransport` impl; `close` tears down whatever this runtime
  * owns: for an embedded runtime both the Aeron client and the embedded media driver
  * in that order, for an external runtime only the client.
  *
  * `close` is unsafe-tier; the caller bridges via `Sync.Unsafe.defer` (which provides
  * the `AllowUnsafe` proof) and `Sync.ensure`.
  */
private[kyo] trait AeronRuntime:
    def transport: AeronTransport
    def close()(using AllowUnsafe): Unit
end AeronRuntime

/** Per-platform Aeron runtime selectors: embedded driver and external-driver connect.
  *
  * `embedded(dir)` launches an embedded Aeron media driver and connects a client,
  * delegating to the per-platform `AeronPlatformTransport.embedded(dir)` selector
  * (JVM: Java client + `MediaDriver.launchEmbedded`; Native/JS: C client + embedded
  * C media driver via the FFI shim). The `dir` parameter is the unique filesystem
  * directory allocated by the caller (via `Path.tempDir`) for this driver instance;
  * each call must receive a distinct directory so that concurrent embedded drivers
  * do not collide on the same CnC file. The row is `< Async` because the
  * `@Ffi.blocking` `driverStart`/`clientConnect` downcalls are surfaced through a
  * `.safe.get` bridge (`Fiber.get` requires `Async`); on JVM the body runs in the
  * Async row for a uniform seam.
  *
  * `external(aeronDir)` connects a client to an EXTERNAL driver already running at
  * `aeronDir`; the caller owns the driver lifecycle, so the returned runtime's
  * `close()` closes ONLY the client, never a driver. This is the shared eager-typed
  * connect primitive: the in-band-failure catch (JVM `AeronException`; FFI
  * `FfiNullPointer`) is mapped ONCE here to `Abort.fail(TopicTransportFailedException)`,
  * consumed by both `Topic.run(aeronDir)` and `AeronClient.connect`.
  */
private[kyo] object AeronPlatform:
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        AeronPlatformTransport.embedded(dir)

    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        AeronPlatformTransport.external(aeronDir)
end AeronPlatform
