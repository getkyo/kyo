package kyo.net

import kyo.*
import kyo.net.internal.backend.IoBackendPlatform

/** The set of I/O backends the shared test harness fans every scenario over, exposed in the same platform-uniform shape the JVM and Native
  * harnesses use so `kyo.net.Test` never names a platform's concrete registry type.
  *
  * JS registers the same posix [[kyo.net.internal.backend.Entry]] backends the JVM and Native registries do (io_uring/epoll/kqueue, in the
  * SHARED source set, each driving its koffi `PosixTransport`) above Node's `NodeBackend` floor. On macOS Node the kqueue leaf runs the native
  * posix transport over koffi and the Node leaf runs `JsTransport`; on Linux Node the epoll/io_uring leaves run instead. Each entry builds its
  * OWN transport through `Entry.build`, so the harness exercises the exact transport production would select for that backend, not a stand-in.
  */
object TestBackends:

    /** One testable backend: its registry [[name]] (`kqueue`/`epoll`/`io_uring`/`node`), whether it [[isAvailable]] on the current host, and a
      * [[build]] thunk that constructs and starts the real [[Transport]] the backend drives. The thunk runs the backend's own `Entry.build`,
      * so a posix leaf exercises the koffi `PosixTransport` and the Node leaf exercises `JsTransport`, each exactly as production builds it.
      *
      * `build` takes the caller's [[Frame]] explicitly: `Entry.build` requires a `Frame`, but the shim's `all` is a plain `val` with no `Frame`
      * of its own (one cannot be derived inside the `kyo` package). The harness passes its own `using Frame`.
      */
    final case class Entry(
        name: String,
        isAvailable: Boolean,
        private val make: Frame => Transport
    ):
        // One transport per backend, built on first use and never torn down, mirroring production: a transport is process-lifetime, so the
        // harness holds one per backend for the run rather than building and discarding one per leaf. Cells share it exactly as every client
        // and server in a process shares NetPlatform.transport; per-connection settings travel with each operation, so sharing costs a cell
        // nothing. Synchronized because leaves can run concurrently and this must construct exactly once per backend.
        private var built: Maybe[Transport] = Absent

        def transport(using frame: Frame): Transport =
            synchronized {
                built match
                    case Present(t) => t
                    case Absent =>
                        val t = make(frame)
                        built = Present(t)
                        t
                end match
            }
        end transport
    end Entry

    /** Every registered backend on this host, in registry order. The harness registers one leaf per entry and cancels the leaves whose
      * `isAvailable` is false, so on macOS Node the io_uring/epoll leaves cancel (wrong OS) while the kqueue and node leaves run; on Linux Node
      * the kqueue leaf cancels. `KYO_NET_ONLY=<backend>` restricts the matrix to one backend (e.g. `KYO_NET_ONLY=kqueue`), mirroring the
      * JVM/Native harness so a single backend can be validated in isolation; absent, every registered backend runs.
      */
    val all: Seq[Entry] =
        import AllowUnsafe.embrace.danger
        val only = sys.env.get("KYO_NET_ONLY")
        IoBackendPlatform.registered.filter(entry => only.forall(_ == entry.name)).map { backend =>
            Entry(
                name = backend.name,
                isAvailable = backend.probe.isAvailable,
                make = frame => backend.build()(using summon[AllowUnsafe], frame)
            )
        }
    end all

end TestBackends
