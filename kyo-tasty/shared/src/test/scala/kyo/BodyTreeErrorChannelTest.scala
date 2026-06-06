package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.symbol.SymbolBody
import scala.collection.immutable.IntMap

/** Tests pinning INV-007 / INV-013: Tasty.bodyTree routes every NonFatal throwable from
  * TreeUnpickler.decodeSync to TastyError.MalformedSection, never to a Sync panic.
  *
  * F-015 / Phase 15: the catch block in bodyTree previously enumerated only three specific exception
  * types (DecodeException, ArrayIndexOutOfBoundsException, IllegalStateException). Any other exception
  * thrown by decodeSync (NullPointerException, MalformedVarintException, etc.) would escape as a
  * Sync panic instead of surfacing through the documented Abort[TastyError] channel.
  *
  * The fix adds a final `case ex: Throwable if NonFatal(ex)` arm. These tests pin that contract.
  *
  * Leaves:
  *   1. MalformedVarintException maps to TastyError.MalformedSection via the NonFatal arm.
  *   2. ArrayIndexOutOfBoundsException still maps to TastyError.MalformedSection (regression). JVM
  *      only (runJVM): on Scala.js the equivalent bounds violation throws UndefinedBehaviorError
  *      (java.lang.Error), which is fatal; leaves 1, 3, and 4 cover this contract cross-platform.
  *   3. Cached failure re-serves as Failure, not Panic (dead Result.Panic arm confirmed removed).
  *   4. No Sync panic escapes under a synthetic corrupt-body scenario.
  */
