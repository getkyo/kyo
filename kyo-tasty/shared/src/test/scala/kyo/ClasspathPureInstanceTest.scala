package kyo

import kyo.Tasty.SymbolId

/** Tests for the pure Classpath instance methods (owner, fullName, binaryName, show, ownersChain,
  * companion, signature, paramLists) that carry no effect row and require no Frame.
  *
  * Tests call the methods directly on a Classpath value obtained from
  * Sync.defer { Classpath.make(...) }. There is no AllowUnsafe.embrace.danger import because none
  * of the tested methods require it.
  *
  * Fixture layout:
  *   0  -> Symbol.Package "shop"            (ownerId = -1; memberIds = [1,2,3])
  *   1  -> Symbol.Class   "Dog"             (ownerId = 0;  declarationIds = [4,7,8]; typeParamIds = [])
  *   2  -> Symbol.Object  "Dog$"            (ownerId = 0;  companion of id 1)
  *   3  -> Symbol.Class   "Inner"           (ownerId = 1)
  *   4  -> Symbol.Method  "bark"            (ownerId = 1;  paramListIds = [[5]]; declaredType = Function([Named(6)], Named(6)))
  *   5  -> Symbol.Parameter "loudness"      (ownerId = 4;  declaredType = Named(6))
  *   6  -> Symbol.Class   "Int"             (ownerId = 0)
  *   7  -> Symbol.Method  "f"               (ownerId = 1;  paramListIds = [[9],[10]]; multi-list)
  *   8  -> Symbol.Method  "g"               (ownerId = 1;  paramListIds = [[]]; no-arg)
  *   9  -> Symbol.Parameter "x"             (ownerId = 7;  declaredType = Named(6))
  *   10 -> Symbol.Parameter "y"             (ownerId = 7;  declaredType = Named(11))
  *   11 -> Symbol.Class   "String"          (ownerId = 0)
  *
  * Root symbol: SymbolId(-1) (not registered; tests verify absence via Maybe.Absent).
  */
