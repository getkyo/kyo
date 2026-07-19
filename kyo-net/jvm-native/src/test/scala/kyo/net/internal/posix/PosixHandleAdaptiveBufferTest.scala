package kyo.net.internal.posix

import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Adaptive receive-buffer predictor. The per-handle predictor grows [[PosixHandle.readBuffer]] when reads keep filling it, via
  * the close-old-then-alloc-larger recipe: allocate the larger buffer, swap the field, THEN close the old one, so the field always points
  * at a live buffer and the old buffer is freed EXACTLY once.
  *
  * The ownership assertions read [[kyo.ffi.Buffer.isClosed]] on the captured old-buffer reference directly (a real off-heap buffer, no mock): a
  * grow must close the previous buffer exactly once, and a later grow must not re-close an already-closed buffer. The end-to-end leaf drives reads
  * through a real [[PollerIoDriver]] over a real loopback socket pair and asserts the bytes delivered after a grow are byte-for-byte correct.
  */
class PosixHandleAdaptiveBufferTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = kyo.ffi.Ffi.load[SocketBindings]

    "PosixHandle adaptive buffer" - {

        "grows after the configured number of consecutive full reads, doubling the buffer" in {
            val seed = 1024
            val h    = PosixHandle.socket(7, seed, Absent)
            assert(h.readBufferSize == seed)
            // The first GrowAfterFullReads-1 full reads do NOT grow (the predictor waits for a sustained pattern).
            var i = 0
            while i < PosixHandle.GrowAfterFullReads - 1 do
                assert(!h.growReadBufferForFullRead(seed), s"unexpected early grow at read $i")
                assert(h.readBufferSize == seed)
                i += 1
            end while
            // The GrowAfterFullReads-th consecutive full read triggers the grow.
            assert(h.growReadBufferForFullRead(seed), "expected a grow on the threshold read")
            assert(h.readBufferSize == seed * 2, s"buffer should have doubled, got ${h.readBufferSize}")
            assert(h.readBuffer.size == seed * 2)
            PosixHandle.close(h)
            succeed
        }

        "a non-full read resets the predictor so a settled connection never grows" in {
            val seed = 1024
            val h    = PosixHandle.socket(8, seed, Absent)
            // Three full reads, then a short read resets the streak; the buffer must not grow.
            assert(!h.growReadBufferForFullRead(seed))
            assert(!h.growReadBufferForFullRead(seed))
            assert(!h.growReadBufferForFullRead(seed))
            assert(!h.growReadBufferForFullRead(seed / 2), "a short read must not grow")
            assert(h.readBufferSize == seed)
            // The streak restarts from zero: it again takes GrowAfterFullReads full reads to grow.
            var i = 0
            while i < PosixHandle.GrowAfterFullReads - 1 do
                assert(!h.growReadBufferForFullRead(seed))
                i += 1
            assert(h.growReadBufferForFullRead(seed))
            assert(h.readBufferSize == seed * 2)
            PosixHandle.close(h)
            succeed
        }

        "the grow follows the recipe: the old buffer is closed exactly once and the new one is live" in {
            val seed = 1024
            val h    = PosixHandle.socket(9, seed, Absent)
            // Capture the seed buffer reference before the grow closes it.
            val oldBuffer = h.readBuffer
            assert(!oldBuffer.isClosed)
            var i = 0
            while i < PosixHandle.GrowAfterFullReads do
                discard(h.growReadBufferForFullRead(seed))
                i += 1
            // Recipe: the old buffer was closed, the field now points at a DIFFERENT, live, larger buffer.
            assert(oldBuffer.isClosed, "old buffer must be closed after the grow")
            val grown = h.readBuffer
            assert(!grown.isClosed, "the grown buffer must be live")
            assert(grown ne oldBuffer, "the field must point at the new buffer, not the closed old one")
            assert(grown.size == seed * 2)
            // A second grow must close the (now-old) grown buffer exactly once and must NOT re-close the already-closed seed buffer.
            i = 0
            while i < PosixHandle.GrowAfterFullReads do
                discard(h.growReadBufferForFullRead(seed * 2))
                i += 1
            assert(grown.isClosed, "the first grown buffer must be closed by the second grow")
            assert(h.readBuffer.size == seed * 4)
            // isClosed is idempotent on the seed buffer (closed once, still closed, never errored by a double close).
            assert(oldBuffer.isClosed)
            PosixHandle.close(h)
            succeed
        }

        "never grows past the MaxReadBufferSize cap" in {
            // Seed at the cap: no further grow is possible regardless of how many full reads arrive.
            val h = PosixHandle.socket(10, PosixHandle.MaxReadBufferSize, Absent)
            var i = 0
            while i < PosixHandle.GrowAfterFullReads * 3 do
                assert(!h.growReadBufferForFullRead(PosixHandle.MaxReadBufferSize), "must not grow past the cap")
                i += 1
            assert(h.readBufferSize == PosixHandle.MaxReadBufferSize)
            PosixHandle.close(h)
            succeed
        }

        "delivers correct bytes after a real grow through the driver (end to end)" in {
            assumePoller()
            // A small seed so a modest payload fills it repeatedly and triggers the grow, then assert the post-grow read is byte-identical.
            val seed   = 512
            val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, seed, Absent)
                    // Drive GrowAfterFullReads full-buffer reads to force a grow, each verified byte-for-byte, then one final post-grow read.
                    def fullPayload(tag: Int): Array[Byte] = Array.tabulate[Byte](seed)(j => ((j + tag) & 0xff).toByte)
                    def readOne(expected: Array[Byte]): Unit < (Abort[Closed] & Async) =
                        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, promise)
                        promise.safe.get.map {
                            case ReadOutcome.Bytes(got) =>
                                assert(got.toArray.toList == expected.toList, "delivered bytes must equal the payload")
                            case other =>
                                fail(s"expected ReadOutcome.Bytes but got $other")
                        }
                    end readOne
                    def sendFull(tag: Int): Unit < (Abort[Closed] & Async) =
                        val clientH = PosixHandle.socket(client, seed, Absent)
                        val w       = driver.write(clientH, Span.fromUnsafe(fullPayload(tag)), 0)
                        assert(w == WriteResult.Done, s"write result=$w")
                        ()
                    end sendFull
                    def loop(n: Int): Unit < (Abort[Closed] & Async) =
                        if n == 0 then ()
                        else sendFull(n).andThen(readOne(fullPayload(n))).andThen(loop(n - 1))
                    // Send + read GrowAfterFullReads full payloads (forces a grow), then one more after the grow.
                    loop(PosixHandle.GrowAfterFullReads).andThen {
                        // The buffer has now grown; a final exact-seed read still delivers correct bytes through the larger buffer.
                        val tag = 99
                        sendFull(tag).andThen(readOne(fullPayload(tag))).map { _ =>
                            assert(acceptedH.readBufferSize > seed, "the buffer should have grown during the run")
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client).poll())
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }
end PosixHandleAdaptiveBufferTest
