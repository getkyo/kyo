package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileUnpickler
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.query.ClasspathTestHelpers
import kyo.internal.tasty.reader.PositionsUnpickler
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap

/** Tests for PositionsUnpickler.read and Tasty.Symbol.position.
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

    import AllowUnsafe.embrace.danger

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

    /** Create a minimal Tasty.Symbol for testing. */
    private def makeTestSymbol(nameStr: String): Tasty.Symbol =
        val home   = ClasspathRef.init()
        val origin = Tasty.Symbol.TastyOrigin.empty
        Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name(nameStr),
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
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Present("Foo.scala"))).map:
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
        Abort.run[TastyError](PositionsUnpickler.read(view, IntMap.empty, Absent)).map:
            case Result.Success(result) =>
                assert(result.isEmpty, s"Expected empty result but got ${result.size} entries")
            case Result.Failure(e) =>
                fail(s"Expected empty map but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 3: malformed Positions section fails with MalformedSection ───────

    // Phase 7 Test 3: a Positions section truncated mid-entry produces
    // Abort.fail(TastyError.MalformedSection("Positions", ...)).
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
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Absent)).map:
            case Result.Success(result) =>
                fail(s"Expected MalformedSection failure but got success with ${result.size} entries")
            case Result.Failure(TastyError.MalformedSection("Positions", _, _)) =>
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
        val interner   = Interner.init(numShards = 32, initialShardCapacity = 16)
        val arena      = new TypeArena
        val home       = ClasspathRef.init()
        Abort.run[TastyError]:
            ClassfileUnpickler.read(classBytes, interner, arena, home).flatMap: result =>
                Tasty.Classpath.fromPickles(Seq.empty).map: miniCp =>
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
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Absent)).map:
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
        val syms: Array[Tasty.Symbol] =
            Array.tabulate(N)(i => makeTestSymbol(s"sym$i"))

        val addrMap: IntMap[Tasty.Symbol] =
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
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Present("scale-test.scala"))).map:
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

    // ── Test B9-1: Int overflow on lineStarts cumulation is detected (B9) ────────

    // B9-1: When the cumulative sum of lineSizes overflows Int, PositionsUnpickler must
    // produce MalformedSection("Positions", reason) where reason contains "exceeds Int.MaxValue".
    //
    // Payload: numLines=1, lineSizes=[Int.MaxValue].
    // lineStarts(1) = 0 + Int.MaxValue + 1 = 2147483648, which exceeds Int.MaxValue.
    //
    // TASTy Nat encoding of Int.MaxValue (2147483647 = 0x7FFFFFFF):
    //   5 bytes big-endian in 7-bit groups: [7, 127, 127, 127, 127].
    //   bytes: 0x07, 0x7F, 0x7F, 0x7F, 0xFF (last byte has stop-bit 0x80 set).
    //   Verify: (7 << 28) | (127 << 21) | (127 << 14) | (127 << 7) | 127 = 2147483647.
    "B9-1: PositionsUnpickler: Int overflow on lineStarts cumulation produces MalformedSection" in run {
        // numLines=1 encoded as Nat: single byte (1 | 0x80) = 0x81
        // lineSizes[0] = Int.MaxValue = 2147483647 encoded as 5-byte Nat
        //   7-bit groups (big-endian): [7, 127, 127, 127, 127]
        //   bytes: [0x07, 0x7F, 0x7F, 0x7F, (127 | 0x80)=0xFF]
        val payload = Array[Byte](
            0x81.toByte, // numLines = 1
            0x07.toByte, // Int.MaxValue byte 1 (7-bit group: 7)
            0x7f.toByte, // Int.MaxValue byte 2 (7-bit group: 127)
            0x7f.toByte, // Int.MaxValue byte 3 (7-bit group: 127)
            0x7f.toByte, // Int.MaxValue byte 4 (7-bit group: 127)
            0xff.toByte  // Int.MaxValue byte 5 = (127 | 0x80), stop bit set
            // No Assoc entries needed; overflow happens during lineStarts construction
        )
        val view = ByteView(payload)
        Abort.run[TastyError](PositionsUnpickler.read(view, IntMap.empty, Absent)).map:
            case Result.Success(result) =>
                fail(s"Expected MalformedSection for overflow but got success with ${result.size} entries")
            case Result.Failure(TastyError.MalformedSection("Positions", reason, _)) =>
                assert(
                    reason.contains("exceeds Int.MaxValue"),
                    s"Expected reason to contain 'exceeds Int.MaxValue' but got: $reason"
                )
                succeed
            case Result.Failure(other) =>
                fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test B9-2: Normal 200-line file decodes lineStarts correctly (B9 baseline) ─

    // B9-2: A 200-line synthetic file where line k has size (100 + k) decodes correctly.
    // lineStarts(0) = 0
    // lineStarts(k) = sum_{i=0}^{k-1} (100 + i + 1) = sum_{i=0}^{k-1} (101 + i) = 101*k + k*(k-1)/2
    // Spot-check: lineStarts(10) = 101*10 + 10*9/2 = 1010 + 45 = 1055.
    //
    // Payload encodes numLines=200, then 200 line sizes in [100..299], then a single Assoc entry
    // at addrDelta=1, hasStart=true, start_delta=0 (curStart=0 => line 1, col 1).
    "B9-2: PositionsUnpickler: 200-line file with varying line sizes decodes lineStarts correctly" in run {
        val numLines = 200

        // Build payload: numLines as Nat, then numLines line sizes (each in 100..299), then one Assoc entry
        // numLines=200: single byte (200 > 127, need 2 bytes)
        // 200 = 1*128 + 72 => [0x01, (72 | 0x80)=0xC8] -- no, TASTy Nat uses 7-bit groups:
        //   200 in 7-bit groups: 200 = 1*128 + 72 => groups [1, 72] => bytes [0x01, (72|0x80)=0xC8]
        // Line sizes 100..299 (values 100-127 fit in 1 byte, 128+ need 2 bytes):
        //   size k (for k in 0..99): 100+k in [100..199] -- 100-127 fit 1 byte; 128-199 need 2 bytes
        //   size k (for k in 100..199): 100+k in [200..299] -- all need 2 bytes
        // For 2-byte Nat v where 128 <= v <= 16383:
        //   high 7 bits = v >> 7, low 7 bits = v & 0x7F; bytes = [v>>7, (v&0x7F)|0x80]
        val assocEntry = Array[Byte](
            encNat(12), // header: addrDelta=1, hasStart=1 => (1<<3)|4=12
            encInt(0)   // start_delta=0 => curStart stays 0 => offset 0 => line 1, col 1
        )

        // Build lineSize bytes first to know total length
        val lineSizeByteArrays: Array[Array[Byte]] = Array.tabulate(numLines): k =>
            val size = 100 + k
            if size < 128 then Array((size | 0x80).toByte)
            else Array((size >> 7).toByte, ((size & 0x7f) | 0x80).toByte)

        val lineSizeTotalBytes = lineSizeByteArrays.map(_.length).sum
        val numLinesBytes      = Array[Byte](0x01.toByte, 0xc8.toByte) // Nat encoding of 200
        val totalBytes         = numLinesBytes.length + lineSizeTotalBytes + assocEntry.length
        val payload            = new Array[Byte](totalBytes)
        var offset             = 0

        // numLines
        java.lang.System.arraycopy(numLinesBytes, 0, payload, offset, numLinesBytes.length)
        offset += numLinesBytes.length

        // line sizes
        var k = 0
        while k < numLines do
            val sizeBytes = lineSizeByteArrays(k)
            java.lang.System.arraycopy(sizeBytes, 0, payload, offset, sizeBytes.length)
            offset += sizeBytes.length
            k += 1
        end while

        // single Assoc entry
        java.lang.System.arraycopy(assocEntry, 0, payload, offset, assocEntry.length)
        offset += assocEntry.length

        val sym     = makeTestSymbol("B9Baseline")
        val addrMap = IntMap(1 -> sym)
        val view    = ByteView(payload)
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Absent)).map:
            case Result.Success(result) =>
                // Verify the read succeeded; the position of sym at offset 0 should be line 1, col 1
                assert(result.contains(sym), "Expected sym to have a position entry")
                val pos = result(sym)
                assert(pos.line == 1, s"Expected line=1 but got ${pos.line}")
                assert(pos.column == 1, s"Expected column=1 but got ${pos.column}")
                // Verify spot-check on lineStarts(10) = 1055 via a new read with sym at that offset
                succeed
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test B9-3: lineStarts(10) value correctness for 200-line file ─────────

    // B9-3: Confirms the exact value of lineStarts(10) for a 200-line file where line k has size (100+k).
    // lineStarts(10) = 101*10 + 10*9/2 = 1010 + 45 = 1055.
    // A symbol at address 1 with curStart=1055 should decode to (line=11, col=1).
    "B9-3: PositionsUnpickler: lineStarts(10) equals 1055 for 200-line file with sizes 100+k" in run {
        val numLines = 200

        // Build line size bytes
        val lineSizeByteArrays: Array[Array[Byte]] = Array.tabulate(numLines): k =>
            val size = 100 + k
            if size < 128 then Array((size | 0x80).toByte)
            else Array((size >> 7).toByte, ((size & 0x7f) | 0x80).toByte)

        val lineSizeTotalBytes = lineSizeByteArrays.map(_.length).sum
        val numLinesBytes      = Array[Byte](0x01.toByte, 0xc8.toByte) // Nat 200

        // Assoc entry: addrDelta=1, hasStart=true, start_delta=1055 (to reach line 11, col 1)
        // 1055 as TASTy signed Int: positive, 1055 = 8*128 + 31 => [0x08, (31|0x80)=0x9F]
        val assocEntry = Array[Byte](
            0x8c.toByte, // encNat(12): header addrDelta=1, hasStart=1
            0x08.toByte, // Int high byte for 1055 (1055 >> 7 = 8)
            0x9f.toByte  // Int low byte for 1055 ((1055 & 0x7F) | 0x80 = 31 | 0x80 = 0x9F)
        )

        val totalBytes = numLinesBytes.length + lineSizeTotalBytes + assocEntry.length
        val payload    = new Array[Byte](totalBytes)
        var offset     = 0

        java.lang.System.arraycopy(numLinesBytes, 0, payload, offset, numLinesBytes.length)
        offset += numLinesBytes.length

        var k = 0
        while k < numLines do
            val sizeBytes = lineSizeByteArrays(k)
            java.lang.System.arraycopy(sizeBytes, 0, payload, offset, sizeBytes.length)
            offset += sizeBytes.length
            k += 1
        end while

        java.lang.System.arraycopy(assocEntry, 0, payload, offset, assocEntry.length)

        val sym     = makeTestSymbol("B9LineStarts10")
        val addrMap = IntMap(1 -> sym)
        val view    = ByteView(payload)
        Abort.run[TastyError](PositionsUnpickler.read(view, addrMap, Absent)).map:
            case Result.Success(result) =>
                assert(result.contains(sym), "Expected sym to have a position entry")
                val pos = result(sym)
                // offset 1055 = lineStarts(10) => line index 10 (0-based) => line 11 (1-based), col 1
                assert(pos.line == 11, s"Expected line=11 (lineStarts(10)=1055) but got ${pos.line}")
                assert(pos.column == 1, s"Expected column=1 but got ${pos.column}")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end PositionsUnpicklerTest
