package kyo.internal

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kyo.AllowUnsafe

/** JVM implementation of [[UnsafeBuffer]] backed by a Panama `MemorySegment`. */
final private[kyo] class JvmUnsafeBuffer(
    private val seg: MemorySegment,
    byteSize: Long,
    closer: () => Unit
) extends UnsafeBuffer(byteSize, closer):

    def getByte(offset: Long)(using AllowUnsafe): Byte              = seg.get(ValueLayout.JAVA_BYTE, offset)
    def setByte(offset: Long, value: Byte)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_BYTE, offset, value)

    def getShort(offset: Long)(using AllowUnsafe): Short              = seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset)
    def setShort(offset: Long, value: Short)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, value)

    def getInt(offset: Long)(using AllowUnsafe): Int              = seg.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
    def setInt(offset: Long, value: Int)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_INT_UNALIGNED, offset, value)

    def getLong(offset: Long)(using AllowUnsafe): Long              = seg.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
    def setLong(offset: Long, value: Long)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value)

    def getFloat(offset: Long)(using AllowUnsafe): Float              = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset)
    def setFloat(offset: Long, value: Float)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset, value)

    def getDouble(offset: Long)(using AllowUnsafe): Double              = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, offset)
    def setDouble(offset: Long, value: Double)(using AllowUnsafe): Unit = seg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, offset, value)

    def copyTo(target: UnsafeBuffer, srcOffset: Long, targetOffset: Long, bytes: Long)(using AllowUnsafe): Unit =
        target match
            case jvm: JvmUnsafeBuffer =>
                MemorySegment.copy(seg, srcOffset, jvm.seg, targetOffset, bytes)
            case _ =>
                // Byte-by-byte fallback for cross-platform copies
                var i = 0L
                while i < bytes do
                    target.setByte(targetOffset + i, seg.get(ValueLayout.JAVA_BYTE, srcOffset + i))
                    i += 1
    end copyTo

    def copyToArray(arr: Array[Byte], srcOffset: Long, len: Int)(using AllowUnsafe): Unit =
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, srcOffset, arr, 0, len)

    def view(offset: Long, byteSize: Long)(using AllowUnsafe): UnsafeBuffer =
        new JvmUnsafeBuffer(seg.asSlice(offset, byteSize), byteSize, () => ())

    def raw(using AllowUnsafe): AnyRef = seg
end JvmUnsafeBuffer
