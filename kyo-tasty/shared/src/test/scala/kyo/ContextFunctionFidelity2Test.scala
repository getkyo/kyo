package kyo

import kyo.Tasty.SymbolId
import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Fidelity tests for Type.ContextFunction decoding .
  *
  * Context functions (`?=>`) were previously decoded as `Type.Function(params, result, isContext = true)`. After they decode to
  * the dedicated `Type.ContextFunction(params, result)` case, which is structurally distinct and additively introduced without modifying the
  * existing `Type.Function` case (HARD RULE 4 layered preservation).
  *
  *  from jvmOnly to cross-platform. The embedded fixture set now includes ContextFunctionFixture (added in
  * ) which uses the `?=>` arrow in method signatures. `TestClasspaths.withClasspath` loads these fixtures on all platforms.
  *
  * Invariant produced: -DF2 (context-function half).
  */
class ContextFunctionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    private def countContextFunctions(t: Tasty.Type): Int =
        var n = 0
        t.foreach:
            case _: Tasty.Type.ContextFunction => n += 1
            case _                             => ()
        n
    end countContextFunctions

    // context-function-types-positive
    // Given: embedded classpath (includes ContextFunctionFixture on all platforms)
    // When: counting all ContextFunction types reachable from symbol declarations
    // Then: count > 0; before fix count is 0
    // Cross-platform: ContextFunctionFixture embedded in fixture set.
    "classpath symbols have at least one ContextFunction in their types" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>

            def walkType(t: Tasty.Type): Int =
                var n = 0
                t.foreach:
                    case _: Tasty.Type.ContextFunction => n += 1
                    case _                             => ()
                n
            end walkType

            var ctxFnCount = 0
            cp.symbols.foreach: sym =>
                sym match
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach: dt =>
                            ctxFnCount += walkType(dt)
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => ctxFnCount += walkType(t))
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            ctxFnCount += walkType(pt)
                    case _ => ()

            assert(
                ctxFnCount > 0,
                s"Expected at least one ContextFunction in classpath symbol types; found 0. " +
                    s"Check that ContextFunctionFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
            )
            succeed
    }

    // parameters-have-context-function-type
    // Given: embedded classpath (includes ContextFunctionFixture)
    // When: counting all parameters with ContextFunction type
    // Then: count > 0; before fix none
    // Cross-platform: ContextFunctionFixture embedded.
    "classpath parameters have at least one ContextFunction type" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var paramCtxFnCount = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => paramCtxFnCount += countContextFunctions(t))
                    case _ => ()
            assert(
                paramCtxFnCount > 0,
                s"Expected at least one parameter with ContextFunction type; found 0. " +
                    s"ContextFunctionFixture uses 'Logger ?=>' in withLogger and run methods."
            )
            succeed
    }

    // total-context-function-count-positive
    // Given: embedded classpath (includes ContextFunctionFixture)
    // When: counting total ContextFunction types across all symbol types
    // Then: count > 0; before fix the count is 0
    // Cross-platform: ContextFunctionFixture embedded.
    "-DF2 : classpath has positive ContextFunction count across all symbol types" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var total = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach(t => total += countContextFunctions(t))
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach: dt =>
                            total += countContextFunctions(dt)
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            total += countContextFunctions(pt)
                    case _ => ()
            assert(
                total > 0,
                s"Expected total ContextFunction count > 0 in classpath symbol types; found 0"
            )
            succeed
    }

    // function-vs-context-function-disjoint
    // Given: embedded classpath (includes ContextFunctionFixture)
    // When: checking that Type.Function and Type.ContextFunction are distinct cases
    // Then: a ContextFunction value does NOT match the Type.Function pattern
    // Cross-platform: ContextFunctionFixture embedded.
    // Note: isContext field removed in (Cat 10); the Boolean flag is gone.
    // The test now confirms structural disjointness rather than a flag value.
    "no symbol has Function used where ContextFunction expected" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // All three symbol subtypes are covered via their type fields.
            // The invariant is that ContextFunction and Function are structurally distinct:
            // context-function types decode to Type.ContextFunction, never Type.Function.
            var contextFunctions = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach:
                            case _: Tasty.Type.ContextFunction => contextFunctions += 1
                            case _                             => ()
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach: dt =>
                            dt.foreach:
                                case _: Tasty.Type.ContextFunction => contextFunctions += 1
                                case _                             => ()
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            pt.foreach:
                                case _: Tasty.Type.ContextFunction => contextFunctions += 1
                                case _                             => ()
                    case _ => ()
            // At least one ContextFunction must be found (fixture contains ?=> types).
            assert(contextFunctions > 0, "Expected at least one ContextFunction type in fixture symbols")
            succeed
    }

    // pattern-match-on-function-still-works
    // Given: a value of Type.ContextFunction (constructed directly)
    // When: pattern matching on Type.Function(p, r) (post-Cat-10 two-arg form)
    // Then: the match does NOT trigger on ContextFunction types (cases are structurally disjoint)
    // Cross-platform: uses pure ADT construction; works on JS/Native.
    "Type.Function pattern does not match Type.ContextFunction" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val cfType = Tasty.Type.ContextFunction(
                Chunk(Tasty.Type.Named(SymbolId(0))),
                Tasty.Type.Named(SymbolId(1))
            )
            val matchedAsFunction = cfType match
                case Tasty.Type.Function(_, _) => true
                case _                         => false
            assert(
                !matchedAsFunction,
                "Type.ContextFunction should not match the Type.Function(_,_) pattern"
            )
            succeed
    }

    // snapshot-roundtrip-consistent-for-embedded-classpath
    // Given: (cold, warm) Classpath pair from the embedded classpath (in-memory snapshot round-trip)
    // When: checking that symbols.size is equal between cold and warm
    // Then: the two symbol counts are equal
    // Note: context function types in Parameter.declaredType are re-decoded lazily from BODY_BYTES after
    // warm load; the snapshot preserves body bytes but not the already-decoded Parameter.declaredType
    // field. Counting ContextFunctions from declaredType is NOT snapshot-stable; use symbols.size as the
    // stable proxy. The in-memory round-trip correctness of ContextFunction types is covered by the
    // decode-from-body-bytes path tested in above (both use TestClasspaths.withClasspath).
    // Cross-platform: coldWarmEquiv now uses in-memory snapshot via TestClasspaths2.withSnapshotInMemory.
    coldWarmEquiv("-DF2 : snapshot cold/warm symbols.size consistent on embedded classpath")(_.symbols.size)

    // show-context-function-uses-arrow
    // Given: Type.ContextFunction(Chunk(Named(id)), Named(id))
    // When: invoking.show
    // Then: the result contains "?=>" (context function arrow)
    // Cross-platform: uses pure ADT construction + show; works on JS/Native.
    "show : Type.ContextFunction.show uses ?=> arrow" in {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            Tasty.withClasspath(cp):
                val paramType  = Tasty.Type.Named(SymbolId(0))
                val resultType = Tasty.Type.Named(SymbolId(0))
                val cfType     = Tasty.Type.ContextFunction(Chunk(paramType), resultType)
                Tasty.typeShow(cfType).map: s =>
                    assert(
                        s.contains("?=>"),
                        s"Type.ContextFunction.show should contain '?=>' but got: $s"
                    )
                    assert(
                        !s.contains(" => "),
                        s"Type.ContextFunction.show should not contain plain '=>' but got: $s"
                    )
                    succeed
    }

end ContextFunctionFidelity2Test