class ClasspathPureInstanceTest extends kyo.test.Test[Any]:

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val shopPkg = Tasty.Symbol.Package(
                SymbolId(0),
                Tasty.Name("shop"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3))
            )
            val dogClass = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("Dog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(SymbolId(4), SymbolId(7), SymbolId(8)),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val dogObject = Tasty.Symbol.Object(
                SymbolId(2),
                Tasty.Name("Dog"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty
            )
            val innerClass = Tasty.Symbol.Class(
                SymbolId(3),
                Tasty.Name("Inner"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val barkMethod = Tasty.Symbol.Method(
                SymbolId(4),
                Tasty.Name("bark"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Function(Chunk(Tasty.Type.Named(SymbolId(6))), Tasty.Type.Named(SymbolId(6)))),
                paramListIds = Chunk(Chunk(SymbolId(5))),
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )
            val loudnessParam = Tasty.Symbol.Parameter(
                SymbolId(5),
                Tasty.Name("loudness"),
                Tasty.Flags.empty,
                SymbolId(4),
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Named(SymbolId(6))),
                Maybe.Absent,
                Chunk.empty
            )
            val intClass = Tasty.Symbol.Class(
                SymbolId(6),
                Tasty.Name("Int"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val xParam = Tasty.Symbol.Parameter(
                SymbolId(9),
                Tasty.Name("x"),
                Tasty.Flags.empty,
                SymbolId(7),
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Named(SymbolId(6))),
                Maybe.Absent,
                Chunk.empty
            )
            val yParam = Tasty.Symbol.Parameter(
                SymbolId(10),
                Tasty.Name("y"),
                Tasty.Flags.empty,
                SymbolId(7),
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Named(SymbolId(11))),
                Maybe.Absent,
                Chunk.empty
            )
            val stringClass = Tasty.Symbol.Class(
                SymbolId(11),
                Tasty.Name("String"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val fMethod = Tasty.Symbol.Method(
                SymbolId(7),
                Tasty.Name("f"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Named(SymbolId(6))),
                paramListIds = Chunk(Chunk(SymbolId(9)), Chunk(SymbolId(10))),
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )
            val gMethod = Tasty.Symbol.Method(
                SymbolId(8),
                Tasty.Name("g"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Present(Tasty.Type.Named(SymbolId(6))),
                paramListIds = Chunk(Chunk.empty),
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )
            Tasty.Classpath.make(
                symbols = Chunk(
                    shopPkg,
                    dogClass,
                    dogObject,
                    innerClass,
                    barkMethod,
                    loudnessParam,
                    intClass,
                    fMethod,
                    gMethod,
                    xParam,
                    yParam,
                    stringClass
                ),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(1), SymbolId(2)),
                packageIds = Chunk(SymbolId(0)),
                fullNameIndex = Dict(
                    "shop"        -> SymbolId(0),
                    "shop.Dog"    -> SymbolId(1),
                    "shop.Dog$"   -> SymbolId(2),
                    "shop.Inner"  -> SymbolId(3),
                    "shop.Int"    -> SymbolId(6),
                    "shop.String" -> SymbolId(11)
                ),
                packageIndex = Dict("shop" -> SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict(SymbolId(1) -> SymbolId(2), SymbolId(2) -> SymbolId(1)),
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "Classpath.owner pure shape: result is Maybe[Symbol] with no Frame required" in {
        buildFixture.map { classpath =>
            val result: Maybe[Tasty.Symbol] = classpath.owner(classpath.symbol(SymbolId(4)).get)
            assert(result.isDefined, "owner of barkMethod must be present")
            succeed
        }
    }

    "Classpath.owner returns the owning class for a method" in {
        buildFixture.map { classpath =>
            val barkMethod = classpath.symbol(SymbolId(4)).get
            val dogClass   = classpath.symbol(SymbolId(1)).get
            assert(classpath.owner(barkMethod) == Maybe.Present(dogClass), "bark's owner must be the Dog class")
            succeed
        }
    }

    "Classpath.owner returns Maybe.Absent for a root-ownered symbol" in {
        buildFixture.map { classpath =>
            val shopPkg = classpath.symbol(SymbolId(0)).get
            assert(classpath.owner(shopPkg) == Maybe.Absent, "shop package has ownerId=-1 so owner returns Absent")
            succeed
        }
    }

    "Classpath.fullName returns the dotted fully-qualified name for a class in a package" in {
        import Tasty.Name.asString
        buildFixture.map { classpath =>
            val dogClass = classpath.symbol(SymbolId(1)).get
            assert(
                classpath.fullName(dogClass).asString == "shop.Dog",
                s"Expected 'shop.Dog' but got '${classpath.fullName(dogClass).asString}'"
            )
            succeed
        }
    }

    "Classpath.fullName returns the dotted fully-qualified name for a nested class" in {
        import Tasty.Name.asString
        buildFixture.map { classpath =>
            val innerClass = classpath.symbol(SymbolId(3)).get
            assert(
                classpath.fullName(innerClass).asString == "shop.Dog.Inner",
                s"Expected 'shop.Dog.Inner' but got '${classpath.fullName(innerClass).asString}'"
            )
            succeed
        }
    }

    "Classpath.binaryName uses dollar-separator for nested classes" in {
        buildFixture.map { classpath =>
            val innerClass = classpath.symbol(SymbolId(3)).get
            val binary     = classpath.binaryName(innerClass)
            assert(binary.contains("$"), s"Expected dollar in binary name for nested class; got '$binary'")
            succeed
        }
    }

    "Classpath.show with default FullyQualified returns dotted fully-qualified name" in {
        buildFixture.map { classpath =>
            val dogClass = classpath.symbol(SymbolId(1)).get
            assert(classpath.show(dogClass) == "shop.Dog", s"Expected 'shop.Dog' but got '${classpath.show(dogClass)}'")
            succeed
        }
    }

    "Classpath.show with explicit Code format returns code-form signature" in {
        buildFixture.map { classpath =>
            val dogClass = classpath.symbol(SymbolId(1)).get
            val result   = classpath.show(dogClass, Tasty.ShowFormat.Code)
            assert(result.startsWith("class "), s"Expected code-form starting with 'class ' but got '$result'")
            succeed
        }
    }

    "Classpath.show with Simple format returns simple name" in {
        buildFixture.map { classpath =>
            val innerClass = classpath.symbol(SymbolId(3)).get
            assert(
                classpath.show(innerClass, Tasty.ShowFormat.Simple) == "Inner",
                s"Expected 'Inner' but got '${classpath.show(innerClass, Tasty.ShowFormat.Simple)}'"
            )
            succeed
        }
    }

    "Classpath.ownersChain returns full chain from method to root package" in {
        buildFixture.map { classpath =>
            val barkMethod = classpath.symbol(SymbolId(4)).get
            val dogClass   = classpath.symbol(SymbolId(1)).get
            val shopPkg    = classpath.symbol(SymbolId(0)).get
            val chain      = classpath.ownersChain(barkMethod)
            assert(chain.size >= 3, s"Expected at least 3 elements in chain but got ${chain.size}: $chain")
            assert(chain(0) == barkMethod, "first element must be the symbol itself")
            assert(chain(1) == dogClass, "second element must be the owning class")
            assert(chain(2) == shopPkg, "third element must be the owning package")
            succeed
        }
    }

    "Classpath.ownersChain for a root-owned symbol returns Chunk containing only that symbol" in {
        buildFixture.map { classpath =>
            val shopPkg = classpath.symbol(SymbolId(0)).get
            assert(
                classpath.ownersChain(shopPkg) == Chunk(shopPkg),
                s"package at root should contain only itself; got ${classpath.ownersChain(shopPkg)}"
            )
            succeed
        }
    }

    "Classpath.ownersChain terminates on unresolvable owner" in {
        val loneMethod = Tasty.Symbol.Method(
            SymbolId(99),
            Tasty.Name("orphan"),
            Tasty.Flags.empty,
            SymbolId(50),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(loneMethod),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fullNameIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            val chain = classpath.ownersChain(loneMethod)
            assert(chain.size == 1, s"Chain must contain only the symbol itself when owner is unresolvable; got $chain")
            assert(chain(0) == loneMethod)
            succeed
        }
    }

    "Classpath.companion returns the companion object for a class" in {
        buildFixture.map { classpath =>
            val dogClass  = classpath.symbol(SymbolId(1)).get
            val dogObject = classpath.symbol(SymbolId(2)).get
            assert(
                classpath.companion(dogClass) == Maybe.Present(dogObject),
                s"Dog class companion must be the Dog object; got ${classpath.companion(dogClass)}"
            )
            succeed
        }
    }

    "Classpath.companion returns Maybe.Absent for a method symbol" in {
        buildFixture.map { classpath =>
            val barkMethod = classpath.symbol(SymbolId(4)).get
            assert(classpath.companion(barkMethod) == Maybe.Absent, "methods have no companion")
            succeed
        }
    }

    "Classpath.signature returns the Scala-syntax string for a method" in {
        buildFixture.map { classpath =>
            classpath.symbol(SymbolId(4)).get match
                case barkMethod: Tasty.Symbol.Method =>
                    val sig = classpath.signature(barkMethod)
                    assert(sig.startsWith("def "), s"Signature must start with 'def '; got '$sig'")
                    assert(sig.contains("bark"), s"Signature must contain method name; got '$sig'")
                    assert(sig.contains("loudness"), s"Signature must contain param name; got '$sig'")
                    succeed
                case other =>
                    fail(s"expected Tasty.Symbol.Method for SymbolId(4), got $other")
        }
    }

    "Classpath.paramLists returns Chunk of Chunk for a multi-param-list method" in {
        buildFixture.map { classpath =>
            classpath.symbol(SymbolId(7)).get match
                case fMethod: Tasty.Symbol.Method =>
                    val lists = classpath.paramLists(fMethod)
                    assert(lists.size == 2, s"Expected 2 param lists but got ${lists.size}")
                    assert(lists(0).size == 1, s"First list must have 1 param but got ${lists(0).size}")
                    assert(lists(1).size == 1, s"Second list must have 1 param but got ${lists(1).size}")
                    succeed
                case other =>
                    fail(s"expected Tasty.Symbol.Method for SymbolId(7), got $other")
        }
    }

    "Classpath.paramLists returns Chunk(Chunk.empty) for a no-arg method" in {
        buildFixture.map { classpath =>
            classpath.symbol(SymbolId(8)).get match
                case gMethod: Tasty.Symbol.Method =>
                    val lists = classpath.paramLists(gMethod)
                    assert(lists.size == 1, s"Expected 1 param list for no-arg method but got ${lists.size}")
                    assert(lists(0).isEmpty, s"The single param list must be empty but got ${lists(0)}")
                    succeed
                case other =>
                    fail(s"expected Tasty.Symbol.Method for SymbolId(8), got $other")
        }
    }

    "Classpath.show requires no Frame context: pure method signature audit" in {
        buildFixture.map { classpath =>
            val dogClass = classpath.symbol(SymbolId(1)).get
            // This compiles without any `using Frame` in scope, proving the method is pure.
            val result: String = classpath.show(dogClass)
            assert(result.nonEmpty, "show must return a non-empty string for a named symbol")
            succeed
        }
    }

    "Classpath.owner, fullName, show, ownersChain, companion, signature, paramLists compile and run on all three platforms" in {
        buildFixture.map { classpath =>
            val dogClass = classpath.symbol(SymbolId(1)).get
            import Tasty.Name.asString
            assert(classpath.owner(dogClass).isDefined)
            assert(classpath.fullName(dogClass).asString.nonEmpty)
            assert(classpath.show(dogClass).nonEmpty)
            assert(classpath.ownersChain(dogClass).nonEmpty)
            assert(classpath.companion(dogClass).isDefined)
            classpath.symbol(SymbolId(4)).get match
                case barkMethod: Tasty.Symbol.Method =>
                    assert(classpath.signature(barkMethod).nonEmpty)
                    assert(classpath.paramLists(barkMethod).nonEmpty)
                    succeed
                case other =>
                    fail(s"expected Tasty.Symbol.Method for SymbolId(4), got $other")
            end match
        }
    }

end ClasspathPureInstanceTest
