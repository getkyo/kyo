package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Regression guard for the stale-fd monotonic-id equality check on the WRITABLE dispatch path of [[PollerIoDriver]].
  *
  * The read path drops a readiness event for a recycled fd via the monotonic-id equality check in `dispatchRead`, and the accept path does
  * the same in `dispatchAccept`. The writable path uses the same guard via `isStaleId` with the id stored in a [[PollerIoDriver.PendingWritable]].
  *
  * The stale condition requires both a Write registration (sets activeFds[fd] = oldId, pendingWritables[fd] = (oldWritable, oldId)) and a
  * Read registration (overwrites activeFds[fd] = newId) to be processed BEFORE the writable event fires. With a running poll loop, both
  * registrations must land in the same drainChanges cycle, BEFORE the poll that returns the writable event. This is guaranteed by submitting
  * both registrations before starting the poll loop: the loop's first drainChanges processes them together.
  */
class PollerIoDriverStaleWritableTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver stale writable on a recycled fd" - {
        "a writable event for a no-longer-current handle id is dropped, not delivered as Success" in {
            assumePoller()
            // Create the driver but do NOT start the poll loop yet: both registrations are submitted before start() so the first
            // drainChanges call processes them together, in the correct order, before any poll returns a writable event.
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val driver   = TestDrivers.forBackend(real, pollerFd)
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    // Two handles over the SAME accepted fd with distinct ids: the OLD writable owner and the NEW reader.
                    val oldHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val newHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    assert(oldHandle.id.packed != newHandle.id.packed, "handles must have distinct ids")

                    // Submit Write registration for oldHandle: when the poll loop starts and drainChanges runs, it sets
                    // activeFds[fd] = oldId and pendingWritables[fd] = (oldWritable, oldId).
                    val oldWritable = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(oldHandle, oldWritable)

                    // Submit Read registration for newHandle: drainChanges processes this next in the SAME cycle (both are in the
                    // changeQueue from the test fiber), overwriting activeFds[fd] = newId (oldId is gone).
                    val newRead = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(newHandle, newRead)

                    // Start the poll loop. Its first drainChanges drains both commands above together, then backend.poll returns the
                    // writable event for the accepted fd (immediately writable). dispatchWritable runs isStaleId(fd, oldId):
                    // activeFds[fd] = newId != oldId -> stale -> Closed. Without the monotonic-id guard, a presence-only
                    // activeFds.containsKey check would find newId present and deliver Success to the wrong (prior) owner.
                    discard(driver.start())

                    Abort.run[Timeout | Closed](Async.timeout(10.seconds)(oldWritable.safe.get)).map { result =>
                        driver.closeHandle(newHandle)
                        discard(sock.close(client))
                        // A stale writable for a recycled fd must resolve Closed (stale-dropped), never Success.
                        result match
                            case Result.Failure(_: Closed) => succeed
                            case Result.Success(()) =>
                                fail(
                                    "stale writable delivered Success to the prior owner: dispatchWritable stale-id guard failed"
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
