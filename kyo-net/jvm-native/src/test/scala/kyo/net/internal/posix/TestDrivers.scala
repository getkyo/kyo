package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Test-tree construction helpers for the posix drivers. They wrap the drivers' package-private dependency constructors so a test can build a
  * driver over a chosen backend or bindings (a recording decorator over a real component, or the real bindings) without any production test
  * factory. Production builds drivers via the public `init(...)`; these helpers live entirely in the test tree.
  */
object TestDrivers:

    /** Build a [[PollerIoDriver]] over a caller-supplied backend and poller fd, loading the real socket bindings. */
    def forBackend(backend: PollerBackend, pollerFd: Int)(using AllowUnsafe): PollerIoDriver =
        PollerIoDriver.init(backend, pollerFd, Ffi.load[SocketBindings])

    /** Build a [[PollerIoDriver]] over a caller-supplied backend, poller fd, and socket bindings (a recording decorator or the real bindings). */
    def forBackend(backend: PollerBackend, pollerFd: Int, sockets: SocketBindings)(using AllowUnsafe): PollerIoDriver =
        PollerIoDriver.init(backend, pollerFd, sockets)

    /** Build an [[IoUringDriver]] over caller-supplied bindings and a ring buffer, bypassing io_uring_queue_init, loading the real socket
      * bindings for the connection-close fd shutdown/close. The bindings are a recording decorator over a real ring or a single-result injector
      * over one (a real ring is still initialized by the caller).
      */
    def forBindings(uring: IoUringBindings, ring: Buffer[Byte])(using AllowUnsafe): IoUringDriver =
        IoUringDriver.init(uring, ring, Ffi.load[SocketBindings])

    /** As [[forBindings]] but over caller-supplied socket bindings (a recording decorator or the real bindings), so a test can observe the
      * connection-close fd shutdown/close.
      */
    def forBindings(uring: IoUringBindings, ring: Buffer[Byte], sockets: SocketBindings)(using AllowUnsafe): IoUringDriver =
        IoUringDriver.init(uring, ring, sockets)

end TestDrivers

/** Poll or reap cycles of a posix driver, each observed through a real event: an always-writable loopback socket whose write readiness the
  * cycle delivers. Driver state is re-checked after each cycle, so a wait needs no clock and a state that never arrives hangs to the leaf cap.
  * Arming the fence registers interest and wakes the loop, and on io_uring its readiness is a reaped CQE, so a leaf asserting a wakeup or a
  * reap count of its own cannot use it.
  */
final class DriverCycles private (driver: kyo.net.internal.transport.IoDriver[PosixHandle], handle: PosixHandle, peer: Int)(using
    AllowUnsafe
):

    def next(using Frame): Unit < Async =
        val p = Promise.Unsafe.init[Unit, Abort[Closed | kyo.net.NetException]]()
        driver.awaitWritable(handle, p)
        p.safe.getResult.unit
    end next

    def until(cond: => Boolean)(using Frame): Unit < Async =
        Loop(())(_ => if cond then Loop.done(()) else next.andThen(Loop.continue(())))

    def close()(using Frame): Unit =
        driver.closeHandle(handle)
        discard(Ffi.load[SocketBindings].close(peer))
    end close
end DriverCycles

object DriverCycles:
    def init(driver: kyo.net.internal.transport.IoDriver[PosixHandle])(using Frame, AllowUnsafe): DriverCycles < Async =
        PosixTestSockets.loopbackPair().map { (client, peer) =>
            new DriverCycles(driver, PosixHandle.socket(client, 64, Absent, Frame.internal), peer)
        }
end DriverCycles
