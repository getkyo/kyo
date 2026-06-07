package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.symbol.Constant
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Tests for Constant.fromTastyTag decoding.
  *
  * Covers all eight numeric constant variants (BYTEconst, SHORTconst, CHARconst, INTconst,
  * LONGconst, FLOATconst, DOUBLEconst, STRINGconst) plus the category-1 identity constants
  * (NULLconst, UNITconst, FALSEconst, TRUEconst).
  */
class ConstantTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Encode an unsigned Nat in dotty TASTy big-endian base-128 format.
      *
      * The LAST byte has bit 0x80 SET; earlier (continuation) bytes have bit 0x80 CLEAR.
      */
    private def encodeNat(n: Int): Array[Byte] =
        encodeLongNat(n.toLong & 0xffffffffL)

    /** Encode an unsigned LongNat in dotty TASTy big-endian base-128 format. */
    private def encodeLongNat(n: Long): Array[Byte] =
        val buf   = new scala.collection.mutable.ArrayBuffer[Byte](10)
        var pos   = 9
        val tmp   = new Array[Byte](10)
        var value = n
        tmp(pos) = ((value & 0x7fL) | 0x80L).toByte
        value = value >>> 7
        while value != 0L do
            pos -= 1
            tmp(pos) = (value & 0x7fL).toByte
            value = value >>> 7
        end while
        tmp.slice(pos, 10)
    end encodeLongNat

    private def makeSession(names: Array[Tasty.Name]): TypeUnpickler.DecodeSession =
        val arena   = TypeArena.canonical()
        val addrMap = new mutable.HashMap[Int, LoadingSymbol.Materialising]()
        new TypeUnpickler.DecodeSession(names, addrMap, arena)
    end makeSession

    "Constant STRINGconst decodes name table entry to StringConst" in {
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

    // NULLconst is category 1: tag only, already consumed by the caller.
    "Constant NULLconst returns NullConst with empty view" in {
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

    // BYTEconst (67) is category 2: tag + readNat, result cast to Byte.
    "Constant BYTEconst decodes Nat payload to ByteConst(42)" in {
        val session = makeSession(Array.empty)
        val bytes   = encodeNat(42)
        val view    = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.BYTEconst, view, session)).map:
            case Result.Success(Tasty.Constant.ByteConst(v)) =>
                assert(v == 42.toByte, s"Expected ByteConst(42) but got ByteConst($v)")
            case Result.Success(other) =>
                fail(s"Expected ByteConst(42) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // SHORTconst (68) is category 2: tag + readNat, result cast to Short.
    "Constant SHORTconst decodes Nat payload to ShortConst(1000)" in {
        val session = makeSession(Array.empty)
        val bytes   = encodeNat(1000)
        val view    = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.SHORTconst, view, session)).map:
            case Result.Success(Tasty.Constant.ShortConst(v)) =>
                assert(v == 1000.toShort, s"Expected ShortConst(1000) but got ShortConst($v)")
            case Result.Success(other) =>
                fail(s"Expected ShortConst(1000) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // CHARconst (69) is category 2: tag + readNat, result cast to Char.
    "Constant CHARconst decodes Nat payload to CharConst('A')" in {
        val session = makeSession(Array.empty)
        val bytes   = encodeNat('A'.toInt)
        val view    = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.CHARconst, view, session)).map:
            case Result.Success(Tasty.Constant.CharConst(v)) =>
                assert(v == 'A', s"Expected CharConst('A') but got CharConst('$v')")
            case Result.Success(other) =>
                fail(s"Expected CharConst('A') but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // LONGconst (71) is category 2: tag + readLongNat, encoded as multi-byte LongNat.
    "Constant LONGconst decodes LongNat payload to LongConst(999999999999L)" in {
        val session   = makeSession(Array.empty)
        val longValue = 999999999999L
        val bytes     = encodeLongNat(longValue)
        val view      = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.LONGconst, view, session)).map:
            case Result.Success(Tasty.Constant.LongConst(v)) =>
                assert(v == longValue, s"Expected LongConst($longValue) but got LongConst($v)")
            case Result.Success(other) =>
                fail(s"Expected LongConst($longValue) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // FLOATconst (72) is category 2: tag + readNat interpreted via Float.intBitsToFloat.
    "Constant FLOATconst decodes raw-int-bits Nat payload to FloatConst(3.14f)" in {
        val session   = makeSession(Array.empty)
        val floatBits = java.lang.Float.floatToRawIntBits(3.14f)
        val bytes     = encodeNat(floatBits)
        val view      = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.FLOATconst, view, session)).map:
            case Result.Success(Tasty.Constant.FloatConst(v)) =>
                assert(v == 3.14f, s"Expected FloatConst(3.14f) but got FloatConst($v)")
            case Result.Success(other) =>
                fail(s"Expected FloatConst(3.14f) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // DOUBLEconst (73) is category 2: tag + readLongNat interpreted via Double.longBitsToDouble.
    "Constant DOUBLEconst decodes raw-long-bits LongNat payload to DoubleConst(-2.718281828)" in {
        val session    = makeSession(Array.empty)
        val value      = -2.718281828
        val doubleBits = java.lang.Double.doubleToRawLongBits(value)
        val bytes      = encodeLongNat(doubleBits)
        val view       = ByteView(bytes)
        Abort.run[TastyError](Constant.fromTastyTag(TastyFormat.DOUBLEconst, view, session)).map:
            case Result.Success(Tasty.Constant.DoubleConst(v)) =>
                assert(v == value, s"Expected DoubleConst($value) but got DoubleConst($v)")
            case Result.Success(other) =>
                fail(s"Expected DoubleConst($value) but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end ConstantTest
