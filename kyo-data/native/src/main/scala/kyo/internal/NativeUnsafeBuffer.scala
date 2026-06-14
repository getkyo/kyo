package kyo.internal

import kyo.AllowUnsafe
import kyo.discard
import scala.scalanative.libc.string as cstring
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Native implementation of [[UnsafeBuffer]] backed by a `Ptr[Byte]`.
  *
  * Uses pointer arithmetic for primitive get/set and `libc.memcpy` for bulk Native-to-Native copies.
  */
final private[kyo] class NativeUnsafeBuffer(
    private var ptr: Ptr[Byte],
    byteSize: Long,
    closer: () => Unit
) extends UnsafeBuffer(byteSize, closer):

    def getByte(offset: Long)(using AllowUnsafe): Byte              = !(ptr + offset)
    def setByte(offset: Long, value: Byte)(using AllowUnsafe): Unit = !(ptr + offset) = value

    def getShort(offset: Long)(using AllowUnsafe): Short              = !((ptr + offset).asInstanceOf[Ptr[Short]])
    def setShort(offset: Long, value: Short)(using AllowUnsafe): Unit = !((ptr + offset).asInstanceOf[Ptr[Short]]) = value

    def getInt(offset: Long)(using AllowUnsafe): Int              = !((ptr + offset).asInstanceOf[Ptr[Int]])
    def setInt(offset: Long, value: Int)(using AllowUnsafe): Unit = !((ptr + offset).asInstanceOf[Ptr[Int]]) = value

    def getLong(offset: Long)(using AllowUnsafe): Long              = !((ptr + offset).asInstanceOf[Ptr[Long]])
    def setLong(offset: Long, value: Long)(using AllowUnsafe): Unit = !((ptr + offset).asInstanceOf[Ptr[Long]]) = value

    def getFloat(offset: Long)(using AllowUnsafe): Float              = !((ptr + offset).asInstanceOf[Ptr[Float]])
    def setFloat(offset: Long, value: Float)(using AllowUnsafe): Unit = !((ptr + offset).asInstanceOf[Ptr[Float]]) = value

    def getDouble(offset: Long)(using AllowUnsafe): Double              = !((ptr + offset).asInstanceOf[Ptr[Double]])
    def setDouble(offset: Long, value: Double)(using AllowUnsafe): Unit = !((ptr + offset).asInstanceOf[Ptr[Double]]) = value

    def copyTo(target: UnsafeBuffer, srcOffset: Long, targetOffset: Long, bytes: Long)(using AllowUnsafe): Unit =
        target match
            case native: NativeUnsafeBuffer =>
                discard(cstring.memcpy(native.ptr + targetOffset, ptr + srcOffset, bytes.toCSize))
            case _ =>
                // Byte-by-byte fallback for cross-platform copies
                var i = 0L
                while i < bytes do
                    target.setByte(targetOffset + i, getByte(srcOffset + i))
                    i += 1
    end copyTo

    def copyToArray(arr: Array[Byte], srcOffset: Long, len: Int)(using AllowUnsafe): Unit =
        var i = 0
        while i < len do
            arr(i) = !(ptr + srcOffset + i)
            i += 1
    end copyToArray

    def view(offset: Long, byteSize: Long)(using AllowUnsafe): UnsafeBuffer =
        new NativeUnsafeBuffer(ptr + offset, byteSize, () => ())

    def raw(using AllowUnsafe): AnyRef = new NativeUnsafeBufferPtr(ptr)
end NativeUnsafeBuffer

/** Boxed wrapper around a Scala Native `Ptr[Byte]` so it can be returned as `AnyRef` from [[NativeUnsafeBuffer.raw]]. `Ptr[Byte]` is a
  * value type on Scala Native and not itself an `AnyRef`.
  */
final private[kyo] class NativeUnsafeBufferPtr(val ptr: Ptr[Byte])
