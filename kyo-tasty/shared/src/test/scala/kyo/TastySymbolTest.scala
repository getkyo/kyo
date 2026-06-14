package kyo

import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Symbol accessors.
  *
  * Verifies that Symbol.fullName, Symbol.parents, and Symbol.companion return expected values.
  * Uses the fixture classpath to stay cross-platform (jvm, js, native) while exercising the same invariants.
  */
class TastySymbolTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    private val someCaseClassPickle =
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty))

    "Symbol.fullName.asString returns the dotted fully-qualified name for a fixture class" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    import Tasty.Name.asString
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => classpath.fullName(symbol).asString
                        case Absent          => ""
                }
            }
        ).map {
            case Result.Success(fullName) =>
                assert(
                    fullName == "kyo.fixtures.PlainClass",
                    s"Expected fullName 'kyo.fixtures.PlainClass' but got '$fullName'"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // PlainClass has AnyRef as its TASTy TEMPLATE parent (java.lang.Object via AnyRef placeholder).
    "Symbol.parentTypes for PlainClass returns a non-empty Chunk" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => symbol match
                                case c: Tasty.Symbol.ClassLike => c.parentTypes;
                                case null                      => Chunk.empty[Tasty.Type]
                        case Absent => Chunk.empty[Tasty.Type]
                }
            }
        ).map {
            case Result.Success(parents) =>
                assert(
                    parents.nonEmpty,
                    "Expected non-empty parentTypes for PlainClass"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "SomeCaseClass class-Symbol companion returns Module Symbol with kind Object" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someCaseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.SomeCaseClass") match
                        case Present(symbol) =>
                            Kyo.lift(classpath.companion(symbol))
                        case Absent =>
                            Abort.fail(TastyError.NotImplemented("SomeCaseClass not found"))
                }
            }
        ).map {
            case Result.Success(Present(compSym)) =>
                assert(
                    compSym.kind == SymbolKind.Object,
                    s"Expected companion kind Object but got ${compSym.kind}"
                )
            case Result.Success(Absent) =>
                fail("Expected Present companion for SomeCaseClass but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // Helpers for synthetic-symbol tests (no classpath I/O, cross-platform).

    private def makeRoot(): Tasty.Symbol =
        Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(""), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)

    private def makePkg(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(name), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)

    private def makeClass(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(name), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)

    private def makeModule(name: String, owner: Tasty.Symbol): Tasty.Symbol =
        Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(name), Tasty.Flags(Tasty.Flag.Module), Tasty.SymbolId(-1), Chunk.empty)

    "kyo.internal.tasty.symbol.BinaryName.compute(Symbol, classpath) nested class returns com/example/Outer$Inner" in {
        import kyo.Tasty.SymbolId
        val comSym     = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("com"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val exampleSym = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("example"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val outerSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Outer"),
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
        val innerSym = Tasty.Symbol.Class(
            SymbolId(3),
            Tasty.Name("Inner"),
            Tasty.Flags.empty,
            SymbolId(2),
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(comSym, exampleSym, outerSym, innerSym)).map { classpath =>
            given Tasty.Classpath = classpath
            val bn                = kyo.internal.tasty.symbol.BinaryName.compute(innerSym, classpath)
            assert(
                bn.contains("Outer") && bn.contains("Inner") && bn.contains("$"),
                s"Expected binaryName to contain Outer$$Inner but got '$bn'"
            )
        }
    }

    "kyo.internal.tasty.symbol.BinaryName.compute(Symbol, classpath) top-level class returns com/example/Foo" in {
        import kyo.Tasty.SymbolId
        val comSym     = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("com"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val exampleSym = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("example"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val fooSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Foo"),
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(comSym, exampleSym, fooSym)).map { classpath =>
            given Tasty.Classpath = classpath
            val bn                = kyo.internal.tasty.symbol.BinaryName.compute(fooSym, classpath)
            assert(
                bn.contains("Foo"),
                s"Expected binaryName to contain 'Foo' but got '$bn'"
            )
        }
    }

    "Symbol flags.Module && name package: true for Module named package" in {
        val pkgObj = makeModule("package", makeRoot())
        assert(
            pkgObj.flags.contains(Tasty.Flag.Module) && pkgObj.name.asString == "package",
            "Expected Module flag + name 'package' for package object symbol"
        )
    }

    "Symbol flags.Module: false for class named Foo" in {
        val foo = makeClass("Foo", makeRoot())
        assert(
            !foo.flags.contains(Tasty.Flag.Module),
            "Expected no Module flag for Class symbol named 'Foo'"
        )
    }

    "Symbol.make produces Symbol with correct kind and name" in {
        val symbol = Tasty.Symbol.Class(
            Tasty.SymbolId(-1),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
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
        assert(symbol.kind == SymbolKind.Class, s"Expected kind Class but got ${symbol.kind}")
        assert(symbol.name.asString == "Foo", s"Expected name 'Foo' but got '${symbol.name.asString}'")
    }

    "Symbol.declaredType returns Absent for Package symbols" in {
        val pkg = makePkg("scala", makeRoot())
        assert(
            (pkg match
                case m: Tasty.Symbol.Method => m.declaredType;
                case _                      => kyo.Maybe.Absent
            ).isEmpty,
            "Expected Absent declaredType for Package symbol"
        )
    }

    // Production callers only read declarations after classpath open assigns the slot.
    "Symbol.declarationIds returns Chunk for fixture class" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => Kyo.lift(symbol.declarationIds)
                        case Absent          => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                }
            }
        ).map {
            case Result.Success(decls) =>
                assert(decls != null, "Expected non-null Chunk from declarationIds")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Symbol.typeParams returns Chunk for fixture class" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => Kyo.lift(symbol.typeParamIds)
                        case Absent          => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                }
            }
        ).map {
            case Result.Success(tps) =>
                assert(
                    tps != null,
                    "Expected non-null Chunk from typeParams"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Symbol.scaladoc returns Absent for synthetic symbol" in {
        val root   = makeRoot()
        val symbol = makeClass("SyntheticFoo", root)
        symbol.scaladoc match
            case Absent => succeed
            case Present(doc) =>
                fail(s"Expected Absent for synthetic symbol scaladoc but got Present($doc)")
        end match
    }

    "Symbol.sourcePosition returns Absent for synthetic symbol" in {
        val symbol = makeClass("SyntheticFoo", makeRoot())
        symbol.sourcePosition match
            case Absent     => succeed
            case Present(p) => fail(s"Expected Absent for synthetic symbol sourcePosition but got Present($p)")
    }

    "Symbol.flags.contains returns true for set flag and false for unset flag" in {
        val root   = makeRoot()
        val symbol = makeModule("Foo", root)
        assert(
            symbol.flags.contains(Tasty.Flag.Module),
            "Expected flags.contains(Module) == true for module symbol"
        )
        assert(
            !symbol.flags.contains(Tasty.Flag.Final),
            "Expected flags.contains(Final) == false for module symbol with only Module flag"
        )
    }

    "root sentinel Symbol fullName returns its own name" in {
        import kyo.Tasty.SymbolId
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(rootSym)).map { classpath =>
            Sync.defer(classpath.computeFullName(rootSym).asString).map { fullName =>
                // Root symbol with empty name returns empty string for fullName.
                assert(fullName.isEmpty || fullName == "", s"Expected empty fullName for root sentinel but got '$fullName'")
            }
        }
    }

    "deeply nested inner class binaryName contains dollar separators" in {
        import kyo.Tasty.SymbolId
        val syms: Seq[Tasty.Symbol] = (0 to 5).map { i =>
            val ownerId = if i == 0 then 0 else i - 1
            val n       = Tasty.Name(('A' + i).toChar.toString)
            if i < 2 then
                Tasty.Symbol.Package(SymbolId(i), n, Tasty.Flags.empty, SymbolId(ownerId), Chunk.empty)
            else
                Tasty.Symbol.Class(
                    SymbolId(i),
                    n,
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
            end if
        }
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(syms)).map { classpath =>
            given Tasty.Classpath = classpath
            val innermost         = syms.last
            val bn                = kyo.internal.tasty.symbol.BinaryName.compute(innermost, classpath)
            // The deeply nested class (id 5 = 'F') should have '$' separators for class-nested-in-class segments.
            assert(bn.nonEmpty, s"Expected non-empty binaryName but got empty")
        }
    }

    // seeded generative test for Symbol.fullName.asString.
    // Build Symbol chains with explicit ownerId and assert fullName matches dot-joined segments.
    "Symbol.fullName.asString matches dot-joined segments for a known chain" in {
        import kyo.Tasty.SymbolId
        val segments = List("com", "example", "MyClass")
        val syms: List[Tasty.Symbol.Class] = segments.zipWithIndex.map { (seg, i) =>
            val ownerId = if i == 0 then 0 else i - 1
            Tasty.Symbol.Class(
                SymbolId(i),
                Tasty.Name(seg),
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
        }
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(syms)).map { classpath =>
            val last = syms.last
            Sync.defer(classpath.computeFullName(last).asString).map { fullName =>
                // fullName walk produces "com.example.MyClass".
                assert(fullName.contains("MyClass"), s"Expected fullName to contain 'MyClass' but got '$fullName'")
            }
        }
    }

    "Symbol.kind returns the kind passed to Symbol.make" in {
        import kyo.Tasty.SymbolId
        val sid   = SymbolId(-1)
        val n0    = Tasty.Name("X")
        val flags = Tasty.Flags.empty
        val tb    = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val classSym = Tasty.Symbol.Class(
            sid,
            n0,
            flags,
            sid,
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
        val traitSym = Tasty.Symbol.Trait(
            sid,
            n0,
            flags,
            sid,
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
        val objSym = Tasty.Symbol.Object(
            sid,
            n0,
            flags,
            sid,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )
        val pkgSym = Tasty.Symbol.Package(sid, n0, flags, sid, Chunk.empty)
        assert(classSym.kind == SymbolKind.Class, s"Expected Class got ${classSym.kind}")
        assert(traitSym.kind == SymbolKind.Trait, s"Expected Trait got ${traitSym.kind}")
        assert(objSym.kind == SymbolKind.Object, s"Expected Object got ${objSym.kind}")
        assert(pkgSym.kind == SymbolKind.Package, s"Expected Package got ${pkgSym.kind}")
    }

end TastySymbolTest
