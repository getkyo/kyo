package kyo.internal.tasty.binary

import scala.scalanative.unsafe.*

/** Scala Native memory-mapped ByteView backed by a POSIX mmap Ptr[Byte].
  *
  * Reads use direct pointer arithmetic so no heap copy is made. After munmap is called (via Scope finalizer in NativeMmapReader), accessing
  * the pointer produces undefined behavior; on most platforms this surfaces as a segfault or bus error. Unlike the JVM path (which uses
  * MemorySegment and throws IllegalStateException on closed arena), Native has no managed memory invalidation. Therefore, post-close reads
  * are guarded by a volatile boolean flag that throws IllegalStateException before touching the pointer.
  */
final class MappedByteView(
    private val ptr: Ptr[Byte],
    private val start: Long,
    private val end: Long,
    private val closed: java.util.concurrent.atomic.AtomicBoolean
) extends ByteView.Mapped:

    private var cursor: Long = start

    private def checkOpen(): Unit =
        if closed.get() then throw new IllegalStateException("mmap arena closed")

    def peekByte(at: Long): Byte =
        checkOpen()
        ptr(at)

    def readByte(): Byte =
        checkOpen()
        val b = ptr(cursor)
        cursor += 1
        b
    end readByte

    def readEnd(): Long =
        val len = Varint.readNat(this)
        cursor + len.toLong

    def subView(from: Long, until: Long): MappedByteView =
        new MappedByteView(ptr, from, until, closed)

    def goto(addr: Long): Unit =
        cursor = addr

    def remaining: Long = end - cursor

    def position: Long = cursor

end MappedByteView
