package kyo.ffi

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kyo.discard

/** Cross-platform tests for [[Buffer.mmapReadOnly]] and [[Buffer.mmapReadWrite]].
  *
  * Lives in the `jvm-native` source set because it uses `java.io.File` for temp file creation, which is available on JVM and Scala Native
  * but not on Scala.js.
  */
class BufferMmapTest extends Test:

    private def withTempFile[A](content: Array[Byte])(f: File => A): A =
        val file = File.createTempFile("kyo-mmap-test-", ".bin")
        file.deleteOnExit()
        try
            val fos = new FileOutputStream(file)
            try fos.write(content)
            finally fos.close()
            f(file)
        finally
            discard(file.delete())
        end try
    end withTempFile

    "mmapReadOnly" - {

        "reads file content correctly" in {
            val content = Array[Byte](0x41, 0x42, 0x43, 0x44) // "ABCD"
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath)
                try
                    assert(buf.size == 4)
                    assert(buf.get(0) == 0x41.toByte)
                    assert(buf.get(1) == 0x42.toByte)
                    assert(buf.get(2) == 0x43.toByte)
                    assert(buf.get(3) == 0x44.toByte)
                finally buf.close()
                end try
            }
        }

        "with offset skips initial bytes" in {
            val content = Array[Byte](10, 20, 30, 40, 50, 60, 70, 80)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath, offset = 3)
                try
                    assert(buf.size == 5)
                    assert(buf.get(0) == 40.toByte)
                    assert(buf.get(4) == 80.toByte)
                finally buf.close()
                end try
            }
        }

        "with size limits mapping" in {
            val content = (0 until 100).map(_.toByte).toArray
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath, offset = 0, size = 10)
                try
                    assert(buf.size == 10)
                    assert(buf.get(0) == 0.toByte)
                    assert(buf.get(9) == 9.toByte)
                finally buf.close()
                end try
            }
        }

        "with offset and size maps sub-region" in {
            val content = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath, offset = 2, size = 4)
                try
                    assert(buf.size == 4)
                    assert(buf.get(0) == 2.toByte)
                    assert(buf.get(3) == 5.toByte)
                finally buf.close()
                end try
            }
        }

        "size=-1 maps entire file" in {
            val content = Array[Byte](11, 22, 33, 44, 55)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath, offset = 0, size = -1)
                try
                    assert(buf.size == 5)
                    (0 until 5).foreach { i =>
                        assert(buf.get(i) == content(i))
                    }
                    succeed
                finally buf.close()
                end try
            }
        }

        "close releases the mapping" in {
            val content = Array[Byte](1, 2, 3)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath)
                assert(buf.get(0) == 1.toByte)
                buf.close()
                interceptThrown[IllegalStateException](buf.get(0))
            }
        }

        "non-existent file throws IOException" in {
            interceptThrown[Exception] {
                Buffer.mmapReadOnly("/nonexistent/path/to/file.bin")
            }
        }

        "empty file maps to size 0" in {
            withTempFile(Array.empty[Byte]) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath)
                try
                    assert(buf.size == 0)
                finally buf.close()
                end try
            }
        }

        "large file (1MB) mmap works" in {
            val size    = 1024 * 1024
            val content = new Array[Byte](size)
            (0 until size).foreach { i =>
                content(i) = (i % 251).toByte
            }
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadOnly(file.getAbsolutePath)
                try
                    assert(buf.size == size)
                    assert(buf.get(0) == content(0))
                    assert(buf.get(size / 2) == content(size / 2))
                    assert(buf.get(size - 1) == content(size - 1))
                    val rng = new java.util.Random(42)
                    (0 until 100).foreach { _ =>
                        val idx = rng.nextInt(size)
                        assert(buf.get(idx) == content(idx))
                    }
                    succeed
                finally buf.close()
                end try
            }
        }
    }

    "mmapReadWrite" - {

        "allows writing and reading back" in {
            val content = Array[Byte](10, 20, 30, 40)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadWrite(file.getAbsolutePath)
                try
                    assert(buf.get(0) == 10.toByte)
                    buf.set(0, 0xff.toByte)
                    assert(buf.get(0) == 0xff.toByte)
                finally buf.close()
                end try
            }
        }

        "written bytes are persisted to file" in {
            val content = Array[Byte](1, 2, 3, 4)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadWrite(file.getAbsolutePath)
                buf.set(0, 99.toByte)
                buf.set(3, 88.toByte)
                buf.close()

                val raf = new RandomAccessFile(file, "r")
                try
                    val bytes = new Array[Byte](4)
                    raf.readFully(bytes)
                    assert(bytes(0) == 99.toByte)
                    assert(bytes(1) == 2.toByte)
                    assert(bytes(2) == 3.toByte)
                    assert(bytes(3) == 88.toByte)
                finally raf.close()
                end try
            }
        }

        "close releases the mapping" in {
            val content = Array[Byte](1, 2, 3)
            withTempFile(content) { file =>
                val buf = Buffer.mmapReadWrite(file.getAbsolutePath)
                buf.set(0, 42.toByte)
                buf.close()
                interceptThrown[IllegalStateException](buf.get(0))
            }
        }
    }
end BufferMmapTest
