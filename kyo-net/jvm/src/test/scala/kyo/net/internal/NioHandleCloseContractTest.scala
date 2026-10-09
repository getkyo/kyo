package kyo.net.internal

import java.io.ByteArrayOutputStream
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
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

    "a release while a record is partly on the wire never splices bytes into that record".pendingUntilFixed(
        "J2: the close_notify is written right after a half-sent record's head, inside that record"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        smallBufferPair().map { (writer, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(writer, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val tls    = handle.tls.getOrElse(throw new IllegalStateException("TLS handle without TLS state"))
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
                discard(readPeer(peer, received, Present(writer))(false))
                driver.cancel(handle)
                driver.closeHandle(handle)
                assert(readPeer(peer, received, Absent)(false), "setup: the release ends the stream")
                val bytes = received.toByteArray
                assert(bytes.length >= whole + headLen, s"setup: the peer must receive the held record's head; got ${bytes.length}")
                assert(
                    bytes.slice(whole, whole + headLen).toList == record.take(headLen).toList,
                    "setup: the head on the wire is the held record's"
                )
                val after      = bytes.drop(whole + headLen)
                val tail       = record.drop(headLen)
                val conforming =
                    tail.startsWith(after) || (after.length > tail.length && after.startsWith(tail) && unwrapAll(serverEngine, bytes)._2)
                assert(
                    conforming,
                    s"after the held record's $headLen-byte head the peer received ${after.length} bytes that are not that record's tail " +
                        s"(${after.take(5).map(b => f"$b%02x").mkString(" ")}...)"
                )
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

    /** Reads `peer` into `into` until `done`, EOF, or `writer` turning writable, parking in a select between reads. Returns whether EOF was
      * reached. The select bound only detects a hang.
      */
    private def readPeer(peer: SocketChannel, into: ByteArrayOutputStream, writer: Maybe[SocketChannel])(done: => Boolean): Boolean =
        val selector = Selector.open()
        try
            peer.configureBlocking(false)
            discard(peer.register(selector, SelectionKey.OP_READ))
            val writerKey                = writer.map(_.register(selector, SelectionKey.OP_WRITE))
            val buf                      = ByteBuffer.allocate(64 << 10)
            val deadline                 = java.lang.System.nanoTime() + HangBound.toNanos
            @tailrec def loop(): Boolean =
                if done then false
                else
                    buf.clear()
                    val n = peer.read(buf)
                    if n < 0 then true
                    else if n > 0 then
                        into.write(buf.array(), 0, n)
                        loop()
                    else if writerKey.exists(k => k.isValid && k.isWritable) then false
                    else
                        if java.lang.System.nanoTime() > deadline then throw new IllegalStateException("the peer saw no bytes, EOF or room")
                        selector.selectedKeys().clear()
                        discard(selector.select(100))
                        loop()
                    end if
            loop()
        finally selector.close()
        end try
    end readPeer

end NioHandleCloseContractTest
