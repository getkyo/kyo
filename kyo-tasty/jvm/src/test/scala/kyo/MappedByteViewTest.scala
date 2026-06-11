package kyo

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kyo.internal.tasty.binary.MappedByteView

/** Tests for MappedByteView Long-typed cursor APIs.
  *
  * MappedByteView.position and related cursor methods return Long after the 64-bit widening. These tests pin that contract and verify the
  * overflow guard in readByte.
  */
class MappedByteViewTest extends kyo.test.Test[Any]:

    /** Create a small temp file backed by a 1-byte mmap, wrapped in a MappedByteView with the given logical end.
      *
      * The view cursor starts at startAddr (set via goto after construction). Caller is responsible for closing the file resources.
      */
    private def makeView(logicalEnd: Long, startCursor: Long)(using AllowUnsafe): (MappedByteView, () => Unit) =
        val tmp = Files.createTempFile("MappedByteViewTest", ".bin")
        tmp.toFile.deleteOnExit()
        Files.write(tmp, Array[Byte](0x42.toByte))
        val raf     = new RandomAccessFile(tmp.toFile, "r")
        val channel = raf.getChannel
        val buffer  = channel.map(FileChannel.MapMode.READ_ONLY, 0, 1)
        val closed  = new AtomicBoolean(false)
        val view    = new MappedByteView(buffer, 0L, logicalEnd, closed)
        view.goto(startCursor)
        val cleanup = () =>
            channel.close()
            raf.close()
        (view, cleanup)
    end makeView

    "MappedByteViewTest: position is Long-typed after goto with cursor beyond Int.MaxValue".onlyJvm in {
        import AllowUnsafe.embrace.danger
        val (view, cleanup) = makeView(5_000_000_000L, 3_000_000_000L)
        try
            assert(view.position == 3_000_000_000L)
        finally cleanup()
    }

    "MappedByteViewTest: readByte past Int.MaxValue raises IllegalStateException with mmap segment overflow".onlyJvm in {
        import AllowUnsafe.embrace.danger
        val (view, cleanup) = makeView(Int.MaxValue.toLong + 2L, Int.MaxValue.toLong + 1L)
        try
            val ex = intercept[IllegalStateException](view.readByte())
            assert(ex.getMessage.contains("mmap segment overflow"))
        finally cleanup()
        end try
    }

end MappedByteViewTest
