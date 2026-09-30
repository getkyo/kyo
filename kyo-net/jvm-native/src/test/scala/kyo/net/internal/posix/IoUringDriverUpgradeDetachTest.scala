package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first coverage for the STARTTLS detach-vs-read strand on io_uring: the single-recv gate in [[IoUringDriver.awaitRead]] rejects a
  * stray plaintext-pump re-arm while `upgradeActive` is set, and the rejection must still fail the promise. Dropping it silently strands the
  * retiring pump forever: the pump tears down by OBSERVING its read promise fail, the dropped promise reaches neither the pending-op table
  * nor the handle, and every later sweep (`cancel`'s pending scan, close) has nothing to find. The upgrade order (PosixTransport.upgradeToTls)
  * is `upgradeActive`/`upgrading` set, then detachForUpgrade (the default driver `cancel`), then `isUpgraded` set; the three leaves pin a
  * stray arm landing before the sweep, after the durable marker, and in the gap between them (where only the handshake's first producer arm
  * can observe it). The poller twin is PollerIoDriverUpgradeDetachTest.
  */
class IoUringDriverUpgradeDetachTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    private def strandScenario(name: String)(arrange: (IoUringDriver, PosixHandle, Promise.Unsafe[ReadOutcome, Abort[Closed]]) => Unit)(
        using
        Frame,
        kyo.test.AssertScope
    ): Unit < Async =
        PosixTestSockets.assumeUring()
        val driver = IoUringDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close())) {
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                Sync.ensure(Sync.defer {
                    driver.closeHandle(handle)
                    discard(sock.close(client).poll())
                }) {
                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    arrange(driver, handle, p)
                    // A stranded stray is a promise nothing completes, which hangs the leaf here.
                    Abort.run[Closed](p.safe.get).map {
                        case Result.Failure(_) => succeed
                        case other             => assert(false, s"$name: unexpected outcome $other")
                    }
                }
            }
        }
    end strandScenario

    "a read armed during the upgrade window is failed, not stranded" in {
        strandScenario("in-window stray, sweep still to come") { (driver, handle, p) =>
            // The stray lands between the flag writes and the detach sweep: the deposit must be visible to the sweep.
            handle.upgradeActive = true
            handle.upgrading = true
            driver.awaitRead(handle, p)
            driver.cancel(handle)
            handle.isUpgraded = true
        }
    }

    "a read armed after the upgrade sweep and marker is failed, not stranded" in {
        strandScenario("post-marker stray") { (driver, handle, p) =>
            // The stray lands strictly after the whole detach: only the arm's own re-check can see the durable marker.
            handle.upgradeActive = true
            handle.upgrading = true
            driver.cancel(handle)
            handle.isUpgraded = true
            driver.awaitRead(handle, p)
        }
    }

    "a stray deposit in the sweep-to-marker gap is failed by the first producer arm" in {
        strandScenario("gap stray") { (driver, handle, p) =>
            // The stray lands after the sweep but before the isUpgraded write: neither the sweep nor the self-re-check can see it, so the
            // handshake's first producer arm (which every handshake issues) must fail the occupant it replaces.
            handle.upgradeActive = true
            handle.upgrading = true
            driver.cancel(handle)
            driver.awaitRead(handle, p)
            handle.isUpgraded = true
            driver.armUpgradeProducerRead(handle)
        }
    }

end IoUringDriverUpgradeDetachTest
