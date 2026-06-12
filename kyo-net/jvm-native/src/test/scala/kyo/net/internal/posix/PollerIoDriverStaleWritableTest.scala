package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Reproduction guard for the stale-fd guard gap on the WRITABLE/connect dispatch path of [[PollerIoDriver]].
  *
  * The read path drops a readiness event for a recycled fd via the monotonic-id equality check in `dispatchRead`
  * (PollerIoDriver.scala:671-675: `currentId != Present(handle.id)` -> Closed) and the accept path does the same in `dispatchAccept`
  * (:238-244). The writable path does NOT: `pendingWritables` (:65) stores a bare `Promise` with no handle and no id, so
  * `dispatchWritable` (:842-851) can only guard with a presence check, `if !activeFds.containsKey(fd)` (:845). When a fd is recycled into a
  * new owner, `awaitRead` / `awaitWritable` overwrite `activeFds[fd]` to the new owner's id (:196, :206) but leave the OLD writable promise
  * in `pendingWritables[fd]`. A writable readiness event the kernel queued for the dead owner is then delivered to the OLD promise as
  * `Success`, because `activeFds.containsKey(fd)` is true (the new id is present). The event the kernel produced for the prior connection is
  * mis-delivered to the live one.
  *
  * This is the inverted twin of the shipped read-path regression "stale event on a recycled fd is dropped via the activeFds id guard"
  * (PollerIoDriverTest.scala:253), built on the same real loopback + real epoll/kqueue infrastructure. It is deterministic: the id overwrite
  * happens synchronously (on the test fiber) before the poll loop dispatches the writable, and the freshly-connected accepted fd is writable
  * the instant it is armed, so the writable event fires without any timing dependency.
  */
class PollerIoDriverStaleWritableTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Start a fresh driver, run `body`, then close it. The poll loop runs on the driver's own fiber for the duration. */
    private def withDriver[A](body: PollerIoDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    "PollerIoDriver stale writable on a recycled fd" - {
        "a writable event for a no-longer-current handle id is dropped, not delivered as Success (8c)" in {
            assumePoller()
            withDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    // Two handles over the SAME accepted fd with distinct ids: the OLD writable owner, then a NEW owner of the fd's id slot.
                    // The accepted side of a connected loopback pair is immediately writable, so the writable readiness event fires
                    // deterministically once awaitWritable arms it.
                    val oldHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val newHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    assert(oldHandle.id != newHandle.id, "handles must have distinct ids")

                    // Arm the OLD writable: pendingWritables[fd] = oldWritable, activeFds[fd] = oldId.
                    val oldWritable = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(oldHandle, oldWritable)

                    // Recycle the fd into newHandle via awaitRead: awaitRead rewrites activeFds[fd] to newId (socket handles have
                    // readFd == writeFd, so the read registration overwrites the same activeFds slot the writable used) WITHOUT touching
                    // pendingWritables[fd], which still holds oldWritable. The OLD owner's id is now gone from activeFds.
                    val newRead = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                    driver.awaitRead(newHandle, newRead)

                    // The accepted fd is writable, so the poll loop fires the writable registration and dispatchWritable(fd) runs. The CORRECT
                    // behavior is to drop the OLD writable as stale (its handle id is no longer current), exactly as dispatchRead drops a stale
                    // read. The buggy presence-only check completes it Success because activeFds.containsKey(fd) is true (newId present).
                    Abort.run[Timeout | Closed](Async.timeout(10.seconds)(oldWritable.safe.get)).map { result =>
                        driver.closeHandle(newHandle)
                        discard(sock.close(client))
                        // A stale writable for a recycled fd must resolve Closed (stale-dropped), never Success delivered to the wrong owner.
                        result match
                            case Result.Failure(_: Closed) => succeed
                            case Result.Success(()) =>
                                fail(
                                    "stale writable delivered Success to a recycled fd's prior owner: dispatchWritable has no monotonic-id " +
                                        "guard (PollerIoDriver.scala:845 uses activeFds.containsKey, not the id equality the read path uses)"
                                )
                            case Result.Failure(_: Timeout) =>
                                fail("writable event never fired: the accepted side of a loopback pair should be immediately writable")
                            case other => fail(s"expected stale writable dropped as Closed, got $other")
                        end match
                    }
                }
            }
        }
    }

end PollerIoDriverStaleWritableTest
