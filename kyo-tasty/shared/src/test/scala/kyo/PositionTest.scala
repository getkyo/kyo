package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.PositionsUnpickler
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.immutable.IntMap

/** Tests for the Position type: sourceFile is a String (not Maybe), absent SOURCEFILE skips
  * position production, and a present SOURCEFILE builds a Position with the correct fields.
  */
class PositionTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "Position(Maybe.Absent, ...) does not compile -- sourceFile is now String not Maybe[String]" in {
        val errs = compiletime.testing.typeCheckErrors(
            "kyo.Tasty.Position(kyo.Maybe.Absent, 10, 20)"
        )
        assert(errs.nonEmpty, "Expected compile error when passing Maybe.Absent to Position.sourceFile, but got none")
        succeed
    }

    // Files compiled without the Attributes SOURCEFILE attribute have their source path only in SOURCE
    // entries inside the Positions stream; kyo-tasty does not resolve those. Symbols stay with
    // sourcePosition == Absent.
    "Positions section with absent SOURCEFILE silently skips positions -- no error emitted" in {
        // Minimal non-empty Positions payload: numLines=1, line sizes=[10], one Assoc entry.
        // header = (1<<3)|4 = 12, start_delta = 0.
        val payload = Array[Byte](
            (1 | 0x80).toByte,  // numLines = 1 (Nat)
            (10 | 0x80).toByte, // line size = 10 (Nat)
            (12 | 0x80).toByte, // header: addrDelta=1, hasStart=1 (Nat)
            (0 | 0x80).toByte   // start_delta = 0 (signed Int)
        )
        val symbol = LoadingSymbol.Materialising(
            id = "Foo".hashCode.abs % 1000,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("Foo")
        )
        val addrMap = IntMap(1 -> symbol)
        val view    = ByteView(payload)
        PositionsUnpickler.read(view, addrMap, Maybe.Absent) match
            case Result.Success(posMap) =>
                assert(
                    posMap.isEmpty,
                    s"Expected empty position map when SOURCEFILE is absent, but got ${posMap.size} entries"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"Expected Success but got hard-fail Abort: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

    "present SOURCEFILE produces Position with the correct sourceFile string" in {
        // Payload: numLines=1, line sizes=[10], one entry at addrDelta=1, start_delta=0 => line 1, col 1.
        val payload = Array[Byte](
            (1 | 0x80).toByte,  // numLines = 1
            (10 | 0x80).toByte, // line size = 10
            (12 | 0x80).toByte, // header: addrDelta=1, hasStart=1
            (0 | 0x80).toByte   // start_delta = 0
        )
        val symbol = LoadingSymbol.Materialising(
            id = "Foo".hashCode.abs % 1000,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("Foo")
        )
        val addrMap = IntMap(1 -> symbol)
        val view    = ByteView(payload)
        PositionsUnpickler.read(view, addrMap, Maybe.Present("Foo.scala")) match
            case Result.Success(posMap) =>
                assert(posMap.size == 1, s"Expected 1 position entry but got ${posMap.size}")
                // LongMap keyed by symbol.id.toLong, not by symbol object.
                assert(posMap.contains(symbol.id.toLong), "Expected symbol.id to have a position entry")
                val pos = posMap(symbol.id.toLong)
                assert(pos.sourceFile == "Foo.scala", s"Expected sourceFile='Foo.scala' but got '${pos.sourceFile}'")
                assert(pos.line == 1, s"Expected line=1 but got ${pos.line}")
                succeed
            case Result.Failure(e) =>
                fail(s"Expected Success but got failure: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

end PositionTest
