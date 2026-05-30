package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.symbol.Constant
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Tests for Constant.fromTastyTag decoding.
  *
  * Phase 21g (T2). Covers STRINGconst name-table lookup and NULLconst identity.
  */
class ConstantTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Encode an unsigned Nat in dotty TASTy big-endian base-128 format.
      *
      * The LAST byte has bit 0x80 SET; earlier (continuation) bytes have bit 0x80 CLEAR.
      */
    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then Array((n | 0x80).toByte)
        else if n < 16384 then Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        else
            Array(
                (n >> 14).toByte,
                ((n >> 7) & 0x7f).toByte,
                ((n & 0x7f) | 0x80).toByte
            )

    private def makeSession(names: Array[Tasty.Name]): TypeUnpickler.DecodeSession =
        val home    = new ClasspathRef
        val arena   = TypeArena.canonical()
        val addrMap = new mutable.HashMap[Int, Tasty.Symbol]()
        new TypeUnpickler.DecodeSession(names, addrMap, arena, home)
    end makeSession

    // Test 1 (T2, Constant): STRINGconst decodes via name table.
    // Given: bytes [STRINGconst tag, Nat(2)] with names(2) = Name("hello").
    // When: Constant.fromTastyTag(STRINGconst, view, session).
    // Then: returns Constant.StringConst("hello").
    // Pins: T2.
    "Constant STRINGconst decodes name table entry to StringConst" in run {
        val names = Array(
            Tasty.Name("dummy0"),
            Tasty.Name("dummy1"),
            Tasty.Name("hello")
        )
        val session = makeSession(names)
        val bytes   = encodeNat(2) // nameRef = 2
        val view    = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.STRINGconst, view, session)).map:
            case Result.Success(c) =>
                c match
                    case Tasty.Constant.StringConst(s) =>
                        assert(s == "hello", s"Expected StringConst(hello) but got StringConst($s)")
                    case other =>
                        fail(s"Expected StringConst(hello) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 2 (T2, Constant): NULLconst returns canonical NullConst.
    // Given: zero bytes after tag (NULLconst is category 1: tag only, already consumed by caller).
    // When: Constant.fromTastyTag(NULLconst, emptyView, session).
    // Then: returns Constant.NullConst.
    // Pins: T2.
    "Constant NULLconst returns NullConst with empty view" in run {
        val session = makeSession(Array.empty)
        val bytes   = Array.empty[Byte]
        val view    = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.NULLconst, view, session)).map:
            case Result.Success(c) =>
                c match
                    case _: Tasty.Constant.NullConst.type => succeed
                    case other                            => fail(s"Expected NullConst but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end ConstantTest
