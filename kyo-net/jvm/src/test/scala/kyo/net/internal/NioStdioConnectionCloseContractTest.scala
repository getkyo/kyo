package kyo.net.internal

import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import kyo.*
import kyo.net.Test

/** Close-contract leaves for the NIO floor's stdio connection. The connection captures `System.in`/`System.out` when it opens, so each leaf
  * swaps in a test-owned stdin pipe and stdout stream only around [[NioStdioConnection.open]] and restores the process streams at once.
  */
class NioStdioConnectionCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Open a stdio connection over a fresh stdin pipe and `stdout`. Closing the pipe's write end at scope exit gives the read pump its EOF,
      * which closes the connection and ends the pump.
      */
    private def openStdio(stdout: PrintStream)(using Frame): kyo.net.Connection < (Sync & Scope) =
        Sync.defer {
            val stdinWriter = new PipedOutputStream()
            val stdinPipe   = new PipedInputStream(stdinWriter)
            val savedIn     = java.lang.System.in
            val savedOut    = java.lang.System.out
            java.lang.System.setIn(stdinPipe)
            java.lang.System.setOut(stdout)
            val conn =
                try NioStdioConnection.open(channelCapacity = 4, readBufferSize = 1024)
                finally
                    java.lang.System.setIn(savedIn)
                    java.lang.System.setOut(savedOut)
            Scope.ensure(Sync.defer {
                conn.close()
                stdinWriter.close()
            }).andThen(conn)
        }

    /** A stdout whose every write fails like a pipe whose reader exited; `attempted` completes on the first write. */
    final private class BrokenPipe(attempted: Promise.Unsafe[Unit, Any]) extends OutputStream:
        override def write(b: Int): Unit                             = fail()
        override def write(b: Array[Byte], off: Int, len: Int): Unit = fail()
        private def fail(): Nothing                                  =
            attempted.completeDiscard(Result.succeed(()))
            throw new IOException("Broken pipe")
    end BrokenPipe

    "a waiter that gives up on onClosing does not complete it".pendingUntilFixed(
        "J9: the stdio closing promise is interruptible, so an interrupted waiter completes the close signal"
    ) in {
        openStdio(java.lang.System.out).map { conn =>
            Async.race(conn.onClosing.safe.get.map(_ => "closing"), Kyo.lift("gave up")).map { winner =>
                assert(winner == "gave up")
                assert(!conn.onClosing.done(), "a waiter giving up on onClosing must leave it pending while the connection is open")
                assert(conn.isOpen)
            }
        }
    }

    "a stdout write that fails closes the connection".pendingUntilFixed(
        "J10: System.out is a PrintStream that swallows the IOException, so a broken stdout is never noticed and every write is lost"
    ) in {
        val attempted = Promise.Unsafe.init[Unit, Any]()
        openStdio(new PrintStream(new BrokenPipe(attempted), true)).map { conn =>
            for
                offered <- Sync.defer(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](16)(1))))
                _       <- attempted.safe.get
                // The bound only turns a connection that never notices the failure into a failure instead of a hang.
                closing <- Abort.run[Timeout](Async.timeout(10.seconds)(conn.onClosing.safe.get))
            yield
                assert(offered == Result.succeed(true), s"outbound must accept the span, got $offered")
                assert(closing.isSuccess, s"a failed stdout write must close the connection, got $closing; isOpen=${conn.isOpen}")
            end for
        }
    }

end NioStdioConnectionCloseContractTest
