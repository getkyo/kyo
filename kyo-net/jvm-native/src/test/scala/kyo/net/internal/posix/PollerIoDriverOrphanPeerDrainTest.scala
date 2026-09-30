package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** A peer that closes with bytes still queued behind the reader's zero receive window becomes an orphan: no fd, its FIN held behind the
  * unsent tail. The reader must still see every byte and then EOF, because each window update releases more of the tail as a fresh arrival,
  * and each arrival is an edge the driver must not lose, whether or not a read is pending when it lands. A lost edge strands the tail in
  * the orphan and the reader stays open with its read armed, which hangs the leaf to its cap.
  */
class PollerIoDriverOrphanPeerDrainTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def patternByte(i: Long): Byte = (i % 251).toByte

    /** Fills the writer until the kernel refuses more (the reader's window is zero and the writer's send buffer is full), returning the
      * number of bytes accepted. Every byte carries its stream offset so the reader can check order.
      */
    private def fill(fd: Int): Long =
        val chunk = 8192
        val buf   = Buffer.alloc[Byte](chunk)
        try
            var total = 0L
            var full  = false
            while !full do
                var i = 0
                while i < chunk do
                    buf.set(i, patternByte(total + i))
                    i += 1
                val n = sock.sendNow(fd, buf, chunk.toLong, 0)
                if n.value > 0 then total += n.value
                else full = true
            end while
            total
        finally buf.close()
        end try
    end fill

    private def drain(driver: PollerIoDriver, handle: PosixHandle, between: => Unit < Async)(using
        Frame,
        kyo.test.AssertScope
    ): (Long, Boolean) < (Async & Abort[Closed]) =
        Loop((0L, true)) { case (got, ordered) =>
            val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, p)
            p.safe.get.map {
                case ReadOutcome.Bytes(s) if s.nonEmpty =>
                    val arr = s.toArray
                    var ok  = ordered
                    var i   = 0
                    while i < arr.length do
                        if arr(i) != patternByte(got + i) then ok = false
                        i += 1
                    between.andThen(Loop.continue((got + arr.length, ok)))
                case ReadOutcome.PeerFin | ReadOutcome.Bytes(_) => Loop.done((got, ordered))
                case other                                      => fail(s"expected bytes then EOF, got $other")
            }
        }

    private def scenario(pauseUntilArrival: Boolean)(using Frame, kyo.test.AssertScope) =
        PosixTestSockets.assumePoller()
        val driver = PollerIoDriver.init()
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close())) {
            DriverCycles.init(driver).map { cycles =>
                Sync.ensure(Sync.defer(cycles.close())) {
                    PosixTestSockets.smallBufferedPair(sndBuf = 16384, rcvBuf = 4096).map { case (writer, reader) =>
                        val sent = fill(writer)
                        // The writer closes with its send buffer still full: an orphan that owes the reader its tail and its FIN.
                        discard(sock.close(writer))
                        // Grown only now, so the first read's window update releases the whole tail and its FIN at once: the FIN is then
                        // the orphan's last edge on any kernel's buffer sizing. A window smaller than the tail would hold the FIN back until
                        // a read the paused leaf never issues.
                        PosixTestSockets.setIntSockOpt(reader, PosixConstants.SO_RCVBUF, 1 << 20)
                        // Larger than everything the small-buffered writer can have queued, so every read is partial and leaves no residual
                        // for readMightHaveMore to chase: each later arrival reaches the reader only as an edge.
                        val handle = PosixHandle.socket(reader, 65536, Absent, Frame.internal)
                        // Paused: the first read opens the window for the whole tail, and the reader re-arms only once the driver has taken
                        // the tail's FIN edge with no read pending, so every edge the orphan will ever produce has already been dropped.
                        val between: Unit < Async =
                            if pauseUntilArrival then cycles.until(driver.isPeerClosed(handle))
                            else Kyo.unit
                        Abort.run[Closed](drain(driver, handle, between)).map { outcome =>
                            driver.closeHandle(handle)
                            discard(sock.close(reader))
                            outcome match
                                case Result.Success((got, ordered)) =>
                                    assert(got == sent, s"the reader saw $got of the $sent bytes the orphaned peer owed before EOF")
                                    assert(ordered, "the drained bytes arrived out of order")
                                case other => fail(s"drain failed: $other")
                            end match
                        }
                    }
                }
            }
        }
    end scenario

    "a reader that re-arms at once drains an orphaned peer's tail and then sees EOF" in scenario(pauseUntilArrival = false)

    "a reader whose next arrival lands while no read is pending still drains the orphaned peer's tail and sees EOF" in
        scenario(pauseUntilArrival = true)

end PollerIoDriverOrphanPeerDrainTest
