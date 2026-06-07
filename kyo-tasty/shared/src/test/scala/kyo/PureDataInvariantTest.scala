package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Verifies that Symbol case classes are pure data: accessors are idempotent, structural fields
  * are stable across aliasing reads, and equality is reflexive.
  *
  * Runs cross-platform via the embedded TASTy fixture classpath.
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

    "Symbol accessors are idempotent (same value on second call)" in {
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

    "ClassLike parentTypes and annotations are stable across aliasing reads" in {
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

    "Symbol equality is reflexive (same instance equals itself)" in {
        openFixtureClasspath.map: cp =>
            val sample = cp.symbols.take(20)
            sample.foreach: sym =>
                assert(sym == sym, s"Symbol $sym: sym == sym returned false (reflexive equality broken)")
                assert(sym.hashCode == sym.hashCode, s"Symbol $sym: hashCode not stable")
            succeed
    }

end PureDataInvariantTest
