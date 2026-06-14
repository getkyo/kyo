package kyo.internal.tasty.binary

import java.nio.MappedByteBuffer
import kyo.AllowUnsafe

/** JVM memory-mapped ByteView backed by a `java.nio.MappedByteBuffer`.
  *
  * The base class `ByteView.Mapped` carries the cursor, the closed-flag arena guard, and the navigation methods. This subclass adds only
  * the JVM-specific byte-access primitive (`buffer.get(at)`).
  *
  * Note: actual file un-mapping on the JVM is best-effort (GC handles it). The shared `closed` flag in the base provides the logical
  * close boundary required by `Symbol.body`.
  */
final class MappedByteView(
    private val buffer: MappedByteBuffer,
    start: Long,
    end: Long,
    closed: java.util.concurrent.atomic.AtomicBoolean
) extends ByteView.Mapped(closed, start, end):

    def peekByte(at: Long): Byte =
        checkOpen()
        buffer.get(at.toInt)

    def readByte()(using AllowUnsafe): Byte =
        checkOpen()
        if cursor > Int.MaxValue then
            throw new IllegalStateException(
                s"MappedByteView cursor $cursor exceeds Int.MaxValue; mmap segment overflow"
            )
        end if
        val b = buffer.get(cursor.toInt)
        cursor += 1
        b
    end readByte

    def subView(from: Long, until: Long): MappedByteView =
        new MappedByteView(buffer, from, until, closed)

end MappedByteView
