package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.WriteResult

/** Poll ready-count read as a raw int. The poll loop reads the number of ready fds as a raw `Int` off the per-driver scratch
  * (`pollScratch.readyCount`, written by the backend's `poll()`), rather than unboxing the `Fiber.Unsafe[Int, Any]` result. The SEMANTICS witness
  * is that the count drives exactly the right number of ready-fd dispatches: an off-by-one or wrong count would either strand a ready fd (its read
  * never completes) or over-read the events array.
  *
  * This leaf arms reads on several connections, makes them all readable in one poll cycle, and asserts every read completes with the correct
  * bytes: the only way all N complete is if `readyCount` correctly bounded the drain over all N ready fds.
  */
class PollerIoDriverReadyCountTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    "PollerIoDriver ready count" - {

        "readyCount drives the correct number of dispatches when many fds are ready at once" in {
            assumePoller()
            val n      = 6
            val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                // Build N loopback pairs.
                def buildPairs(acc: List[(Int, Int)], remaining: Int): List[(Int, Int)] < (Abort[Closed] & Async) =
                    if remaining == 0 then acc
                    else PosixTestSockets.loopbackPair().map(p => buildPairs(p :: acc, remaining - 1))
                buildPairs(Nil, n).map { pairs =>
                    val accepted = pairs.map { case (_, a) => PosixHandle.socket(a, PosixHandle.DefaultReadBufferSize, Absent) }
                    // Each connection gets a DISTINCT one-byte payload so a misrouted/missed dispatch is detectable by value, not just count.
                    val payloads = (0 until n).map(i => (i + 1).toByte).toList
                    // Write all N payloads from the client side first, so by the time we arm reads many fds are ready in (close to) one poll cycle.
                    pairs.zip(payloads).foreach { case ((client, _), b) =>
                        val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val w       = driver.write(clientH, Span.fromUnsafe(Array[Byte](b)), 0)
                        assert(w == WriteResult.Done, s"write result=$w")
                    }
                    // Arm a read on every accepted fd and collect all results. Every read MUST complete (the readyCount bounded the drain over all
                    // ready fds); a wrong count would leave at least one of these promises uncompleted, hanging the test (caught by the harness
                    // timeout as a failure) or deliver a wrong value.
                    def readAll(pending: List[(PosixHandle, Byte)]): List[Byte] < (Abort[Closed] & Async) =
                        pending match
                            case Nil => Nil: List[Byte]
                            case (h, exp) :: rest =>
                                val promise = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                                driver.awaitRead(h, promise)
                                promise.safe.get.map { got =>
                                    assert(got.size == 1, s"expected one byte, got ${got.size}")
                                    assert(got.toArray.head == exp, s"wrong byte for a connection: ${got.toArray.head} != $exp")
                                    readAll(rest).map(got.toArray.head :: _)
                                }
                    readAll(accepted.zip(payloads)).map { received =>
                        accepted.foreach(driver.closeHandle)
                        pairs.foreach { case (client, _) => discard(sock.close(client).poll()) }
                        // Every distinct payload was delivered exactly once: the count drove exactly N dispatches, no more, no fewer.
                        assert(received.sorted == payloads.sorted, s"all $n payloads must be delivered: $received vs $payloads")
                    }
                }
            }.map(_ => succeed)
        }

        "a single ready fd delivers exactly one read (count of 1 is exact)" in {
            assumePoller()
            val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](8)(i => (i + 1).toByte)
                    assert(driver.write(clientH, Span.fromUnsafe(payload), 0) == WriteResult.Done)
                    val promise = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                    driver.awaitRead(acceptedH, promise)
                    promise.safe.get.map { got =>
                        driver.closeHandle(acceptedH)
                        discard(sock.close(client).poll())
                        assert(got.toArray.toList == payload.toList)
                    }
                }
            }.map(_ => succeed)
        }
    }
end PollerIoDriverReadyCountTest
