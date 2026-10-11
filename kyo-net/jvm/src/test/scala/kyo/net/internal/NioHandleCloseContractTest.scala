package kyo.net.internal

import java.io.ByteArrayOutputStream
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.*
import scala.annotation.tailrec

/** Close-contract leaves for the TLS close_notify that [[NioHandle.close]] sends: a real JDK `SSLEngine` peer reads the raw bytes the
  * release puts on the wire, so a misplaced alert is seen exactly as a remote TLS stack would see it.
  */
class NioHandleCloseContractTest extends Test with NioCloseContractFixtures:

    import AllowUnsafe.embrace.danger

    "a release while a record is partly on the wire never splices bytes into that record" in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        smallBufferPair().map { (writer, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(writer, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val tls    = handle.tls.getOrElse(throw new IllegalStateException("TLS handle without TLS state"))
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val span = Span.fromUnsafe(Array.tabulate[Byte](16384)(i => (i % 251).toByte))
                // One record per write, so the stream offset of the record left half-sent is the sum of the whole ones before it.
                @tailrec def writeUntilHeld(whole: Int, records: Int): Int =
                    if records > 10000 then throw new IllegalStateException(s"no write was partial after $records records")
                    driver.write(handle, span, 0) match
                        case WriteResult.Done          => writeUntilHeld(whole + tls.netOutBuf.limit(), records + 1)
                        case WriteResult.Partial(_, _) => whole
                        case other                     => throw new IllegalStateException(s"write returned $other")
                    end match
                end writeUntilHeld
                val whole   = writeUntilHeld(0, 0)
                val headLen = tls.netOutBuf.position()
                val held    = tls.netOutBuf.duplicate()
                held.position(0)
                val record = remaining(held)
                assert(tls.pendingCiphertext && headLen > 0, s"setup: a record must be half-sent; head $headLen of ${record.length}")
                // The peer reads until the writer has room again, so the release's own write is not refused by a full buffer.
                val received = new ByteArrayOutputStream
                val writable = Promise.Unsafe.init[Unit, Abort[Closed | kyo.net.NetException]]()
                driver.awaitWritable(handle, writable)
                readPeer(driver, peer, received, Present(writable)).map { _ =>
                    driver.cancel(handle)
                    driver.closeHandle(handle)
                    readPeer(driver, peer, received, Absent).map { ended =>
                        assert(ended, "setup: the release ends the stream")
                        val bytes = received.toByteArray
                        assert(bytes.length >= whole + headLen, s"setup: the peer must receive the held record's head; got ${bytes.length}")
                        assert(
                            bytes.slice(whole, whole + headLen).toList == record.take(headLen).toList,
                            "setup: the head on the wire is the held record's"
                        )
                        val after      = bytes.drop(whole + headLen)
                        val tail       = record.drop(headLen)
                        val conforming =
                            tail.startsWith(after) ||
                                (after.length > tail.length && after.startsWith(tail) && unwrapAll(serverEngine, bytes)._2)
                        assert(
                            conforming,
                            s"after the held record's $headLen-byte head the peer received ${after.length} bytes that are not that record's " +
                                s"tail (${after.take(5).map(b => f"$b%02x").mkString(" ")}...)"
                        )
                    }
                }
            }
        }
    }

    /** A loopback pair whose writer and peer buffers are small, so a few records fill them. */
    private def smallBufferPair()(using Frame): (SocketChannel, SocketChannel) < (Async & Abort[java.io.IOException]) =
        NioLoopbackPair.open(
            configureListener = _.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(4096)): Unit,
            configureClient = writer =>
                writer.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(4096))
                writer.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE): Unit
        )

    /** Reads `peer` into `into` without blocking until EOF or `writable` completes, one selector pass of `driver` between empty reads.
      * Returns whether EOF was reached. The pass bound only turns a hang into a failure.
      */
    private def readPeer(
        driver: NioIoDriver,
        peer: SocketChannel,
        into: ByteArrayOutputStream,
        writable: Maybe[Promise.Unsafe[Unit, Abort[Closed | kyo.net.NetException]]]
    )(using Frame): Boolean < Async =
        val buf = ByteBuffer.allocate(64 << 10)
        Loop.indexed { round =>
            Sync.defer {
                peer.configureBlocking(false)
                buf.clear()
                val n = peer.read(buf)
                if n > 0 then into.write(buf.array(), 0, n)
                n
            }.map { n =>
                if n < 0 then Loop.done(true)
                else if n > 0 then Loop.continue
                else if writable.exists(_.done()) then Loop.done(false)
                else if round >= 100000 then throw new IllegalStateException("the peer saw no bytes, EOF or room")
                else selectorPasses(driver, 1).andThen(Loop.continue)
            }
        }
    end readPeer

end NioHandleCloseContractTest
