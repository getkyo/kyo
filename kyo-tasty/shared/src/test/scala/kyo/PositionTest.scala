package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.PositionsUnpickler
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.immutable.IntMap

/** Tests for the Position redesign (Cat 8).
  *
  * Leaves:
  *   1. positionSourceFileRejectsAbsent: Position constructor requires a String sourceFile; Maybe.Absent does not compile.
  *   2. absentSourceFileSkipsPositions: absent SOURCEFILE silently skips position production (no error emitted).
  *   3. presentSourceFileBuildsPosition: a present SOURCEFILE produces a Position with the correct sourceFile string.
  *   4. everySymbolSubtypeCarriesSourcePosition: covered in SymbolAdtVariantCoverageTest.
  */
class PositionTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Leaf 1: positionSourceFileRejectsAbsent ──────────────────────────────

    // Given: a probe compileErrors("Position(Maybe.Absent, 10, 20)")
    // When: the test asserts
    // Then: the returned string is non-empty (sourceFile: String, cannot accept Maybe.Absent)
    "Leaf 1: Position(Maybe.Absent, ...) does not compile -- sourceFile is now String not Maybe[String]" in {
        val errs = compiletime.testing.typeCheckErrors(
            "kyo.Tasty.Position(kyo.Maybe.Absent, 10, 20)"
        )
        assert(errs.nonEmpty, "Expected compile error when passing Maybe.Absent to Position.sourceFile, but got none")
        succeed
    }

    // ── Leaf 2: absentSourceFileSkipsPositions ───────────────────────────────

    // Given: a PositionsUnpickler.read call with a non-empty Positions payload but sourceFile = Absent.
    // When: the read returns.
    // Then: the result map is empty, no error is emitted. Pre-Scala-3.3 TASTy files and files compiled
    //       without the Attributes SOURCEFILE attribute have their source path only in SOURCE entries inside
    //       the Positions stream; kyo-tasty does not resolve those. Symbols stay with sourcePosition == Absent.
    "Leaf 2: Positions section with absent SOURCEFILE silently skips positions -- no error emitted" in {
        // Minimal non-empty Positions payload: numLines=1, line sizes=[10], one Assoc entry.
        // header = (1<<3)|4 = 12, start_delta = 0.
        val payload = Array[Byte](
            (1 | 0x80).toByte,  // numLines = 1 (Nat)
            (10 | 0x80).toByte, // line size = 10 (Nat)
            (12 | 0x80).toByte, // header: addrDelta=1, hasStart=1 (Nat)
            (0 | 0x80).toByte   // start_delta = 0 (signed Int)
        )
        val sym = LoadingSymbol.Materialising(
            id = "Foo".hashCode.abs % 1000,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("Foo")
        )
        val addrMap = IntMap(1 -> sym)
        val view    = ByteView(payload)
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Maybe.Absent)).map:
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
    }

    // ── Leaf 3: presentSourceFileBuildsPosition ──────────────────────────────

    // Given: a PositionsUnpickler.read call with a non-empty Positions payload and sourceFile = Present("Foo.scala").
    // When: the read succeeds.
    // Then: the result map contains the symbol with sourceFile == "Foo.scala".
    "Leaf 3: present SOURCEFILE produces Position with the correct sourceFile string" in {
        // Payload: numLines=1, line sizes=[10], one entry at addrDelta=1, start_delta=0 => line 1, col 1.
        val payload = Array[Byte](
            (1 | 0x80).toByte,  // numLines = 1
            (10 | 0x80).toByte, // line size = 10
            (12 | 0x80).toByte, // header: addrDelta=1, hasStart=1
            (0 | 0x80).toByte   // start_delta = 0
        )
        val sym = LoadingSymbol.Materialising(
            id = "Foo".hashCode.abs % 1000,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("Foo")
        )
        val addrMap = IntMap(1 -> sym)
        val view    = ByteView(payload)
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Maybe.Present("Foo.scala"))).map:
            case Result.Success(posMap) =>
                assert(posMap.size == 1, s"Expected 1 position entry but got ${posMap.size}")
                // F-006: LongMap keyed by sym.id.toLong, not by symbol object.
                assert(posMap.contains(sym.id.toLong), "Expected sym.id to have a position entry")
                val pos = posMap(sym.id.toLong)
                assert(pos.sourceFile == "Foo.scala", s"Expected sourceFile='Foo.scala' but got '${pos.sourceFile}'")
                assert(pos.line == 1, s"Expected line=1 but got ${pos.line}")
                succeed
            case Result.Failure(e) =>
                fail(s"Expected Success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end PositionTest
