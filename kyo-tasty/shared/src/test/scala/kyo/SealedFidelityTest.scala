package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.symbol.SymbolKind

/** Fidelity tests for permittedSubclasses populated from @scala.annotation.internal.Child
  * annotations.
  *
  * One case confirms that Symbol.EnumCase is a proper subtype of Symbol.Class.
  *
  * Uses embedded fixture sealed hierarchies (kyo.fixtures.Animal/Vehicle/NonSealedMarker) instead
  * of stdlib scala.Option / scala.util.Either, so the four cases run cross-platform.
  */
class SealedFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    //   animal-permitted-subclasses
    "kyo.fixtures.Animal.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty) contains Dog and Cat" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findClassLike("kyo.fixtures.Animal") match
                case Present(animalSym) =>
                    val children = (animalSym match
                        case c: Tasty.Symbol.Class =>
                            c.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case t: Tasty.Symbol.Trait =>
                            t.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case _ => Chunk.empty
                    )
                    if children.isEmpty then
                        fail(
                            "kyo.fixtures.Animal.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty) was empty; expected Chunk(Dog, Cat)"
                        )
                    else
                        Kyo.foreach(children)(c => Sync.defer(classpath.computeFullName(c).asString)).map { fullNameList =>
                            val fullNames = fullNameList.toSet
                            assert(
                                fullNames.exists(f => f.endsWith("Dog") || f.endsWith(".Dog")),
                                s"Expected kyo.fixtures.Dog in permittedSubclasses of kyo.fixtures.Animal; got $fullNames"
                            )
                            assert(
                                fullNames.exists(f => f.endsWith("Cat") || f.endsWith(".Cat")),
                                s"Expected kyo.fixtures.Cat in permittedSubclasses of kyo.fixtures.Animal; got $fullNames"
                            )
                            succeed
                        }
                    end if
                case Absent =>
                    fail("classpath.findClassLike(kyo.fixtures.Animal) returned Absent; Animal fixture must be in classpath")
        }
    }

    //   vehicle-permitted-subclasses
    "kyo.fixtures.Vehicle.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty) contains Car and Bike" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findClassLike("kyo.fixtures.Vehicle") match
                case Present(symbol) =>
                    val children = (symbol match
                        case c: Tasty.Symbol.Class =>
                            c.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case t: Tasty.Symbol.Trait =>
                            t.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case _ => Chunk.empty
                    )
                    if children.isEmpty then
                        fail(
                            "kyo.fixtures.Vehicle.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty) was empty; expected Chunk(Car, Bike)"
                        )
                    else
                        Kyo.foreach(children)(c => Sync.defer(classpath.computeFullName(c).asString)).map { fullNameList =>
                            val fullNames = fullNameList.toSet
                            assert(
                                fullNames.exists(f => f.endsWith("Car") || f.endsWith(".Car")),
                                s"Expected kyo.fixtures.Car in permittedSubclasses of kyo.fixtures.Vehicle; got $fullNames"
                            )
                            assert(
                                fullNames.exists(f => f.endsWith("Bike") || f.endsWith(".Bike")),
                                s"Expected kyo.fixtures.Bike in permittedSubclasses of kyo.fixtures.Vehicle; got $fullNames"
                            )
                            succeed
                        }
                    end if
                case Absent =>
                    fail("classpath.findClassLike(kyo.fixtures.Vehicle) returned Absent")
        }
    }

    // every-sealed-class-has-permits
    // classpath (115/119 sealed classes). Embedded fixtures (JS/Native) are 100%: all fixture sealed hierarchies
    // (Animal, Vehicle, SealedBase, Color, Shape) have permits. The 85% floor holds on all three platforms.
    ">= 85% of sealed classes have permittedSubclasses populated" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val sealedClasses = classpath.allClassLike.filter(_.isSealed)
            val total         = sealedClasses.size
            assert(total > 0, s"No sealed classes found on classpath; fixtures must include at least Animal, Vehicle, SealedBase")
            val withPermits = sealedClasses.count(sc =>
                sc match
                    case c: Tasty.Symbol.Class => c.permittedSubclassIds.exists(_.nonEmpty);
                    case t: Tasty.Symbol.Trait => t.permittedSubclassIds.exists(_.nonEmpty);
                    case _                     => false
            )
            val pct = withPermits.toDouble / total.toDouble * 100.0
            assert(
                pct >= 85.0,
                s"Only ${withPermits}/${total} (${pct.toInt}%) of sealed classes have permittedSubclasses populated; expected >= 85%"
            )
            succeed
        }
    }

    //   non-sealed-class-returns-empty
    "non-sealed class permittedSubclasses returns empty Chunk" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findClass("kyo.fixtures.NonSealedMarker") match
                case Present(s) =>
                    assert(
                        !s.isSealed,
                        s"Expected kyo.fixtures.NonSealedMarker to be non-sealed but got isSealed=${s.isSealed}"
                    )
                    val children = s.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty)
                    Kyo.foreach(children)(c => Sync.defer(classpath.computeFullName(c).asString)).map { childFullNames =>
                        assert(
                            children.isEmpty,
                            s"Expected empty Chunk for non-sealed kyo.fixtures.NonSealedMarker.permittedSubclassIds.map(_.flatMap(id => classpath.symbol(id).toChunk)).getOrElse(Chunk.empty); " +
                                s"got ${children.size} children: " +
                                childFullNames.mkString(", ")
                        )
                        succeed
                    }
                case Absent =>
                    fail("classpath.findClass(kyo.fixtures.NonSealedMarker) returned Absent; NonSealedMarker fixture must be in classpath")
            end match
        }
    }

    //   enum-case symbols pattern-match as Symbol.EnumCase
    //   provide EnumCase symbols on JS/Native. The leaf succeeds vacuously when enumCases.isEmpty.
    "enum-case symbols pattern-match as Symbol.EnumCase" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val enumCases = classpath.symbols.filter(_.kind == SymbolKind.EnumCase)
            if enumCases.isEmpty then
                succeed
            else
                val asEnumCaseCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
                assert(
                    asEnumCaseCount == enumCases.size,
                    s"Expected all ${enumCases.size} EnumCase-kind symbols to be Symbol.EnumCase instances, " +
                        s"but only $asEnumCaseCount matched. " +
                        s"Symbol.EnumCase must be a Symbol.EnumCase, not a Symbol.Class."
                )
                // Symbol.EnumCase is a peer of Symbol.Class under Symbol.ClassLike, not a subtype of Symbol.Class.
                val asClassLikeCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.ClassLike])
                assert(
                    asClassLikeCount == enumCases.size,
                    s"Expected Symbol.EnumCase to match Symbol.ClassLike, but $asClassLikeCount out of ${enumCases.size} did"
                )
                val asClassCount = enumCases.count(_.isInstanceOf[Tasty.Symbol.Class])
                assert(
                    asClassCount == 0,
                    s"Symbol.EnumCase is NOT a subtype of Symbol.Class; expected 0 matches but got $asClassCount"
                )
                succeed
            end if
        }
    }

end SealedFidelityTest
