package kyo

/** Verifies that Symbol case classes are pure data: accessors are idempotent, structural fields
  * are stable across aliasing reads, and equality is reflexive.
  *
  * Runs cross-platform via the embedded TASTy fixture classpath.
  */
class PureDataInvariantTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val fixturePickles: Chunk[Tasty.Pickle] = Chunk(
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty)),
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty)),
        Tasty.Pickle("generic-box", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.genericBoxTasty)),
        Tasty.Pickle("outer", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.outerTasty)),
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty)),
        Tasty.Pickle("color", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.colorTasty)),
        Tasty.Pickle("base-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.baseClassTasty)),
        Tasty.Pickle("child-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.childClassTasty)),
        Tasty.Pickle("shape", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.shapeTasty))
    )

    "Symbol accessors are idempotent (same value on second call)" in {
        Tasty.withPickles(fixturePickles) {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                val sample            = classpath.symbols.take(50)
                sample.foreach { symbol =>
                    import Tasty.Name.asString
                    val name1 = symbol.name.asString
                    val name2 = symbol.name.asString
                    assert(name1 == name2, s"Symbol $symbol: name not stable ($name1 vs $name2)")

                    val kind1 = symbol.kind
                    val kind2 = symbol.kind
                    assert(kind1 == kind2, s"Symbol $symbol: kind not stable ($kind1 vs $kind2)")

                    // Flags compared via bits Long (AnyVal, no CanEqual)
                    val flags1 = symbol.flags.bits
                    val flags2 = symbol.flags.bits
                    assert(flags1 == flags2, s"Symbol $symbol: flags.bits not stable ($flags1 vs $flags2)")
                }
                succeed
            }
        }
    }

    "ClassLike parentTypes and annotations are stable across aliasing reads" in {
        Tasty.withPickles(fixturePickles) {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                val sample            = classpath.symbols.take(50)
                sample.foreach { symbol =>
                    symbol match
                        case cl: Tasty.Symbol.ClassLike =>
                            val parents1 = cl.parentTypes
                            val parents2 = cl.parentTypes
                            assert(parents1 == parents2, s"ClassLike $symbol: parentTypes aliasing read differs")

                            val anns1 = cl.annotations
                            val anns2 = cl.annotations
                            assert(anns1 == anns2, s"ClassLike $symbol: annotations aliasing read differs")
                        case _ => ()
                }
                succeed
            }
        }
    }

    "Symbol equality is reflexive (same instance equals itself)" in {
        Tasty.withPickles(fixturePickles) {
            Tasty.classpath.map { classpath =>
                val sample = classpath.symbols.take(20)
                sample.foreach { symbol =>
                    assert(symbol == symbol, s"Symbol $symbol: symbol == symbol returned false (reflexive equality broken)")
                    assert(symbol.hashCode == symbol.hashCode, s"Symbol $symbol: hashCode not stable")
                }
                succeed
            }
        }
    }

end PureDataInvariantTest
