package java.lang.foreign

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib.malloc
import scala.scalanative.libc.string.memcpy
import scala.scalanative.unsigned._

/** A minimal stub for Java's MemorySegment using Scala Native pointers.
  *
  * This implementation wraps a pointer along with the allocated size in bytes.
  * It also provides basic support for slicing and for reading/writing primitive values,
  * which are used by the Layout instances in the shared code.
  */
final class MemorySegment private (ptr: Ptr[Byte], val byteSize: Long) {
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
  def get(layout: ValueLayout.ofByte, offset: Long): Byte = {
    !(ptr + offset).cast[Byte]
  }
  def get(layout: ValueLayout.ofShort, offset: Long): Short = {
    !(ptr + offset).cast[Short]
  }
  def get(layout: ValueLayout.ofInt, offset: Long): Int = {
    !(ptr + offset).cast[Int]
  }
  def get(layout: ValueLayout.ofLong, offset: Long): Long = {
    !(ptr + offset).cast[Long]
  }
  def get(layout: ValueLayout.ofFloat, offset: Long): Float = {
    !(ptr + offset).cast[Float]
  }
  def get(layout: ValueLayout.ofDouble, offset: Long): Double = {
    !(ptr + offset).cast[Double]
  }
  def get(layout: ValueLayout, offset: Long): MemorySegment =
    new MemorySegment(!(ptr + offset).cast[Ptr[Byte]], byteSize)

  /** Writes a value to memory using the provided layout.
    */
  def set(layout: ValueLayout.ofByte, offset: Long, value: Byte): Unit = {
    require(offset + unsafe.sizeOf[Byte] <= byteSize)
    !(ptr + offset).cast[Byte] = value
  }
  def set(layout: ValueLayout.ofShort, offset: Long, value: Short): Unit = {
    require(offset + unsafe.sizeOf[Short] <= byteSize)
    !(ptr + offset).cast[Short] = value
  }
  def set(layout: ValueLayout.ofInt, offset: Long, value: Int): Unit = {
    require(offset + unsafe.sizeOf[Int] <= byteSize)
    !(ptr + offset).cast[Int] = value
  }
  def set(layout: ValueLayout.ofLong, offset: Long, value: Long): Unit = {
    require(offset + unsafe.sizeOf[Long] <= byteSize)
    !(ptr + offset).cast[Long] = value
  }
  def set(layout: ValueLayout.ofFloat, offset: Long, value: Float): Unit = {
    require(offset + unsafe.sizeOf[Float] <= byteSize)
    !(ptr + offset).cast[Float] = value
  }
  def set(layout: ValueLayout.ofDouble, offset: Long, value: Double): Unit = {
    require(offset + unsafe.sizeOf[Double] <= byteSize)
    !(ptr + offset).cast[Double] = value
  }
}

object MemorySegment {
  private[foreign] def allocate(byteSize: Long): MemorySegment = {
    val ptr = malloc(byteSize.toULong).asInstanceOf[Ptr[Byte]]
    if (ptr == null) throw new RuntimeException("malloc returned null")
    new MemorySegment(ptr, byteSize)
  }
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

/** Minimal inline stub for ValueLayout.
  * This defines constants for the primitive types.
  */
sealed trait ValueLayout

object ValueLayout {
  case object JAVA_BYTE extends ValueLayout
  case object JAVA_SHORT extends ValueLayout
  case object JAVA_INT extends ValueLayout
  case object JAVA_LONG extends ValueLayout
  case object JAVA_FLOAT extends ValueLayout
  case object JAVA_DOUBLE extends ValueLayout

  type ofByte = JAVA_BYTE.type
  type ofShort = JAVA_SHORT.type
  type ofInt = JAVA_INT.type
  type ofLong = JAVA_LONG.type
  type ofFloat = JAVA_FLOAT.type
  type ofDouble = JAVA_DOUBLE.type
}