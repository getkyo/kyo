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
  def get(layout: ValueLayout.ofBoolean, offset: Long): Boolean = {
    require(offset + unsafe.sizeOf[Bool] <= byteSize)
    !(ptr + offset).cast[Bool]
  }
  def get(layout: ValueLayout.ofByte, offset: Long): Byte = {
    require(offset + unsafe.sizeOf[Byte] <= byteSize)
    !(ptr + offset).cast[Byte]
  }
  def get(layout: ValueLayout.ofShort, offset: Long): Short = {
    require(offset + unsafe.sizeOf[Short] <= byteSize)
    !(ptr + offset).cast[Short]
  }
  def get(layout: ValueLayout.ofInt, offset: Long): Int = {
    require(offset + unsafe.sizeOf[Int] <= byteSize)
    !(ptr + offset).cast[Int]
  }
  def get(layout: ValueLayout.ofLong, offset: Long): Long = {
    require(offset + unsafe.sizeOf[Long] <= byteSize)
    !(ptr + offset).cast[Long]
  }
  def get(layout: ValueLayout.ofFloat, offset: Long): Float = {
    require(offset + unsafe.sizeOf[Float] <= byteSize)
    !(ptr + offset).cast[Float]
  }
  def get(layout: ValueLayout.ofDouble, offset: Long): Double = {
    require(offset + unsafe.sizeOf[Double] <= byteSize)
    !(ptr + offset).cast[Double]
  }
  def get(layout: ValueLayout.ofChar, offset: Long): Char = {
    require(offset + unsafe.sizeOf[Char] <= byteSize)
    !(ptr + offset).cast[Char]
  }
  def get(layout: AddressLayout, offset: Long): MemorySegment =
    new MemorySegment(!(ptr + offset).cast[Ptr[Byte]], byteSize)

  /** Writes a value to memory using the provided layout.
    */
  def set(layout: ValueLayout.ofBoolean, offset: Long, value: Boolean): Unit = {
    require(offset + unsafe.sizeOf[Bool] <= byteSize)
    !(ptr + offset).cast[Bool] = value
  }
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
  def set(layout: ValueLayout.ofChar, offset: Long, value: Char): Unit = {
    require(offset + unsafe.sizeOf[Char] <= byteSize)
    !(ptr + offset).cast[Char] = value
  }
  def set(layout: AddressLayout, offset: Long, value: MemorySegment): Unit =
    !(ptr + offset).cast[Ptr[Byte]] = value.ptr
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
  sealed trait ofBoolean extends ValueLayout
  sealed trait ofByte extends ValueLayout
  sealed trait ofChar extends ValueLayout
  sealed trait ofShort extends ValueLayout
  sealed trait ofInt extends ValueLayout
  sealed trait ofLong extends ValueLayout
  sealed trait ofFloat extends ValueLayout
  sealed trait ofDouble extends ValueLayout

  case object JAVA_BOOLEAN extends ValueLayout
  case object JAVA_BYTE extends ValueLayout
  case object JAVA_CHAR extends ValueLayout
  case object JAVA_CHAR_UNALIGNED extends ValueLayout
  case object JAVA_SHORT extends ValueLayout
  case object JAVA_SHORT_UNALIGNED extends ValueLayout
  case object JAVA_INT extends ValueLayout
  case object JAVA_INT_UNALIGNED extends ValueLayout
  case object JAVA_LONG extends ValueLayout
  case object JAVA_LONG_UNALIGNED extends ValueLayout
  case object JAVA_FLOAT extends ValueLayout
  case object JAVA_FLOAT_UNALIGNED extends ValueLayout
  case object JAVA_DOUBLE extends ValueLayout
  case object JAVA_DOUBLE_UNALIGNED extends ValueLayout
}
sealed trait AddressLayout extends ValueLayout