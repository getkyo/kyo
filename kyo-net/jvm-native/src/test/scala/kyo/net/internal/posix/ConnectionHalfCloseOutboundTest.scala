package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.Connection as InternalConnection

// This suite lives in jvm-native/src/test because the Connection pumps run over PosixTransport's PollerIoDriver on JVM-posix and Native;
// JS uses the Node transport.

/** Regression guard for outbound byte-loss when the peer half-closes (`shutdown(SHUT_WR)` -> local read EOF).
  *
  * TCP half-close is the standard request/response idiom: a peer does `shutdown(SHUT_WR)` to signal it is done sending (the local read returns
  * 0) while the connection's other direction stays open, so the local side must still finish sending its response and the peer keeps READING
  * until the local side closes. `PosixTestSockets.halfClose` documents SHUT_WR as "signaling EOF to the peer without closing the connection".
  *
  * kyo-net's [[kyo.net.internal.transport.ReadPump]] treats a 0-byte read as EOF and calls `teardown()`, which runs the connection's `closeFn`.
  * `closeFn` (`Connection.scala`) drains the OUTBOUND channel with `closeAwaitEmpty()` (mirroring the ReadPump's careful inbound drain): the
  * [[kyo.net.internal.transport.WritePump]] keeps taking the already-queued spans until the channel is empty, then takes `Closed` from the
  * closing channel, runs its own teardown, and re-enters `closeFn`, which only then closes the fd. A bare `outbound.close()` here would discard
  * any bytes the application queued but the WritePump had not yet flushed; this leaf locks the drain in.
  *
  * Deterministic scenario over a real loopback pair and a real [[PollerIoDriver]]:
  *   1. `smallBufferedPair` shrinks both kernel buffers so a large write genuinely fills and parks the WritePump in `awaitWritable` (a real
  *      EAGAIN, no scripting). The WritePump (parked) is then NOT taking from the channel.
  *   2. While the WritePump is parked, a distinct `tail` payload (0xAB) is queued on `outbound`; with no taker it sits in the channel queue.
  *   3. The peer does `halfClose` (SHUT_WR), so the local ReadPump observes EOF and tears down.
  *   4. The peer KEEPS READING (an event-driven `awaitRead` drain through the driver, exactly what a real half-closing peer does). Each read
  *      frees kernel-buffer space, the parked WritePump resumes, flushes the partial filler and then the queued tail, and the teardown closes
  *      the fd, which terminates the peer drain with EOF.
  *   5. The drained bytes must END with every tail byte: queued outbound bytes were flushed in order, never dropped.
  *
  * The concurrent peer drain is load-bearing, not a convenience: with both kernel buffers at 2048 bytes the parked WritePump can only make
  * progress when the peer reads, so a peer that waited for the fd to close before draining would deadlock the scenario (the teardown waits on
  * the flush, the flush waits on the peer reading). macOS masks this with its large effective socket buffers; Linux does not.
  *
  * Anti-flakiness: no sleep. The WritePump's park is latched via the `RecordingIoDriver.onAwaitWritable` hook (fires after the real writable
  * registration); the teardown is latched via the `onCloseHandle` hook (fires when `closeFn` runs `driver.closeHandle`); the peer drain is a
  * real-event `awaitRead` loop bounded by the teardown's EOF. Every wait is a real-event latch.
  */
class ConnectionHalfCloseOutboundTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("Connection pumps over PollerIoDriver need epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Drain `peerH` through the driver until EOF (an empty span), returning every byte read in arrival order. Each `awaitRead` is a
      * real-event latch completing when the kernel delivers bytes (or the peer's fd closes); the loop terminates on the EOF the local
      * teardown produces by closing the connection's fd, so it is bounded by a real terminal condition (no sleep, no spin).
      */
    private def drainPeer(driver: PollerIoDriver, peerH: PosixHandle)(using Frame): Array[Byte] < (Abort[Closed] & Async) =
        Loop(Array.emptyByteArray) { acc =>
            val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
            driver.awaitRead(peerH, p)
            p.safe.get.map { chunk =>
                if chunk.isEmpty then Loop.done(acc)
                else Loop.continue(acc ++ chunk.toArray)
            }
        }

    "Connection half-close" - {

        "queued outbound bytes are flushed (not dropped) when the peer half-closes (SHUT_WR -> read EOF)" in {
            assumePoller()
            val driver = PollerIoDriver.init(transportConfig)
            val spy    = new RecordingIoDriver(driver)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                // Shrink both kernel buffers so the first large write fills and parks the WritePump in awaitWritable (a real EAGAIN).
                PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (clientFd, peerFd) =>
                    val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                    // Build connection A over the recording driver; capacity is large enough to hold the queued tail behind the parked write.
                    val conn = InternalConnection.init(handle, spy, transportConfig.channelCapacity)

                    // Latch fired when the WritePump arms awaitWritable (i.e. the first write went Partial and the pump is parked, not taking).
                    val writePumpParked = Promise.Unsafe.init[Unit, Any]()
                    spy.onAwaitWritable = _ => writePumpParked.completeDiscard(Result.succeed(()))

                    // Latch fired when closeFn runs driver.closeHandle (the EOF teardown completed).
                    val tornDown = Promise.Unsafe.init[Unit, Any]()
                    spy.onCloseHandle = () => tornDown.completeDiscard(Result.succeed(()))

                    conn.start()

                    // Filler (0x11) far larger than the shrunk buffers, so the WritePump's first write returns Partial and parks. Tail (0xAB)
                    // is a disjoint byte value so its presence in the peer's drained bytes is unambiguous.
                    val filler = Span.fromUnsafe(Array.fill[Byte](256 * 1024)(0x11.toByte))
                    val tail   = Span.fromUnsafe(Array.fill[Byte](64)(0xab.toByte))

                    conn.outbound.offer(filler) match
                        case Result.Success(true) => ()
                        case other                => fail(s"filler offer should be accepted, got $other")

                    writePumpParked.safe.get.map { _ =>
                        // The WritePump is parked awaiting writable (peer has not read yet). Queue the tail: with no taker it sits in the
                        // outbound channel queue, which is exactly what an abrupt outbound.close() on EOF teardown would drop.
                        conn.outbound.offer(tail) match
                            case Result.Success(true) => ()
                            case other                => fail(s"tail offer should be accepted into the channel queue, got $other")

                        // Peer half-closes its write side: the local ReadPump observes EOF and tears down. The peer then keeps reading (the
                        // drain below), so the parked WritePump resumes as kernel-buffer space frees, flushes filler then tail, and the
                        // teardown's fd close EOF-terminates the drain.
                        PosixTestSockets.halfClose(sock, peerFd)

                        val peerH = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                        Abort.run[Closed](drainPeer(driver, peerH)).map { drainResult =>
                            tornDown.safe.get.map { _ =>
                                driver.closeHandle(peerH)
                                val drained = drainResult match
                                    case Result.Success(bytes) => bytes
                                    case other                 => fail(s"peer drain must end with the teardown's EOF, got $other")
                                val tailMarker = drained.count(_ == 0xab.toByte)
                                assert(
                                    tailMarker == tail.size,
                                    s"half-close dropped queued outbound bytes: peer received $tailMarker of ${tail.size} tail (0xAB) bytes " +
                                        s"(total drained=${drained.length}). closeFn must drain outbound (closeAwaitEmpty) on a peer " +
                                        "half-close so bytes queued behind the parked WritePump are flushed, not discarded."
                                )
                                assert(
                                    drained.takeRight(tail.size).forall(_ == 0xab.toByte),
                                    "the tail must be flushed AFTER the filler, in submission order"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

end ConnectionHalfCloseOutboundTest
