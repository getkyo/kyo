package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.symbol.SymbolBody
import scala.collection.immutable.IntMap

/** Tests pinning that Tasty.bodyTree routes every NonFatal throwable from TreeUnpickler.decodeSync
  * to TastyError.MalformedSection, never to a Sync panic. Covers MalformedVarintException via the
  * NonFatal catch arm, truncated-body upfront bounds check, cached failure re-serving, and no
  * panic under synthetic corrupt-body input.
  */
class BodyTreeErrorChannelTest extends kyo.test.Test[Any]:

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

    // Synthetic corrupt bytes: TERMREFdirect (0x3E) followed by 10x 0x00 continuation bytes
    // triggers Varint.readLongNat guard firing MalformedVarintException, caught by NonFatal arm.
    "MalformedVarintException maps to TastyError.MalformedSection via NonFatal arm" in {
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

    // bodyTree's upfront bounds check fires before any unsafe read, so MalformedSection is
    // produced identically on JVM, JS, and Native without throwing ArrayIndexOutOfBoundsException.
    "truncated body (bodyEnd beyond sectionBytes) maps to TastyError.MalformedSection cross-platform" in {
        // One byte: TERMREFdirect tag (0x3E). bodyEnd=12 is beyond the array length of 1.
        // The upfront bounds check in bodyTree (bodyEnd > sectionLen) fires and returns MalformedSection.
        val tooShortBytes: Array[Byte] = Array[Byte](0x3e)
        val sym                        = makeValSym(2)
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = 12, // beyond the array length of 1; upfront check fires
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
                            // The upfront check reports the exact tuple so callers can diagnose without rerun.
                            assert(
                                ms.reason.contains("bodyStart=0") && ms.reason.contains("bodyEnd=12") && ms.reason.contains(
                                    "sectionSize=1"
                                ),
                                s"Expected upfront-check reason with the (start, end, size) tuple; got '${ms.reason}'"
                            )
                            succeed
                        case other =>
                            fail(s"Expected TastyError.MalformedSection but got $other")
                case Result.Panic(t) =>
                    fail(s"bodyTree must NOT produce a Sync panic for truncated body; got: $t")
    }

    // After a first bodyTree call that produces TastyError.MalformedSection, the result is memoised
    // in DecodeContext.bodyMemo. A second call reads from the memo and re-serves the cached Failure.
    "cached decode failure re-serves as MalformedSection on second bodyTree call" in {
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

    "no Sync panic under any NonFatal corrupt body bytes" in {
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
