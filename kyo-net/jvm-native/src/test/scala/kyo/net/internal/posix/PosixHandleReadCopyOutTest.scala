package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Read copy-out correctness. The read path delivers the bytes read off the socket to the consumer; the property under test is
  * the absence of aliasing: the delivered bytes are IDENTICAL to what the peer sent (same bytes, length, order) AND a later read does NOT
  * corrupt an earlier read's already-delivered bytes (no aliasing of the reused recv buffer).
  *
  * The reused `readBuffer` is recv'd into on every read, so back-to-back reads exercise whether the previous read's delivered bytes survive the
  * next recv into the same buffer. This leaf drives real reads through a real [[PollerIoDriver]] over a loopback pair and retains each delivered
  * span across the following read to prove no aliasing corruption.
  */
class PosixHandleReadCopyOutTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    private def readVia(driver: PollerIoDriver, handle: PosixHandle)(using
        Frame,
        kyo.test.AssertScope
    ): Span[Byte] < (Abort[Closed] & Async) =
        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        driver.awaitRead(handle, promise)
        promise.safe.get.map {
            case ReadOutcome.Bytes(span) => span
            case other                   => fail(s"expected ReadOutcome.Bytes but got $other")
        }
    end readVia

    /** Send `bytes` from the client side as one write (the loopback buffer holds it). */
    private def send(driver: PollerIoDriver, client: Int, bytes: Array[Byte])(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Abort[Closed] & Async) =
        val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
        val w       = driver.write(clientH, Span.fromUnsafe(bytes), 0)
        assert(w == WriteResult.Done, s"write result=$w")
        ()
    end send

    "PosixHandle read copy-out" - {

        "delivered bytes equal the payload byte-for-byte" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](300)(i => (i & 0xff).toByte)
                    send(driver, client, payload).andThen(readVia(driver, acceptedH)).map { got =>
                        driver.closeHandle(acceptedH)
                        discard(sock.close(client).poll())
                        assert(got.toArray.toList == payload.toList, "delivered bytes must equal the payload exactly")
                    }
                }
            }.map(_ => succeed)
        }

        "back-to-back reads: a second read does not corrupt the first's already-delivered bytes (no aliasing)" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val first     = Array.tabulate[Byte](200)(i => (i & 0xff).toByte)
                    val second    = Array.tabulate[Byte](200)(i => ((i + 128) & 0xff).toByte)
                    // Read the first chunk and RETAIN the delivered span, then read the second chunk into the (reused) buffer. If the read
                    // delivered an aliasing view of the reused buffer, the second recv would overwrite the first span; assert it did NOT.
                    send(driver, client, first).andThen(readVia(driver, acceptedH)).map { got1 =>
                        val snapshot1 = got1.toArray.toList
                        send(driver, client, second).andThen(readVia(driver, acceptedH)).map { got2 =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client).poll())
                            assert(got2.toArray.toList == second.toList, "second read must deliver the second payload")
                            // The first delivered span must still hold the first payload (not corrupted by the second recv).
                            assert(got1.toArray.toList == first.toList, "first read's bytes must survive the second read (no aliasing)")
                            assert(snapshot1 == first.toList)
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "bytes are correct across a chunk boundary (a payload split over two reads)" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val partA     = Array.tabulate[Byte](100)(i => (i & 0xff).toByte)
                    val partB     = Array.tabulate[Byte](100)(i => ((i + 50) & 0xff).toByte)
                    // Two distinct sends; drain both and concatenate. The concatenation must equal partA ++ partB in order.
                    def drainAll(acc: List[Byte], remaining: Int): List[Byte] < (Abort[Closed] & Async) =
                        if remaining <= 0 then acc
                        else
                            readVia(driver, acceptedH).map { got =>
                                drainAll(acc ++ got.toArray.toList, remaining - got.size)
                            }
                    send(
                        driver,
                        client,
                        partA
                    ).andThen(send(driver, client, partB)).andThen(drainAll(Nil, partA.length + partB.length)).map {
                        all =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client).poll())
                            assert(all == (partA.toList ++ partB.toList), "bytes across the chunk boundary must be in order and intact")
                    }
                }
            }.map(_ => succeed)
        }
    }
end PosixHandleReadCopyOutTest
