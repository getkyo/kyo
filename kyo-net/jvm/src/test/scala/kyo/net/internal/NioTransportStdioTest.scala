package kyo.net.internal

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import kyo.*
import kyo.net.NetException
import kyo.net.NetStdioAlreadyOpenException
import kyo.net.Test

/** Tests the NIO floor's stdio factory ([[NioTransport.stdio]] over [[NioStdioConnection]]): the round trip through the pump fibers over
  * swapped `System.in`/`System.out` streams, the process-wide single-stdio claim CAS, and the EOF close. In-process and deterministic: the
  * connection captures the streams at open time, so a piped stdin and a pattern-watching stdout stand in for fds 0/1 with no child process.
  */
class NioTransportStdioTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Create a transport using the standard factory. Starts its own event loop driver. Caller must call close(). */
    def mkTransport()(using Frame): NioTransport =
        NioTransport.init()

    /** Returns whether `pattern` occurs as a contiguous slice of `haystack`. */
    private def containsSlice(haystack: Array[Byte], pattern: Array[Byte]): Boolean =
        (0 to haystack.length - pattern.length).exists { start =>
            var i = 0
            while i < pattern.length && haystack(start + i) == pattern(i) do i += 1
            i == pattern.length
        }

    /** OutputStream capturing every written byte; completes `seen` with a snapshot the first time the accumulated bytes contain `pattern`
      * (idempotent via the promise). All writes arrive through the single swapped `System.out` PrintStream, whose own per-write locking
      * serializes them, so the buffer needs no further coordination; the pattern check keeps the leaf robust when a concurrently running
      * suite prints through the same swapped stream.
      */
    final private class PatternOutputStream(pattern: Array[Byte], seen: Promise.Unsafe[Array[Byte], Any]) extends java.io.OutputStream:
        private val buffer = new java.io.ByteArrayOutputStream()
        override def write(b: Int): Unit =
            buffer.write(b)
            check()
        override def write(b: Array[Byte], off: Int, len: Int): Unit =
            buffer.write(b, off, len)
            check()
        private def check(): Unit =
            val snapshot = buffer.toByteArray
            if containsSlice(snapshot, pattern) then seen.completeDiscard(Result.succeed(snapshot))
    end PatternOutputStream

    "stdio round-trips bytes over swapped streams, rejects a second claim, and closes on stdin EOF" in {
        given Frame         = Frame.internal
        val inboundMessage  = "ping-via-nio-stdio-in".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val outboundMessage = "pong-via-nio-stdio-out".getBytes(java.nio.charset.StandardCharsets.UTF_8)

        // Swap the process streams BEFORE stdio(): the connection captures System.in/System.out at open time.
        val stdinWriter = new PipedOutputStream()
        val stdinPipe   = new PipedInputStream(stdinWriter)
        val stdoutSeen  = Promise.Unsafe.init[Array[Byte], Any]()
        val savedIn     = java.lang.System.in
        val savedOut    = java.lang.System.out
        java.lang.System.setIn(stdinPipe)
        java.lang.System.setOut(new PrintStream(new PatternOutputStream(outboundMessage, stdoutSeen), true))
        val transport = mkTransport()

        def restore(): Unit =
            // Unpark a read pump still blocked in stdinPipe.read (EOF closes the connection and exits the pump), then put the real
            // streams back. Runs on every exit path, so a failed assertion never leaves the process streams swapped.
            try stdinWriter.close()
            catch case _: java.io.IOException => ()
            java.lang.System.setIn(savedIn)
            java.lang.System.setOut(savedOut)
            discard(transport.close())
        end restore

        // Take from the connection's inbound channel until `target` bytes accumulated (a pipe read may split the message across reads).
        def collect(conn: kyo.net.Connection, target: Int): Array[Byte] < (Async & Abort[Closed]) =
            Loop(Array.emptyByteArray) { acc =>
                if acc.length >= target then Loop.done(acc)
                else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
            }

        Sync.ensure(Sync.defer(restore())) {
            transport.stdio().safe.get.map { conn =>
                // Inbound: bytes written to the swapped stdin come out of the connection's inbound channel.
                Sync.defer {
                    stdinWriter.write(inboundMessage)
                    stdinWriter.flush()
                }.andThen(collect(conn, inboundMessage.length)).map { received =>
                    assert(received.sameElements(inboundMessage), s"stdin round-trip mismatch: got ${received.toList}")
                    // Outbound: a span put on the connection's outbound channel is written + flushed to the swapped stdout.
                    conn.outbound.safe.put(Span.fromUnsafe(outboundMessage)).andThen {
                        stdoutSeen.safe.get.map { written =>
                            assert(containsSlice(written, outboundMessage), s"stdout round-trip mismatch: got ${written.toList}")
                            // A second stdio() while the first is open loses the claim CAS.
                            Abort.run[NetException | Closed](transport.stdio().safe.get).map { second =>
                                second match
                                    case Result.Failure(_: NetStdioAlreadyOpenException) => ()
                                    case other => fail(s"expected NetStdioAlreadyOpenException, got $other")
                                // EOF on stdin closes the connection: close the write end, then await the connection's close signal.
                                Sync.defer(stdinWriter.close()).andThen {
                                    conn.onClosing.safe.get.map { _ =>
                                        assert(!conn.isOpen, "connection must report closed after stdin EOF")
                                        succeed
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end NioTransportStdioTest
