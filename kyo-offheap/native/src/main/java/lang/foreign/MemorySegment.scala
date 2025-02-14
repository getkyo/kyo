package java.lang.foreign

import scala.scalanative.unsafe._
import scala.scalanative.libc.string.memcpy
import scala.scalanative.unsigned._

/** A minimal stub for Java's MemorySegment using Scala Native pointers.
  *
  * This implementation wraps a pointer along with the allocated size in bytes.
  * It also provides basic support for slicing and for reading/writing primitive values,
  * which are used by the Layout instances in the shared code.
  */
final class MemorySegment(ptr: Ptr[Byte], val byteSize: Long) {
  /** Creates a new MemorySegment that is a slice of this segment.
    *
    * @param offset The offset (in bytes) at which the slice begins.
    * @param newSize The size (in bytes) of the new segment.
    * @return A new MemorySegment representing the slice.
    */
  def asSlice(offset: Long, newSize: Long): MemorySegment = {
    if (offset < 0 || newSize < 0 || offset + newSize > byteSize)
      throw new IllegalArgumentException("Invalid slice parameters")
    new MemorySegment(ptr + offset, newSize)
  }

  /** Reads a value from memory using the provided layout.
    */
  def get(layout: ValueLayout.ofByte, offset: Long): Byte = !(ptr + offset).cast[Byte]
  def get(layout: ValueLayout.ofShort, offset: Long): Short = !(ptr + offset).cast[Short]
  def get(layout: ValueLayout.ofInt, offset: Long): Int = !(ptr + offset).cast[Int]
  def get(layout: ValueLayout.ofLong, offset: Long): Long = !(ptr + offset).cast[Long]
  def get(layout: ValueLayout.ofFloat, offset: Long): Float = !(ptr + offset).cast[Float]
  def get(layout: ValueLayout.ofDouble, offset: Long): Double = !(ptr + offset).cast[Double]
  
  // def get(layout: ValueLayout, offset: Long): MemorySegment = new MemorySegment(!(ptr + offset).cast[Ptr[Byte]], byteSize)

  /** Writes a value to memory using the provided layout.
    */
  def set(layout: ValueLayout.ofByte, offset: Long, value: Byte): Unit = !(ptr + offset).cast[Byte] = value
  def set(layout: ValueLayout.ofShort, offset: Long, value: Short): Unit = !(ptr + offset).cast[Short] = value
  def set(layout: ValueLayout.ofInt, offset: Long, value: Int): Unit = !(ptr + offset).cast[Int] = value
  def set(layout: ValueLayout.ofLong, offset: Long, value: Long): Unit = !(ptr + offset).cast[Long] = value
  def set(layout: ValueLayout.ofFloat, offset: Long, value: Float): Unit = !(ptr + offset).cast[Float] = value
  def set(layout: ValueLayout.ofDouble, offset: Long, value: Double): Unit = !(ptr + offset).cast[Double] = value

  // def set(layout: ValueLayout, offset: Long, value: MemorySegment): Unit = !(ptr + offset).cast[Ptr[Byte]] = value.ptr
}

object MemorySegment {
  /** Copies a block of memory from the source segment to the destination segment.
    *
    * @param srcSegment The source MemorySegment.
    * @param srcOffset The offset (in bytes) into the source segment.
    * @param dstSegment The destination MemorySegment.
    * @param dstOffset The offset (in bytes) into the destination segment.
    * @param bytes The number of bytes to copy.
    */
  def copy(srcSegment: MemorySegment, srcOffset: Long, dstSegment: MemorySegment, dstOffset: Long, bytes: Long): Unit = {
    memcpy(dstSegment.ptr + dstOffset, srcSegment.ptr + srcOffset, bytes.toULong)
    ()
  }
}
