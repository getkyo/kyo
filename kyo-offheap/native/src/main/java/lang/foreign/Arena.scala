package kyo.offheap.native.java.lang.foreign

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib._

// Stub implementation for Java's Arena
class Arena {
  // Allocate a memory segment of the specified size
  def allocate[A: Layout](size: Long): MemorySegment = {
    // Calculate the total size needed
    val totalSize = size * summon[Layout[A]].size
    // Allocate memory using malloc
    val ptr = malloc(totalSize)
    if (ptr == null) {
      throw new OutOfMemoryError(s"Failed to allocate $totalSize bytes")
    }
    // Return a new MemorySegment
    new MemorySegment(ptr, size)
  }

  // Close the arena and free any allocated memory
  def close(segment: MemorySegment): Unit = {
    // Free the memory allocated for the segment
    free(segment.ptr)
  }
}