package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ConstantPool
import kyo.internal.tasty.reader.SectionIndex

/** Every TastyError.MalformedSection, ClassfileFormatError, and SnapshotFormatError carries a byteOffset field.
  */
class TastyErrorTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeName(s: String): Tasty.Name =
        val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        Tasty.Name(kyo.internal.tasty.binary.Utf8.decode(bytes, 0, bytes.length))
    end makeName

    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then Array((n | 0x80).toByte)
        else Array(((n >> 7) & 0x7f).toByte, ((n & 0x7f) | 0x80).toByte)
    end encodeNat

    // Test 1: MalformedSection carries byte offset
    //        The nameRef Nat encodes as one byte at position 0. After reading it,
    //        view.position == 1. The MalformedSection is raised with byteOffset = 1.
    "MalformedSection carries non-zero byte offset when cursor has advanced" in {
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
    //        cp_count field (not at the classfile header). We feed:
    //          cp_count = 2 (2 bytes: 0x00 0x02)
    //          idx=1 tag = 0xFF (1 byte: unknown tag)
    //        ConstantPool.read reads: count (2 bytes) -> position 2, then tag (1 byte)
    //        > position 3. Unknown tag fires: errorOffset = view.position = 3L.
    "ClassfileFormatError captures constant-pool decode position" in {
        val bytes: Array[Byte] = Array(
            0x00.toByte,
            0x02.toByte, // cp_count = 2 (one entry at index 1)
            0xff.toByte  // unknown CP tag -> errorOffset = view.position after reading = 3L
        )
        val view = ByteView(bytes)
        Abort.run[TastyError](ConstantPool.read(view, "<test>")).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, _, 3L)) =>
                    succeed
                case Result.Failure(TastyError.ClassfileFormatError(_, _, off)) =>
                    fail(s"Expected byteOffset == 3L but got $off")
                case other =>
                    fail(s"Expected ClassfileFormatError but got: $other")
    }

    // Test 3: SnapshotFormatError carries byte position field
    //        detection path in SnapshotReader has no stream cursor available (it checks
    //        array bytes before any cursor-based read), so byteOffset = 0L is the sentinel.
    "SnapshotFormatError carries byteOffset field (0L sentinel for no-cursor path)" in {
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

    "SymbolNotFound carries fqn field and empty classpath lookup returns Absent" in {
        val err: TastyError.SymbolNotFound = TastyError.SymbolNotFound("missing.X")
        assert(err.fqn == "missing.X", s"Expected fqn 'missing.X' but got: ${err.fqn}")
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            val result = cp.findClass("missing.X")
            result match
                case Maybe.Absent =>
                    succeed
                case Maybe.Present(sym) =>
                    fail(s"Expected Absent but got Present($sym)")
            end match
    }

    // Test 5: requireClass("") raises InvalidFqn, not NotFound
    //        This distinguishes a caller programming error (empty input) from a genuine not-found result.
    "requireClass empty string raises InvalidFqn not NotFound" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).flatMap: cp =>
            Abort.run[TastyError](cp.requireClass("")).map: result =>
                result match
                    case Result.Failure(TastyError.InvalidFqn("", r)) =>
                        assert(r.contains("non-empty"), s"Expected reason containing 'non-empty' but got: $r")
                        succeed
                    case Result.Failure(TastyError.NotFound("")) =>
                        fail("requireClass(\"\") should raise InvalidFqn, not NotFound")
                    case other =>
                        fail(s"Expected InvalidFqn but got: $other")
    }

end TastyErrorTest