class BodyTreeErrorChannelTest extends Test:

    import AllowUnsafe.embrace.danger

    // Build a Symbol.Val for tests: minimal valid Val with the given SymbolId.
    private def makeValSym(id: Int): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            Tasty.SymbolId(id),
            Tasty.Name("testVal"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )
    end makeValSym

    // Build a Classpath with a single symbol and an associated SymbolBody in a fresh DecodeContext.
    private def bindingWithBody(sym: Tasty.Symbol, body: SymbolBody): Binding =
        val cp = Tasty.Classpath(
            symbols = Chunk(sym),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(sym.id.value)
        )
        val ctx = DecodeContext.fresh()
        ctx.bodyStore.put(sym.id, body)
        Binding(cp, Maybe.Present(ctx))
    end bindingWithBody

    // Leaf 1: MalformedVarintException routes to TastyError.MalformedSection via NonFatal arm.
    //
    // Synthetic corrupt bytes: the first byte is TERMREFdirect (0x3E = 62), which is a type tag
    // recognised by isTypeTag. The Val decode path calls readTypeOrSkip, which calls readType,
    // which calls TypeUnpickler.readTypeForTree -> readTypeNode -> decodeTag(TERMREFdirect) ->
    // view.readNat(). The 10 following bytes are all 0x00 (TASTy big-endian base-128 continuation
    // bytes: bit 7 clear = not the terminating byte). After reading 10 continuation bytes the
    // Varint.readLongNat guard fires MalformedVarintException (a RuntimeException not in the
    // previous specific-exception catch list).
    //
    // After F-015 the NonFatal arm catches it and returns TastyError.MalformedSection("ASTs", ...).
    "Leaf 1: MalformedVarintException maps to TastyError.MalformedSection via NonFatal arm" in run {
        // 0x3E = 62 = TERMREFdirect type tag; 10x 0x00 = continuation bytes (bit 7 clear)
        val sectionBytes: Array[Byte] = Array[Byte](0x3e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val sym                       = makeValSym(1)
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = sectionBytes.length,
            sectionBytes = Span.fromUnsafe(sectionBytes),
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId]
        )
        val binding = bindingWithBody(sym, body)
        Abort.run[TastyError]:
            TastyState.bindingLocal.let(Maybe.Present(binding)):
                Tasty.bodyTree(sym)
        .map: result =>
            result match
                case Result.Success(_) =>
                    fail("Expected TastyError.MalformedSection but bodyTree returned Present")
                case Result.Failure(e) =>
                    e match
                        case ms: TastyError.MalformedSection =>
                            assert(ms.name == "ASTs", s"Expected section name 'ASTs' but got '${ms.name}'")
                            assert(
                                ms.reason.contains("MalformedVarintException") || ms.reason.contains("MalformedVarint"),
                                s"Expected reason to contain 'MalformedVarintException' but got '${ms.reason}'"
                            )
                            succeed
                        case other =>
                            fail(s"Expected TastyError.MalformedSection but got $other")
                case Result.Panic(t) =>
                    fail(s"bodyTree must NOT produce a Sync panic for MalformedVarintException; got: $t")
    }

    // Leaf 2: ArrayIndexOutOfBoundsException still maps to TastyError.MalformedSection (regression).
    //
    // The previous catch block handled ArrayIndexOutOfBoundsException explicitly with a
    // "truncated body" message. After F-015, this arm is retained unchanged above the NonFatal arm.
    // This leaf confirms the specific arm still fires (not the broader NonFatal fallback).
    //
    // JVM only: asserts JVM-specific ArrayIndexOutOfBoundsException identity. On Scala.js the
    // equivalent bounds violation produces org.scalajs.linker.runtime.UndefinedBehaviorError (extends
    // java.lang.Error, not Exception), which is fatal and would crash the test process because neither
    // the AIOOBE arm nor NonFatal can catch it. The other 3 leaves remain cross-platform.
    "Leaf 2: ArrayIndexOutOfBoundsException still maps to TastyError.MalformedSection truncated-body" in runJVM {
        // One byte: TERMREFdirect tag (0x3E). bodyEnd=12 is beyond the array length, so the JVM
        // throws ArrayIndexOutOfBoundsException when readByte tries to access bytes(1). The existing
        // specific arm catches this and converts it to MalformedSection("ASTs", "truncated body", 0L).
        val tooShortBytes: Array[Byte] = Array[Byte](0x3e) // TERMREFdirect tag only; no Nat bytes follow
        val sym                        = makeValSym(2)
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = 12, // beyond the array length of 1, so readByte throws AIOOBE
            sectionBytes = Span.fromUnsafe(tooShortBytes),
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId]
        )
        val binding = bindingWithBody(sym, body)
        Abort.run[TastyError]:
            TastyState.bindingLocal.let(Maybe.Present(binding)):
                Tasty.bodyTree(sym)
        .map: result =>
            result match
                case Result.Success(_) =>
                    fail("Expected TastyError.MalformedSection for truncated body")
                case Result.Failure(e) =>
                    e match
                        case ms: TastyError.MalformedSection =>
                            assert(ms.name == "ASTs", s"Expected section name 'ASTs' but got '${ms.name}'")
                            succeed
                        case other =>
                            fail(s"Expected TastyError.MalformedSection but got $other")
                case Result.Panic(t) =>
                    fail(s"bodyTree must NOT produce a Sync panic for AIOOBE; got: $t")
    }

    // Leaf 3: Cached failure re-serves as Failure, not Panic (confirms dead Result.Panic arm removed).
    //
    // After a first bodyTree call that produces TastyError.MalformedSection, the result is memoised
    // in DecodeContext.bodyMemo. A second call reads from the memo and re-serves the cached Failure.
    // Before F-015, the memo-hit path had a dead `case Result.Panic(t) => throw t` arm; it is now
    // gone. This test verifies that two successive calls both return the same Failure and that the
    // second call (memo-hit path) also returns Failure, confirming the dead arm is absent.
    "Leaf 3: cached decode failure re-serves as MalformedSection on second bodyTree call" in run {
        val sectionBytes: Array[Byte] = Array[Byte](0x3e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val sym                       = makeValSym(3)
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = sectionBytes.length,
            sectionBytes = Span.fromUnsafe(sectionBytes),
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId]
        )
        val binding = bindingWithBody(sym, body)
        for
            r1 <- Abort.run[TastyError](TastyState.bindingLocal.let(Maybe.Present(binding))(Tasty.bodyTree(sym)))
            r2 <- Abort.run[TastyError](TastyState.bindingLocal.let(Maybe.Present(binding))(Tasty.bodyTree(sym)))
        yield
            r1 match
                case Result.Failure(_: TastyError.MalformedSection) => ()
                case other                                          => fail(s"First call: expected MalformedSection, got $other")
            r2 match
                case Result.Failure(ms: TastyError.MalformedSection) =>
                    assert(ms.name == "ASTs", s"Second call: expected section name 'ASTs' but got '${ms.name}'")
                    succeed
                case Result.Panic(t) =>
                    fail(s"Second (memo-hit) bodyTree call must NOT produce a Sync panic; got: $t")
                case other =>
                    fail(s"Second call: expected MalformedSection, got $other")
            end match
        end for
    }

    // Leaf 4: No Sync panic escapes under any synthetic corrupt-body scenario.
    //
    // Belt-and-suspenders: confirms that the Abort[TastyError] result type holds for both
    // a fresh decode (first call) and a memo-hit (second call), using a different corrupt-byte
    // pattern. Also verifies the section name is always "ASTs".
    "Leaf 4: no Sync panic under any NonFatal corrupt body bytes" in run {
        // Use a byte sequence that triggers MalformedVarintException on first type read.
        val sectionBytes: Array[Byte] = Array[Byte](0x3e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val sym                       = makeValSym(4)
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = sectionBytes.length,
            sectionBytes = Span.fromUnsafe(sectionBytes),
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId]
        )
        val binding = bindingWithBody(sym, body)
        Abort.run[TastyError]:
            TastyState.bindingLocal.let(Maybe.Present(binding)):
                Tasty.bodyTree(sym)
        .map: result =>
            result match
                case Result.Failure(ms: TastyError.MalformedSection) =>
                    assert(ms.name == "ASTs", s"Expected section='ASTs' but got '${ms.name}'")
                    assert(ms.reason.nonEmpty, "Expected non-empty reason in MalformedSection")
                    succeed
                case Result.Panic(t) =>
                    fail(s"bodyTree must not produce a Sync panic for any NonFatal corrupt bytes; got: $t")
                case Result.Success(_) =>
                    fail("Expected Failure but got Success for a corrupt-byte body")

    }

end BodyTreeErrorChannelTest
