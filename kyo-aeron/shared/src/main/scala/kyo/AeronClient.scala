package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronRuntime
import kyo.internal.AeronTransport

/** A `Scope`-managed handle to a connected Aeron client against an externally-running media driver.
  *
  * Obtain one with [[AeronClient.connect]], then hand it to the `Topic.run(client)` overload. Reach
  * for this when several `Topic.run` scopes should share a single client, paying the connect cost
  * once rather than per scope.
  *
  * The handle owns the connection for the lifetime of the enclosing `Scope` and closes exactly once
  * on scope exit, whether that exit is normal, an error, or a cancellation. `Topic.run(client)` does
  * not close it, so the client outlives each individual `Topic.run` scope.
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
      * lifetime to the current `Scope`, closing it exactly once on scope exit.
      *
      * The connect does not block a carrier thread. For a one-shot connect-use-close, reach for
      * [[Topic.run]]`(aeronDir)`; to thread the client through a scoped continuation, wrap `connect`
      * in `Scope.run`.
      *
      * @param aeronDir
      *   the directory of the externally-running media driver
      * @return
      *   a `Scope`-managed [[AeronClient]], aborting [[TopicTransportFailedException]] when no driver is reachable
      */
    def connect(aeronDir: Path)(using Frame): AeronClient < (Scope & Async & Abort[TopicTransportFailedException]) =
        Scope.acquireRelease(connectUnscoped(aeronDir))(client => Sync.Unsafe.defer(client.unsafe.close()))

    /** Connects a client whose lifetime the caller owns (mirrors `Fiber.initUnscoped` /
      * `Command.spawnUnscoped`).
      *
      * Reach for this only when the client must outlive every enclosing `Scope`, such as a backend
      * holding one client across many operations. The caller MUST close it exactly once via
      * `client.unsafe.close()`; nothing else will. Prefer [[connect]] whenever a `Scope` can own the
      * lifetime.
      *
      * @param aeronDir
      *   the directory of the externally-running media driver
      * @return
      *   an unscoped [[AeronClient]] the caller closes, aborting [[TopicTransportFailedException]]
      *   when no driver is reachable
      */
    def connectUnscoped(aeronDir: Path)(using Frame): AeronClient < (Async & Abort[TopicTransportFailedException]) =
        AeronPlatform.external(aeronDir.unsafe.show).map(runtime =>
            Sync.Unsafe.defer(Unsafe.fromRuntime(runtime).safe)
        )

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code.
      * See AllowUnsafe for more details.
      */
    sealed abstract class Unsafe extends Serializable:
        private[kyo] def transport: AeronTransport
        def close()(using AllowUnsafe): Unit
        def safe: AeronClient = this
    end Unsafe

    object Unsafe:
        /** Wraps an already-connected runtime. The connect, and its eager failure catch, happens in
          * `AeronPlatform.external`, not here.
          */
        private[kyo] def fromRuntime(runtime: AeronRuntime)(using AllowUnsafe): AeronClient.Unsafe =
            new AeronClient.Unsafe:
                private[kyo] def transport: AeronTransport = runtime.transport
                def close()(using AllowUnsafe): Unit       = runtime.close()
    end Unsafe
end AeronClient
