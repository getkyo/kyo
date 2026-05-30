package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileFormat
import kyo.internal.tasty.classfile.ConstantPool
import kyo.internal.tasty.symbol.Interner

/** Tests for ConstantPool constant pool entry validation (Phase 05c C3, Phase 09 B5).
  *
  * Pins: C3 - Utf8Lazy eagerly copies bytes from Mapped ByteView; no IllegalStateException. B5 - Typed accessors validate cross-entry
  * reference kinds; malformed pool produces structured errors.
  */
class ConstantPoolTest extends Test:

    import AllowUnsafe.embrace.danger

    private val interner = Interner.init(numShards = 4, initialShardCapacity = 8)

    /** Minimal in-memory ByteView.Mapped stub backed by an Array[Byte].
      *
      * Implements all abstract methods using the backing array so tests can exercise the Mapped branch in ConstantPool.read without an
      * actual mmap file.
      */
    final private class HeapMappedStub(private val data: Array[Byte]) extends ByteView.Mapped:
        private var cursor: Long = 0L

        def peekByte(at: Long): Byte = data(at.toInt)
        def readByte()(using AllowUnsafe): Byte =
            val b = data(cursor.toInt)
            cursor += 1
            b
        end readByte
        def readEnd()(using AllowUnsafe): Long =
            val len = readNat()
            cursor + len.toLong
        def subView(from: Long, until: Long): ByteView =
            val s = new HeapMappedStub(data)
            s.goto(from)
            s
        end subView
        def goto(addr: Long)(using AllowUnsafe): Unit = cursor = addr
        def remaining: Long                           = data.length.toLong - cursor
        def position: Long                            = cursor
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

    /** Build a pool with two entries: slot 1 = ClassRef(nameIdx=2), slot 2 = Utf8(s).
      *
      * count=3, entry[1]=ClassRef(nameIdx=2), entry[2]=Utf8(s). Format: u2 count=3 u1 tag=7 (CONSTANT_Class), u2 nameIdx=2 u1 tag=1
      * (CONSTANT_Utf8), u2 len, u1* bytes
      */
    private def buildClassRefThenUtf8Bytes(s: String): Array[Byte] =
        val strBytes = s.getBytes("UTF-8")
        val len      = strBytes.length
        // 2 (count) + 3 (ClassRef) + 1+2+len (Utf8)
        val buf = new Array[Byte](2 + 3 + 1 + 2 + len)
        // count = 3
        buf(0) = 0
        buf(1) = 3
        // entry[1]: CONSTANT_Class, nameIdx=2
        buf(2) = ClassfileFormat.CONSTANT_Class.toByte
        buf(3) = 0
        buf(4) = 2
        // entry[2]: CONSTANT_Utf8
        buf(5) = ClassfileFormat.CONSTANT_Utf8.toByte
        buf(6) = ((len >> 8) & 0xff).toByte
        buf(7) = (len & 0xff).toByte
        var i = 0
        while i < len do
            buf(8 + i) = strBytes(i)
            i += 1
        buf
    end buildClassRefThenUtf8Bytes

    /** Build a pool with one Long entry: slot 1 = Long(value), slot 2 = Hole.
      *
      * count=3 (slots 1 and 2 consumed by one Long). Format: u2 count=3 u1 tag=5 (CONSTANT_Long), u8 value
      */
    private def buildLongPoolBytes(value: Long): Array[Byte] =
        // 2 (count) + 1 (tag) + 8 (value)
        val buf = new Array[Byte](2 + 1 + 8)
        buf(0) = 0
        buf(1) = 3
        buf(2) = ClassfileFormat.CONSTANT_Long.toByte
        // big-endian 8 bytes
        buf(3) = ((value >>> 56) & 0xff).toByte
        buf(4) = ((value >>> 48) & 0xff).toByte
        buf(5) = ((value >>> 40) & 0xff).toByte
        buf(6) = ((value >>> 32) & 0xff).toByte
        buf(7) = ((value >>> 24) & 0xff).toByte
        buf(8) = ((value >>> 16) & 0xff).toByte
        buf(9) = ((value >>> 8) & 0xff).toByte
        buf(10) = (value & 0xff).toByte
        buf
    end buildLongPoolBytes

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

    // -------------------------------------------------------------------------
    // Phase 09 tests (B5): typed accessor validates entry kind; structured errors on mismatch.
    // -------------------------------------------------------------------------

    // Test B5-1: utf8(idx) rejects a ClassRef entry; error message names the found kind.
    "utf8 at a ClassRef index yields structured error naming ClassRef" in run {
        // Given: pool with entry[1]=ClassRef(nameIdx=2), entry[2]=Utf8("scala/Int").
        // When: utf8(1) is called.
        // Then: Abort[TastyError] with message containing "expected Utf8" and "ClassRef".
        // Pins: B5.
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(1)).map:
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

    // Test B5-2: classRef resolves ClassRef.nameIdx to Utf8 string.
    "classRef resolving through nameIdx returns the Utf8 string" in run {
        // Given: pool with entry[1]=ClassRef(nameIdx=2), entry[2]=Utf8("scala/Int").
        // When: classRef(1) is called.
        // Then: returns "scala/Int".
        // Pins: B5.
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(1)).map:
                    case Result.Success(name) =>
                        assert(name == "scala/Int", s"Expected 'scala/Int' but got '$name'")
                    case other =>
                        fail(s"Expected success but got $other")
    }

    // Test B5-3: utf8At the Utf8 slot succeeds; classRef(idx) where idx points to Utf8 yields structured error naming Utf8.
    "classRef at a Utf8 index yields structured error naming Utf8" in run {
        // Given: pool with entry[1]=ClassRef(nameIdx=2), entry[2]=Utf8("scala/Int").
        // When: classRef(2) is called (slot 2 is Utf8, not ClassRef).
        // Then: Abort[TastyError] with message containing "ClassRef" and "Utf8".
        // Pins: B5.
        val bytes = buildClassRefThenUtf8Bytes("scala/Int")
        val view  = ByteView(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(2)).map:
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

    // Test T2-1: entry() on an out-of-range index yields a structured ClassfileFormatError.
    "entry at an out-of-range index yields structured ClassfileFormatError containing index and 'out of bounds'" in run {
        // Given: a pool with 4 real entries (count=5); entry[1]=Utf8("a"), [2]=Utf8("b"), [3]=Utf8("c"), [4]=Utf8("d").
        // When: utf8(99) is called (index 99 is far out of range).
        // Then: Abort[TastyError.ClassfileFormatError] with message containing "99" and "out of bounds" (case-insensitive).
        // Pins: T2 out-of-range coverage.
        val entries = Array("a", "b", "c", "d")
        // Build: u2 count=5, then 4x (u1 tag=1, u2 len, u1* bytes)
        val bufs      = entries.map(_.getBytes("UTF-8"))
        val totalSize = 2 + bufs.foldLeft(0)((acc, b) => acc + 1 + 2 + b.length)
        val buf       = new Array[Byte](totalSize)
        buf(0) = 0
        buf(1) = 5 // count=5 => slots 1..4
        var pos = 2
        bufs.foreach: bs =>
            buf(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
            buf(pos + 1) = ((bs.length >> 8) & 0xff).toByte
            buf(pos + 2) = (bs.length & 0xff).toByte
            var i = 0
            while i < bs.length do
                buf(pos + 3 + i) = bs(i)
                i += 1
            pos += 1 + 2 + bs.length
        val view = ByteView(buf)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(99)).map:
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

    // Test T2-2: ClassRef resolution via nameIdx returns the Utf8 string.
    "ClassRef at slot 5 with nameIdx=6 resolves to the Utf8 string at slot 6" in run {
        // Given: pool with 6 entries: slots 1..4 = Utf8 padding, slot 5 = ClassRef(nameIdx=6), slot 6 = Utf8("scala/Int").
        // When: classRef(5) is called.
        // Then: returns "scala/Int".
        // Pins: T2 ClassRef resolution.
        val padding = Array("x", "y", "z", "w")
        val target  = "scala/Int"
        val padBufs = padding.map(_.getBytes("UTF-8"))
        val tgtBuf  = target.getBytes("UTF-8")
        // Layout: u2 count=7, entries[1..4]=Utf8 padding, entry[5]=ClassRef(nameIdx=6), entry[6]=Utf8("scala/Int")
        // entry[5]: u1 tag=7, u2 nameIdx=6  -> 3 bytes
        // entry[6]: u1 tag=1, u2 len, u1* bytes -> 1+2+tgtBuf.length bytes
        val totalSize = 2 + padBufs.foldLeft(0)((acc, b) => acc + 1 + 2 + b.length) + 3 + (1 + 2 + tgtBuf.length)
        val buf       = new Array[Byte](totalSize)
        buf(0) = 0
        buf(1) = 7 // count=7 => slots 1..6
        var pos = 2
        padBufs.foreach: bs =>
            buf(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
            buf(pos + 1) = ((bs.length >> 8) & 0xff).toByte
            buf(pos + 2) = (bs.length & 0xff).toByte
            var i = 0
            while i < bs.length do
                buf(pos + 3 + i) = bs(i)
                i += 1
            pos += 1 + 2 + bs.length
        // entry[5]: ClassRef(nameIdx=6)
        buf(pos) = ClassfileFormat.CONSTANT_Class.toByte
        buf(pos + 1) = 0
        buf(pos + 2) = 6
        pos += 3
        // entry[6]: Utf8("scala/Int")
        buf(pos) = ClassfileFormat.CONSTANT_Utf8.toByte
        buf(pos + 1) = ((tgtBuf.length >> 8) & 0xff).toByte
        buf(pos + 2) = (tgtBuf.length & 0xff).toByte
        var i = 0
        while i < tgtBuf.length do
            buf(pos + 3 + i) = tgtBuf(i)
            i += 1
        val view = ByteView(buf)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.classRef(5)).map:
                    case Result.Success(name) =>
                        assert(name == "scala/Int", s"Expected 'scala/Int' but got '$name'")
                    case other =>
                        fail(s"Expected classRef(5)='scala/Int', got $other")
    }

    // Test B5-4: entry() rejects Long/Double Hole slot with structured error.
    "utf8 at a Long/Double Hole slot yields structured error" in run {
        // Given: pool with entry[1]=Long(42L); slot 2 is the Hole.
        // When: utf8(2) is called (slot 2 is a Hole).
        // Then: Abort[TastyError] mentioning "hole" (case-insensitive).
        // Pins: B5.
        val bytes = buildLongPoolBytes(42L)
        val view  = ByteView(bytes)
        Abort.run(ConstantPool.read(view, interner, "<test>")).map:
            case Result.Failure(err) => fail(s"Read failed: $err")
            case Result.Panic(ex)    => fail(s"Read panicked: $ex")
            case Result.Success(pool) =>
                Abort.run(pool.utf8(2)).map:
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

end ConstantPoolTest
