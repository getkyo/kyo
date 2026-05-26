package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.CommentsUnpickler
import kyo.internal.reflect.type_.TypeArena
import scala.collection.immutable.IntMap

/** Tests for CommentsUnpickler.read and Reflect.Symbol.scaladoc.
  *
  * Plan Phase 6 G3 tests 1-6.
  *
  * The Comments section format (from dotty CommentUnpickler.scala):
  * {{{
  * Entry = Addr Utf8 LongInt
  * Addr  = Nat          -- byte address in AST section
  * Utf8  = Nat Byte*    -- length-prefixed UTF-8 text
  * LongInt = ...        -- source span (skipped here)
  * }}}
  *
  * TASTy Nat encoding: small values (0-127) use a single byte with bit 7 SET = (n | 0x80).toByte. TASTy LongInt encoding: for zero span,
  * use 0x80.toByte (bit 7 SET = stop bit, bit 6 CLEAR = non-negative sign; value decodes to 0).
  */
class CommentsUnpicklerTest extends Test:

    // ── Encoding helpers ─────────────────────────────────────────────────────

    /** Encode a small Nat (0-127) as a single TASTy byte. */
    private def encNat(n: Int): Array[Byte] =
        assert(n >= 0 && n < 128, s"encNat only handles 0-127, got $n")
        Array((n | 0x80).toByte)

    /** Encode a UTF-8 string as Nat(length) + bytes. */
    private def encUtf8(s: String): Array[Byte] =
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        assert(bytes.length < 128, s"encUtf8 only handles strings < 128 bytes, got ${bytes.length}")
        encNat(bytes.length) ++ bytes
    end encUtf8

    /** Encode a single Comments section entry: addr + text + span(0). */
    private def encEntry(addr: Int, text: String): Array[Byte] =
        encNat(addr) ++ encUtf8(text) ++ Array(0x80.toByte) // span = 0

    /** Build a Comments section payload from a sequence of (addr, text) entries. */
    private def buildSection(entries: (Int, String)*): Array[Byte] =
        entries.toArray.flatMap((addr, text) => encEntry(addr, text))

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

    // ── Test 1: documented class produces entry containing the scaladoc text ─

    // Phase 6 Test 1: a TASTy Comments section with a documented class produces a result with an
    // entry for that symbol containing the documented text.
    "CommentsUnpickler: documented class entry produces result with comment text" in run {
        val sym     = makeTestSymbol("Foo")
        val addrMap = IntMap(10 -> sym) // addr 10 -> sym
        val payload = buildSection(10 -> "/** My doc */")
        val view    = ByteView(payload)
        Abort.run[ReflectError](CommentsUnpickler.read(view, addrMap)).map:
            case Result.Success(result) =>
                assert(result.size == 1, s"Expected 1 entry but got ${result.size}")
                assert(result.contains(sym), "Expected sym to have a comment entry")
                val text = result(sym)
                assert(text.contains("My doc"), s"Expected text to contain 'My doc' but got: $text")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 2: no Comments section payload returns empty map ────────────────

    // Phase 6 Test 2: an empty Comments section (zero-length payload) returns an empty map without
    // error.
    "CommentsUnpickler: empty payload returns empty map without error" in run {
        val view = ByteView(Array.empty[Byte])
        Abort.run[ReflectError](CommentsUnpickler.read(view, IntMap.empty)).map:
            case Result.Success(result) =>
                assert(result.isEmpty, s"Expected empty result but got ${result.size} entries")
            case Result.Failure(e) =>
                fail(s"Expected empty map but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 3: malformed section (truncated mid-entry) fails with MalformedSection ─

    // Phase 6 Test 3: a Comments section truncated mid-entry produces
    // Abort.fail(ReflectError.MalformedSection("Comments", ...)).
    "CommentsUnpickler: truncated section produces MalformedSection error" in run {
        // Write addr Nat(5) = 0x85, then start a Utf8: Nat(20) = 0x94, then only 3 bytes of a
        // 20-byte string. ArrayIndexOutOfBoundsException triggers MalformedSection.
        val payload = Array[Byte](
            0x85.toByte, // addr = 5
            0x94.toByte, // text length = 20
            'a'.toByte,  // only 1 of 20 bytes (truncated)
            'b'.toByte,
            'c'.toByte
            // missing 17 more bytes + span
        )
        val sym     = makeTestSymbol("Truncated")
        val addrMap = IntMap(5 -> sym)
        val view    = ByteView(payload)
        Abort.run[ReflectError](CommentsUnpickler.read(view, addrMap)).map:
            case Result.Success(result) =>
                fail(s"Expected MalformedSection failure but got success with ${result.size} entries")
            case Result.Failure(ReflectError.MalformedSection("Comments", _)) =>
                succeed
            case Result.Failure(other) =>
                fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 4: symbol with no comment has scaladoc==Absent; with comment has Present ─

    // Phase 6 Test 4: after CommentsUnpickler populates _scaladoc, a symbol with no comment has
    // scaladoc == Absent and a symbol with a comment has scaladoc == Present(text).
    "CommentsUnpickler: symbol with comment gets Present; symbol without comment gets Absent" in run {
        val symWithDoc    = makeTestSymbol("WithDoc")
        val symWithoutDoc = makeTestSymbol("WithoutDoc")
        val addrMap       = IntMap(1 -> symWithDoc, 2 -> symWithoutDoc)
        // Only addr 1 has a comment; addr 2 is in addrMap but has no comment entry in the section.
        val payload = buildSection(1 -> "/** documented */")
        val view    = ByteView(payload)
        Abort.run[ReflectError](CommentsUnpickler.read(view, addrMap)).map:
            case Result.Success(comments) =>
                // Simulate mergeResults: assign _scaladoc from commentsBySymbol
                import AllowUnsafe.embrace.danger
                for (sym, text) <- comments do
                    if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe(text))
                // Symbols not in the comment map get Absent
                if !symWithDoc._scaladoc.isSet then symWithDoc._scaladoc.set(Maybe.Absent)
                if !symWithoutDoc._scaladoc.isSet then symWithoutDoc._scaladoc.set(Maybe.Absent)
                // Verify
                assert(
                    symWithDoc.scaladoc.isDefined,
                    s"Expected symWithDoc.scaladoc to be Present but got ${symWithDoc.scaladoc}"
                )
                assert(
                    symWithDoc.scaladoc.get.contains("documented"),
                    s"Expected scaladoc text to contain 'documented' but got ${symWithDoc.scaladoc}"
                )
                assert(
                    !symWithoutDoc.scaladoc.isDefined,
                    s"Expected symWithoutDoc.scaladoc to be Absent but got ${symWithoutDoc.scaladoc}"
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 5: two sibling definitions are independently accessible ─────────

    // Phase 6 Test 5: comments from two sibling definitions in the same file are independently
    // accessible with no cross-contamination between addresses.
    "CommentsUnpickler: two sibling definitions have independent scaladoc entries" in run {
        val symAlpha = makeTestSymbol("Alpha")
        val symBeta  = makeTestSymbol("Beta")
        val addrMap  = IntMap(10 -> symAlpha, 20 -> symBeta)
        val payload  = buildSection(10 -> "/** Alpha doc */", 20 -> "/** Beta doc */")
        val view     = ByteView(payload)
        Abort.run[ReflectError](CommentsUnpickler.read(view, addrMap)).map:
            case Result.Success(comments) =>
                assert(comments.size == 2, s"Expected 2 entries but got ${comments.size}")
                assert(comments.contains(symAlpha), "Expected symAlpha to have a comment")
                assert(comments.contains(symBeta), "Expected symBeta to have a comment")
                assert(
                    comments(symAlpha).contains("Alpha doc"),
                    s"symAlpha comment wrong: ${comments(symAlpha)}"
                )
                assert(
                    comments(symBeta).contains("Beta doc"),
                    s"symBeta comment wrong: ${comments(symBeta)}"
                )
                // No cross-contamination: Alpha's doc is not Beta's doc
                assert(
                    !comments(symAlpha).contains("Beta"),
                    s"symAlpha unexpectedly contains 'Beta': ${comments(symAlpha)}"
                )
                assert(
                    !comments(symBeta).contains("Alpha"),
                    s"symBeta unexpectedly contains 'Alpha': ${comments(symBeta)}"
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 6: Java classfile symbol always has scaladoc == Absent ──────────

    // Phase 6 Test 6: a Java-sourced classfile symbol always has scaladoc == Absent because
    // classfiles have no Comments section; ClassfileUnpickler sets _scaladoc to Absent for all
    // classfile symbols.
    "CommentsUnpickler: Java classfile symbol always has scaladoc == Absent" taggedAs jvmOnly in run {
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
                    !result.classSymbol.scaladoc.isDefined,
                    s"Expected Absent scaladoc for classfile symbol but got ${result.classSymbol.scaladoc}"
                )
                val memberFailures = result.symbols.filter(_.scaladoc.isDefined)
                assert(
                    memberFailures.isEmpty,
                    s"Expected all member symbols to have Absent scaladoc but found ${memberFailures.length} with Present: " +
                        memberFailures.map(s => s"'${s.name.asString}'=${s.scaladoc}").mkString(", ")
                )
            case Result.Failure(e) =>
                fail(s"Expected success but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end CommentsUnpicklerTest
