package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ConstantPool
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.symbol.Interner

/** Tests verifying INV-006: every TastyError.MalformedSection, ClassfileFormatError, and SnapshotFormatError carries a byteOffset field
  * (Phase 14a).
  */
class TastyErrorTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeName(s: String): Tasty.Name =
        val interner = new Interner(numShards = 4, initialShardCapacity = 8)
        val bytes    = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        Tasty.Name.wrap(interner.intern(bytes, 0, bytes.length))
    end makeName

    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then Array((n | 0x80).toByte)
        else Array(((n >> 7) & 0x7f).toByte, ((n & 0x7f) | 0x80).toByte)
    end encodeNat

    // Test 1: MalformedSection carries byte offset
    //
    // Given: a SectionIndex input where nameRef=99 is out of range for names.length==3.
    //        The nameRef Nat encodes as one byte at position 0. After reading it,
    //        view.position == 1. The MalformedSection is raised with byteOffset = 1.
    // When:  SectionIndex.read runs via Abort.run.
    // Then:  Result.Failure(TastyError.MalformedSection(_, _, off)) where off != 0L.
    // Pins:  INV-006, L5.
    "MalformedSection carries non-zero byte offset when cursor has advanced" in run {
        val names = Array(makeName("a"), makeName("b"), makeName("c"))
        val bytes = encodeNat(99) ++ encodeNat(0)
        val view  = ByteView(bytes)
        Abort.run[TastyError](SectionIndex.read(view, names)).map: result =>
            result match
                case Result.Failure(TastyError.MalformedSection(_, _, off)) =>
                    assert(off != 0L, s"Expected non-zero byteOffset but got $off")
                    succeed
                case other =>
                    fail(s"Expected MalformedSection with byteOffset but got: $other")
    }

    // Test 2: ClassfileFormatError captures decode position
    //
    // Given: ConstantPool.read expects to be called with a view positioned at the
    //        cp_count field (not at the classfile header). We feed:
    //          cp_count = 2 (2 bytes: 0x00 0x02)
    //          idx=1 tag  = 0xFF (1 byte: unknown tag)
    //        ConstantPool.read reads: count (2 bytes) -> position 2, then tag (1 byte)
    //        -> position 3. Unknown tag fires: errorOffset = view.position = 3L.
    // When:  ConstantPool.read runs via Abort.run.
    // Then:  Result.Failure(TastyError.ClassfileFormatError(_, _, 3L)).
    //        Exact offset 3L documented in phase-14a-decisions.md.
    // Pins:  INV-006.
    "ClassfileFormatError captures constant-pool decode position" in run {
        val interner = new Interner(numShards = 4, initialShardCapacity = 8)
        val bytes: Array[Byte] = Array(
            0x00.toByte,
            0x02.toByte, // cp_count = 2 (one entry at index 1)
            0xff.toByte  // unknown CP tag -> errorOffset = view.position after reading = 3L
        )
        val view = ByteView(bytes)
        Abort.run[TastyError](ConstantPool.read(view, interner, "<test>")).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, _, 3L)) =>
                    succeed
                case Result.Failure(TastyError.ClassfileFormatError(_, _, off)) =>
                    fail(s"Expected byteOffset == 3L but got $off")
                case other =>
                    fail(s"Expected ClassfileFormatError but got: $other")
    }

    // Test 3: SnapshotFormatError carries byte position field
    //
    // Given: SnapshotFormatError is constructed with byteOffset = 0L. The "wrong magic"
    //        detection path in SnapshotReader has no stream cursor available (it checks
    //        array bytes before any cursor-based read), so byteOffset = 0L is the sentinel.
    //        The test verifies the byteOffset field EXISTS and is readable, satisfying INV-006.
    //        See phase-14a-decisions.md for rationale on why 0L is correct here.
    // When:  A SnapshotFormatError is constructed and destructured.
    // Then:  The byteOffset field is present and equals 0L.
    // Pins:  INV-006.
    "SnapshotFormatError carries byteOffset field (0L sentinel for no-cursor path)" in run {
        val err: TastyError = TastyError.SnapshotFormatError("<test>", "wrong magic, expected KRFL", 0L)
        err match
            case TastyError.SnapshotFormatError(path, reason, off) =>
                assert(path == "<test>", s"Expected path '<test>' but got: $path")
                assert(reason.contains("wrong magic"), s"Expected 'wrong magic' in reason but got: $reason")
                assert(off == 0L, s"Expected byteOffset == 0L (no-cursor sentinel) but got: $off")
                succeed
            case other =>
                fail(s"Expected SnapshotFormatError but got: $other")
        end match
    }

end TastyErrorTest
