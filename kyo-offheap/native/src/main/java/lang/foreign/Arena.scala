package java.lang.foreign

import java.util.concurrent.ConcurrentLinkedQueue
import scala.scalanative.libc.stdlib.free
import scala.scalanative.unsafe.*

/** A minimal stub for Java's Arena using Scala Native.
  *
  * An Arena is used to track off-heap allocations so that they can be automatically deallocated when the scope ends.
  */
final class Arena extends AutoCloseable:
    private val allocations                  = new ConcurrentLinkedQueue[MemorySegment]()
    @volatile private var _isClosed: Boolean = false
    def isClosed: Boolean                    = _isClosed

    /** Allocates a block of off-heap memory of the given size (in bytes).
      *
      * @param byteSize
      *   The size in bytes to allocate.
      * @return
      *   A MemorySegment representing the allocated block.
      */
    def allocate(byteSize: Long): MemorySegment =
        if _isClosed then
            throw new IllegalStateException("Arena is closed")
        val segment = MemorySegment.allocate(byteSize, this)
        allocations.add(segment)
        segment
    end allocate

    /** Frees all memory that was allocated through this Arena.
      */
    override def close(): Unit =
        _isClosed = true
        var seg = allocations.poll()
        while seg != null do
            free(seg.ptr)
            seg = allocations.poll()
    end close
end Arena

object Arena:
    /** Shared Arena.
      *
      * This is used by the shared Memory API to obtain an Arena for allocation.
      */
    def ofShared(): Arena = new Arena()
end Arena
