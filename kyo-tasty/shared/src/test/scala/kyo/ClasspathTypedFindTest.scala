package kyo

import kyo.Tasty.SymbolId

/** Tests for typed Classpath find* lookups.
  *
  * Fixture: Class "A" (pkg.A), Trait "T" (pkg.T), Object "O" (pkg.O), Package "sub" (pkg.sub),
  * Package "pkg", and a second Class "A" in pkg.sub.A (same simple name, different package).
  */
class ClasspathTypedFindTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeClass(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
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

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
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

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(ownerId), members)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val clsA   = makeClass(0, "A", ownerId = 4)
            val trtT   = makeTrait(1, "T", ownerId = 4)
            val objO   = makeObject(2, "O", ownerId = 4)
            val subPkg = makePackage(3, "sub", ownerId = 4, Chunk(SymbolId(5)))
            val pkg    = makePackage(4, "pkg", ownerId = -1, Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3)))
            val clsA2  = makeClass(5, "A", ownerId = 3)
            Tasty.Classpath.make(
                symbols = Chunk(clsA, trtT, objO, subPkg, pkg, clsA2),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2)),
                packageIds = Chunk(SymbolId(3), SymbolId(4)),
                fullNameIndex = Dict(
                    "pkg.A"     -> SymbolId(0),
                    "pkg.T"     -> SymbolId(1),
                    "pkg.O"     -> SymbolId(2),
                    "pkg.sub.A" -> SymbolId(5)
                ),
                packageIndex = Dict("pkg" -> SymbolId(4), "pkg.sub" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "findClass returns Present[Class] for a class fully-qualified name" in {
        buildFixture.map { classpath =>
            classpath.findClass("pkg.A") match
                case Maybe.Present(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.A but got Absent")
        }
    }

    "findClass returns Absent for a trait fully-qualified name" in {
        buildFixture.map { classpath =>
            val result = classpath.findClass("pkg.T")
            assert(result == Maybe.Absent, s"Expected Absent for trait pkg.T but got $result")
        }
    }

    "findTrait returns Present[Trait] for a trait fully-qualified name" in {
        buildFixture.map { classpath =>
            classpath.findTrait("pkg.T") match
                case Maybe.Present(t) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.T but got Absent")
        }
    }

    "findObject returns Present[Object] for an object fully-qualified name" in {
        buildFixture.map { classpath =>
            classpath.findObject("pkg.O") match
                case Maybe.Present(o) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg.O but got Absent")
        }
    }

    "findClassLike returns Present[ClassLike] for class, trait, and object fully-qualified names" in {
        buildFixture.map { classpath =>
            classpath.findClassLike("pkg.A") match
                case Maybe.Present(c: Tasty.Symbol.Class) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Class but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.A")
            end match
            classpath.findClassLike("pkg.T") match
                case Maybe.Present(t: Tasty.Symbol.Trait) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Trait but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.T")
            end match
            classpath.findClassLike("pkg.O") match
                case Maybe.Present(o: Tasty.Symbol.Object) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Maybe.Present(other) => fail(s"Expected Symbol.Object but got $other")
                case Maybe.Absent         => fail("Expected Present for pkg.O")
            end match
        }
    }

    "findPackage returns Present[Package] for a package fully-qualified name" in {
        buildFixture.map { classpath =>
            classpath.findPackage("pkg") match
                case Maybe.Present(p) =>
                    assert(p.name.asString == "pkg", s"Expected Package name 'pkg' but got '${p.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg but got Absent")
        }
    }

    "findClassesByName returns all Class instances with the given simple name" in {
        buildFixture.map { classpath =>
            val result = classpath.findClassesByName("A")
            assert(result.size == 2, s"Expected 2 classes named A but got ${result.size}: $result")
            result.foreach {
                case _: Tasty.Symbol.Class => ()
                case null                  => fail("Expected Symbol.Class but got null")
            }
            assert(result.forall(_.name.asString == "A"), s"All results must be named 'A' but got: ${result.map(_.name.asString)}")
        }
    }

    "findClassByBinary returns Present[Class] for a binary name" in {
        buildFixture.map { classpath =>
            classpath.findClassByBinary("pkg/A") match
                case Maybe.Present(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Maybe.Absent =>
                    fail("Expected Present for pkg/A but got Absent")
        }
    }

    "findClass returns Absent for a missing fully-qualified name" in {
        buildFixture.map { classpath =>
            val result = classpath.findClass("does.not.exist")
            assert(result == Maybe.Absent, s"Expected Absent for missing fully-qualified name but got $result")
            succeed
        }
    }

end ClasspathTypedFindTest
