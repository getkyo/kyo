package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileFormat
import kyo.internal.tasty.classfile.ConstantPool

/** Tests for ConstantPool: entry type validation, ClassRef resolution, and structured errors on malformed input. */
class ConstantPoolTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Minimal in-memory ByteView.Mapped stub backed by an Array[Byte].
      *
      * Implements all abstract methods using the backing array so tests can exercise the Mapped branch in ConstantPool.read without an
      * actual mmap file.
      */
    final private class HeapMappedStub(private val data: Array[Byte]) extends ByteView.Mapped(
            new java.util.concurrent.atomic.AtomicBoolean(false),
            0L,
            data.length.toLong
        ):
        def peekByte(at: Long): Byte = data(at.toInt)
        def readByte()(using AllowUnsafe): Byte =
            val b = data(cursor.toInt)
            cursor += 1
            b
        end readByte
        def subView(from: Long, until: Long): ByteView =
            val s = new HeapMappedStub(data)
            s.goto(from)
            s
        end subView
    end HeapMappedStub

    /** Build a minimal constant pool byte stream with one UTF-8 entry encoding the given string.
      *
      * Format: u2 count=2, u1 tag=1 (CONSTANT_Utf8), u2 length, u1* bytes.
      */
    private def buildUtf8PoolBytes(s: String): Array[Byte] =
        val strBytes = s.getBytes("UTF-8")
        val len      = strBytes.length
        val buffer   = new Array[Byte](2 + 1 + 2 + len)
        // count = 2 (one entry; pool slots 1.count-1, so count=2 means one real entry)
        buffer(0) = 0
        buffer(1) = 2
        // tag = CONSTANT_Utf8
        buffer(2) = ClassfileFormat.CONSTANT_Utf8.toByte
        // length (big-endian u2)
        buffer(3) = ((len >> 8) & 0xff).toByte
        buffer(4) = (len & 0xff).toByte
        // UTF-8 bytes
        var i = 0
        while i < len do
            buffer(5 + i) = strBytes(i)
            i += 1
        buffer
    end buildUtf8PoolBytes

    /** Build a pool with two entries: slot 1 = ClassRef(nameIdx=2), slot 2 = Utf8(s).
      *
      * count=3, entry[1]=ClassRef(nameIdx=2), entry[2]=Utf8(s). Format: u2 count=3 u1 tag=7 (CONSTANT_Class), u2 nameIdx=2 u1 tag=1
      * (CONSTANT_Utf8), u2 len, u1* bytes
      */
    private def buildClassRefThenUtf8Bytes(s: String): Array[Byte] =
        val strBytes = s.getBytes("UTF-8")
        val len      = strBytes.length
        // 2 (count) + 3 (ClassRef) + 1+2+len (Utf8)
        val buffer = new Array[Byte](2 + 3 + 1 + 2 + len)
        // count = 3
        buffer(0) = 0
        buffer(1) = 3
        // entry[1]: CONSTANT_Class, nameIdx=2
        buffer(2) = ClassfileFormat.CONSTANT_Class.toByte
        buffer(3) = 0
        buffer(4) = 2
        // entry[2]: CONSTANT_Utf8
        buffer(5) = ClassfileFormat.CONSTANT_Utf8.toByte
        buffer(6) = ((len >> 8) & 0xff).toByte
        buffer(7) = (len & 0xff).toByte
        var i = 0
        while i < len do
            buffer(8 + i) = strBytes(i)
            i += 1
        buffer
    end buildClassRefThenUtf8Bytes

    /** Build a pool with one Long entry: slot 1 = Long(value), slot 2 = Hole.
      *
      * count=3 (slots 1 and 2 consumed by one Long). Format: u2 count=3 u1 tag=5 (CONSTANT_Long), u8 value
      */
    private def buildLongPoolBytes(value: Long): Array[Byte] =
        // 2 (count) + 1 (tag) + 8 (value)
        val buffer = new Array[Byte](2 + 1 + 8)
        buffer(0) = 0
        buffer(1) = 3
        buffer(2) = ClassfileFormat.CONSTANT_Long.toByte
        // big-endian 8 bytes
        buffer(3) = ((value >>> 56) & 0xff).toByte
        buffer(4) = ((value >>> 48) & 0xff).toByte
        buffer(5) = ((value >>> 40) & 0xff).toByte
        buffer(6) = ((value >>> 32) & 0xff).toByte
        buffer(7) = ((value >>> 24) & 0xff).toByte
        buffer(8) = ((value >>> 16) & 0xff).toByte
        buffer(9) = ((value >>> 8) & 0xff).toByte
        buffer(10) = (value & 0xff).toByte
        buffer
    end buildLongPoolBytes

    "ConstantPool.read on Mapped ByteView decodes UTF-8 entry correctly" in {
        val bytes = buildUtf8PoolBytes("foo")
        val view  = new HeapMappedStub(bytes)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) =>
                fail(s"Expected success but got failure: $err")
            case Result.Panic(ex) =>
                fail(s"Expected success but got panic: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(1)).map { r =>
                    r match
                        case Result.Success(s) => assert(s == "foo", s"Expected 'foo' but got '$s'")
                        case other             => fail(s"Expected utf8(1)='foo', got $other")
                }
        end match
    }

    "ConstantPool.read on Mapped ByteView: cursor is positioned past pool after read" in {
        val bytes = buildUtf8PoolBytes("bar")
        val view  = new HeapMappedStub(bytes)
        ConstantPool.read(view, "<test>") match
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
        end match
    }

    "utf8 at a ClassRef index yields structured error naming ClassRef" in {
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(1)).map {
                    case Result.Success(s) =>
                        fail(s"Expected failure but utf8(1) returned '$s'")
                    case Result.Panic(ex) =>
                        fail(s"Expected Failure but got Panic: $ex")
                    case Result.Failure(TastyError.ClassfileFormatError(_, msg, _)) =>
                        assert(msg.contains("Utf8"), s"Error message should mention 'Utf8': $msg")
                        assert(msg.contains("ClassRef"), s"Error message should mention 'ClassRef': $msg")
                    case Result.Failure(other) =>
                        fail(s"Unexpected error type: $other")
                }
        end match
    }

    "classRef resolving through nameIdx returns the Utf8 string" in {
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(1)).map {
                    case Result.Success(name) =>
                        assert(name == "scala/Int", s"Expected 'scala/Int' but got '$name'")
                    case other =>
                        fail(s"Expected success but got $other")
                }
        end match
    }

    "classRef at a Utf8 index yields structured error naming Utf8" in {
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(2)).map {
                    case Result.Success(name) =>
                        fail(s"Expected failure but classRef(2) returned '$name'")
                    case Result.Panic(ex) =>
                        fail(s"Expected Failure but got Panic: $ex")
                    case Result.Failure(TastyError.ClassfileFormatError(_, msg, _)) =>
                        assert(msg.contains("ClassRef"), s"Error message should mention 'ClassRef': $msg")
                        assert(msg.contains("Utf8"), s"Error message should mention 'Utf8': $msg")
                    case Result.Failure(other) =>
                        fail(s"Unexpected error type: $other")
                }
        end match
    }

    "entry at an out-of-range index yields structured ClassfileFormatError containing index and 'out of bounds'" in {
        val entries = Array("a", "b", "c", "d")
        // Build: u2 count=5, then 4x (u1 tag=1, u2 len, u1* bytes)
        val bufs      = entries.map(_.getBytes("UTF-8"))
        val totalSize = 2 + bufs.foldLeft(0)((acc, b) => acc + 1 + 2 + b.length)
        val buffer    = new Array[Byte](totalSize)
        buffer(0) = 0
        buffer(1) = 5 // count=5 => slots 1..4
        var pos = 2
        bufs.foreach { bs =>
            buffer(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
            buffer(pos + 1) = ((bs.length >> 8) & 0xff).toByte
            buffer(pos + 2) = (bs.length & 0xff).toByte
            var i = 0
            while i < bs.length do
                buffer(pos + 3 + i) = bs(i)
                i += 1
            pos += 1 + 2 + bs.length
        }
        val view = ByteView(buffer)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(99)).map {
                    case Result.Success(s) =>
                        fail(s"Expected failure but utf8(99) returned '$s'")
                    case Result.Panic(ex) =>
                        fail(s"Expected Failure but got Panic: $ex")
                    case Result.Failure(TastyError.ClassfileFormatError(_, msg, _)) =>
                        assert(msg.contains("99"), s"Error message should contain index 99: $msg")
                        assert(
                            msg.toLowerCase.contains("out of bounds") || msg.toLowerCase.contains("out of range"),
                            s"Error message should mention 'out of bounds' or 'out of range': $msg"
                        )
                    case Result.Failure(other) =>
                        fail(s"Unexpected error type: $other")
                }
        end match
    }

    "ClassRef at slot 5 with nameIdx=6 resolves to the Utf8 string at slot 6" in {
        val padding = Array("x", "y", "z", "w")
        val target  = "scala/Int"
        val padBufs = padding.map(_.getBytes("UTF-8"))
        val tgtBuf  = target.getBytes("UTF-8")
        // Layout: u2 count=7, entries[1.4]=Utf8 padding, entry[5]=ClassRef(nameIdx=6), entry[6]=Utf8("scala/Int")
        // entry[5]: u1 tag=7, u2 nameIdx=6 -> 3 bytes
        // entry[6]: u1 tag=1, u2 len, u1* bytes -> 1+2+tgtBuf.length bytes
        val totalSize = 2 + padBufs.foldLeft(0)((acc, b) => acc + 1 + 2 + b.length) + 3 + (1 + 2 + tgtBuf.length)
        val buffer    = new Array[Byte](totalSize)
        buffer(0) = 0
        buffer(1) = 7 // count=7 => slots 1..6
        var pos = 2
        padBufs.foreach { bs =>
            buffer(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
            buffer(pos + 1) = ((bs.length >> 8) & 0xff).toByte
            buffer(pos + 2) = (bs.length & 0xff).toByte
            var i = 0
            while i < bs.length do
                buffer(pos + 3 + i) = bs(i)
                i += 1
            pos += 1 + 2 + bs.length
        }
        // entry[5]: ClassRef(nameIdx=6)
        buffer(pos) = ClassfileFormat.CONSTANT_Class.toByte
        buffer(pos + 1) = 0
        buffer(pos + 2) = 6
        pos += 3
        // entry[6]: Utf8("scala/Int")
        buffer(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
        buffer(pos + 1) = ((tgtBuf.length >> 8) & 0xff).toByte
        buffer(pos + 2) = (tgtBuf.length & 0xff).toByte
        var i = 0
        while i < tgtBuf.length do
            buffer(pos + 3 + i) = tgtBuf(i)
            i += 1
        val view = ByteView(buffer)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(5)).map {
                    case Result.Success(name) =>
                        assert(name == "scala/Int", s"Expected 'scala/Int' but got '$name'")
                    case other =>
                        fail(s"Expected classRef(5)='scala/Int', got $other")
                }
        end match
    }

    "utf8 at a Long/Double Hole slot yields structured error" in {
        val bytes = buildLongPoolBytes(42L)
        val view  = ByteView(bytes)
        ConstantPool.read(view, "<test>") match
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(2)).map {
                    case Result.Success(s) =>
                        fail(s"Expected failure but utf8(2) returned '$s'")
                    case Result.Panic(ex) =>
                        fail(s"Expected Failure but got Panic: $ex")
                    case Result.Failure(TastyError.ClassfileFormatError(_, msg, _)) =>
                        assert(
                            msg.toLowerCase.contains("hole") || msg.toLowerCase.contains("long/double"),
                            s"Error message should mention Hole or Long/Double: $msg"
                        )
                    case Result.Failure(other) =>
                        fail(s"Unexpected error type: $other")
                }
        end match
    }

end ConstantPoolTest
