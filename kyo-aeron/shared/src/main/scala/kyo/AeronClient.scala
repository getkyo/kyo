package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronRuntime
import kyo.internal.AeronTransport

/** A `Scope`-managed handle to a connected Aeron client against an externally-running media driver.
  *
  * Obtain one with [[AeronClient.connect]], then hand it to the `Topic.run(client)` overload to run
  * `Topic`-effectful computations against it. Reach for this when several `Topic.run` scopes should
  * share a single client: connecting once and reusing the handle avoids paying the connect cost on
  * every scope.
  *
  * The handle owns the connection for the lifetime of the enclosing `Scope`: it closes exactly once
  * on scope exit, whether that exit is normal, an error, or a cancellation. `Topic.run(client)` runs
  * against the shared client without closing it, so the client outlives each individual `Topic.run`
  * scope and is released only when its own scope ends.
  *
  * Connecting to a directory where no driver is running fails with [[TopicTransportFailedException]], reported
  * at the connect call before any publish or stream begins.
  *
  * @see [[AeronClient.connect]] to obtain a connected client
  * @see [[Topic.run]] for the `run(client)` overload that runs against this client
  */
opaque type AeronClient = AeronClient.Unsafe

object AeronClient:
    extension (self: AeronClient)
        /** Exposes the low-level [[AeronClient.Unsafe]] view of this client. */
        def unsafe: Unsafe = self

    /** Connects a client to the externally-running Aeron media driver at `aeronDir` and binds its
      * lifetime to the current `Scope`.
      *
      * Use this to obtain an [[AeronClient]] that several `Topic.run(client)` scopes can share, paying
      * the connect cost once rather than per scope. The returned client closes exactly once when the
      * enclosing `Scope` exits (normal, error, or cancellation).
      *
      * The connect does not block a carrier thread. If no driver is running at `aeronDir`, the
      * computation fails with [[TopicTransportFailedException]] at the connect, before the client is returned.
      *
      * For a one-shot connect-use-close, reach for [[Topic.run]]`(aeronDir)`; to thread the client
      * through a scoped continuation, wrap `connect` in `Scope.run`.
      *
      * @param aeronDir
      *   the directory of the externally-running media driver
      * @return
      *   a `Scope`-managed [[AeronClient]], aborting [[TopicTransportFailedException]] when no driver is reachable
      */
    def connect(aeronDir: Path)(using Frame): AeronClient < (Scope & Async & Abort[TopicTransportFailedException]) =
        Scope.acquireRelease(
            AeronPlatform.external(aeronDir.unsafe.show).map(runtime =>
                Sync.Unsafe.defer(Unsafe.fromRuntime(runtime).safe)
            )
        )(client => Sync.Unsafe.defer(client.unsafe.close()))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code.
      * See AllowUnsafe for more details.
      */
    sealed abstract class Unsafe extends Serializable:
        private[kyo] def transport: AeronTransport
        def close()(using AllowUnsafe): Unit
        def safe: AeronClient = this
    end Unsafe

    object Unsafe:
        // Wraps an ALREADY-CONNECTED AeronRuntime into an AeronClient.Unsafe. The connect itself (and
        // its eager-in-band failure catch) is performed by AeronPlatform.external, not here.
        private[kyo] def fromRuntime(runtime: AeronRuntime)(using AllowUnsafe): AeronClient.Unsafe =
            new AeronClient.Unsafe:
                private[kyo] def transport: AeronTransport = runtime.transport
                def close()(using AllowUnsafe): Unit       = runtime.close()
    end Unsafe
end AeronClient
