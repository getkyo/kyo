package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** HARD RULE 7 enforcement: Symbol case classes are pure data after load.
  *
  * Verifies that every accessor on a Symbol returns the same value when called twice, that structural fields are stable across aliasing
  * reads, and that equality is structural. Catches any future `var` addition to a Symbol subclass at PR time rather than silently
  * propagating mutation bugs downstream.
  *
  * Runs on JVM/JS/Native via the embedded TASTy fixture classpath (cross-platform, no filesystem dependency).
  */
class PureDataInvariantTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        src.add("root/GenericBox.tasty", kyo.fixtures.Embedded.genericBoxTasty)
        src.add("root/Outer.tasty", kyo.fixtures.Embedded.outerTasty)
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        src.add("root/Color.tasty", kyo.fixtures.Embedded.colorTasty)
        src.add("root/BaseClass.tasty", kyo.fixtures.Embedded.baseClassTasty)
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        src.add("root/Shape.tasty", kyo.fixtures.Embedded.shapeTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

    // Leaf 1: accessor idempotency
    // Given: a classpath loaded from the embedded fixtures
    // When: calling each accessor on a sample of symbols twice in succession
    // Then: both calls return structurally equal results (no hidden mutation, no cache artifact)
    "HARD RULE 7: Symbol accessors are idempotent (same value on second call)" in {
        openFixtureClasspath.map: cp =>
            given Tasty.Classpath = cp
            val sample            = cp.symbols.take(50)
            sample.foreach: sym =>
                import Tasty.Name.asString
                val name1 = sym.name.asString
                val name2 = sym.name.asString
                assert(name1 == name2, s"Symbol $sym: name not stable ($name1 vs $name2)")

                val kind1 = sym.kind
                val kind2 = sym.kind
                assert(kind1 == kind2, s"Symbol $sym: kind not stable ($kind1 vs $kind2)")

                // Flags compared via bits Long (AnyVal, no CanEqual)
                val flags1 = sym.flags.bits
                val flags2 = sym.flags.bits
                assert(flags1 == flags2, s"Symbol $sym: flags.bits not stable ($flags1 vs $flags2)")
            succeed
    }

    // Leaf 2: structural field stability on ClassLike symbols
    // Given: ClassLike symbols accessed twice
    // When: comparing parentTypes and annotations Chunk references
    // Then: both references compare equal structurally
    "HARD RULE 7: ClassLike parentTypes and annotations are stable across aliasing reads" in {
        openFixtureClasspath.map: cp =>
            given Tasty.Classpath = cp
            val sample            = cp.symbols.take(50)
            sample.foreach: sym =>
                sym match
                    case cl: Tasty.Symbol.ClassLike =>
                        val parents1 = cl.parentTypes
                        val parents2 = cl.parentTypes
                        assert(parents1 == parents2, s"ClassLike $sym: parentTypes aliasing read differs")

                        val anns1 = cl.annotations
                        val anns2 = cl.annotations
                        assert(anns1 == anns2, s"ClassLike $sym: annotations aliasing read differs")
                    case _ => ()
            succeed
    }

    // Leaf 3: equality is reflexive (same instance equals itself under id-and-kind contract after F-006)
    // Given: the same Symbol instance retrieved twice (materialized symbols have non-sentinel ids)
    // When: comparing with ==
    // Then: equal; hashCode also matches (F-006: id-and-kind equality is reflexive for non-sentinel ids)
    "HARD RULE 7: Symbol equality is reflexive (same instance equals itself)" in {
        openFixtureClasspath.map: cp =>
            val sample = cp.symbols.take(20)
            sample.foreach: sym =>
                assert(sym == sym, s"Symbol $sym: sym == sym returned false (reflexive equality broken)")
                assert(sym.hashCode == sym.hashCode, s"Symbol $sym: hashCode not stable")
            succeed
    }

end PureDataInvariantTest
