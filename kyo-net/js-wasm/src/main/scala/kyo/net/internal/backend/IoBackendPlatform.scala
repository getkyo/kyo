package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Transport
import kyo.net.internal.JsHandle
import kyo.net.internal.JsIoDriver
import kyo.net.internal.JsTransport
import kyo.net.internal.transport.IoDriver

/** JS/Wasm Node backend and the always-available floor.
  *
  * `NodeBackend` drives Node's own event loop (a `JsIoDriver`) and is an [[Entry]]: it builds its own `JsTransport`. The koffi posix backends
  * (io_uring/epoll/kqueue, in the SHARED source set) register ABOVE it, so on a posix Node host the native readiness transport is selected and
  * Node is the floor, exactly as the JVM registry ranks the posix backends above the NIO floor. Node terminates its own TLS, so `JsTransport`
  * needs no in-process engine; the posix transport on JS does BoringSSL TLS through koffi (`TlsProviderPlatform.engine`).
  */
private[net] object NodeBackend extends Entry:
    type Handle = JsHandle

    def name = "node"

    def priority = 10

    /** Node's net stack is built in; nothing to stage, so nothing can be missing for this host. */
    def libraryIds: Chunk[String] = Chunk.empty

    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = CapabilityOutcome.Available

    def createDriver()(using AllowUnsafe, Frame): IoDriver[JsHandle] =
        JsIoDriver.init()

    /** JS is single-threaded (one Node event loop), so one `JsIoDriver` suffices; `JsTransport.init` builds the pool over `createDriver`. */
    def build()(using AllowUnsafe, Frame): Transport =
        JsTransport.init(poolSize = 1)
end NodeBackend

/** JS/Wasm `registered` list and selection entry point, mirroring the JVM/Native `IoBackendPlatform`: the koffi posix backends
  * (io_uring 30, epoll 20, kqueue 20) above the always-available `NodeBackend` floor (10). On a posix Node host the highest available entry is
  * a posix backend (kqueue on macOS, epoll/io_uring on Linux, gated by each backend's own OS check and koffi probe), so the native
  * `PosixTransport` runs; with no usable posix syscall, an unloadable native, or `-Dkyo.net.backend=node`, the Node floor wins and `JsTransport`
  * runs. The backends register themselves as [[Entry]] with no wrapper, and selection runs through the same shared `IoBackend.select` the JVM,
  * Native, and TLS registries use, so adding a backend is a list edit, never a `select` edit.
  */
private[net] object IoBackendPlatform:

    val registered: Chunk[Entry] =
        Chunk(IoUringBackend, EpollBackend, KqueueBackend, NodeBackend)

    /** The selected JS/Wasm entry honoring `-Dkyo.net.backend`. On a posix Node host this is a posix backend (`kqueue`/`epoll`/`io_uring`), and
      * `node` only when forced or when no posix backend is available.
      */
    def selected(using AllowUnsafe, Frame): Entry =
        IoBackend.select[Entry, NetBackendUnavailableException](
            registered,
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetBackendUnavailableException(forced, report.render)
        ).getOrThrow

    /** Build the selected JS/Wasm transport: the koffi `PosixTransport` when a posix backend wins, else `NodeBackend`'s `JsTransport`. Honors
      * `-Dkyo.net.backend` and, for an unforced selection, degrades to the next available backend when a higher-priority one probes available
      * but fails to build (so an unloadable koffi posix native falls back to Node rather than failing the whole transport).
      */
    def transport()(using AllowUnsafe, Frame): Transport =
        IoBackend.selectAndBuild[Entry, Transport](
            registered,
            _.build(),
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetBackendUnavailableException(forced, report.render)
        ).getOrThrow

end IoBackendPlatform
