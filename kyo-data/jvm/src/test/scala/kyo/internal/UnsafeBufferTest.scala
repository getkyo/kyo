package kyo.internal

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import kyo.AllowUnsafe

class UnsafeBufferTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "alloc" - {
        "write and read all primitive types" in {
            val buf = UnsafeBuffer.alloc(64)
            try
                buf.setByte(0, 42.toByte)
                assert(buf.getByte(0) == 42.toByte)

                buf.setShort(2, 1234.toShort)
                assert(buf.getShort(2) == 1234.toShort)

                buf.setInt(4, 123456)
                assert(buf.getInt(4) == 123456)

                buf.setLong(8, 123456789L)
                assert(buf.getLong(8) == 123456789L)

                buf.setFloat(16, 3.14f)
                assert(buf.getFloat(16) == 3.14f)

                buf.setDouble(24, 2.71828)
                assert(buf.getDouble(24) == 2.71828)
                succeed
            finally buf.close()
            end try
        }

        "isClosed before and after close" in {
            val buf = UnsafeBuffer.alloc(16)
            assert(!buf.isClosed)
            buf.close()
            assert(buf.isClosed)
            succeed
        }

        "double close is idempotent" in {
            val buf = UnsafeBuffer.alloc(16)
            buf.close()
            buf.close() // should not throw
            assert(buf.isClosed)
            succeed
        }
    }

    "allocConfined" - {
        "basic read and write" in {
            val buf = UnsafeBuffer.allocConfined(32)
            try
                buf.setInt(0, 42)
                assert(buf.getInt(0) == 42)
                buf.setLong(8, 99L)
                assert(buf.getLong(8) == 99L)
                succeed
            finally buf.close()
            end try
        }
    }

    "fromArray" - {
        "wraps byte array and reads back values" in {
            val arr = Array[Byte](1, 2, 3, 4, 5)
            val buf = UnsafeBuffer.fromArray(arr)
            assert(buf.byteSize == 5L)
            assert(buf.getByte(0) == 1.toByte)
            assert(buf.getByte(4) == 5.toByte)
            succeed
        }
    }

    "fromUtf8" - {
        "NUL-terminated with correct byteSize" in {
            val buf = UnsafeBuffer.fromUtf8("hello")
            try
                assert(buf.byteSize == 6L) // 5 chars + NUL
                assert(buf.getByte(0) == 'h'.toByte)
                assert(buf.getByte(4) == 'o'.toByte)
                assert(buf.getByte(5) == 0.toByte) // NUL terminator
                succeed
            finally buf.close()
            end try
        }
    }

    "copyTo" - {
        "between two buffers" in {
            val src = UnsafeBuffer.alloc(16)
            val dst = UnsafeBuffer.alloc(16)
            try
                src.setInt(0, 42)
                src.setInt(4, 99)
                src.copyTo(dst, 0, 0, 8)
                assert(dst.getInt(0) == 42)
                assert(dst.getInt(4) == 99)
                succeed
            finally
                src.close()
                dst.close()
            end try
        }
    }

    "copyToArray" - {
        "copy to byte array" in {
            val buf = UnsafeBuffer.alloc(4)
            try
                buf.setByte(0, 10)
                buf.setByte(1, 20)
                buf.setByte(2, 30)
                buf.setByte(3, 40)
                val arr = new Array[Byte](4)
                buf.copyToArray(arr, 0, 4)
                assert(arr(0) == 10.toByte)
                assert(arr(1) == 20.toByte)
                assert(arr(2) == 30.toByte)
                assert(arr(3) == 40.toByte)
                succeed
            finally buf.close()
            end try
        }
    }

    "view" - {
        "creates a sub-view sharing the same memory" in {
            val buf = UnsafeBuffer.alloc(32)
            try
                buf.setInt(0, 10)
                buf.setInt(4, 20)
                buf.setInt(8, 30)
                buf.setInt(12, 40)
                val v = buf.view(8, 16)
                assert(v.byteSize == 16L)
                assert(v.getInt(0) == 30)
                assert(v.getInt(4) == 40)
                // writes to view visible in original
                v.setInt(0, 99)
                assert(buf.getInt(8) == 99)
                succeed
            finally buf.close()
            end try
        }

        "view with zero offset gives same data" in {
            val buf = UnsafeBuffer.alloc(8)
            try
                buf.setLong(0, 12345L)
                val v = buf.view(0, 8)
                assert(v.getLong(0) == 12345L)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Byte]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Byte]]
            val buf    = UnsafeBuffer.alloc(8)
            try
                layout.write(buf, 0, 42.toByte)
                assert(layout.read(buf, 0) == 42.toByte)
                assert(layout.size == 1)
                assert(layout.alignment == 1)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Short]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Short]]
            val buf    = UnsafeBuffer.alloc(8)
            try
                layout.write(buf, 0, 1234.toShort)
                assert(layout.read(buf, 0) == 1234.toShort)
                assert(layout.size == 2)
                assert(layout.alignment == 2)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Int]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Int]]
            val buf    = UnsafeBuffer.alloc(8)
            try
                layout.write(buf, 0, 123456)
                assert(layout.read(buf, 0) == 123456)
                assert(layout.size == 4)
                assert(layout.alignment == 4)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Long]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Long]]
            val buf    = UnsafeBuffer.alloc(16)
            try
                layout.write(buf, 0, 123456789L)
                assert(layout.read(buf, 0) == 123456789L)
                assert(layout.size == 8)
                assert(layout.alignment == 8)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Float]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Float]]
            val buf    = UnsafeBuffer.alloc(8)
            try
                layout.write(buf, 0, 3.14f)
                assert(layout.read(buf, 0) == 3.14f)
                assert(layout.size == 4)
                assert(layout.alignment == 4)
                succeed
            finally buf.close()
            end try
        }
    }

    "UnsafeLayout[Double]" - {
        "read and write via layout" in {
            val layout = summon[UnsafeLayout[Double]]
            val buf    = UnsafeBuffer.alloc(16)
            try
                layout.write(buf, 0, 2.71828)
                assert(layout.read(buf, 0) == 2.71828)
                assert(layout.size == 8)
                assert(layout.alignment == 8)
                succeed
            finally buf.close()
            end try
        }
    }

    "BorrowOwner" - {
        "isValid and revoke" in {
            val owner = new BorrowOwner("test-owner")
            assert(owner.isValid)
            assert(owner.label == "test-owner")
            owner.revoke()
            assert(!owner.isValid)
            succeed
        }

        "BorrowRevoked exception" in {
            val ex = new BorrowRevoked("my-owner")
            assert(ex.getMessage == "Borrowed buffer revoked: my-owner")
            succeed
        }
    }

    "mmapReadOnly" - {
        "write file then mmap and read back" in {
            val tmpFile = Files.createTempFile("unsafebuf-test-", ".bin")
            try
                val data = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
                Files.write(tmpFile, data)
                val buf = UnsafeBuffer.mmapReadOnly(tmpFile.toString)
                try
                    assert(buf.byteSize == 8L)
                    assert(buf.getByte(0) == 1.toByte)
                    assert(buf.getByte(7) == 8.toByte)
                    succeed
                finally buf.close()
                end try
            finally Files.deleteIfExists(tmpFile): Unit
            end try
        }
    }

    "mmapReadWrite" - {
        "mmap, write, read back" in {
            val tmpFile = Files.createTempFile("unsafebuf-test-rw-", ".bin")
            try
                // Write initial data
                val data = new Array[Byte](16)
                Files.write(tmpFile, data)
                val buf = UnsafeBuffer.mmapReadWrite(tmpFile.toString)
                try
                    buf.setInt(0, 42)
                    buf.setInt(4, 99)
                    assert(buf.getInt(0) == 42)
                    assert(buf.getInt(4) == 99)
                    succeed
                finally buf.close()
                end try
            finally Files.deleteIfExists(tmpFile): Unit
            end try
        }
    }

    "wrapBorrowed" - {
        "wrap MemorySegment and read/write" in {
            val arena = Arena.ofShared()
            try
                val seg = arena.allocate(16)
                seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 0, 42)
                val buf = UnsafeBuffer.wrapBorrowed(seg, 16)
                assert(buf.getInt(0) == 42)
                buf.setInt(4, 99)
                assert(buf.getInt(4) == 99)
                succeed
            finally arena.close()
            end try
        }
    }

    "raw" - {
        "returns the MemorySegment" in {
            val buf = UnsafeBuffer.alloc(16)
            try
                val rawRef = buf.raw
                assert(rawRef.isInstanceOf[MemorySegment])
                succeed
            finally buf.close()
            end try
        }
    }

    "error paths" - {
        "mmapReadOnly on nonexistent file throws IOException" in {
            intercept[java.io.IOException] {
                UnsafeBuffer.mmapReadOnly("/nonexistent/path/file.bin")
            }
            succeed
        }

        "wrapBorrowed with wrong type throws" in {
            intercept[IllegalArgumentException] {
                UnsafeBuffer.wrapBorrowed("not a MemorySegment", 16)
            }
            succeed
        }

        "fromUtf8 empty string produces single NUL byte" in {
            val buf = UnsafeBuffer.fromUtf8("")
            try
                assert(buf.byteSize == 1L)
                assert(buf.getByte(0) == 0.toByte)
                succeed
            finally buf.close()
            end try
        }
    }

end UnsafeBufferTest
