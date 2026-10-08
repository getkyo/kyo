package kyo.internal.tasty.binary

import kyo.AllowUnsafe
import scala.scalanative.unsafe.*

/** Scala Native memory-mapped ByteView backed by a POSIX mmap `Ptr[Byte]`.
  *
  * The base class `ByteView.Mapped` carries the cursor, the closed-flag arena guard, and the navigation methods. This subclass adds only
  * the Native-specific byte-access primitive (raw pointer indexing). After munmap is called (via the Scope finalizer in
  * `NativeMmapReader`), the `closed` flag transitions to true and the inherited `checkOpen()` throws `IllegalStateException` before any
  * dereference of the now-invalid pointer.
  *
  * `mappedSize` is the length of the whole mapping, shared by every sub-view. A read outside `[0, mappedSize)` throws
  * `IndexOutOfBoundsException` before touching the pointer, the same bound and exception as `MappedByteBuffer.get` on the JVM; without
  * it a corrupt offset reads past the mapping and faults the process.
  */
final class MappedByteView(
    private val ptr: Ptr[Byte],
    private val mappedSize: Long,
    start: Long,
    end: Long,
    closed: java.util.concurrent.atomic.AtomicBoolean
) extends ByteView.Mapped(closed, start, end):

    private def checkIndex(at: Long): Unit =
        if at < 0L || at >= mappedSize then throw new IndexOutOfBoundsException(s"index $at outside mapping of $mappedSize bytes")

    def peekByte(at: Long): Byte =
        checkOpen()
        checkIndex(at)
        ptr(at)
    end peekByte

    def readByte()(using AllowUnsafe): Byte =
        checkOpen()
        checkIndex(cursor)
        val b = ptr(cursor)
        cursor += 1
        b
    end readByte

    def subView(from: Long, until: Long): MappedByteView =
        new MappedByteView(ptr, mappedSize, from, until, closed)

end MappedByteView
