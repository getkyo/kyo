package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Test-tree construction helpers for the posix drivers. They wrap the drivers' package-private dependency constructors so a test can build a
  * driver over a chosen backend or bindings (a recording decorator over a real component, or the real bindings) without any production test
  * factory. Production builds drivers via the public `init(...)`; these helpers live entirely in the test tree.
  */
object TestDrivers:

    import AllowUnsafe.embrace.danger

    /** Build a [[PollerIoDriver]] over a caller-supplied backend and poller fd, loading the real socket bindings. */
    def forBackend(backend: PollerBackend, pollerFd: Int)(using AllowUnsafe): PollerIoDriver =
        new PollerIoDriver(backend, pollerFd, Ffi.load[SocketBindings])

    /** Build a [[PollerIoDriver]] over a caller-supplied backend, poller fd, and socket bindings (a recording decorator or the real bindings). */
    def forBackend(backend: PollerBackend, pollerFd: Int, sockets: SocketBindings): PollerIoDriver =
        new PollerIoDriver(backend, pollerFd, sockets)

    /** Build an [[IoUringDriver]] over caller-supplied bindings and a ring buffer, bypassing io_uring_queue_init, loading the real socket
      * bindings for the connection-close fd shutdown/close. The bindings are a recording decorator over a real ring or a single-result injector
      * over one (a real ring is still initialized by the caller).
      */
    def forBindings(uring: IoUringBindings, ring: Buffer[Byte])(using AllowUnsafe): IoUringDriver =
        new IoUringDriver(uring, ring, Ffi.load[SocketBindings])

    /** As [[forBindings]] but over caller-supplied socket bindings (a recording decorator or the real bindings), so a test can observe the
      * connection-close fd shutdown/close.
      */
    def forBindings(uring: IoUringBindings, ring: Buffer[Byte], sockets: SocketBindings): IoUringDriver =
        new IoUringDriver(uring, ring, sockets)

end TestDrivers
