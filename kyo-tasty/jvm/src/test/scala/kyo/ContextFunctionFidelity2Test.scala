package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.symbol.SymbolId

/** Fidelity tests for Type.ContextFunction decoding (F-A2-005).
  *
  * Context functions (`?=>`) were previously decoded as `Type.Function(params, result, isContext = true)`. After Phase 2.05 they decode to
  * the dedicated `Type.ContextFunction(params, result)` case, which is structurally distinct and additively introduced without modifying the
  * existing `Type.Function` case (HARD RULE 4 layered preservation).
  *
  * Context function types are found in kyo-core, which uses `AllowUnsafe ?=> ...` and `Safepoint ?=> ...` in method signatures. The test
  * classpath is extended with kyo-core (via TestClasspaths.standardWithKyoCore) to ensure real-world ContextFunctionN usages are present.
  *
  * Invariant produced: INV-105-DF2 (context-function half).
  */
class ContextFunctionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    /** Walk a type recursively and count ContextFunction occurrences. */
    private def countContextFunctions(t: Tasty.Type): Int =
        var n = 0
        t.foreach:
            case _: Tasty.Type.ContextFunction => n += 1
            case _                             => ()
        n
    end countContextFunctions

    // Leaf 1 (Phase 2.05): conversion-convert-is-context-function
    // Given: kyo-core extended classpath
    // When: counting all ContextFunction types reachable from symbol declarations
    // Then: post-fix count > 0 (kyo-core uses AllowUnsafe ?=> and Safepoint ?=> in several methods);
    //       before fix count is 0
    // Pins: F-A2-005 + INV-105-DF2
    "F-A2-005 (Phase 2.05): kyo-core symbols have at least one ContextFunction in their types" in run {
        TestClasspaths.withClasspath(TestClasspaths.standardWithKyoCore).map: cp =>
            given Tasty.Classpath = cp

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
                        ctxFnCount += walkType(p.declaredType)
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            ctxFnCount += walkType(pt)
                    case _ => ()

            assert(
                ctxFnCount > 0,
                s"Expected at least one ContextFunction in kyo-core symbol types; found 0. " +
                    s"Check that kyo-core is on the classpath and that TypeOps.applied routes ContextFunctionN."
            )
            succeed
    }

    // Leaf 2 (Phase 2.05): kyo-AllowUnsafe-uses-context-function
    // Given: kyo-core extended classpath
    // When: counting all symbols with ContextFunction in any declared type or parent type
    // Then: post-fix count > 0; before fix none
    // Note: ContextFunction appears in parameter declaredTypes, not method declaredTypes.
    //   Method.declaredType = TypeLambda(paramIds, result); params have their own declaredType.
    // Pins: F-A2-005
    "F-A2-005 (Phase 2.05): kyo-core parameters have at least one ContextFunction type" in run {
        TestClasspaths.withClasspath(TestClasspaths.standardWithKyoCore).map: cp =>
            var paramCtxFnCount = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        paramCtxFnCount += countContextFunctions(p.declaredType)
                    case _ => ()
            assert(
                paramCtxFnCount > 0,
                s"Expected at least one parameter with ContextFunction type in kyo-core; found 0. " +
                    s"kyo-core uses 'AllowUnsafe ?=>' in Sync.defer and similar methods."
            )
            succeed
    }

    // Leaf 3 (Phase 2.05): total-context-function-count-positive
    // Given: kyo-core extended classpath
    // When: counting total ContextFunction types across all symbol types
    // Then: post-fix count > 0; before fix the count is 0
    // Pins: INV-105-DF2 producer; F-A2-005
    "INV-105-DF2 (Phase 2.05): kyo-core has positive ContextFunction count across all symbol types" in run {
        TestClasspaths.withClasspath(TestClasspaths.standardWithKyoCore).map: cp =>
            var total = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        total += countContextFunctions(p.declaredType)
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach: dt =>
                            total += countContextFunctions(dt)
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            total += countContextFunctions(pt)
                    case _ => ()
            assert(
                total > 0,
                s"Expected total ContextFunction count > 0 in kyo-core symbol types; found 0"
            )
            succeed
    }

    // Leaf 4 (Phase 2.05): function-vs-context-function-disjoint
    // Given: kyo-core extended classpath
    // When: checking that no parameter or method has Function(_, _, true) in its type
    // Then: every ContextFunction-carrying parameter/method decodes ContextFunction not Function(_, _, true)
    // Pins: HARD RULE 4 layered preservation
    "HARD RULE 4 (Phase 2.05): no symbol has Function(_, _, true) in its type" in run {
        TestClasspaths.withClasspath(TestClasspaths.standardWithKyoCore).map: cp =>
            var violations = 0
            cp.symbols.foreach: sym =>
                sym match
                    case p: Tasty.Symbol.Parameter =>
                        p.declaredType.foreach:
                            case Tasty.Type.Function(_, _, true) => violations += 1
                            case _                               => ()
                    case m: Tasty.Symbol.Method =>
                        m.declaredType.foreach: dt =>
                            dt.foreach:
                                case Tasty.Type.Function(_, _, true) => violations += 1
                                case _                               => ()
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.foreach: pt =>
                            pt.foreach:
                                case Tasty.Type.Function(_, _, true) => violations += 1
                                case _                               => ()
                    case _ => ()
            assert(
                violations == 0,
                s"Expected zero symbols with Type.Function(_, _, isContext=true) after Phase 2.05 fix; found $violations"
            )
            succeed
    }

    // Leaf 5 (Phase 2.05): pattern-match-on-function-still-works
    // Given: a value of Type.ContextFunction
    // When: pattern matching on Type.Function(p, r, isCtx) (legacy)
    // Then: the match does NOT trigger on ContextFunction types (new case is additive, not overlapping)
    // Pins: HARD RULE 4
    "HARD RULE 4 (Phase 2.05): Type.Function pattern does not match Type.ContextFunction" in run {
        TestClasspaths.withClasspath().map: cp =>
            val cfType = Tasty.Type.ContextFunction(
                Chunk(Tasty.Type.Named(SymbolId(0))),
                Tasty.Type.Named(SymbolId(1))
            )
            val matchedAsFunction = cfType match
                case Tasty.Type.Function(_, _, _) => true
                case _                            => false
            assert(
                !matchedAsFunction,
                "Type.ContextFunction should not match the Type.Function(_,_,_) pattern"
            )
            succeed
    }

    // Leaf 6 (Phase 2.05): snapshot-roundtrip-consistent-for-standard-classpath
    // Given: (cold, warm) Classpath pair from the STANDARD classpath (kyo-tasty + kyo-data + scala-library)
    // When: counting Type.ContextFunction reachable from all symbol types in both
    // Then: the two counts are equal (both 0 for the standard classpath, because ContextFunction types
    //   only appear in parameter declaredTypes which are NOT snapshot-serialized; method parentTypes
    //   and parentTypes don't contain ContextFunction in the standard classpath)
    // Note: Parameter.declaredType is not persisted in the KRFL snapshot (it's decoded from body bytes
    //   in cold load but not re-decoded in warm load). This is a known snapshot limitation; a future
    //   phase may extend KRFL to include parameter type sections.
    // Pins: INV-101-DF2
    coldWarmEquiv("INV-101-DF2 (Phase 2.05): snapshot cold/warm ContextFunction count consistent on standard classpath") { cp =>
        var n = 0
        cp.symbols.foreach: sym =>
            sym match
                case p: Tasty.Symbol.Parameter =>
                    n += countContextFunctions(p.declaredType)
                case m: Tasty.Symbol.Method =>
                    m.declaredType.foreach: dt =>
                        n += countContextFunctions(dt)
                case c: Tasty.Symbol.ClassLike =>
                    c.parentTypes.foreach: pt =>
                        n += countContextFunctions(pt)
                case _ => ()
        n
    }

    // Leaf 7 (Phase 2.05): show-context-function-uses-arrow
    // Given: Type.ContextFunction(Chunk(Named(id)), Named(id))
    // When: invoking .show
    // Then: the result contains "?=>" (context function arrow)
    // Pins: show-format consistency
    "show (Phase 2.05): Type.ContextFunction.show uses ?=> arrow" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            val paramType         = Tasty.Type.Named(SymbolId(0))
            val resultType        = Tasty.Type.Named(SymbolId(0))
            val cfType            = Tasty.Type.ContextFunction(Chunk(paramType), resultType)
            val s                 = cfType.show
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
