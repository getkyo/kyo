package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** The poller's fd-keyed tables (`activeFds`, `pendingReads`, `pendingWritables`, `pendingAccepts`) are PRIMITIVE open-addressing
  * maps (int key, no `Integer` boxing) that are NOT thread-safe, made safe by routing EVERY mutation through the change queue so it is applied ONLY
  * on the poll-loop carrier (single-writer confinement).
  *
  * The CONFINEMENT is the property under test: with many fibers concurrently arming reads, cancelling, and re-arming on many fds while the poll
  * loop is delivering readiness, a non-thread-safe map that was mutated off the poll fiber would corrupt its probe chains (a lost entry hangs a
  * read; a crossed entry misroutes bytes). This leaf drives that concurrency on a REAL [[PollerIoDriver]] over real loopback pairs and asserts
  * every read completes with the CORRECT bytes: correctness under concurrency is the observable witness that the maps are written by one carrier.
  * The PRIMITIVE / no-box property of the map types themselves is asserted directly in [[kyo.net.internal.util.IntMapTest]] (the unit tests on
  * `IntLongMap` / `IntRefMap`); the de-boxing witness for the `ChunkConsumer` SAM is [[TlsEngineIoChunkConsumerTest]].
  */
class PollerIoDriverFdMapTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver fd-map confinement" - {

        "concurrent register + deregister across many fds keeps every read correct (single-writer confinement)" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                val n = 12
                // Build N loopback pairs; each connection carries a DISTINCT payload byte so a misrouted read (a crossed map entry) is detected by
                // value.
                def buildPairs(acc: List[(Int, Int)], remaining: Int): List[(Int, Int)] < (Abort[Closed] & Async) =
                    if remaining == 0 then acc
                    else PosixTestSockets.loopbackPair().map(p => buildPairs(p :: acc, remaining - 1))
                buildPairs(Nil, n).map { pairs =>
                    val conns = pairs.zipWithIndex.map { case ((client, accepted), i) =>
                        (
                            PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent),
                            PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent),
                            client,
                            accepted,
                            (i + 1).toByte
                        )
                    }
                    // Run N independent connection lifecycles CONCURRENTLY (Async.foreach across N fibers): each writes its distinct payload, arms a
                    // read, reads it back, then closes the handle. Concurrently across N fibers this drives many activeFds+pendingReads PUTs (each
                    // awaitRead) AND many removals (each closeHandle's OpDeregister) through the change queue at once -- the concurrent
                    // register+deregister the single-writer confinement must serialize on the poll fiber. A non-confined non-thread-safe
                    // map would corrupt a probe chain under this concurrency (a lost entry hangs a read; a crossed entry misroutes bytes), so every
                    // read completing with ITS OWN payload is the observable witness of confinement.
                    Async.foreach(conns) { case (clientH, acceptedH, client, accepted, payload) =>
                        val w = driver.write(clientH, Span.fromUnsafe(Array[Byte](payload)), 0)
                        assert(w == WriteResult.Done, s"write result=$w")
                        val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, p)
                        p.safe.get.map {
                            case ReadOutcome.Bytes(got) =>
                                assert(got.size == 1, s"expected one byte, got ${got.size}")
                                assert(got.toArray.head == payload, s"misrouted read: ${got.toArray.head} != $payload (map corruption?)")
                                // Close the handle here (concurrently with the other fibers' reads + closes): exercises OpDeregister map removal under
                                // concurrency, the deregister half of the confinement.
                                driver.closeHandle(acceptedH)
                                discard(sock.close(client).poll())
                                payload
                            case other =>
                                fail(s"expected ReadOutcome.Bytes, got $other")
                                payload
                        }
                    }.map { delivered =>
                        // Every distinct payload was delivered exactly once: the maps stayed correct under concurrent register + deregister.
                        assert(delivered.sorted == conns.map(_._5).sorted, s"all $n reads must deliver their own payload: $delivered")
                    }
                }
            }.map(_ => succeed)
        }
    }
end PollerIoDriverFdMapTest
