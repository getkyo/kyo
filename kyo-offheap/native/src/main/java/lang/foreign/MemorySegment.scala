package java.lang.foreign

import scala.scalanative.libc.stdlib.malloc
import scala.scalanative.libc.string.memcpy
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** A minimal stub for Java's MemorySegment using Scala Native pointers.
  *
  * This implementation wraps a pointer along with the allocated size in bytes. It also provides basic support for slicing and for
  * reading/writing primitive values, which are used by the Layout instances in the shared code.
  */
final class MemorySegment private (private[foreign] val ptr: Ptr[Byte], val byteSize: Long):
    /** Creates a new MemorySegment that is a slice of this segment.
      *
      * @param offset
      *   The offset (in bytes) at which the slice begins.
      * @param newSize
      *   The size (in bytes) of the new segment.
      * @return
      *   A new MemorySegment representing the slice.
      */
    def asSlice(offset: Long, newSize: Long): MemorySegment =
        if offset < 0 || newSize < 0 || offset + newSize > byteSize then
            throw new IllegalArgumentException("Invalid slice parameters")
        new MemorySegment(ptr + offset, newSize)
    end asSlice

    /** Reads a value from memory using the provided layout.
      */
    def get(layout: ValueLayout.ofBoolean, offset: Long): Boolean =
        require(offset + unsafe.sizeOf[CBool] <= byteSize)
        !(ptr + offset).cast[Bool]
    def get(layout: ValueLayout.ofByte, offset: Long): Byte =
        require(offset + unsafe.sizeOf[Byte] <= byteSize)
        !(ptr + offset).cast[Byte]
    def get(layout: ValueLayout.ofShort, offset: Long): Short =
        require(offset + unsafe.sizeOf[CShort] <= byteSize)
        !(ptr + offset).cast[Short]
    def get(layout: ValueLayout.ofInt, offset: Long): Int =
        require(offset + unsafe.sizeOf[CInt] <= byteSize)
        !(ptr + offset).cast[Int]
    def get(layout: ValueLayout.ofLong, offset: Long): Long =
        require(offset + unsafe.sizeOf[CLong] <= byteSize)
        !(ptr + offset).cast[Long]
    def get(layout: ValueLayout.ofFloat, offset: Long): Float =
        require(offset + unsafe.sizeOf[CFloat] <= byteSize)
        !(ptr + offset).cast[Float]
    def get(layout: ValueLayout.ofDouble, offset: Long): Double =
        require(offset + unsafe.sizeOf[CDouble] <= byteSize)
        !(ptr + offset).cast[Double]
    def get(layout: ValueLayout.ofChar, offset: Long): Char =
        require(offset + unsafe.sizeOf[CChar] <= byteSize)
        !(ptr + offset).cast[Char]
    def get(layout: AddressLayout, offset: Long): MemorySegment =
        val newByteSize = byteSize - offset
        require(newByteSize >= 0)
        new MemorySegment(!(ptr + offset).cast[Ptr[Byte]], newByteSize)
    end get

    /** Writes a value to memory using the provided layout.
      */
    def set(layout: ValueLayout.ofBoolean, offset: Long, value: Boolean): Unit =
        require(offset + unsafe.sizeOf[CBool] <= byteSize)
        !(ptr + offset).cast[Bool] = value
    def set(layout: ValueLayout.ofByte, offset: Long, value: Byte): Unit =
        require(offset + unsafe.sizeOf[Byte] <= byteSize)
        !(ptr + offset).cast[Byte] = value
    def set(layout: ValueLayout.ofShort, offset: Long, value: Short): Unit =
        require(offset + unsafe.sizeOf[CShort] <= byteSize)
        !(ptr + offset).cast[Short] = value
    def set(layout: ValueLayout.ofInt, offset: Long, value: Int): Unit =
        require(offset + unsafe.sizeOf[CInt] <= byteSize)
        !(ptr + offset).cast[Int] = value
    def set(layout: ValueLayout.ofLong, offset: Long, value: Long): Unit =
        require(offset + unsafe.sizeOf[CLong] <= byteSize)
        !(ptr + offset).cast[Long] = value
    def set(layout: ValueLayout.ofFloat, offset: Long, value: Float): Unit =
        require(offset + unsafe.sizeOf[CFloat] <= byteSize)
        !(ptr + offset).cast[Float] = value
    def set(layout: ValueLayout.ofDouble, offset: Long, value: Double): Unit =
        require(offset + unsafe.sizeOf[CDouble] <= byteSize)
        !(ptr + offset).cast[Double] = value
    def set(layout: ValueLayout.ofChar, offset: Long, value: Char): Unit =
        require(offset + unsafe.sizeOf[CChar] <= byteSize)
        !(ptr + offset).cast[Char] = value
    def set(layout: AddressLayout, offset: Long, value: MemorySegment): Unit =
        require(offset + value.byteSize <= byteSize)
        memcpy((ptr + offset).cast[Ptr[Byte]], value.ptr, value.byteSize.toULong)
    end set
end MemorySegment

object MemorySegment:
    private[foreign] def allocate(byteSize: Long): MemorySegment =
        val ptr = malloc(byteSize).asInstanceOf[Ptr[Byte]]
        if ptr == null then throw new RuntimeException("malloc returned null")
        new MemorySegment(ptr, byteSize)
    end allocate

    /** Copies a block of memory from the source segment to the destination segment.
      *
      * @param srcSegment
      *   The source MemorySegment.
      * @param srcOffset
      *   The offset (in bytes) into the source segment.
      * @param dstSegment
      *   The destination MemorySegment.
      * @param dstOffset
      *   The offset (in bytes) into the destination segment.
      * @param bytes
      *   The number of bytes to copy.
      */
    def copy(srcSegment: MemorySegment, srcOffset: Long, dstSegment: MemorySegment, dstOffset: Long, bytes: Long): Unit =
        require(srcOffset + bytes <= srcSegment.byteSize)
        require(dstOffset + bytes <= dstSegment.byteSize)
        memcpy(dstSegment.ptr + dstOffset, srcSegment.ptr + srcOffset, bytes.toULong)
    end copy
end MemorySegment

/** Minimal inline stub for ValueLayout. This defines constants for the primitive types.
  */
sealed trait ValueLayout

object ValueLayout:
    sealed trait ofBoolean extends ValueLayout
    sealed trait ofByte    extends ValueLayout
    sealed trait ofChar    extends ValueLayout
    sealed trait ofShort   extends ValueLayout
    sealed trait ofInt     extends ValueLayout
    sealed trait ofLong    extends ValueLayout
    sealed trait ofFloat   extends ValueLayout
    sealed trait ofDouble  extends ValueLayout

    case object JAVA_BOOLEAN extends ofBoolean
    case object JAVA_BYTE    extends ofByte
    case object JAVA_CHAR    extends ofChar
    case object JAVA_SHORT   extends ofShort
    case object JAVA_INT     extends ofInt
    case object JAVA_LONG    extends ofLong
    case object JAVA_FLOAT   extends ofFloat
    case object JAVA_DOUBLE  extends ofDouble
    case object ADDRESS      extends ValueLayout
end ValueLayout
sealed trait AddressLayout extends ValueLayout
