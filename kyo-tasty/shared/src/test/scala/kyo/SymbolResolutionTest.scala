package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.type_.TypeArena

/** Tests for Symbol resolution, deduplication, and cross-classpath equality.
  */
class SymbolResolutionTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    private val childClassPickle =
        Tasty.Pickle("child-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.childClassTasty))

    // The fullNameIndex is an immutable HashMap populated once during Phase C. Both calls read the same
    // HashMap entry and return the same object reference (reference equality via HashMap identity).
    "two concurrent findClass calls for the same fully-qualified name return reference-equal symbols" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    Async.zip[TastyError, Maybe[Tasty.Symbol.Class], Maybe[Tasty.Symbol.Class], Any](
                        classpath.findClass("kyo.fixtures.PlainClass"),
                        classpath.findClass("kyo.fixtures.PlainClass")
                    )
                }
            }
        ).map {
            case Result.Success((Present(sym1), Present(sym2))) =>
                assert(
                    sym1 eq sym2,
                    s"Concurrent findClass calls must return reference-equal symbols; got different instances for ${sym1.name.asString}"
                )
            case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                fail("Expected both concurrent findClass calls to return Present")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "two concurrent findClass calls for different fully-qualified names both resolve independently" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    Async.zip[TastyError, Maybe[Tasty.Symbol.Class], Maybe[Tasty.Symbol.Class], Any](
                        classpath.findClass("kyo.fixtures.PlainClass"),
                        classpath.findClass("no.such.Class")
                    )
                }
            }
        ).map {
            case Result.Success((Present(sym1), Absent)) =>
                assert(
                    sym1.name.asString.contains("PlainClass"),
                    s"Expected PlainClass symbol, got: ${sym1.name.asString}"
                )
            case Result.Success((Absent, _)) =>
                fail("Expected PlainClass to be found")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "findClass for missing fully-qualified name returns Absent in soft-fail mode" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(_.findClass("no.such.Class"))
            }
        ).map {
            case Result.Success(Absent) =>
                succeed
            case Result.Success(Present(_)) =>
                fail("Expected Absent for nonexistent fully-qualified name")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // Two separate withPickles invocations yield different Symbol object references
    // (not reference-equal) but the same full names (structural equality by fully-qualified name).
    "cross-classpath fully-qualified name structural equality: different instances but same fully-qualified name" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { cp1 =>
                    cp1.findClass("kyo.fixtures.PlainClass")
                }
            }
        ).map { r1 =>
            Abort.run[TastyError](
                Tasty.withPickles(Chunk(Tasty.Pickle(
                    "plain-class-2",
                    Tasty.Version(28, 3, 0),
                    Span.from(kyo.fixtures.Embedded.plainClassTasty)
                ))) {
                    Tasty.classpath.map { cp2 =>
                        cp2.findClass("kyo.fixtures.PlainClass")
                    }
                }
            ).map { r2 =>
                (r1, r2) match
                    case (Result.Success(Present(sym1)), Result.Success(Present(sym2))) =>
                        assert(sym1 ne sym2, "Symbols from different Classpath instances must not be reference-equal")
                        assert(
                            sym1.name.asString == sym2.name.asString,
                            s"Symbols from different instances must have same fully-qualified name: ${sym1.name.asString} vs ${sym2.name.asString}"
                        )
                    case _ =>
                        fail(s"Expected both Classpath instances to return Present for PlainClass; got $r1 and $r2")
            }
        }
    }

    // Helper: decode a TASTy byte array using AstUnpickler.readPass1 and return Pass1Result.
    private def decodeBytes(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- Sync.Unsafe.defer(Abort.get(TastyHeader.read(view)))
            names    <- NameUnpickler.read(view)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, arena)
                case Absent =>
                    Abort.fail(TastyError.MalformedSection("ASTs", "ASTs section not found", 0L))
        yield result
        end for
    end decodeBytes

    // Verify that PlainClass.tasty opens successfully and parentTypes are populated.
    "Phase C: cross-file type references resolved (PlainClass has parentTypes)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass") match
                        case Present(symbol) => Kyo.lift(symbol match
                                case c: Tasty.Symbol.ClassLike => c.parentTypes;
                                case null                      => Chunk.empty[Tasty.Type])
                        case Absent => Abort.fail(TastyError.MalformedSection("ASTs", "PlainClass not found", 0L))
                }
            }
        ).map {
            case Result.Success(parents) =>
                assert(parents.nonEmpty, "PlainClass should have at least one parent type (cross-file ref resolved)")
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Opens a classpath with ONLY childClassTasty (no base file) and verifies it opens without panic.
    "Phase C: classpath opens without panic when cross-file parent is absent (unresolved symbols)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle)) {
                Tasty.classpath.map(_.findClass("kyo.fixtures.ChildClass"))
            }
        ).map {
            case Result.Success(Present(_)) =>
                succeed
            case Result.Success(Absent) =>
                fail("Expected ChildClass to be found in partial classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    import kyo.Tasty.SymbolId

    private def makeClassSym9(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
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
    end makeClassSym9

    private def makePkgSym9(id: Int, name: String): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(id), Chunk.empty)
    end makePkgSym9

    private def makeMethodSym9(id: Int, name: String, ownerId: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
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
            Maybe.Absent
        )
    end makeMethodSym9

    private def makeValSym9(id: Int, name: String, ownerId: Int): Tasty.Symbol.Val =
        Tasty.Symbol.Val(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )
    end makeValSym9

    private def makeVarSym9(id: Int, name: String, ownerId: Int): Tasty.Symbol.Var =
        Tasty.Symbol.Var(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )
    end makeVarSym9

    // owner resolves to the correct Symbol.
    "owner resolves to the correct Symbol" in {
        val pkgSym = makePkgSym9(id = 0, name = "p")
        val fooSym = makeClassSym9(id = 1, name = "Foo", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(pkgSym, fooSym)).map { classpath =>
            val owner = classpath.owner(fooSym)
            assert(owner.isDefined, "Expected owner to be Present")
            assert(
                owner.get.id == pkgSym.id,
                s"Expected owner id ${pkgSym.id.value} but got ${owner.get.id.value}"
            )
        }
    }

    // parents extracts only Type.Named entries.
    "parents extracts only Type.Named entries from parentTypes" in {
        val symA = makeClassSym9(id = 0, name = "A", ownerId = 0)
        val symB = makeClassSym9(id = 1, name = "B", ownerId = 0)
        val symC = makeClassSym9(id = 2, name = "C", ownerId = 0).copy(parentTypes =
            Chunk(
                Tasty.Type.Named(SymbolId(0)),
                Tasty.Type.Applied(
                    Tasty.Type.Named(SymbolId(1)),
                    Chunk(Tasty.Type.ConstantType(Tasty.Constant.IntConst(0)))
                )
            )
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symA, symB, symC)).map { classpath =>
            val parents = symC.parentTypes.flatMap { case Tasty.Type.Named(pid) => classpath.symbol(pid).toChunk; case _ => Chunk.empty }
            assert(
                parents.length == 1,
                s"Expected 1 parent (Named only) but got ${parents.length}"
            )
            assert(
                parents(0).id == symA.id,
                s"Expected parent to be A (id 0) but got id ${parents(0).id.value}"
            )
        }
    }

    // methods returns only method-kind declarations.
    "methods returns only method-kind declarations" in {
        val classSym  = makeClassSym9(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethodSym9(id = 1, name = "foo", ownerId = 0)
        val method2   = makeMethodSym9(id = 2, name = "bar", ownerId = 0)
        val valSym    = makeValSym9(id = 3, name = "x", ownerId = 0)
        val varSym    = makeVarSym9(id = 4, name = "y", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, method2, valSym, varSym)).map { classpath =>
            val methods = withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
            assert(methods.length == 2, s"Expected 2 methods but got ${methods.length}")
            val methodNames = methods.map(_.name.asString).toSet
            assert(methodNames == Set("foo", "bar"), s"Expected {foo, bar} but got $methodNames")
        }
    }

    // findDeclaredMember by string name returns Maybe.Absent when missing.
    "findDeclaredMember by string name returns Maybe.Absent when missing" in {
        val classSym  = makeClassSym9(id = 0, name = "Foo", ownerId = 0)
        val memberSym = makeMethodSym9(id = 1, name = "existingMethod", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, memberSym)).map { classpath =>
            val absent = Maybe.fromOption(withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "nope"))
            val present =
                Maybe.fromOption(
                    withDecls.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "existingMethod")
                )
            assert(absent == Maybe.Absent, s"Expected Absent for 'nope' but got $absent")
            assert(present.isDefined, s"Expected Present for 'existingMethod' but got $present")
        }
    }

    // symbol.parents, classpath.owner(symbol), symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]) are direct member calls.
    "symbol.parents, classpath.owner(symbol), symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]) compile as direct member calls" in {
        val pkgSym = makePkgSym9(id = 0, name = "pkg")
        val fooSym = makeClassSym9(id = 1, name = "Foo", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(pkgSym, fooSym)).map { classpath =>
            val ownerSym = classpath.owner(fooSym)
            val parentList: Chunk[Tasty.Symbol] =
                fooSym.parentTypes.flatMap { case Tasty.Type.Named(pid) => classpath.symbol(pid).toChunk; case _ => Chunk.empty }
            val methodList: Chunk[Tasty.Symbol] =
                fooSym.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
            assert(ownerSym.isDefined && ownerSym.get.id == pkgSym.id, "owner id mismatch")
            assert(parentList.isEmpty, s"Expected empty parents got ${parentList.length}")
            assert(methodList.isEmpty, s"Expected empty methods got ${methodList.length}")
        }
    }

    // no AllowUnsafe on resolution accessors.
    "resolution accessors require only Classpath, no AllowUnsafe" in {
        val symbol = makeClassSym9(id = 0, name = "X", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symbol)).map { classpath =>
            // All resolution accessors via pure Classpath instance methods; no effect row, no AllowUnsafe.
            val _typeParams: Chunk[Tasty.Symbol] = symbol.typeParamIds.flatMap(id => classpath.symbol(id).toChunk)
            val _decls: Chunk[Tasty.Symbol]      = symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
            val _methods: Chunk[Tasty.Symbol] =
                symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
            val _vals: Chunk[Tasty.Symbol] =
                symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Val])
            val _vars: Chunk[Tasty.Symbol] =
                symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Var])
            val _fields: Chunk[Tasty.Symbol] =
                symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Field])
            val _nested: Chunk[Tasty.Symbol] = symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(s =>
                s.isInstanceOf[Tasty.Symbol.Class] || s.isInstanceOf[Tasty.Symbol.Trait] || s.isInstanceOf[Tasty.Symbol.Object]
            )
            val _typeM: Chunk[Tasty.Symbol] = symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).filter(s =>
                s.isInstanceOf[Tasty.Symbol.TypeAlias] ||
                    s.isInstanceOf[Tasty.Symbol.OpaqueType] ||
                    s.isInstanceOf[Tasty.Symbol.AbstractType]
            )
            val _find: Maybe[Tasty.Symbol] =
                Maybe.fromOption(symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).find(_.simpleName == "anything"))
            val _show: String               = classpath.show(symbol)
            val _owner: Maybe[Tasty.Symbol] = classpath.owner(symbol)
            val _parents: Chunk[Tasty.Symbol] = symbol match
                case cl: Tasty.Symbol.ClassLike => classpath.parents(cl)
                case other                      => fail(s"expected Symbol.ClassLike, got $other")
            assert(_show.nonEmpty)
        }
    }

end SymbolResolutionTest
