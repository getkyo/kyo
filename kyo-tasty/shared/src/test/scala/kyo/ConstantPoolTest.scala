package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileFormat
import kyo.internal.tasty.classfile.ConstantPool
import kyo.internal.tasty.symbol.Interner

/** Tests for ConstantPool.read accepting ByteView.Mapped (Phase 05c, C3).
  *
  * Pins: C3 - Utf8Lazy eagerly copies bytes from Mapped ByteView; no IllegalStateException.
  */
class ConstantPoolTest extends Test:

    private val interner = new Interner(numShards = 4, initialShardCapacity = 8)

    /** Minimal in-memory ByteView.Mapped stub backed by an Array[Byte].
      *
      * Implements all abstract methods using the backing array so tests can exercise the Mapped branch in ConstantPool.read without an
      * actual mmap file.
      */
    final private class HeapMappedStub(private val data: Array[Byte]) extends ByteView.Mapped:
        private var cursor: Long = 0L

        def peekByte(at: Long): Byte = data(at.toInt)
        def readByte(): Byte =
            val b = data(cursor.toInt)
            cursor += 1
            b
        end readByte
        def readEnd(): Long =
            val len = readNat()
            cursor + len.toLong
        def subView(from: Long, until: Long): ByteView =
            val s = new HeapMappedStub(data)
            s.goto(from)
            s
        end subView
        def goto(addr: Long): Unit = cursor = addr
        def remaining: Long        = data.length.toLong - cursor
        def position: Long         = cursor
    end HeapMappedStub

    /** Build a minimal constant pool byte stream with one UTF-8 entry encoding the given string.
      *
      * Format: u2 count=2, u1 tag=1 (CONSTANT_Utf8), u2 length, u1* bytes.
      */
    private def buildUtf8PoolBytes(s: String): Array[Byte] =
        val strBytes = s.getBytes("UTF-8")
        val len      = strBytes.length
        val buf      = new Array[Byte](2 + 1 + 2 + len)
        // count = 2 (one entry; pool slots 1..count-1, so count=2 means one real entry)
        buf(0) = 0
        buf(1) = 2
        // tag = CONSTANT_Utf8
        buf(2) = ClassfileFormat.CONSTANT_Utf8.toByte
        // length (big-endian u2)
        buf(3) = ((len >> 8) & 0xff).toByte
        buf(4) = (len & 0xff).toByte
        // UTF-8 bytes
        var i = 0
        while i < len do
            buf(5 + i) = strBytes(i)
            i += 1
        buf
    end buildUtf8PoolBytes

    // -------------------------------------------------------------------------
    // Test 1 (C3): ConstantPool.read succeeds on a Mapped ByteView; utf8 entry decoded correctly.
    // -------------------------------------------------------------------------
    "ConstantPool.read on Mapped ByteView decodes UTF-8 entry correctly" in run {
        // Given: a Mapped ByteView wrapping pool bytes with a single UTF-8 entry "foo".
        // When: ConstantPool.read is called with the Mapped view.
        // Then: utf8(1) returns "foo" without throwing IllegalStateException.
        // Pins: C3.
        val bytes = buildUtf8PoolBytes("foo")
        val view  = new HeapMappedStub(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map: result =>
            result match
                case Result.Failure(err) =>
                    fail(s"Expected success but got failure: $err")
                case Result.Panic(ex) =>
                    fail(s"Expected success but got panic: $ex")
                case Result.Success(pool) =>
                    Abort.run(pool.utf8(1)).map: r =>
                        r match
                            case Result.Success(s) => assert(s == "foo", s"Expected 'foo' but got '$s'")
                            case other             => fail(s"Expected utf8(1)='foo', got $other")
    }

    // -------------------------------------------------------------------------
    // Test 2 (C3): ConstantPool.read on Mapped ByteView preserves cursor position after read.
    // -------------------------------------------------------------------------
    "ConstantPool.read on Mapped ByteView: cursor is positioned past pool after read" in run {
        // Given: Mapped ByteView with cursor initially at 0; pool bytes for "bar".
        // When: ConstantPool.read is called.
        // Then: the view cursor is positioned at the end of the pool (no cursor corruption).
        // Pins: C3.
        val bytes = buildUtf8PoolBytes("bar")
        val view  = new HeapMappedStub(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map: result =>
            result match
                case Result.Failure(err) =>
                    fail(s"Expected success but got failure: $err")
                case Result.Panic(ex) =>
                    fail(s"Expected success but got panic: $ex")
                case Result.Success(_) =>
                    // cursor should be at end of the pool bytes
                    assert(
                        view.position == bytes.length.toLong,
                        s"Expected cursor at ${bytes.length} but got ${view.position}"
                    )
    }

end ConstantPoolTest
