package kyo

import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Classpath.Indices.bySourceFile invariants and the pure symbol-index lookups.
  *
  * Coverage:
  *   bySourceFile groups every symbol that carries a sourcePosition under its sourceFile key.
  *   SymbolIds within each bySourceFile chunk are ascending (insertion-order from the symbol array).
  *   bySourceFile is a pure function of sourcePosition fields; rebuilding from symbols gives the same Dict.
  *   Cold open + bySourceFile build triggers zero body-byte decodes (bodyMemo stays at size 0).
  *   symbolsInFile / symbolsByName / symbolsByPrefix are O(1)-or-better total lookups over the resident
  *   indices, each kind included, unknown input returning an empty Chunk.
  *   The object Tasty companion delegators are thin: equal to their Classpath instance counterparts.
  *   The 3 lookups decode zero bodies, extending the no-body-decode guarantee.
  */
class SymbolIndexTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import Tasty.SymbolId

    private val childClassPickle =
        Tasty.Pickle("child-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.childClassTasty))

    private val baseClassPickle =
        Tasty.Pickle("base-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.baseClassTasty))

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    private val someCaseClassPickle =
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty))

    /** Resolves the embedded `SOURCEFILEattr` path for a fully-qualified class, failing the test if either the
      * class or its sourcePosition is absent. The path is compiled in, so it is discovered dynamically rather
      * than hardcoded.
      */
    private def sourceFileOf(classpath: Tasty.Classpath, fullName: String)(using kyo.test.AssertScope): String =
        classpath.findClass(fullName) match
            case Maybe.Present(sym) =>
                sym.sourcePosition match
                    case Maybe.Present(pos) => pos.sourceFile
                    case Maybe.Absent       => fail(s"$fullName has Absent sourcePosition")
            case Maybe.Absent => fail(s"$fullName not found")

    "bySourceFile groups symbols by sourceFile (cold load)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    (classpath.indices.bySourceFile, classpath.symbols)
                }
            }
        ).map {
            case Result.Success((bySourceFile, symbols)) =>
                assert(bySourceFile.nonEmpty, "bySourceFile must be non-empty for fixtures with SOURCEFILE attribute")
                val symbolById = symbols.map(s => s.id -> s).toMap
                bySourceFile.foreach { (file, ids) =>
                    assert(ids.nonEmpty, s"chunk for '$file' must not be empty")
                    ids.foreach { id =>
                        symbolById.get(id) match
                            case Some(sym) =>
                                sym.sourcePosition match
                                    case Maybe.Present(pos) =>
                                        assert(
                                            pos.sourceFile == file,
                                            s"Symbol $id grouped under '$file' but sourcePosition.sourceFile='${pos.sourceFile}'"
                                        )
                                    case Maybe.Absent =>
                                        fail(s"Symbol $id in bySourceFile('$file') has Absent sourcePosition")
                            case None =>
                                fail(s"SymbolId $id in bySourceFile but not found in classpath.symbols")
                    }
                }
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "bySourceFile chunk is ordered ascending by SymbolId" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map(_.indices.bySourceFile)
            }
        ).map {
            case Result.Success(bySourceFile) =>
                bySourceFile.foreach { (file, ids) =>
                    val seq = ids.toSeq
                    seq.zip(seq.tail).foreach { case (a, b) =>
                        assert(
                            a.value <= b.value,
                            s"SymbolIds in bySourceFile('$file') must be ascending: got $a then $b"
                        )
                    }
                }
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "bySourceFile is a pure function of sourcePosition: rebuild equals stored" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val rebuilt =
                        classpath.symbols.foldLeft(
                            scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ArrayBuffer[SymbolId]]
                        ) { (b, sym) =>
                            sym.sourcePosition match
                                case Maybe.Present(pos) =>
                                    b.getOrElseUpdate(pos.sourceFile, new scala.collection.mutable.ArrayBuffer()) += sym.id
                                case Maybe.Absent => ()
                            end match
                            b
                        }
                    val rebuiltDict = Dict.from(rebuilt.map((k, v) => k -> Chunk.from(v.toSeq)).toMap)
                    (rebuiltDict, classpath.indices.bySourceFile)
                }
            }
        ).map {
            case Result.Success((rebuiltDict, storedDict)) =>
                assert(
                    rebuiltDict.is(storedDict),
                    "Rebuilt bySourceFile from symbol.sourcePosition must equal stored bySourceFile"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "absent sourcePosition contributes to no bySourceFile entry" in {
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val pkgSym  = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("test"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val noPosSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("NoPos"),
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
        val cp = Tasty.Classpath.make(
            symbols = Chunk(rootSym, pkgSym, noPosSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk(noPosSym.id),
            packageIds = Chunk(rootSym.id, pkgSym.id),
            fullNameIndex = Dict("test.NoPos" -> noPosSym.id),
            packageIndex = Dict("test" -> pkgSym.id),
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )
        assert(
            cp.indices.bySourceFile.isEmpty,
            s"bySourceFile must be empty when all symbols have Absent sourcePosition; got ${cp.indices.bySourceFile.toMap}"
        )
        succeed
    }

    "empty classpath yields empty bySourceFile" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk.empty) {
                Tasty.classpath.map(_.indices.bySourceFile)
            }
        ).map {
            case Result.Success(bySourceFile) =>
                assert(bySourceFile.isEmpty, s"bySourceFile must be empty for an empty classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "bySourceFile build during cold open decodes zero body bytes" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val bsf = classpath.indices.bySourceFile
                    Tasty.bindingLocal.use { bindingMaybe =>
                        bindingMaybe match
                            case Maybe.Present(binding) =>
                                binding.decodeCtx match
                                    case Maybe.Present(ctx) =>
                                        val memoSize = ctx.bodyMemo.size()
                                        assert(
                                            memoSize == 0,
                                            s"bySourceFile build must not trigger body decode; bodyMemo.size=$memoSize"
                                        )
                                    case Maybe.Absent => ()
                            case Maybe.Absent => ()
                        end match
                        assert(bsf.nonEmpty, "bySourceFile must be non-empty after cold open with SOURCEFILE-bearing fixtures")
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsInFile returns every kind defined in the file, ascending by id" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val file   = sourceFileOf(classpath, "kyo.fixtures.PlainClass")
                    val result = classpath.symbolsInFile(file)
                    assert(result.nonEmpty, s"symbolsInFile('$file') must be non-empty")
                    val ids = result.map(_.id.value)
                    ids.zip(ids.tail).foreach { case (a, b) =>
                        assert(a <= b, s"symbolsInFile('$file') ids must be ascending: got $a then $b")
                    }
                    result.foreach { sym =>
                        sym.sourcePosition match
                            case Maybe.Present(pos) =>
                                assert(
                                    pos.sourceFile == file,
                                    s"Symbol ${sym.id} from symbolsInFile('$file') has sourceFile '${pos.sourceFile}'"
                                )
                            case Maybe.Absent =>
                                fail(s"Symbol ${sym.id} from symbolsInFile('$file') has Absent sourcePosition")
                    }
                    assert(result.exists(_.kind == SymbolKind.Class), "symbolsInFile must include the file's class")
                    assert(
                        result.exists(s => s.kind == SymbolKind.Method && s.simpleName == "identityMethod"),
                        "symbolsInFile must include the real method identityMethod, a non-class kind"
                    )
                    assert(
                        result.exists(s => s.kind == SymbolKind.Val && s.simpleName == "topLevelVal"),
                        "symbolsInFile must include the real value topLevelVal, a non-class kind"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsInFile on an unknown file returns empty" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    assert(classpath.symbolsInFile("does/not/exist.scala") == Chunk.empty)
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "Tasty.symbolsInFile delegator equals the instance method" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                for
                    childFile    <- Tasty.classpath.map(sourceFileOf(_, "kyo.fixtures.ChildClass"))
                    viaDelegator <- Tasty.symbolsInFile(childFile)
                    viaInstance  <- Tasty.classpath.map(_.symbolsInFile(childFile))
                yield
                    assert(viaDelegator.nonEmpty, s"expected symbols for '$childFile'")
                    assert(viaDelegator == viaInstance, "Tasty.symbolsInFile must equal classpath.symbolsInFile")
                    succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsByName returns ALL kinds, a superset of findClassesByName" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someCaseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val byName      = classpath.symbolsByName("SomeCaseClass")
                    val classesOnly = classpath.findClassesByName("SomeCaseClass")
                    assert(classesOnly.nonEmpty, "findClassesByName(\"SomeCaseClass\") must find the class")
                    assert(
                        classesOnly.forall(c => byName.exists(_.id == c.id)),
                        "symbolsByName must contain every findClassesByName element"
                    )
                    // The companion object's own TASTy name already carries the "$" suffix (e.g. "SomeCaseClass$"),
                    // so it falls under a different bySimpleName key and is not the non-class member proven here.
                    // The synthetic top-level accessor for the companion singleton shares the class's exact simple
                    // name and has kind Val; that is the non-class kind symbolsByName("SomeCaseClass") surfaces.
                    assert(
                        byName.exists(_.kind == SymbolKind.Val),
                        "symbolsByName must include the companion singleton's Val accessor, a non-class kind"
                    )
                    assert(byName.size > classesOnly.size, "symbolsByName must be a strict superset of findClassesByName")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsByName unknown/empty name returns empty" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    assert(classpath.symbolsByName("NoSuchName") == Chunk.empty)
                    assert(classpath.symbolsByName("") == Chunk.empty)
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsByPrefix matches the shared prefix, excludes non-matches" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val result = classpath.symbolsByPrefix("Chi")
                    assert(result.nonEmpty, "symbolsByPrefix(\"Chi\") must match ChildClass")
                    assert(result.forall(_.simpleName.startsWith("Chi")), "every result must start with \"Chi\"")
                    assert(result.exists(_.simpleName == "ChildClass"), "must include ChildClass")
                    assert(!result.exists(_.simpleName == "BaseClass"), "must exclude BaseClass")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsByPrefix empty prefix returns all named symbols" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val names       = Chunk.from(classpath.indices.bySimpleName.toMap.keys.toSeq)
                    val expectedIds = names.flatMap(classpath.symbolsByName).map(_.id).toSet
                    val actual      = classpath.symbolsByPrefix("")
                    assert(
                        actual.map(_.id).toSet == expectedIds,
                        "symbolsByPrefix(\"\") must equal the union of symbolsByName over all distinct simple names"
                    )
                    assert(actual.size == expectedIds.size, "each named symbol must appear exactly once")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolsByPrefix non-matching prefix returns empty" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    assert(classpath.symbolsByPrefix("ZZZ_no_match") == Chunk.empty)
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "all three delegators are thin (equal to instance counterparts)" in {
        val byNameInputs   = Chunk("ChildClass", "BaseClass", "NoSuchName")
        val byPrefixInputs = Chunk("Chi", "Base", "ZZZ_no_match")
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                for
                    childFile <- Tasty.classpath.map(sourceFileOf(_, "kyo.fixtures.ChildClass"))
                    fileInputs = Chunk(childFile, "unknown.scala", "")
                    inFileDelegator   <- Kyo.foreach(fileInputs)(f => Tasty.symbolsInFile(f))
                    inFileInstance    <- Tasty.classpath.map(cp => fileInputs.map(cp.symbolsInFile))
                    byNameDelegator   <- Kyo.foreach(byNameInputs)(n => Tasty.symbolsByName(n))
                    byNameInstance    <- Tasty.classpath.map(cp => byNameInputs.map(cp.symbolsByName))
                    byPrefixDelegator <- Kyo.foreach(byPrefixInputs)(p => Tasty.symbolsByPrefix(p))
                    byPrefixInstance  <- Tasty.classpath.map(cp => byPrefixInputs.map(cp.symbolsByPrefix))
                yield
                    assert(inFileDelegator == inFileInstance, "symbolsInFile delegator must equal instance method for every input")
                    assert(byNameDelegator == byNameInstance, "symbolsByName delegator must equal instance method for every input")
                    assert(
                        byPrefixDelegator == byPrefixInstance,
                        "symbolsByPrefix delegator must equal instance method for every input"
                    )
                    succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "the pure-index lookups decode ZERO bodies (no-regression guard)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val childFile = sourceFileOf(classpath, "kyo.fixtures.ChildClass")
                    val inFile    = classpath.symbolsInFile(childFile)
                    val byName    = classpath.symbolsByName("ChildClass")
                    val byPrefix  = classpath.symbolsByPrefix("Chi")
                    Tasty.bindingLocal.use { bindingMaybe =>
                        bindingMaybe match
                            case Maybe.Present(binding) =>
                                binding.decodeCtx match
                                    case Maybe.Present(ctx) =>
                                        val memoSize = ctx.bodyMemo.size()
                                        assert(
                                            memoSize == 0,
                                            s"pure-index lookups must not trigger body decode; bodyMemo.size=$memoSize"
                                        )
                                    case Maybe.Absent => ()
                            case Maybe.Absent => ()
                        end match
                        assert(inFile.nonEmpty, "symbolsInFile must return ChildClass's symbols")
                        assert(byName.nonEmpty, "symbolsByName(\"ChildClass\") must return a match")
                        assert(byPrefix.nonEmpty, "symbolsByPrefix(\"Chi\") must return a match")
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end SymbolIndexTest
