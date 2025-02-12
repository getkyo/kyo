package java.lang.foreign

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib.{malloc, free}

/** A minimal stub for Java's Arena using Scala Native.
  *
  * An Arena is used to track off-heap allocations so that they can be
  * automatically deallocated when the scope ends.
  */
final class Arena {
  private var allocations: List[MemorySegment] = Nil

  /** Allocates a block of off-heap memory of the given size (in bytes).
    *
    * @param size The size in bytes to allocate.
    * @return A MemorySegment representing the allocated block.
    */
  def allocate(size: Long): MemorySegment = {
    val p = malloc(size.toULong).asInstanceOf[Ptr[Byte]]
    if (p == null) throw new OutOfMemoryError("malloc failed")
    val segment = new MemorySegment(p, size)
    allocations = segment :: allocations
    segment
  }

  /** Frees all memory that was allocated through this Arena.
    */
  def close(): Unit = {
    allocations.foreach(seg => free(seg.ptr))
    allocations = Nil
  }
}

object Arena {
  /** Shared Arena.
    *
    * This is used by the shared Memory API to obtain an Arena for allocation.
    */
  def ofShared(): Arena = new Arena()
}