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
final class MemorySegment private (private[foreign] val ptr: Ptr[Byte], val byteSize: Long, arena: Arena):
    private[MemorySegment] def checkOpen(): Unit =
        if arena.isClosed then
            throw new IllegalStateException("MemorySegment accessed after Arena was closed")

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
        checkOpen()
        if offset < 0 || newSize < 0 || offset + newSize > byteSize then
            throw new IllegalArgumentException(s"Invalid slice parameters: byteSize=$byteSize, offset=$offset, newSize=$newSize")
        else
            MemorySegment(ptr + offset, newSize, arena)
        end if
    end asSlice

    /** Reads a value from memory using the provided layout.
      */
    def get(layout: ValueLayout.OfBoolean, offset: Long): Boolean =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[CBool]]
    end get
    def get(layout: ValueLayout.OfByte, offset: Long): Byte =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Byte]]
    end get
    def get(layout: ValueLayout.OfShort, offset: Long): Short =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Short]]
    end get
    def get(layout: ValueLayout.OfInt, offset: Long): Int =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Int]]
    end get
    def get(layout: ValueLayout.OfLong, offset: Long): Long =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Long]]
    end get
    def get(layout: ValueLayout.OfFloat, offset: Long): Float =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Float]]
    end get
    def get(layout: ValueLayout.OfDouble, offset: Long): Double =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Double]]
    end get
    def get(layout: ValueLayout.OfChar, offset: Long): Char =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Char]]
    end get
    def get(layout: AddressLayout, offset: Long): MemorySegment =
        checkOpen()
        val newByteSize = byteSize - offset
        require(newByteSize >= 0)
        new MemorySegment((ptr + offset).asInstanceOf[Ptr[Byte]], newByteSize, arena)
    end get

    /** Writes a value to memory using the provided layout.
      */
    def set(layout: ValueLayout.OfBoolean, offset: Long, value: Boolean): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[CBool]] = value
    end set
    def set(layout: ValueLayout.OfByte, offset: Long, value: Byte): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Byte]] = value
    end set
    def set(layout: ValueLayout.OfShort, offset: Long, value: Short): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Short]] = value
    end set
    def set(layout: ValueLayout.OfInt, offset: Long, value: Int): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Int]] = value
    end set
    def set(layout: ValueLayout.OfLong, offset: Long, value: Long): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Long]] = value
    end set
    def set(layout: ValueLayout.OfFloat, offset: Long, value: Float): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Float]] = value
    end set
    def set(layout: ValueLayout.OfDouble, offset: Long, value: Double): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Double]] = value
    end set
    def set(layout: ValueLayout.OfChar, offset: Long, value: Char): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[Char]] = value
    end set
    def set(layout: AddressLayout, offset: Long, value: MemorySegment): Unit =
        checkOpen()
        require(offset + layout.byteSize <= byteSize)
        val _ = memcpy((ptr + offset).asInstanceOf[Ptr[Byte]], value.ptr, value.byteSize.toCSize)
    end set
end MemorySegment

object MemorySegment:

    /** Allocates a new MemorySegment of the given byte size. */
    private[foreign] def allocate(byteSize: Long, arena: Arena): MemorySegment =
        val ptr = malloc(byteSize).asInstanceOf[Ptr[Byte]]
        if ptr == null then throw new RuntimeException("malloc returned null")
        new MemorySegment(ptr, byteSize, arena)
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
        srcSegment.checkOpen()
        dstSegment.checkOpen()
        require(srcOffset + bytes <= srcSegment.byteSize)
        require(dstOffset + bytes <= dstSegment.byteSize)
        val _ = memcpy(dstSegment.ptr + dstOffset, srcSegment.ptr + srcOffset, bytes.toCSize)
        ()
    end copy

end MemorySegment

sealed trait ValueLayout:
    def byteSize: Long

object ValueLayout:
    trait OfBoolean extends ValueLayout
    trait OfByte    extends ValueLayout
    trait OfChar    extends ValueLayout
    trait OfShort   extends ValueLayout
    trait OfInt     extends ValueLayout
    trait OfLong    extends ValueLayout
    trait OfFloat   extends ValueLayout
    trait OfDouble  extends ValueLayout

    case object JAVA_BOOLEAN extends OfBoolean:
        val byteSize = sizeOf[CBool]
    case object JAVA_BYTE extends OfByte:
        val byteSize = sizeOf[Byte]
    case object JAVA_CHAR extends OfChar:
        val byteSize = sizeOf[CChar]
    case object JAVA_SHORT extends OfShort:
        val byteSize = sizeOf[CShort]
    case object JAVA_INT extends OfInt:
        val byteSize = sizeOf[CInt]
    case object JAVA_LONG extends OfLong:
        val byteSize = sizeOf[CLong]
    case object JAVA_FLOAT extends OfFloat:
        val byteSize = sizeOf[CFloat]
    case object JAVA_DOUBLE extends OfDouble:
        val byteSize = sizeOf[CDouble]
    case object ADDRESS extends AddressLayout:
        val byteSize = sizeOf[Ptr[Byte]]
end ValueLayout

sealed trait AddressLayout extends ValueLayout
