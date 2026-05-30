package kyo.internal.tasty.binary

import java.nio.MappedByteBuffer
import kyo.AllowUnsafe

/** JVM memory-mapped ByteView backed by a java.nio.MappedByteBuffer.
  *
  * Reads access the mapped region via MappedByteBuffer.get(index). A closed flag guards against post-unmap reads: when the Scope finalizer
  * fires, the flag is set and subsequent reads throw IllegalStateException, which Symbol.body catches and maps to
  * TastyError.ClasspathClosed.
  *
  * Note: actual file un-mapping in Java is best-effort (JVM GC handles it). The closed flag provides a clean logical boundary for the
  * semantics required by Symbol.body.
  *
  * @param buf
  *   the MappedByteBuffer backing the mapped file.
  * @param start
  *   absolute start offset within the buffer for this view.
  * @param end
  *   absolute exclusive end offset within the buffer for this view.
  * @param closed
  *   shared flag; set to true when the mapping's Scope finalizer fires.
  */
final class MappedByteView(
    private val buf: MappedByteBuffer,
    private val start: Long,
    private val end: Long,
    private[binary] val closed: java.util.concurrent.atomic.AtomicBoolean
) extends ByteView.Mapped:

    private var cursor: Long = start

    private def checkOpen(): Unit =
        if closed.get() then throw new IllegalStateException("mmap arena closed")

    def peekByte(at: Long): Byte =
        checkOpen()
        buf.get(at.toInt)

    def readByte()(using AllowUnsafe): Byte =
        checkOpen()
        if cursor > Int.MaxValue then
            throw new IllegalStateException(
                s"MappedByteView cursor $cursor exceeds Int.MaxValue; mmap segment overflow"
            )
        end if
        val b = buf.get(cursor.toInt)
        cursor += 1
        b
    end readByte

    def readEnd()(using AllowUnsafe): Long =
        val len = Varint.readNat(this)
        cursor + len.toLong

    def subView(from: Long, until: Long): MappedByteView =
        new MappedByteView(buf, from, until, closed)

    def goto(addr: Long)(using AllowUnsafe): Unit =
        cursor = addr

    def remaining: Long = end - cursor

    def position: Long = cursor

end MappedByteView
