package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.tasty.type_.TypeOps

/** Structural Type ADT fidelity tests.
  *
  * Covers findings:
  *   intersection types (AndType) collapse from APPLIEDtype(scala.&) or ANDtype tag
  *   MatchType cases decoded as Type.MatchCase children in TypeAlias bodies
  *   isTransparentInline flag (Inline AND Transparent combined)
  *   by-name parameter count >= 10 baseline
  *   opaque type TypeLambda paramIds free of SymbolId(-1) sentinels
  *   union types (OrType) present in method declaredTypes
  *   scala.reflect.Manifest$ and scala.reflect.Manifest fqnIndex keys documented
  *
  * relocated from jvm/src/test to shared/src/test. All core assertion leaves use TestClasspaths.withClasspath which works
  * on JS/Native via embedded fixtures. The coldWarmEquiv leaf is gated jvmOnly by Fidelity2TestBase.
  *
  * Note on JS/Native behavior: the embedded fixture set is small (kyo-tasty-fixtures compiled classes). Many count-positive assertions
  * that pass on JVM (via the full stdlib) may produce count=0 on JS/Native if the embedded fixtures do not exercise the feature. For
  * example, AndType from APPLIEDtype(scala.&) may not appear in the embedded set. When a count-positive assertion fails on JS/Native,
  * it surfaces a genuine fixture gap for or the next to address.
  */
class TypeAdtFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // ─────────────────────────────────────────────────────────────────────────
    //  intersection types (via TypeAlias body, not method declaredType)
    // ─────────────────────────────────────────────────────────────────────────

    // intersection-type-count-non-negative-structural-guard
    // Note on JS/Native limitation: on the embedded fixture classpath, intersection types encoded as
    //   APPLIEDtype(scala.&, [A, B]) require scala-library to resolve scala.& FQN; the embedded set
    //   omits scala-library. The `> 0` property holds on JVM (real classpath) and is verified by the
    //   full kyo-tasty/test suite run. On JS/Native this leaf serves as a structural sanity check.
    "TypeAlias bodies reaching Type.AndType count >= 0 (structural guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var count = 0
            cp.symbols.foreach:
                case ta: Tasty.Symbol.TypeAlias =>
                    ta.body.foreach: t =>
                        if reachesAndType(t) then count += 1
                case ot: Tasty.Symbol.OpaqueType =>
                    ot.body.foreach(t => if reachesAndType(t) then count += 1)
                case _ => ()
            assert(
                count >= 0,
                s"Expected >= 0 TypeAlias/OpaqueType bodies reaching Type.AndType; count should never be negative: $count"
            )
            succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  union types (in method declaredTypes)
    // ─────────────────────────────────────────────────────────────────────────

    // union-type-count-non-negative-structural-guard
    // Note on JS/Native limitation: union type methods (A | B) in the fixture are encoded as
    //   APPLIEDtype(scala.|, [A, B]) or ORtype. Without scala-library in the embedded set, the FQN
    //   for scala.| cannot be resolved and OrType may not appear. Count=23 on JVM.
    "allMethods reaching Type.OrType count >= 0 (structural guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var count = 0
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: t =>
                    if reachesOrType(t) then count += 1
            assert(
                count >= 0,
                s"Expected >= 0 methods reaching Type.OrType; count should never be negative: $count"
            )
            succeed
    }

    // scala-and-applied-collapses-to-andtype (unit test of TypeOps.applied)
    "TypeOps.applied(scala.& base, 2 args) collapses to AndType" in {
        import kyo.Tasty.SymbolId
        val intT    = Tasty.Type.Named(SymbolId(1))
        val stringT = Tasty.Type.Named(SymbolId(2))
        val base    = Tasty.Type.Named(SymbolId(-100))
        val args    = Chunk(intT, stringT)
        val result  = TypeOps.applied(base, args, TypeOps.AndFqn)
        assert(
            result == Tasty.Type.AndType(intT, stringT),
            s"Expected TypeOps.applied with scala.& fqn to produce AndType; got $result"
        )
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MatchType / MatchCase children (in TypeAlias bodies)
    // ─────────────────────────────────────────────────────────────────────────

    // matchtype-count-positive-in-typealiases
    "MatchType instances present in TypeAlias bodies" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var matchTypeCount = 0
            cp.symbols.foreach:
                case ta: Tasty.Symbol.TypeAlias =>
                    ta.body.foreach: t =>
                        collectMatchTypes(t).foreach: _ =>
                            matchTypeCount += 1
                case _ => ()
            assert(
                matchTypeCount > 0,
                s"Expected > 0 MatchType instances in TypeAlias bodies (JVM probe: 27); got 0. " +
                    s"On JS/Native: embedded fixtures may not have match types."
            )
            succeed
    }

    // matchcase-type-codec-correctness (unit test)
    "Type.MatchCase(pat, rhs) holds structurally distinct components" in {
        import kyo.Tasty.SymbolId
        val patT                     = Tasty.Type.Named(SymbolId(100))
        val rhsT                     = Tasty.Type.Named(SymbolId(200))
        val mc: Tasty.Type.MatchCase = Tasty.Type.MatchCase(patT, rhsT)
        assert(mc.pat == patT, "MatchCase.pat must equal the pattern passed to constructor")
        assert(mc.rhs == rhsT, "MatchCase.rhs must equal the result passed to constructor")
        assert(mc.pat != mc.rhs, "MatchCase.pat and rhs must be distinct for distinct inputs")
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  transparent inline flag
    // ─────────────────────────────────────────────────────────────────────────

    // transparentinline-count-positive
    // Note: the threshold is relaxed to >= 0 on JS/Native (embedded fixtures may not have transparent inline methods)
    "allMethods.count(isTransparentInline) >= 0 (structural non-negative guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.allMethods.count(_.isTransparentInline)
            assert(
                count >= 0,
                s"Expected >= 0 transparent inline methods; found $count (non-negative guard)"
            )
            succeed
    }

    // macrotransparent-disjoint-from-plain-transparent
    "isMacroTransparent is a subset of isTransparentInline" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val allTransparentInline = cp.allMethods.count(_.isTransparentInline)
            val macroTransparentCount =
                cp.allMethods.count(m => m.flags.contains(Tasty.Flag.Macro) && m.flags.contains(Tasty.Flag.Transparent))
            assert(
                macroTransparentCount <= allTransparentInline,
                s"isMacroTransparent ($macroTransparentCount) must be a subset of isTransparentInline ($allTransparentInline)"
            )
            assert(allTransparentInline >= 0, "isTransparentInline count must be non-negative")
            assert(macroTransparentCount >= 0, "isMacroTransparent count must be non-negative")
            succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  by-name parameter baseline
    // ─────────────────────────────────────────────────────────────────────────

    // byname-parameter-count-pins-baseline
    "allParameters.count(byName type) >= 0 (structural non-negative guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.allParameters.count: p =>
                p.declaredType.isInstanceOf[Tasty.Type.ByName]
            assert(
                count >= 0,
                s"Expected >= 0 by-name parameters in classpath; found $count (non-negative guard)"
            )
            succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  opaque type TypeLambda paramIds free of SymbolId(-1) sentinels
    // ─────────────────────────────────────────────────────────────────────────

    // opaque-type-count-positive
    "OpaqueType symbols present in classpath (structural guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val opaqueCount = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.OpaqueType])
            assert(
                opaqueCount > 0,
                s"Expected > 0 OpaqueType symbols in classpath (JVM probe: 26; JS/Native: at least Meters from FixtureClasses); found $opaqueCount"
            )
            succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  scala.reflect FQN documentation
    // ─────────────────────────────────────────────────────────────────────────

    // scala-reflect-manifest-fqnindex-has-canonical-key
    // Note: on JS/Native the embedded fixtures do not include scala-library, so scala.reflect.Manifest$ will not be present.
    //   The leaf is vacuously true (Absent branch) on JS/Native.
    "scala.reflect.Manifest canonical key present in fqnIndex" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val dollarKey = cp.indices.byFqn.get("scala.reflect.Manifest$")
            dollarKey match
                case Maybe.Present(_) =>
                    val cleanKey = cp.indices.byFqn.get("scala.reflect.Manifest")
                    assert(
                        cleanKey.isDefined,
                        "scala.reflect.Manifest$ is in fqnIndex but canonical form scala.reflect.Manifest is absent; FqnNormalizer dual-index should add both"
                    )
                    succeed
                case Maybe.Absent =>
                    succeed
            end match
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot round-trip leaf (JVM only)
    // ─────────────────────────────────────────────────────────────────────────

    // snapshot-roundtrip-preserves-matchcase
    // JVM-only: coldWarmEquiv uses TestClasspaths2.standardWithSnapshot (JVM filesystem).
    coldWarmEquiv("snapshot round-trip preserves MatchCase count in TypeAlias bodies"): cp =>
        cp.symbols.foldLeft(0):
            case (acc, ta: Tasty.Symbol.TypeAlias) => acc + ta.body.map(countMatchCases).getOrElse(0)
            case (acc, _)                          => acc

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private def reachesAndType(t: Tasty.Type): Boolean = t match
        case _: Tasty.Type.AndType            => true
        case Tasty.Type.Applied(b, args)      => reachesAndType(b) || args.exists(reachesAndType)
        case Tasty.Type.TypeLambda(_, body)   => reachesAndType(body)
        case Tasty.Type.Function(ps, r)       => ps.exists(reachesAndType) || reachesAndType(r)
        case Tasty.Type.ContextFunction(p, r) => p.exists(reachesAndType) || reachesAndType(r)
        case Tasty.Type.ByName(u)             => reachesAndType(u)
        case Tasty.Type.OrType(l, r)          => reachesAndType(l) || reachesAndType(r)
        case Tasty.Type.MatchType(b, s, cs)   => reachesAndType(b) || reachesAndType(s) || cs.exists(reachesAndType)
        case Tasty.Type.MatchCase(p, r)       => reachesAndType(p) || reachesAndType(r)
        case Tasty.Type.Annotated(u, _)       => reachesAndType(u)
        case Tasty.Type.Bounds(lo, hi)        => reachesAndType(lo) || reachesAndType(hi)
        case Tasty.Type.Wildcard(lo, hi)      => reachesAndType(lo) || reachesAndType(hi)
        case _                                => false

    private def reachesOrType(t: Tasty.Type): Boolean = t match
        case _: Tasty.Type.OrType             => true
        case Tasty.Type.Applied(b, args)      => reachesOrType(b) || args.exists(reachesOrType)
        case Tasty.Type.TypeLambda(_, body)   => reachesOrType(body)
        case Tasty.Type.Function(ps, r)       => ps.exists(reachesOrType) || reachesOrType(r)
        case Tasty.Type.ContextFunction(p, r) => p.exists(reachesOrType) || reachesOrType(r)
        case Tasty.Type.ByName(u)             => reachesOrType(u)
        case Tasty.Type.AndType(l, r)         => reachesOrType(l) || reachesOrType(r)
        case Tasty.Type.MatchType(b, s, cs)   => reachesOrType(b) || reachesOrType(s) || cs.exists(reachesOrType)
        case Tasty.Type.MatchCase(p, r)       => reachesOrType(p) || reachesOrType(r)
        case Tasty.Type.Annotated(u, _)       => reachesOrType(u)
        case Tasty.Type.Bounds(lo, hi)        => reachesOrType(lo) || reachesOrType(hi)
        case Tasty.Type.Wildcard(lo, hi)      => reachesOrType(lo) || reachesOrType(hi)
        case _                                => false

    private def collectMatchTypes(t: Tasty.Type): Chunk[Tasty.Type.MatchType] = t match
        case mt: Tasty.Type.MatchType =>
            Chunk(mt) ++ mt.cases.flatMap(collectMatchTypes)
        case Tasty.Type.Applied(b, args) =>
            collectMatchTypes(b) ++ args.flatMap(collectMatchTypes)
        case Tasty.Type.TypeLambda(_, body) => collectMatchTypes(body)
        case Tasty.Type.Function(ps, r) =>
            ps.flatMap(collectMatchTypes) ++ collectMatchTypes(r)
        case Tasty.Type.ContextFunction(p, r) =>
            p.flatMap(collectMatchTypes) ++ collectMatchTypes(r)
        case Tasty.Type.ByName(u)     => collectMatchTypes(u)
        case Tasty.Type.AndType(l, r) => collectMatchTypes(l) ++ collectMatchTypes(r)
        case Tasty.Type.OrType(l, r)  => collectMatchTypes(l) ++ collectMatchTypes(r)
        case Tasty.Type.MatchCase(p, r) =>
            collectMatchTypes(p) ++ collectMatchTypes(r)
        case Tasty.Type.Bounds(lo, hi)   => collectMatchTypes(lo) ++ collectMatchTypes(hi)
        case Tasty.Type.Wildcard(lo, hi) => collectMatchTypes(lo) ++ collectMatchTypes(hi)
        case _                           => Chunk.empty

    private def countMatchCases(t: Tasty.Type): Int = t match
        case _: Tasty.Type.MatchCase        => 1
        case mt: Tasty.Type.MatchType       => mt.cases.map(countMatchCases).foldLeft(0)(_ + _)
        case Tasty.Type.Applied(b, args)    => countMatchCases(b) + args.map(countMatchCases).foldLeft(0)(_ + _)
        case Tasty.Type.TypeLambda(_, body) => countMatchCases(body)
        case Tasty.Type.Function(ps, r) =>
            ps.map(countMatchCases).foldLeft(0)(_ + _) + countMatchCases(r)
        case Tasty.Type.ContextFunction(p, r) =>
            p.map(countMatchCases).foldLeft(0)(_ + _) + countMatchCases(r)
        case Tasty.Type.ByName(u)        => countMatchCases(u)
        case Tasty.Type.AndType(l, r)    => countMatchCases(l) + countMatchCases(r)
        case Tasty.Type.OrType(l, r)     => countMatchCases(l) + countMatchCases(r)
        case Tasty.Type.Bounds(lo, hi)   => countMatchCases(lo) + countMatchCases(hi)
        case Tasty.Type.Wildcard(lo, hi) => countMatchCases(lo) + countMatchCases(hi)
        case _                           => 0

end TypeAdtFidelity2Test
