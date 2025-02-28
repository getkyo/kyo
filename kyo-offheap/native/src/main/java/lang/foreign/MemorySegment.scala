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
            throw new IllegalArgumentException(s"Invalid slice parameters: byteSize=$byteSize, offset=$offset, newSize=$newSize")
        else
            MemorySegment(ptr + offset, newSize)

    /** Reads a value from memory using the provided layout. */
    def get[T](layout: ValueLayout, offset: Long)(using tag: Tag[T]): T =
        require(offset + layout.byteSize <= byteSize)
        !(ptr + offset).asInstanceOf[Ptr[T]]

    /** Writes a value to memory using the provided layout. */
    def set[T](layout: ValueLayout, offset: Long, value: T)(using tag: Tag[T]): Unit =
        require(offset + layout.byteSize <= byteSize)
        !((ptr + offset).asInstanceOf[Ptr[T]]) = value

end MemorySegment

object MemorySegment:

    /** Allocates a new MemorySegment of the given byte size. */
    def allocate(byteSize: Long): MemorySegment =
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
        val _ = memcpy(dstSegment.ptr + dstOffset, srcSegment.ptr + srcOffset, bytes.toCSize)
        ()
    end copy

end MemorySegment

sealed trait ValueLayout:
    val byteSize: Long

object ValueLayout:
    case object JAVA_BOOLEAN extends ValueLayout:
        val byteSize = sizeOf[CBool]
    case object JAVA_BYTE extends ValueLayout:
        val byteSize = sizeOf[Byte]
    case object JAVA_CHAR extends ValueLayout:
        val byteSize = sizeOf[CChar]
    case object JAVA_SHORT extends ValueLayout:
        val byteSize = sizeOf[CShort]
    case object JAVA_INT extends ValueLayout:
        val byteSize = sizeOf[CInt]
    case object JAVA_LONG extends ValueLayout:
        val byteSize = sizeOf[CLong]
    case object JAVA_FLOAT extends ValueLayout:
        val byteSize = sizeOf[CFloat]
    case object JAVA_DOUBLE extends ValueLayout:
        val byteSize = sizeOf[CDouble]
    case object ADDRESS extends ValueLayout:
        val byteSize = sizeOf[Ptr[Byte]]
end ValueLayout

sealed trait AddressLayout extends ValueLayout
