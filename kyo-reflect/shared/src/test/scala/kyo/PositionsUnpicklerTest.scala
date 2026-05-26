package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.PositionsUnpickler
import kyo.internal.reflect.type_.TypeArena
import scala.collection.immutable.IntMap

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
        val addrMap = IntMap(5 -> sym)
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
        Abort.run[ReflectError](PositionsUnpickler.read(view, IntMap.empty, Absent)).map:
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
        val addrMap = IntMap(1 -> sym)
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
        val interner   = new Interner(numShards = 32, initialShardCapacity = 16)
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
        val addrMap  = IntMap(3 -> symAlpha, 7 -> symBeta)
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

    // ── Test T-P5-1: IntMap addrMap with 10,000 entries, correctness at scale ──

    // T-P5-1: PositionsUnpickler.readSync with an IntMap addrMap of 10,000 entries returns
    // correct position mappings for all 10,000 entries. Behavioral correctness at scale.
    // Integer-allocation-elimination assertion is deferred to Phase 8 re-profiling per plan.
    //
    // Payload construction:
    //   - numLines = 10001 (enough lines for offset 10000)
    //   - Each line has size 0 so lineStarts(k) = k (since lineStarts(k) = lineStarts(k-1) + 0 + 1)
    //   - Each Assoc entry: addrDelta=1 (header=(1<<3)|4=12), start_delta=1 (offset advances by 1 per entry)
    //   - Entry i (0-based): curIndex = i+1, curStart = i+1; offset i+1 => line i+1, col 1
    //   - addrMap has {i+1 -> sym(i)} for i in [0, 10000)
    //
    // Spot checks at entries 0, 999, 4999, 7777, 9999.
    "T-P5-1: PositionsUnpickler.readSync with IntMap addrMap of 10,000 entries returns correct positions" in run {
        val N = 10000

        // Build 10,000 symbols, one per addr index 1..N
        val syms: Array[Reflect.Symbol] =
            Array.tabulate(N)(i => makeTestSymbol(s"sym$i"))

        val addrMap: IntMap[Reflect.Symbol] =
            IntMap.from((0 until N).map(i => (i + 1) -> syms(i)))

        // Build the payload bytes:
        //   LinesSizes: numLines=N+1, then N+1 zero-size lines (each as a single-byte Nat 0x80)
        //   Assoc stream: N entries each = [encNat(12), encInt(1)]
        //     header 12 = (1<<3)|4 = addrDelta=1, hasStart=true
        //     start_delta = 1 (small positive Int, fits in 1 byte as 0x81)
        val lineCount = N + 1
        // numLines = 10001 encodes as 2 bytes (multi-byte Nat); lineCount line sizes each 1 byte
        val lineSizeBytes   = 2 + lineCount // 2 bytes for numLines Nat + lineCount bytes for sizes
        val assocEntryBytes = 2             // 1 byte header + 1 byte start_delta
        val totalBytes      = lineSizeBytes + N * assocEntryBytes
        val payload         = new Array[Byte](totalBytes)
        var pos             = 0

        // numLines as Nat: lineCount = 10001, multi-byte Nat encoding
        // Nat encoding: groups of 7 bits; continuation bit = 0x80 CLEAR on all but last; last byte has 0x80 SET.
        // 10001 in binary: 10011100010001 (14 bits) => two groups: [0001001] [1100010001]
        // Wait, TASTy Nat: last byte = value | 0x80; preceding bytes have bit7=0.
        // 10001 = 0x2711; in 7-bit groups (big-endian): [1] [39] [17] but only 2 groups needed:
        // 10001 = 78 * 128 + 17 => first byte = 78 (0x4E, no stop bit), second byte = 17 | 0x80 = 0x91
        // Verify: (78 << 7) | 17 = 9984 + 17 = 10001. Correct.
        payload(pos) = 0x4e.toByte; pos += 1 // high 7 bits of 10001
        payload(pos) = 0x91.toByte; pos += 1 // low 7 bits of 10001 | 0x80 stop

        // Line sizes: lineCount bytes, each = 0 | 0x80 = 0x80 (size 0, stop bit set)
        var li = 0
        while li < lineCount do
            payload(pos) = 0x80.toByte
            pos += 1
            li += 1
        end while

        // Assoc entries: N entries, each = [0x8c, 0x81]
        // header = 12 | 0x80 = 0x8c (single-byte Nat for 12)
        // start_delta = 1 | 0x80 = 0x81 (single-byte signed Int for +1)
        var ei = 0
        while ei < N do
            payload(pos) = 0x8c.toByte; pos += 1 // encNat(12)
            payload(pos) = 0x81.toByte; pos += 1 // encInt(1)
            ei += 1
        end while

        val view = ByteView(payload)
        Abort.run[ReflectError](PositionsUnpickler.read(view, addrMap, Present("scale-test.scala"))).map:
            case Result.Success(result) =>
                assert(result.size == N, s"Expected $N position entries but got ${result.size}")
                // Spot-check 5 entries: indices 0, 999, 4999, 7777, 9999
                val checks = Seq(0, 999, 4999, 7777, 9999)
                for i <- checks do
                    val sym = syms(i)
                    assert(result.contains(sym), s"Expected sym$i to have a position entry")
                    val pos = result(sym)
                    // curIndex = i+1, curStart = i+1; lineStarts(k) = k for size-0 lines.
                    // offset = i+1; binary search finds highest k with lineStarts(k) <= i+1.
                    // lineStarts(i+1) = i+1 exactly matches, so line index = i+1 (0-based) => line i+2 (1-based).
                    // col = offset - lineStarts(i+1) + 1 = (i+1) - (i+1) + 1 = 1.
                    val expectedLine = i + 2
                    assert(
                        pos.line == expectedLine,
                        s"sym$i expected line=${expectedLine} but got ${pos.line}"
                    )
                    assert(
                        pos.column == 1,
                        s"sym$i expected column=1 but got ${pos.column}"
                    )
                    assert(
                        pos.sourceFile == Present("scale-test.scala"),
                        s"sym$i expected sourceFile=Present(scale-test.scala) but got ${pos.sourceFile}"
                    )
                end for
                succeed
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end PositionsUnpicklerTest
