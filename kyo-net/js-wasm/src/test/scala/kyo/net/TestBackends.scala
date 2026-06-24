package kyo.net

import kyo.*
import kyo.net.internal.JsTransport
import kyo.net.internal.backend.IoBackendPlatform

/** The set of I/O backends the shared test harness fans every scenario over, exposed in the same platform-uniform shape as the posix shim so
  * the harness in `kyo.net.Test` never names a platform's concrete registry type.
  *
  * JS has a single backend: Node's `NodeBackend`, registered in `IoBackendPlatform.registered` as a `Backend{name, isAvailable,
  * createDriver}`. That shape differs from the posix `Entry` (it hands back an `IoDriver`, not a built transport), so the JS shim builds the
  * transport itself through `JsTransport.init`, which selects the same `JsIoDriver` the production JS transport uses. The single Node backend
  * is always available, so its leaf always runs.
  */
object TestBackends:

    /** One testable backend: its registry [[name]] (`node`), whether it [[isAvailable]] on the current host, and a [[build]] thunk that
      * constructs and starts a real [[Transport]] over it. The thunk runs the same single-driver `JsTransport.init` the production JS
      * transport uses, so the transport the harness exercises matches production.
      *
      * `build` takes the caller's [[Frame]] explicitly: `JsTransport.init` requires a `Frame`, but the shim's `all` is a plain `val` with no
      * `Frame` of its own (one cannot be derived inside the `kyo` package). The harness passes its own `using Frame`.
      */
    final case class Entry(
        name: String,
        isAvailable: Boolean,
        build: (TransportConfig, Frame) => Transport
    )

    /** Every registered backend on this host (the single Node backend). The harness registers one leaf per entry and cancels the leaves whose
      * `isAvailable` is false; Node is always available, so its leaf always runs.
      */
    val all: Seq[Entry] =
        import AllowUnsafe.embrace.danger
        IoBackendPlatform.registered.map { backend =>
            Entry(
                name = backend.name,
                isAvailable = backend.isAvailable,
                build = (config, frame) =>
                    JsTransport.init(
                        poolSize = 1,
                        channelCapacity = config.channelCapacity,
                        handshakeTimeout = config.handshakeTimeout
                    )(using summon[AllowUnsafe], frame)
            )
        }
    end all

end TestBackends
