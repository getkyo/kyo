package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.PositionsUnpickler
import kyo.internal.reflect.type_.TypeArena

/** Tests for PositionsUnpickler.read and Reflect.Symbol.position.
  *
  * Plan Phase 7 G2 tests 1-5.
  *
  * The Positions section format (from dotty TastyFormat.scala, verbatim):
  * {{{
  * Standard-Section: "Positions" LinesSizes Assoc*
  *   LinesSizes = Nat Nat*             -- number of lines, then size of each line (chars, NOT counting '\n')
  *   Assoc      = Header offset_Delta? offset_Delta? point_Delta?
  *              | SOURCE nameref_Int   -- SOURCE=4 is a special header value indicating a source-path change
  *   Header     = addr_delta<<3 | hasStart<<2 | hasEnd<<1 | hasPoint   (encoded as Nat)
  * }}}
  *
  * All Assoc values (header, deltas) are encoded as TASTy Nat/Int respectively.
  *
  * TASTy Nat encoding: continuation bit = 0x80 CLEAR; last byte has 0x80 SET. Small values (0-127) = single byte (value | 0x80). TASTy Int
  * (signed): first byte bit-6 is sign; same continuation convention.
  */
class PositionsUnpicklerTest extends Test:

    // ── Encoding helpers ─────────────────────────────────────────────────────

    /** Encode a small non-negative Nat (0-127) as a single TASTy byte. */
    private def encNat(n: Int): Byte =
        assert(n >= 0 && n < 128, s"encNat only handles 0-127, got $n")
        (n | 0x80).toByte

    /** Encode a small signed Int (0-63) as a single TASTy byte (bit 6 = sign = 0). */
    private def encInt(n: Int): Byte =
        assert(n >= 0 && n < 64, s"encInt helper only handles 0-63, got $n")
        (n | 0x80).toByte

    /** Encode a negative signed Int (-64 to -1) as a single TASTy byte. */
    private def encNegInt(n: Int): Byte =
        assert(n >= -64 && n < 0, s"encNegInt helper only handles -64..-1, got $n")
        // Sign-extend: bit 6 = 1 (sign), value in low 6 bits, stop bit = 0x80.
        // For a single-byte signed Int: encode = (n & 0x3f) | 0x80
        ((n & 0x3f) | 0x80).toByte
    end encNegInt

    /** Create a minimal Reflect.Symbol for testing. */
    private def makeTestSymbol(nameStr: String): Reflect.Symbol =
        val home   = new ClasspathRef
        val origin = Reflect.Symbol.TastyOrigin.empty
        Reflect.Symbol.make(
            Reflect.SymbolKind.Class,
            Reflect.Flags.empty,
            Reflect.Name(nameStr),
            null,
            home,
            origin,
            Absent
        )
    end makeTestSymbol

    // ── Test 1: fixture TASTy position: class Foo at line 3, column 1 ─────────

    // Phase 7 Test 1: a synthetic Positions section encoding a single class definition at line 3, column 1.
    // LineSizes = [10, 5, 20] means:
    //   line 1: chars 0-10 (11 chars incl. '\n'), lineStarts[0]=0
    //   line 2: chars 11-16 (6 chars incl. '\n'), lineStarts[1]=11
    //   line 3: chars 17-37, lineStarts[2]=17
    // The class definition at offset 17 => line 3, column 1.
    // addrMap has {5 -> sym} so header = (5<<3)|4 = 44 (addrDelta=5, hasStart=true), start_delta=17.
    "PositionsUnpickler: class at line 3 column 1 returns Present(Position(Foo.scala, 3, 1))" in run {
        val sym     = makeTestSymbol("Foo")
        val addrMap = Map(5 -> sym)
        // LinesSizes: numLines=3, sizes=[10,5,20]
        // Assoc: header=(5<<3)|4=44 (addrDelta=5, hasStart=true), start_delta=17
        val payload = Array[Byte](
            encNat(3),  // numLines = 3
            encNat(10), // line 1 size = 10
            encNat(5),  // line 2 size = 5
            encNat(20), // line 3 size = 20
            encNat(44), // header: addrDelta=5, hasStart=1, hasEnd=0, hasPoint=0 => (5<<3)|4 = 44
            encInt(17)  // start_delta = 17 => offset 17 => line 3, col 1
        )
        val view = ByteView(payload)
        Abort.run[ReflectError](PositionsUnpickler.read(view, addrMap, Present("Foo.scala"))).map:
            case Result.Success(result) =>
                assert(result.size == 1, s"Expected 1 position entry but got ${result.size}")
                assert(result.contains(sym), "Expected sym to have a position entry")
                val pos = result(sym)
                assert(pos.sourceFile == Present("Foo.scala"), s"Expected sourceFile=Present(Foo.scala) but got ${pos.sourceFile}")
                assert(pos.line == 3, s"Expected line=3 but got ${pos.line}")
                assert(pos.column == 1, s"Expected column=1 but got ${pos.column}")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 2: no Positions section returns empty map without error ──────────

    // Phase 7 Test 2: an empty Positions section (zero-length payload) returns an empty map without error.
    "PositionsUnpickler: empty payload returns empty map without error" in run {
        val view = ByteView(Array.empty[Byte])
        Abort.run[ReflectError](PositionsUnpickler.read(view, Map.empty, Absent)).map:
            case Result.Success(result) =>
                assert(result.isEmpty, s"Expected empty result but got ${result.size} entries")
            case Result.Failure(e) =>
                fail(s"Expected empty map but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 3: malformed Positions section fails with MalformedSection ───────

    // Phase 7 Test 3: a Positions section truncated mid-entry produces
    // Abort.fail(ReflectError.MalformedSection("Positions", ...)).
    // Payload: numLines=2, line sizes [10, 10], then a header byte indicating hasStart=true but
    // no start_delta follows (truncated). ArrayIndexOutOfBoundsException triggers MalformedSection.
    "PositionsUnpickler: truncated section produces MalformedSection error" in run {
        val payload = Array[Byte](
            encNat(2),  // numLines = 2
            encNat(10), // line 1 size = 10
            encNat(10), // line 2 size = 10
            encNat(12)  // header: addrDelta=1, hasStart=1, hasEnd=0, hasPoint=0 => (1<<3)|4 = 12; but no start_delta follows
            // truncated here -- missing the signed Int for start_delta
        )
        val sym     = makeTestSymbol("Truncated")
        val addrMap = Map(1 -> sym)
        val view    = ByteView(payload)
        Abort.run[ReflectError](PositionsUnpickler.read(view, addrMap, Absent)).map:
            case Result.Success(result) =>
                fail(s"Expected MalformedSection failure but got success with ${result.size} entries")
            case Result.Failure(ReflectError.MalformedSection("Positions", _)) =>
                succeed
            case Result.Failure(other) =>
                fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 4: Java classfile symbol has position == Absent ─────────────────

    // Phase 7 Test 4: a Java-sourced classfile symbol always has position == Absent because
    // classfiles have no TASTy Positions section; ClassfileUnpickler sets _position to Absent.
    "PositionsUnpickler: Java classfile symbol always has position == Absent" taggedAs jvmOnly in run {
        val classBytes = kyo.fixtures.Embedded.arrayRecordClass
        val interner   = new Interner(32)
        val arena      = new TypeArena
        val home       = new ClasspathRef
        Abort.run[ReflectError]:
            ClassfileUnpickler.read(classBytes, interner, arena, home).flatMap: result =>
                Reflect.Classpath.fromPickles(Seq.empty).map: miniCp =>
                    ClasspathTestHelpers.assignExtraHomes(
                        miniCp,
                        Seq(result.classSymbol) ++ result.symbols.toSeq ++ result.typeParams.toSeq
                    )
                    result
        .map:
            case Result.Success(result) =>
                assert(
                    !result.classSymbol.position.isDefined,
                    s"Expected Absent position for classfile symbol but got ${result.classSymbol.position}"
                )
                val memberFailures = result.symbols.filter(_.position.isDefined)
                assert(
                    memberFailures.isEmpty,
                    s"Expected all member symbols to have Absent position but found ${memberFailures.length} with Present: " +
                        memberFailures.map(s => s"'${s.name.asString}'=${s.position}").mkString(", ")
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 5: two siblings have distinct line/column values ─────────────────

    // Phase 7 Test 5: two sibling definitions in the same file have distinct line/column values.
    // LineSizes = [10, 10]: line 1 chars 0-10, line 2 chars 11-21.
    // Sym1 at addrIndex=3, offset=2 => line 1, col 3.
    // Sym2 at addrIndex=7, offset_delta=+11 => cumulative offset=13 => line 2, col 3.
    // (addrDelta from previous is 7-3=4; this second entry has hasStart=true.)
    "PositionsUnpickler: two sibling definitions have distinct line/column values" in run {
        val symAlpha = makeTestSymbol("Alpha")
        val symBeta  = makeTestSymbol("Beta")
        val addrMap  = Map(3 -> symAlpha, 7 -> symBeta)
        // LinesSizes: numLines=2, sizes=[10,10]
        // Entry 1: addrDelta=3, hasStart=1 => header=(3<<3)|4=28, start_delta=2 => offset=2 => (line 1, col 3)
        // Entry 2: addrDelta=4, hasStart=1 => header=(4<<3)|4=36, start_delta=11 => offset=2+11=13 => (line 2, col 3)
        val payload = Array[Byte](
            encNat(2),  // numLines = 2
            encNat(10), // line 1 size = 10
            encNat(10), // line 2 size = 10
            encNat(28), // entry 1 header: addrDelta=3, hasStart=1 => (3<<3)|4 = 28
            encInt(2),  // start_delta = 2 => curStart=2 => line 1, col 3
            encNat(36), // entry 2 header: addrDelta=4, hasStart=1 => (4<<3)|4 = 36
            encInt(11)  // start_delta = 11 => curStart=13 => line 2, col 3
        )
        val view = ByteView(payload)
        Abort.run[ReflectError](PositionsUnpickler.read(view, addrMap, Absent)).map:
            case Result.Success(result) =>
                assert(result.size == 2, s"Expected 2 entries but got ${result.size}")
                assert(result.contains(symAlpha), "Expected symAlpha to have a position entry")
                assert(result.contains(symBeta), "Expected symBeta to have a position entry")
                val posAlpha = result(symAlpha)
                val posBeta  = result(symBeta)
                assert(
                    posAlpha.line == 1 && posAlpha.column == 3,
                    s"symAlpha expected (line=1, col=3) but got (${posAlpha.line}, ${posAlpha.column})"
                )
                assert(
                    posBeta.line == 2 && posBeta.column == 3,
                    s"symBeta expected (line=2, col=3) but got (${posBeta.line}, ${posBeta.column})"
                )
                assert(
                    posAlpha.line != posBeta.line,
                    s"Siblings must be on different lines but both on line ${posAlpha.line}"
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end PositionsUnpicklerTest
