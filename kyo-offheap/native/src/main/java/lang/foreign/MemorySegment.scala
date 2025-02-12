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
final class MemorySegment(val ptr: Ptr[Byte], val byteSize: Long) {
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
  def get(layout: Any, offset: Long): Any =
    if (layout == ValueLayout.JAVA_BYTE) {
      !(ptr + offset).cast[Byte]
    } else if (layout == ValueLayout.JAVA_SHORT) {
      !(ptr + offset).cast[Short]
    } else if (layout == ValueLayout.JAVA_INT) {
      !(ptr + offset).cast[Int]
    } else if (layout == ValueLayout.JAVA_LONG) {
      !(ptr + offset).cast[Long]
    } else if (layout == ValueLayout.JAVA_FLOAT) {
      !(ptr + offset).cast[Float]
    } else if (layout == ValueLayout.JAVA_DOUBLE) {
      !(ptr + offset).cast[Double]
    } else {
      throw new UnsupportedOperationException("Unsupported layout in get")
    }

  /** Writes a value to memory using the provided layout.
    */
  def set(layout: Any, offset: Long, value: Any): Unit =
    if (layout == ValueLayout.JAVA_BYTE) {
      !(ptr + offset).cast[Byte] = value.asInstanceOf[Byte]
    } else if (layout == ValueLayout.JAVA_SHORT) {
      !(ptr + offset).cast[Short] = value.asInstanceOf[Short]
    } else if (layout == ValueLayout.JAVA_INT) {
      !(ptr + offset).cast[Int] = value.asInstanceOf[Int]
    } else if (layout == ValueLayout.JAVA_LONG) {
      !(ptr + offset).cast[Long] = value.asInstanceOf[Long]
    } else if (layout == ValueLayout.JAVA_FLOAT) {
      !(ptr + offset).cast[Float] = value.asInstanceOf[Float]
    } else if (layout == ValueLayout.JAVA_DOUBLE) {
      !(ptr + offset).cast[Double] = value.asInstanceOf[Double]
    } else {
      throw new UnsupportedOperationException("Unsupported layout in set")
    }
}

object MemorySegment {
  /** Copies a block of memory from the source segment to the destination segment.
    *
    * @param src The source MemorySegment.
    * @param srcOffset The offset (in bytes) into the source segment.
    * @param dst The destination MemorySegment.
    * @param dstOffset The offset (in bytes) into the destination segment.
    * @param byteCount The number of bytes to copy.
    */
  def copy(src: MemorySegment, srcOffset: Long, dst: MemorySegment, dstOffset: Long, byteCount: Long): Unit = {
    memcpy(dst.ptr + dstOffset, src.ptr + srcOffset, byteCount.toULong)
    ()
  }
}
