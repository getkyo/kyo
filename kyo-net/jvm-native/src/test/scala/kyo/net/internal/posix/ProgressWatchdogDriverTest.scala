package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.ProgressWatchdog
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** The watchdog over a real [[PollerIoDriver]]: a driver whose poll loop is parked is caught, the same driver left running is not.
  *
  * The wedge is a [[RecordingPollerBackend]] one-shot pre-poll hold: the first poll cycle parks on a test-controlled pending fiber, so the
  * driver never drains its change queue and a read with data waiting on the socket never completes. Both leaves run the watch under
  * `Clock.withTimeControl`, so the verdict depends on the driver's progress alone.
  */
class ProgressWatchdogDriverTest extends Test:

    import AllowUnsafe.embrace.danger

    private def watchRead(
        readPromise: Promise.Unsafe[ReadOutcome, Abort[Closed]]
    )(using Frame): Result[NetException | Closed | ProgressWatchdog.Stalled, ReadOutcome] < Async =
        Clock.withTimeControl { tc =>
            Fiber.initUnscoped(Abort.run[NetException | Closed | ProgressWatchdog.Stalled](
                ProgressWatchdog.run(20.seconds, 3)(progress => readPromise.safe.get.map(outcome => progress.tick.andThen(outcome)))
            )).map { fiber =>
                // The clock is driven only while the watch runs, and stops the moment it ends: a watch that ends interrupts its own
                // pending sleeper, so a fence still waiting for one would wait forever.
                Async.raceFirst(fiber.get, Loop.forever(tc.awaitPendingSleepers(1).andThen(tc.advance(20.seconds))))
            }
        }

    private def withDriver[A](hold: Maybe[Promise.Unsafe[Int, Any]])(
        body: (PollerIoDriver, Promise.Unsafe[ReadOutcome, Abort[Closed]]) => A < (Async & Abort[Throwable])
    )(using Frame, kyo.test.AssertScope): A < (Async & Abort[Throwable]) =
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val entered  = Promise.Unsafe.init[Unit, Any]()
            backend.setPrePollLatch(entered)
            hold.foreach(backend.setPrePollHold)
            val driver = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            entered.safe.get.andThen {
                val handle      = PosixHandle.socket(acceptedFd, PosixTestSockets.ReadBufferSize, Absent, Frame.internal)
                val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, readPromise)
                assert(spy.sendNow(clientFd, Buffer.fromArray(Array[Byte](7)), 1, 0).value == 1)
                Sync.ensure {
                    hold.foreach(_.completeDiscard(Result.succeed(0)))
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                }(body(driver, readPromise))
            }
        }

    "a driver whose poll loop is wedged with a read outstanding is failed as stalled, with the driver in the diagnostics" in {
        PosixTestSockets.assumePoller()
        withDriver(Present(Promise.Unsafe.init[Int, Any]())) { (driver, readPromise) =>
            watchRead(readPromise).map {
                case Result.Failure(stalled: ProgressWatchdog.Stalled) =>
                    val name    = s"PollerIoDriver@${java.lang.System.identityHashCode(driver)}"
                    val section = stalled.diagnostics.split("=== ").find(_.startsWith(s"$name ==="))
                    assert(
                        section.exists(_.contains("changeQueuePending=true")),
                        s"the wedged driver's state is missing: ${stalled.diagnostics}"
                    )
                case other => fail(s"a wedged driver was not caught: $other")
            }
        }
    }

    "the same driver with its poll loop running completes the read" in {
        PosixTestSockets.assumePoller()
        withDriver(Absent) { (_, readPromise) =>
            watchRead(readPromise).map {
                case Result.Success(ReadOutcome.Bytes(span)) => assert(span.toArray.toSeq == Seq(7.toByte))
                case other                                   => fail(s"a running driver did not complete the read: $other")
            }
        }
    }

end ProgressWatchdogDriverTest
