package kyo.internal

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kyo.discard

/** JVM factory implementations for [[UnsafeBuffer]]. */
private[kyo] object UnsafeBufferPlatform:

    def alloc(byteSize: Long): UnsafeBuffer =
        val arena = Arena.ofShared()
        try
            val seg = arena.allocate(byteSize)
            new JvmUnsafeBuffer(seg, byteSize, () => arena.close())
        catch
            case t: Throwable =>
                arena.close()
                throw t
        end try
    end alloc

    def allocConfined(byteSize: Long): UnsafeBuffer =
        val arena = Arena.ofConfined()
        try
            val seg = arena.allocate(byteSize)
            new JvmUnsafeBuffer(seg, byteSize, () => arena.close())
        catch
            case t: Throwable =>
                arena.close()
                throw t
        end try
    end allocConfined

    def fromArray(arr: Array[Byte]): UnsafeBuffer =
        val seg = MemorySegment.ofArray(arr)
        new JvmUnsafeBuffer(seg, arr.length.toLong, () => ())
    end fromArray

    def fromUtf8(s: String): UnsafeBuffer =
        val bytes     = s.getBytes(StandardCharsets.UTF_8)
        val totalSize = bytes.length.toLong + 1 // NUL terminator
        val arena     = Arena.ofShared()
        try
            val seg = arena.allocate(totalSize)
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, seg, 0L, bytes.length.toLong)
            seg.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0.toByte) // NUL terminator
            new JvmUnsafeBuffer(seg, totalSize, () => arena.close())
        catch
            case t: Throwable =>
                arena.close()
                throw t
        end try
    end fromUtf8

    def mmapReadOnly(path: String, offset: Long, size: Long): UnsafeBuffer =
        mmapImpl(path, offset, size, readOnly = true)

    def mmapReadWrite(path: String, offset: Long, size: Long): UnsafeBuffer =
        mmapImpl(path, offset, size, readOnly = false)

    private def mmapImpl(path: String, offset: Long, size: Long, readOnly: Boolean): UnsafeBuffer =
        val jpath = Path.of(path)
        val openOptions =
            if readOnly then Array(StandardOpenOption.READ)
            else Array(StandardOpenOption.READ, StandardOpenOption.WRITE)
        val channel = FileChannel.open(jpath, openOptions*)
        try
            val fileSize = channel.size()
            val mapSize  = if size < 0 then fileSize - offset else size
            if mapSize == 0 then
                val closer: () => Unit = () => channel.close()
                new JvmUnsafeBuffer(MemorySegment.NULL, 0L, closer)
            else
                val mode = if readOnly then FileChannel.MapMode.READ_ONLY else FileChannel.MapMode.READ_WRITE
                val mbb  = channel.map(mode, offset, mapSize)
                val seg  = MemorySegment.ofBuffer(mbb)
                val closer: () => Unit =
                    if readOnly then
                        channel.close()
                        () => () // channel already closed, mapping unmapped on GC
                    else
                        () =>
                            try
                                discard(mbb.force())
                            finally channel.close()
                new JvmUnsafeBuffer(seg, mapSize, closer)
            end if
        catch
            case e: Exception =>
                channel.close()
                throw e
        end try
    end mmapImpl

    def wrapBorrowed(raw: AnyRef, byteSize: Long): UnsafeBuffer =
        raw match
            case seg: MemorySegment =>
                val sized =
                    if seg.byteSize() == 0L then seg.reinterpret(byteSize).nn
                    else seg
                new JvmUnsafeBuffer(sized, byteSize, () => ())
            case other =>
                throw new IllegalArgumentException(
                    s"wrapBorrowed expects a MemorySegment on JVM, got: ${other.getClass.getName}"
                )
    end wrapBorrowed

end UnsafeBufferPlatform
