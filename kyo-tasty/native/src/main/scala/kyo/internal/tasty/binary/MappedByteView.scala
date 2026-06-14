package kyo.internal.tasty.binary

import kyo.AllowUnsafe
import scala.scalanative.unsafe.*

/** Scala Native memory-mapped ByteView backed by a POSIX mmap `Ptr[Byte]`.
  *
  * The base class `ByteView.Mapped` carries the cursor, the closed-flag arena guard, and the navigation methods. This subclass adds only
  * the Native-specific byte-access primitive (raw pointer indexing). After munmap is called (via the Scope finalizer in
  * `NativeMmapReader`), the `closed` flag transitions to true and the inherited `checkOpen()` throws `IllegalStateException` before any
  * dereference of the now-invalid pointer.
  */
final class MappedByteView(
    private val ptr: Ptr[Byte],
    start: Long,
    end: Long,
    closed: java.util.concurrent.atomic.AtomicBoolean
) extends ByteView.Mapped(closed, start, end):

    def peekByte(at: Long): Byte =
        checkOpen()
        ptr(at)

    def readByte()(using AllowUnsafe): Byte =
        checkOpen()
        val b = ptr(cursor)
        cursor += 1
        b
    end readByte

    def subView(from: Long, until: Long): MappedByteView =
        new MappedByteView(ptr, from, until, closed)

end MappedByteView
