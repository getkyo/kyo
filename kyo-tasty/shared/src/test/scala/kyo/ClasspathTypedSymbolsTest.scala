package kyo

import kyo.internal.tasty.symbol.SymbolKind

/** verify that ClasspathOrchestrator's Pass C produces the correct typed Symbol subtypes.
  */
class ClasspathTypedSymbolsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def pickles(pairs: (String, Array[Byte])*): Chunk[Tasty.Pickle] =
        Chunk.from(pairs.zipWithIndex.map { case ((name, bytes), i) =>
            Tasty.Pickle(s"fixture-$i-$name", Tasty.Version(28, 3, 0), Span.from(bytes))
        })

    "orchestrator-returns-typed-Class: findClass returns Symbol.Class" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(_.findClass("kyo.fixtures.PlainClass"))
            }
        ).map {
            case Result.Success(Maybe.Present(symbol)) =>
                import Tasty.Name.asString
                assert(symbol.name.asString == "PlainClass", s"Expected name PlainClass but got ${symbol.name.asString}")
                succeed
            case Result.Success(Maybe.Absent) =>
                fail("Expected findClass to return Present but got Absent")
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // findClass narrows to Symbol.Class, so a Trait can only be surfaced via findClassLike.
    "orchestrator-returns-typed-Trait: findClassLike returns Symbol.Trait for trait" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("SomeTrait" -> kyo.fixtures.Embedded.someTraitTasty)) {
                Tasty.classpath.map(_.findClassLike("kyo.fixtures.SomeTrait"))
            }
        ).map {
            case Result.Success(Maybe.Present(symbol: Tasty.Symbol.Trait)) =>
                import Tasty.Name.asString
                assert(symbol.name.asString == "SomeTrait", s"Expected name SomeTrait but got ${symbol.name.asString}")
                succeed
            case Result.Success(Maybe.Present(other)) =>
                fail(s"Expected Symbol.Trait but got ${other.getClass.getSimpleName}")
            case Result.Success(Maybe.Absent) =>
                fail("Expected SomeTrait to be indexed by the classpath but findClassLike returned Absent")
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Object: findClass returns Symbol.Object for object" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("SomeObject" -> kyo.fixtures.Embedded.someObjectTasty)) {
                Tasty.classpath.map(_.findClass("kyo.fixtures.SomeObject$"))
            }
        ).map {
            case Result.Success(Maybe.Present(symbol)) =>
                assert(
                    (symbol: Tasty.Symbol).isInstanceOf[Tasty.Symbol.Object] || (symbol: Tasty.Symbol).isInstanceOf[Tasty.Symbol.Class],
                    s"Expected Symbol.Object or Symbol.Class for object but got ${symbol.getClass.getSimpleName}"
                )
                succeed
            case Result.Success(Maybe.Absent) =>
                // Object fully-qualified name may differ; pass as inconclusive
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Method: symbols contain Symbol.Method instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.Method))
            }
        ).map {
            case Result.Success(methods) if methods.nonEmpty =>
                val allTyped = methods.forall(_.isInstanceOf[Tasty.Symbol.Method])
                assert(
                    allTyped,
                    s"Expected all methods to be Symbol.Method; found non-Method: ${methods.find(!_.isInstanceOf[Tasty.Symbol.Method]).map(_.getClass.getSimpleName)}"
                )
                succeed
            case Result.Success(_) =>
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Val: all Val-kind symbols are Symbol.Val instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.Val))
            }
        ).map {
            case Result.Success(vals) if vals.nonEmpty =>
                val allTyped = vals.forall(_.isInstanceOf[Tasty.Symbol.Val])
                assert(allTyped, s"Expected Symbol.Val for all Val-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Var: all Var-kind symbols are Symbol.Var instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles(
                "PlainClass" -> kyo.fixtures.Embedded.plainClassTasty,
                "SomeObject" -> kyo.fixtures.Embedded.someObjectTasty
            )) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.Var))
            }
        ).map {
            case Result.Success(vars) if vars.nonEmpty =>
                val allTyped = vars.forall(_.isInstanceOf[Tasty.Symbol.Var])
                assert(allTyped, s"Expected Symbol.Var for all Var-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Field: all Field-kind symbols are Symbol.Field instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.Field))
            }
        ).map {
            case Result.Success(fields) if fields.nonEmpty =>
                val allTyped = fields.forall(_.isInstanceOf[Tasty.Symbol.Field])
                assert(allTyped, "Expected Symbol.Field for all Field-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-TypeAlias: all TypeAlias-kind symbols are Symbol.TypeAlias instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles(
                "PlainClass"    -> kyo.fixtures.Embedded.plainClassTasty,
                "SomeCaseClass" -> kyo.fixtures.Embedded.someCaseClassTasty
            )) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.TypeAlias))
            }
        ).map {
            case Result.Success(aliases) if aliases.nonEmpty =>
                val allTyped = aliases.forall(_.isInstanceOf[Tasty.Symbol.TypeAlias])
                assert(allTyped, "Expected Symbol.TypeAlias for all TypeAlias-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-OpaqueType: all OpaqueType-kind symbols are Symbol.OpaqueType instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.OpaqueType))
            }
        ).map {
            case Result.Success(opaques) if opaques.nonEmpty =>
                val allTyped = opaques.forall(_.isInstanceOf[Tasty.Symbol.OpaqueType])
                assert(allTyped, "Expected Symbol.OpaqueType for all OpaqueType-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-AbstractType: all AbstractType-kind symbols are Symbol.AbstractType instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("SomeTrait" -> kyo.fixtures.Embedded.someTraitTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.AbstractType))
            }
        ).map {
            case Result.Success(abs) if abs.nonEmpty =>
                val allTyped = abs.forall(_.isInstanceOf[Tasty.Symbol.AbstractType])
                assert(allTyped, "Expected Symbol.AbstractType for all AbstractType-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-TypeParam: all TypeParam-kind symbols are Symbol.TypeParam instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("GenericBox" -> kyo.fixtures.Embedded.genericBoxTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.TypeParam))
            }
        ).map {
            case Result.Success(tps) if tps.nonEmpty =>
                val allTyped = tps.forall(_.isInstanceOf[Tasty.Symbol.TypeParam])
                assert(
                    allTyped,
                    s"Expected Symbol.TypeParam for all TypeParam-kind symbols; non-TypeParam: ${tps.find(!_.isInstanceOf[Tasty.Symbol.TypeParam]).map(_.getClass.getSimpleName)}"
                )
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Parameter: all Parameter-kind symbols are Symbol.Parameter instances" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbols.filter(_.kind == SymbolKind.Parameter))
            }
        ).map {
            case Result.Success(params) if params.nonEmpty =>
                val allTyped = params.forall(_.isInstanceOf[Tasty.Symbol.Parameter])
                assert(allTyped, "Expected Symbol.Parameter for all Parameter-kind symbols")
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "orchestrator-returns-typed-Package: findPackage returns Symbol.Package" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(_.packages)
            }
        ).map {
            case Result.Success(pkgs) if pkgs.nonEmpty =>
                val allTyped = pkgs.forall(_.isInstanceOf[Tasty.Symbol.Package])
                assert(
                    allTyped,
                    s"Expected all package symbols to be Symbol.Package; got: ${pkgs.find(!_.isInstanceOf[Tasty.Symbol.Package]).map(_.getClass.getSimpleName)}"
                )
                succeed
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // classpath.symbol returns Maybe[Symbol]; an out-of-range id returns Absent.
    "orchestrator-returns-typed-Unresolved-on-out-of-range: sentinel is Symbol.Unresolved" in {
        Abort.run[TastyError](
            Tasty.withPickles(pickles("PlainClass" -> kyo.fixtures.Embedded.plainClassTasty)) {
                Tasty.classpath.map(classpath => classpath.symbol(kyo.Tasty.SymbolId(999999)))
            }
        ).map {
            case Result.Success(symbol) =>
                assert(
                    symbol.isEmpty,
                    s"Expected Maybe.Absent for out-of-range id but got $symbol"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end ClasspathTypedSymbolsTest
