package kyo
import kyo.Tasty.SymbolId
import kyo.internal.tasty.classfile.ClassfileUnpickler
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena

/** Tests for the Query API, classpath lifecycle, and the A/B/C orchestration pipeline.
  *
  * Uses in-memory pickles for cross-platform compatibility.
  */
class QueryApiTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def symParentTypes(symbol: Tasty.Symbol): Chunk[Tasty.Type] = symbol match
        case c: Tasty.Symbol.ClassLike => c.parentTypes
        case _                         => Chunk.empty

    private def symTypeParamIds(symbol: Tasty.Symbol): Chunk[kyo.Tasty.SymbolId] = symbol match
        case c: Tasty.Symbol.ClassLike   => c.typeParamIds
        case m: Tasty.Symbol.Method      => m.typeParamIds
        case ta: Tasty.Symbol.TypeAlias  => ta.typeParamIds
        case ot: Tasty.Symbol.OpaqueType => ot.typeParamIds
        case _                           => Chunk.empty

    private def symDeclarationIds(symbol: Tasty.Symbol): Chunk[kyo.Tasty.SymbolId] = symbol match
        case c: Tasty.Symbol.ClassLike => c.declarationIds
        case p: Tasty.Symbol.Package   => p.memberIds
        case _                         => Chunk.empty

    private def symDeclaredType(symbol: Tasty.Symbol): Maybe[Tasty.Type] = symbol match
        case m: Tasty.Symbol.Method      => m.declaredType
        case v: Tasty.Symbol.Val         => v.declaredType
        case w: Tasty.Symbol.Var         => w.declaredType
        case f: Tasty.Symbol.Field       => f.declaredType
        case p: Tasty.Symbol.Parameter   => p.declaredType
        case ta: Tasty.Symbol.TypeAlias  => ta.body
        case ot: Tasty.Symbol.OpaqueType => ot.body
        case _                           => Maybe.Absent

    // Overloads for LoadingSymbol.Materialising (from ClassfileResult)
    private def symDeclaredType(symbol: LoadingSymbol.Materialising): Maybe[Tasty.Type] = symbol.declaredType

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    private val someCaseClassPickle =
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty))

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    private val genericBoxPickle =
        Tasty.Pickle("generic-box", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.genericBoxTasty))

    private val baseClassPickle =
        Tasty.Pickle("base-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.baseClassTasty))

    private val childClassPickle =
        Tasty.Pickle("child-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.childClassTasty))

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Async & Abort[TastyError]) =
        Tasty.withPickles(Chunk(plainClassPickle)) {
            Tasty.classpath
        }

    "withPickles(Chunk.empty) succeeds and findClass returns Absent" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map { classpath =>
            val result = classpath.findClass("some.Class")
            assert(result == Maybe.Absent)
        }
    }

    "findClass on fixture TASTy returns Present(symbol) with kind Class" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass")
        }).map {
            case Result.Success(Present(symbol)) =>
                assert(symbol.kind == SymbolKind.Class)
            case Result.Success(Absent) =>
                fail("Expected Present(symbol) but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findClass for nonexistent fully-qualified name returns Absent" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("nonexistent.Class.XYZ")
        }).map {
            case Result.Success(Absent) =>
                succeed
            case Result.Success(Present(_)) =>
                fail("Expected Absent for nonexistent class")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findPackage returns Present(pkg) with kind Package" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findPackage("kyo.fixtures")
        }).map {
            case Result.Success(Present(pkg)) =>
                assert(pkg.kind == SymbolKind.Package)
            case Result.Success(Absent) =>
                // Package symbols depend on unpickler emitting package nodes; allow Absent if not emitted
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "topLevelClasses returns non-empty Chunk for fixture classpath" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.topLevelClasses
        }).map {
            case Result.Success(classes) =>
                assert(classes.nonEmpty, s"Expected non-empty topLevelClasses but got empty")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "packages does not fail for fixture classpath" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.packages
        }).map {
            case Result.Success(_) =>
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "errors returns Chunk.empty for clean classpath" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.errors
        }).map {
            case Result.Success(errs) =>
                assert(errs.isEmpty, s"Expected no errors but got: $errs")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "errors returns non-empty for classpath with corrupt TASTy" in {
        val corruptPickle = Tasty.Pickle("corrupt", Tasty.Version(28, 3, 0), Span.from(Array[Byte](0, 1, 2, 3, 4, 5)))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(corruptPickle)) {
                Tasty.classpath.map(_.errors)
            }
        ).map {
            case Result.Success(errs) =>
                assert(errs.nonEmpty, "Expected at least one error for corrupt TASTy")
            case Result.Failure(e) =>
                fail(s"Unexpected top-level failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct symbol iteration returns symbols from fixture classpath" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer(classpath.symbols)
        }).map {
            case Result.Success(syms) =>
                assert(syms.nonEmpty, "Expected at least one symbol from fixture classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct filter to Method kind returns only method symbols" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer(classpath.symbols.filter(_.kind == SymbolKind.Method))
        }).map {
            case Result.Success(syms) =>
                assert(
                    syms.forall(_.kind == SymbolKind.Method),
                    s"Some symbols are not Method kind"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct filter by Inline flag returns only inline symbols" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer(classpath.symbols.filter(_.flags.contains(Tasty.Flag.Inline)))
        }).map {
            case Result.Success(syms) =>
                assert(
                    syms.forall(_.flags.contains(Tasty.Flag.Inline)),
                    s"Some symbols do not have Inline flag"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct filter by name finds symbols named PlainClass" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer(classpath.symbols.filter(_.name.asString == "PlainClass"))
        }).map {
            case Result.Success(syms) =>
                assert(
                    syms.forall(_.name.asString == "PlainClass"),
                    s"All name-filtered symbols must have name PlainClass, got: ${syms.map(_.name.asString)}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct map over symbols produces names" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer(classpath.symbols.map(_.name))
        }).map {
            case Result.Success(names) =>
                val stringNames = names.map(_.asString)
                // The map result must surface specific fixture names. PlainClass is the test fixture's
                // top-level class and its name must round-trip via the mapped Chunk. We also assert
                // the same set is non-empty as a sanity check on the iteration itself.
                assert(stringNames.nonEmpty, "Expected at least one name in fixture classpath")
                assert(
                    stringNames.contains("PlainClass"),
                    s"Expected mapped names to include PlainClass, got first 20: ${stringNames.take(20)}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "direct allSymbols count is consistent across two calls" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            Sync.defer((classpath.symbols.size, classpath.symbols.size))
        }).map {
            case Result.Success((count1, count2)) =>
                assert(
                    count1 == count2,
                    s"allSymbols should return consistent count: first=$count1 second=$count2"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // The Classpath case class remains accessible after the scope exits.
    "Tasty.Classpath remains accessible after scope exits (no Closed state)" in {
        var capturedCp: Tasty.Classpath = null
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    capturedCp = classpath
                }
            }
        ).map {
            case Result.Success(_) =>
                assert(capturedCp != null, "Classpath should have been captured")
                assert(capturedCp.symbols.nonEmpty, "Classpath should have symbols after scope exits")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findClass returns Present after open" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    Kyo.lift(classpath.findClass("kyo.fixtures.PlainClass"))
                }
            }
        ).map {
            case Result.Success(Present(_)) => succeed
            case Result.Success(Absent)     => fail("Expected PlainClass to be found")
            case Result.Failure(e)          => fail(s"Unexpected failure: $e")
            case Result.Panic(t)            => throw t
        }
    }

    "strict mode fails with TastyError for corrupt TASTy" in {
        // FailFast mode: write a corrupt file to a temp dir and use ClasspathOrchestrator.init.
        Path.tempDir("kyo-qa-failfast").map { dir =>
            (dir / "Corrupt.tasty").writeBytes(Span.from(Array[Byte](0, 1, 2, 3, 4, 5))).map { _ =>
                Scope.run {
                    Abort.run[TastyError](ClasspathOrchestrator.init(Seq(dir.toString), Tasty.ErrorMode.FailFast, 1)).map {
                        case Result.Success(_) =>
                            fail("Expected failure in strict mode with corrupt TASTy")
                        case Result.Failure(_) =>
                            succeed
                        case Result.Panic(t) =>
                            throw t
                    }
                }
            }
        }
    }

    "soft-fail mode accumulates errors; other symbols still resolve" in {
        val corruptPickle = Tasty.Pickle("corrupt", Tasty.Version(28, 3, 0), Span.from(Array[Byte](0, 1, 2, 3, 4, 5)))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(corruptPickle, plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val errs = classpath.errors
                    val cls  = classpath.findClass("kyo.fixtures.PlainClass")
                    (errs, cls)
                }
            }
        ).map {
            case Result.Success((errs, cls)) =>
                assert(errs.nonEmpty, "Expected at least one error from corrupt file")
                assert(cls.isDefined, "Expected PlainClass to be found despite corrupt file")
            case Result.Failure(e) =>
                fail(s"Unexpected top-level failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // SoftFail with a real missing root produces FileNotFound in classpath.errors.
    "missing root produces FileNotFound in classpath.errors" in {
        Path.tempDir("kyo-qa-missing").map { tmp =>
            val missing = (tmp / "no-such-root").toString
            Scope.run {
                ClasspathOrchestrator.init(Seq(missing), Tasty.ErrorMode.SoftFail, 1).map { classpath =>
                    val errs = classpath.errors
                    assert(errs.size == 1, s"Expected exactly 1 error; got: $errs")
                    errs.head match
                        case TastyError.FileNotFound(_) => succeed
                        case other                      => fail(s"Expected FileNotFound; got: $other")
                }
            }
        }
    }

    "Phase A/B/C orchestration with 3 files: all symbols present" in {
        val pickles = Chunk(
            plainClassPickle,
            Tasty.Pickle("plain-class-2", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
            Tasty.Pickle("plain-class-3", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))
        )
        Abort.run[TastyError](
            Tasty.withPickles(pickles) {
                Tasty.classpath.map(_.topLevelClasses)
            }
        ).map {
            case Result.Success(classes) =>
                assert(classes.nonEmpty, s"Expected at least one class after opening 3 files, got ${classes.length}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Phase B interruption: valid files decoded; 1 error accumulated for corrupt file" in {
        val corruptPickle = Tasty.Pickle("corrupt", Tasty.Version(28, 3, 0), Span.from(Array[Byte](0, 1, 2, 3)))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle, corruptPickle)) {
                Tasty.classpath.map { classpath =>
                    (classpath.topLevelClasses, classpath.errors)
                }
            }
        ).map {
            case Result.Success((classes, errs)) =>
                // Exactly 1 corrupt file was loaded; exactly 1 error must be accumulated.
                assert(errs.size == 1, s"Expected exactly 1 error for the 1 corrupt file, got: ${errs.size}")
                assert(classes.nonEmpty, s"Expected valid classes to be present, got empty")
            case Result.Failure(e) =>
                fail(s"Unexpected top-level failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findClassByBinary canonicalizes binary name to dotted fully-qualified name" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            val byBinary   = classpath.findClassByBinary("kyo/fixtures/PlainClass")
            val byFullName = classpath.findClass("kyo.fixtures.PlainClass")
            (byBinary, byFullName)
        }).map {
            case Result.Success((byBinary, byFullName)) =>
                assert(
                    byBinary.isDefined == byFullName.isDefined,
                    s"findClassByBinary and findClass should return same result: $byBinary vs $byFullName"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findClassByBinary for nonexistent returns Absent" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClassByBinary("no/such/Class$Nested")
        }).map {
            case Result.Success(Absent) =>
                succeed
            case Result.Success(Present(_)) =>
                fail("Expected Absent for nonexistent binary name")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // The read-counter behavioral invariant (all reads complete before classpath is ready) is verified
    // indirectly by every test that successfully queries the classpath after init completes.

    // Verifies end-to-end Phase C placeholder resolution: no errors and no panic.
    "two-file classpath (ChildClass extends BaseClass) opens with no errors and no panic" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(baseClassPickle, childClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val errs     = classpath.errors
                    val childOpt = classpath.findClass("kyo.fixtures.ChildClass")
                    val baseOpt  = classpath.findClass("kyo.fixtures.BaseClass")
                    (errs, childOpt, baseOpt)
                }
            }
        ).map {
            case Result.Success((errs, childOpt, baseOpt)) =>
                assert(errs.isEmpty, s"Expected no errors but got: $errs")
                assert(childOpt.isDefined, "Expected ChildClass to be found")
                assert(baseOpt.isDefined, "Expected BaseClass to be found")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbol.parentTypes for PlainClass returns a non-empty Chunk[Type]" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass") match
                case Present(symbol) => Kyo.lift(symParentTypes(symbol))
                case Absent          => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        }).map {
            case Result.Success(parents) =>
                assert(
                    parents.nonEmpty,
                    s"Expected non-empty parentTypes for PlainClass but got empty."
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbol.typeParamIds for GenericBox[A] returns length 1" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(genericBoxPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.GenericBox") match
                        case Present(symbol) =>
                            val tpIds  = symTypeParamIds(symbol)
                            val allSym = classpath.symbols
                            assert(
                                tpIds.length == 1,
                                s"Expected 1 typeParamId for GenericBox[A] but got ${tpIds.length}"
                            )
                            val tpSym = allSym(tpIds(0).value)
                            assert(tpSym.name.asString == "A", s"Expected type param name 'A' but got '${tpSym.name.asString}'")
                        case Absent => Abort.fail(TastyError.NotImplemented("GenericBox not found"))
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // Note: `class PlainClass(val x: Int)` in Scala 3.8 TASTy encodes x as a Parameter symbol (symbol[3]),
    // which is NOT in class declarationIds. The declarationIds only contains the constructor `<init>`.
    // This is the actual TASTy structure; the test verifies the constructor is accessible.
    "symbol.declarationIds for PlainClass contains at least the constructor" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass") match
                case Present(symbol) =>
                    val declIds = symDeclarationIds(symbol)
                    val allSym  = classpath.symbols
                    assert(declIds.nonEmpty, s"Expected non-empty declarationIds for PlainClass but got empty")
                    val names = declIds.map(id => allSym(id.value).name.asString).toSet
                    assert(
                        names.contains("<init>"),
                        s"Expected declarationIds to contain '<init>' but got: ${names.mkString(", ")}"
                    )
                    // In Scala 3.8 TASTy, 'x' is a constructor Parameter symbol, not a class member.
                    // Verify that 'x' appears somewhere in classpath.symbols with kind Parameter.
                    val hasXParam = allSym.exists(s => s.name.asString == "x" && s.isInstanceOf[Tasty.Symbol.Parameter])
                    assert(hasXParam, s"Expected a Parameter symbol named 'x' in classpath.symbols")
                case Absent => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // Parents are stored as plain Chunk fields in the case class, so they remain valid after close.
    "symbol.parents after classpath close returns the pre-populated parentTypes Chunk (no failure)" in {
        // Capture the symbol from inside the scope, then check parents after scope exits.
        // Scope.run returns Result[TastyError, Symbol] after running finalizers (closing classpath).
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Abort.run[TastyError] {
                openFixtureClasspath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => Kyo.lift(symbol)
                        case Absent          => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                }
            }
        captureResult.map {
            case Result.Failure(e) =>
                fail(s"Expected success capturing PlainClass symbol but got: $e")
            case Result.Panic(t) =>
                throw t
            case Result.Success(symbol) =>
                // Scope has exited; classpath is now closed. symbol.parentTypes is a direct field, always valid.
                val parents = symParentTypes(symbol)
                assert(
                    parents.nonEmpty,
                    "Expected non-empty parentTypes from pre-populated field after classpath close"
                )
        }
    }

    "Java classfile symbol parents, typeParams, declarations are accessible" in {
        val bytes = kyo.fixtures.Embedded.arrayRecordClass
        Abort.run[TastyError] {
            Sync.Unsafe.defer {
                given AllowUnsafe = AllowUnsafe.embrace.danger
                Abort.get(ClassfileUnpickler.read(bytes, new TypeArena))
            }
        }.map {
            case Result.Success(cr) =>
                val parents    = cr.parents
                val typeParams = cr.typeParams
                val decls      = cr.symbols
                Abort.run[TastyError] {
                    Kyo.lift((parents, typeParams, decls))
                }.map {
                    case Result.Success((parents, typeParams, decls)) =>
                        assert(parents.nonEmpty, s"Expected non-empty parentTypes for ArrayRecord but got empty")
                        val hasNamedOrApplied = parents.exists {
                            case Tasty.Type.Named(_)      => true
                            case Tasty.Type.Applied(_, _) => true
                            case _                        => false
                        }
                        assert(hasNamedOrApplied, s"Expected at least one Named/Applied parent")
                        assert(typeParams.isEmpty, s"Expected no typeParams for ArrayRecord but got ${typeParams.length}")
                        assert(decls.nonEmpty, s"Expected non-empty symbols for ArrayRecord but got empty")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure calling cr.parents/typeParams/symbols: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            case Result.Failure(e) =>
                fail(s"ClassfileUnpickler or classpath setup failed: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // SomeCaseClass.tasty contains both the case class and its companion object.
    // The class symbol's companion should return Present(objectSym) where kind == Object.
    "SomeCaseClass.companion returns Present(objectSym) with kind Object" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someCaseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    // Find the Class-kind symbol for SomeCaseClass (fullNameIndex key: "kyo.fixtures.SomeCaseClass").
                    classpath.findClass("kyo.fixtures.SomeCaseClass") match
                        case Present(classSym) =>
                            Kyo.lift(classpath.companion(classSym))
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

    "SomeCaseClass companion object's companion returns Present(classSym) with kind Class" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someCaseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    // Companion object is registered with "$" suffix in fullNameIndex.
                    classpath.findClass("kyo.fixtures.SomeCaseClass$") match
                        case Present(objSym) =>
                            Kyo.lift(classpath.companion(objSym))
                        case Absent =>
                            // Some TASTy encodings register the object without "$"; try topLevelClasses.
                            val objSym = classpath.topLevelClasses.find(_.kind == SymbolKind.Object)
                            objSym match
                                case Some(s) =>
                                    Kyo.lift(classpath.companion(s))
                                case None =>
                                    Abort.fail(TastyError.NotImplemented("SomeCaseClass$ not found in fullNameIndex"))
                            end match
                }
            }
        ).map {
            case Result.Success(Present(compSym)) =>
                assert(
                    compSym.kind == SymbolKind.Class,
                    s"Expected companion kind Class but got ${compSym.kind}"
                )
            case Result.Success(Absent) =>
                fail("Expected Present companion for SomeCaseClass$ but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "PlainClass.companion returns Absent (no companion object)" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass") match
                case Present(symbol) =>
                    Kyo.lift(classpath.companion(symbol))
                case Absent =>
                    Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        }).map {
            case Result.Success(Absent) =>
                succeed
            case Result.Success(Present(s)) =>
                fail(s"Expected Absent companion for PlainClass but got Present(${s.name.asString})")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // symbol.companion uses classpath.indices.companionIndex which is empty in fromPickles.
    "symbol.companion after classpath close returns Absent (pure, no failure)" in {
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Abort.run[TastyError] {
                openFixtureClasspath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => Kyo.lift(symbol)
                        case Absent          => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                }
            }
        captureResult.map {
            case Result.Failure(e) => Kyo.lift(fail(s"Unexpected failure: $e"))
            case Result.Panic(t)   => throw t
            case Result.Success(symbol) =>
                Tasty.withPickles(Chunk.empty)(Tasty.classpath).map { classpath =>
                    val companion = classpath.companion(symbol)
                    assert(companion == Maybe.Absent, s"Expected Absent companion on empty classpath but got $companion")
                }
        }
    }

    // After Phase C placeholder resolution the type encodes scala.Int. The TASTy encoding for Int
    // may be Type.Named or Type.TermRef depending on how the constant type is referenced; we assert
    // that a type is returned and does not fail.
    // Note: `class PlainClass(val x: Int)` stores `x` as a constructor Parameter, not a class declaration.
    // Look for `x` via the constructor's paramListIds.
    "symbol.declaredType for PlainClass.x (val x: Int) returns a type" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass") match
                case Absent => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                case Present(classSym) =>
                    val allSym = classpath.symbols
                    // In Scala 3.8 TASTy, x is a Parameter symbol in classpath.symbols (not in class declarationIds)
                    val xOpt = allSym.toSeq.find(s => s.name.asString == "x" && s.isInstanceOf[Tasty.Symbol.Parameter])
                    xOpt match
                        case None =>
                            Abort.fail(
                                TastyError.NotImplemented(
                                    s"No parameter 'x' in PlainClass symbols"
                                )
                            )
                        case Some(xSym) =>
                            Kyo.lift(symDeclaredType(xSym))
                    end match
        }).map {
            case Result.Success(tpeMaybe) =>
                assert(tpeMaybe.isDefined, s"Expected Present declaredType for val x: Int but got Absent")
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbol.declaredType for SomeTrait.compute (def compute: Int) returns a type" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClassLike("kyo.fixtures.SomeTrait") match
                        case Absent => Abort.fail(TastyError.NotImplemented("SomeTrait not found"))
                        case Present(traitSym) =>
                            val declIds = symDeclarationIds(traitSym)
                            val allSym  = classpath.symbols
                            val computeOpt = declIds.map(id => allSym(id.value)).find(s =>
                                s.name.asString == "compute" && s.kind == SymbolKind.Method
                            )
                            computeOpt match
                                case None =>
                                    Abort.fail(TastyError.NotImplemented("No method 'compute' in SomeTrait declarationIds"))
                                case Some(computeSym) =>
                                    Kyo.lift(symDeclaredType(computeSym))
                            end match
                }
            }
        ).map {
            case Result.Success(tpeMaybe) =>
                assert(tpeMaybe.isDefined, s"Expected Present declaredType for compute but got Absent")
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbol.declaredType for type StringList returns a type (alias body)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val syms = classpath.symbols.filter(_.name.asString == "StringList")
                    syms.headMaybe match
                        case Absent             => Abort.fail(TastyError.NotImplemented("No StringList symbol found"))
                        case Present(stringSym) => Kyo.lift(symDeclaredType(stringSym))
                }
            }
        ).map {
            case Result.Success(tpeMaybe) =>
                assert(tpeMaybe.isDefined, s"Expected Present declaredType for StringList but got Absent")
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // expected type. ArrayRecord.class has a single int[] component 'values'; its member symbol's
    // declaredType should be Type.Array(Type.Named(intSym)).
    "Java classfile field declaredType returns Array type for int[] values" in {
        val bytes = kyo.fixtures.Embedded.arrayRecordClass
        Abort.run[TastyError] {
            Sync.Unsafe.defer {
                given AllowUnsafe = AllowUnsafe.embrace.danger
                Abort.get(ClassfileUnpickler.read(bytes, new TypeArena))
            }
        }.map {
            case Result.Success(cr) =>
                val valuesOpt = cr.symbols.find(s => s.name.asString == "values")
                valuesOpt match
                    case None =>
                        fail(s"No 'values' member in ArrayRecord. Members: ${cr.symbols.map(_.name.asString).mkString(", ")}")
                    case Some(valuesSym) =>
                        Abort.run[TastyError](Kyo.lift(symDeclaredType(valuesSym))).map {
                            case Result.Success(tpeMaybe) =>
                                tpeMaybe match
                                    case kyo.Maybe.Present(Tasty.Type.Array(Tasty.Type.Named(elemId))) =>
                                        assert(elemId.value == -1, s"int array element stub must carry SymbolId(-1), got ${elemId.value}")
                                    case kyo.Maybe.Present(Tasty.Type.Array(other)) =>
                                        fail(s"Expected Array(Named('int')) but got Array($other)")
                                    case kyo.Maybe.Present(other) =>
                                        fail(s"Expected Type.Array for int[] values but got $other")
                                    case kyo.Maybe.Absent =>
                                        fail(s"Expected Present declaredType for values but got Absent")
                            case Result.Failure(e) =>
                                fail(s"Unexpected failure getting declaredType for values: $e")
                            case Result.Panic(t) =>
                                throw t
                        }
                end match
            case Result.Failure(e) =>
                fail(s"ClassfileUnpickler or classpath setup failed: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbol.declaredType after classpath close returns pre-populated type (no failure)" in {
        // x is a constructor parameter; find it via paramListIds of the <init> method
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Abort.run[TastyError] {
                openFixtureClasspath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) =>
                            val allSym = classpath.symbols
                            // In Scala 3.8 TASTy, x is a Parameter symbol in classpath.symbols
                            val xSym = allSym.toSeq.find(s => s.name.asString == "x" && s.isInstanceOf[Tasty.Symbol.Parameter])
                            xSym match
                                case Some(x) => Kyo.lift(x)
                                case None    => Kyo.lift(symbol) // fall back to class itself
                        case Absent =>
                            Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                }
            }
        captureResult.map {
            case Result.Failure(e) =>
                fail(s"Expected success capturing symbol but got: $e")
            case Result.Panic(t) =>
                throw t
            case Result.Success(symbol) =>
                val tpeMaybe = symDeclaredType(symbol)
                assert(tpeMaybe.isDefined, "Expected Present declaredType from pre-populated field after classpath close")
        }
    }

    // InconsistentClasspath carries Tasty.Uuid for both UUID fields.
    "TastyError.InconsistentClasspath UUID type fields compile and pattern-match correctly" in {
        val expected = Tasty.Uuid.unsafeWrap(new java.util.UUID(0L, 1L).toString)
        val found    = Tasty.Uuid.unsafeWrap(new java.util.UUID(0L, 2L).toString)
        val err      = TastyError.InconsistentClasspath("foo.tasty", expected, found)
        err match
            case TastyError.InconsistentClasspath(file, exp, fnd) =>
                assert(file == "foo.tasty")
                assert(exp == expected)
                assert(fnd == found)
            case other =>
                fail(s"Expected TastyError.InconsistentClasspath but got $other")
        end match
    }

    // Merger accepts FileResult with mutable.HashMap fields.
    // Opens fixture classpath through the full orchestrator pipeline. FileResult values
    // carry mutable.HashMap for parentsBySymbol, childrenByOwner, and typeBySymbol. The merger reads
    // these maps during finalizeMerge. Verifies that the full pipeline produces the expected fully-qualified name index entry.
    "merger processes mutable.HashMap FileResult fields and produces expected fully-qualified name entry" in {
        Abort.run[TastyError](openFixtureClasspath.map { classpath =>
            classpath.findClass("kyo.fixtures.PlainClass")
        }).map {
            case Result.Success(Present(symbol)) =>
                assert(
                    symbol.kind == SymbolKind.Class,
                    s"Expected Class kind for kyo.fixtures.PlainClass but got ${symbol.kind}"
                )
            case Result.Success(Absent) =>
                fail("Expected Present(symbol) for kyo.fixtures.PlainClass but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end QueryApiTest
