package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Tests for Phase 7: Symbol resolution, deduplication, and cross-classpath equality.
  *
  * Plan tests 19, 21, 35.
  */
class SymbolResolutionTest extends Test:

    import AllowUnsafe.embrace.danger

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)

    // Test 19: two concurrent findClass calls for the same FQN return reference-equal Symbol instances.
    // The fqnIndex is an immutable HashMap populated once during Phase C. Both calls read the same
    // HashMap entry and return the same object reference (reference equality via HashMap identity).
    "two concurrent findClass calls for the same FQN return reference-equal symbols" in run {
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[TastyError, Maybe[Tasty.Symbol.Class], Maybe[Tasty.Symbol.Class], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("kyo.fixtures.PlainClass")
                )).map:
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

    // Test 21 (renumbered from prior Test 20): two concurrent findClass calls for different FQNs both resolve independently
    "two concurrent findClass calls for different FQNs both resolve independently" in run {
        // Use the same file twice with different paths so we get two distinct FQNs
        // Since we only have PlainClass, we open a classpath with it twice (once in each root path slot)
        // and look up the same FQN plus a non-existent one
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                Async.zip[TastyError, Maybe[Tasty.Symbol.Class], Maybe[Tasty.Symbol.Class], Any](
                    cp.findClass("kyo.fixtures.PlainClass"),
                    cp.findClass("no.such.Class")
                )).map:
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

    // Test 21: Unresolved sentinel: findClass for a missing FQN returns Absent (soft-fail mode)
    "findClass for missing FQN returns Absent in soft-fail mode" in run {
        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("no.such.Class")).map:
                case Result.Success(Absent) =>
                    succeed
                case Result.Success(Present(_)) =>
                    fail("Expected Absent for nonexistent FQN")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 35: cross-classpath structural equality by FQN
    // Two separate Classpath instances over the same roots yield different Symbol object references
    // (not reference-equal) but the same full names (structural equality by FQN).
    "cross-classpath FQN structural equality: different instances but same FQN" in run {
        val src1 = fixtureSource()
        val src2 = fixtureSource()
        Scope.run:
            Abort.run[TastyError](
                openClasspath(src1).flatMap: cp1 =>
                    openClasspath(src2).map: cp2 =>
                        val sym1Opt = cp1.findClass("kyo.fixtures.PlainClass")
                        val sym2Opt = cp2.findClass("kyo.fixtures.PlainClass")
                        (sym1Opt, sym2Opt)
            ).map:
                case Result.Success((Present(sym1), Present(sym2))) =>
                    assert(sym1 ne sym2, "Symbols from different Classpath instances must not be reference-equal")
                    assert(
                        sym1.name.asString == sym2.name.asString,
                        s"Symbols from different Classpath instances must have same FQN: ${sym1.name.asString} vs ${sym2.name.asString}"
                    )
                case Result.Success((Absent, _)) | Result.Success((_, Absent)) =>
                    fail("Expected both Classpath instances to return Present for PlainClass")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Helper: decode a TASTy byte array using AstUnpickler.readPass1 and return Pass1Result.
    private def decodeBytes(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- TastyHeader.read(view)
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

    // Phase 2 Test 1 (redesigned for Phase 07): cross-file type references are resolved via fqnIndex
    // at Phase C finalizeMerge. The UnresolvedRef mechanism is deleted.
    // Verify that PlainClass.tasty opens successfully and parentTypes are populated.
    "Phase C: cross-file type references resolved (PlainClass has parentTypes)" in run {
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym match
                            case c: Tasty.Symbol.ClassLike => c.parentTypes;
                            case null                      => Chunk.empty[Tasty.Type])
                    case Absent => Abort.fail(TastyError.MalformedSection("ASTs", "PlainClass not found", 0L))).map:
                case Result.Success(parents) =>
                    assert(parents.nonEmpty, "PlainClass should have at least one parent type (cross-file ref resolved)")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Phase 2 Test 2: missing-class placeholder resolves to Unresolved sentinel when base file is absent.
    //
    // Design note: childClassTasty has no TYPEREFpkg/TYPEREFin for BaseClass (same compilation unit).
    // Parts a-c are redesigned to use PlainClass.tasty which has real cross-file UnresolvedRef
    // entries, then simulate Phase C with the FQN absent from fqnIndex.
    // Part d still uses childClassTasty + openClasspath to confirm no panic from unset slots.
    //
    // Steps:
    //   a) Decode PlainClass.tasty to get real UnresolvedRef placeholders.
    //   b) Take the first placeholder; simulate Phase C with fqnIndex MISS: synthesize Unresolved sentinel.
    //   c) Verify replaceSlot.get() returns Named(sym) with sym.kind == Unresolved and same FQN.
    //   d) Init a full classpath with ONLY childClassTasty (no base) via initInto and verify
    //      it opens without panic (no unset SingleAssign).
    // Phase 2 Test 2 (redesigned for Phase 07): unresolved cross-file references become Unresolved
    // symbols in the classpath. Verify that ChildClass.tasty opens without panic when base file is absent.
    "Phase C: classpath opens without panic when cross-file parent is absent (unresolved symbols)" in run {
        val src = MemoryFileSource()
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        Scope.run:
            Abort.run[TastyError](openClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.ChildClass")).map:
                case Result.Success(Present(_)) =>
                    succeed
                case Result.Success(Absent) =>
                    fail("Expected ChildClass to be found in partial classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Phase 09: Plan-mandated tests (Leaves 1-6) ──────────────────────────

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
            Chunk.empty,
            Maybe.Absent
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
            Maybe.Absent,
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
            Chunk.empty,
            Maybe.Absent
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
            Chunk.empty,
            Maybe.Absent
        )
    end makeVarSym9

    // Phase 09 Leaf 1: owner resolves to the correct Symbol.
    // Pins: INV-005 + INV-001
    "Phase09: owner resolves to the correct Symbol" in run {
        val pkgSym = makePkgSym9(id = 0, name = "p")
        val fooSym = makeClassSym9(id = 1, name = "Foo", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(pkgSym, fooSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.owner(fooSym).map: owner =>
                    assert(owner.isDefined, "Expected owner to be Present")
                    assert(
                        owner.get.id == pkgSym.id,
                        s"Expected owner id ${pkgSym.id.value} but got ${owner.get.id.value}"
                    )
                    succeed
    }

    // Phase 09 Leaf 2: parents extracts only Type.Named entries.
    // Pins: INV-002 + INV-005
    "Phase09: parents extracts only Type.Named entries from parentTypes" in run {
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(symA, symB, symC)).map: cp =>
            val parents = symC.parentTypes.flatMap { case Tasty.Type.Named(pid) => cp.symbol(pid).toChunk; case _ => Chunk.empty }
            assert(
                parents.length == 1,
                s"Expected 1 parent (Named only) but got ${parents.length}"
            )
            assert(
                parents(0).id == symA.id,
                s"Expected parent to be A (id 0) but got id ${parents(0).id.value}"
            )
    }

    // Phase 09 Leaf 3: methods returns only method-kind declarations.
    // Pins: INV-005
    "Phase09: methods returns only method-kind declarations" in run {
        val classSym  = makeClassSym9(id = 0, name = "Foo", ownerId = 0)
        val method1   = makeMethodSym9(id = 1, name = "foo", ownerId = 0)
        val method2   = makeMethodSym9(id = 2, name = "bar", ownerId = 0)
        val valSym    = makeValSym9(id = 3, name = "x", ownerId = 0)
        val varSym    = makeVarSym9(id = 4, name = "y", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, method1, method2, valSym, varSym)).map: cp =>
            val methods = withDecls.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
            assert(methods.length == 2, s"Expected 2 methods but got ${methods.length}")
            val methodNames = methods.map(_.name.asString).toSet
            assert(methodNames == Set("foo", "bar"), s"Expected {foo, bar} but got $methodNames")
    }

    // Phase 09 Leaf 4: findDeclaredMember by string name returns Maybe.Absent when missing.
    // Pins: INV-007
    "Phase09: findDeclaredMember by string name returns Maybe.Absent when missing" in run {
        val classSym  = makeClassSym9(id = 0, name = "Foo", ownerId = 0)
        val memberSym = makeMethodSym9(id = 1, name = "existingMethod", ownerId = 0)
        val withDecls = classSym.copy(declarationIds = Chunk(SymbolId(1)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(withDecls, memberSym)).map: cp =>
            val absent = Maybe.fromOption(withDecls.declarationIds.flatMap(id => cp.symbol(id).toChunk).find(_.simpleName == "nope"))
            val present =
                Maybe.fromOption(withDecls.declarationIds.flatMap(id => cp.symbol(id).toChunk).find(_.simpleName == "existingMethod"))
            assert(absent == Maybe.Absent, s"Expected Absent for 'nope' but got $absent")
            assert(present.isDefined, s"Expected Present for 'existingMethod' but got $present")
    }

    // Phase 09 Leaf 5: sym.parents, Tasty.owner(sym), sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]) are direct member calls.
    // Pins: API discipline rule
    "Phase09: sym.parents, Tasty.owner(sym), sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]) compile as direct member calls" in run {
        val pkgSym = makePkgSym9(id = 0, name = "pkg")
        val fooSym = makeClassSym9(id = 1, name = "Foo", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(pkgSym, fooSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.owner(fooSym).map: ownerSym =>
                    val parentList: Chunk[Tasty.Symbol] =
                        fooSym.parentTypes.flatMap { case Tasty.Type.Named(pid) => cp.symbol(pid).toChunk; case _ => Chunk.empty }
                    val methodList: Chunk[Tasty.Symbol] =
                        fooSym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
                    assert(ownerSym.isDefined && ownerSym.get.id == pkgSym.id, "owner id mismatch")
                    assert(parentList.isEmpty, s"Expected empty parents got ${parentList.length}")
                    assert(methodList.isEmpty, s"Expected empty methods got ${methodList.length}")
                    succeed
    }

    // Phase 09 Leaf 6: no AllowUnsafe on resolution accessors.
    // Pins: INV-010
    "Phase09: resolution accessors require only Classpath, no AllowUnsafe" in run {
        val sym = makeClassSym9(id = 0, name = "X", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(sym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                // All resolution accessors via Tasty.* free functions (Phase 06 API)
                val _typeParams: Chunk[Tasty.Symbol] = sym.typeParamIds.flatMap(id => cp.symbol(id).toChunk)
                val _decls: Chunk[Tasty.Symbol]      = sym.declarationIds.flatMap(id => cp.symbol(id).toChunk)
                val _methods: Chunk[Tasty.Symbol] =
                    sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method])
                val _vals: Chunk[Tasty.Symbol] =
                    sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Val])
                val _vars: Chunk[Tasty.Symbol] =
                    sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Var])
                val _fields: Chunk[Tasty.Symbol] =
                    sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Field])
                val _nested: Chunk[Tasty.Symbol] = sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(s =>
                    s.isInstanceOf[Tasty.Symbol.Class] || s.isInstanceOf[Tasty.Symbol.Trait] || s.isInstanceOf[Tasty.Symbol.Object]
                )
                val _typeM: Chunk[Tasty.Symbol] = sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(s =>
                    s.isInstanceOf[Tasty.Symbol.TypeAlias] ||
                        s.isInstanceOf[Tasty.Symbol.OpaqueType] ||
                        s.isInstanceOf[Tasty.Symbol.AbstractType]
                )
                val _find: Maybe[Tasty.Symbol] =
                    Maybe.fromOption(sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).find(_.simpleName == "anything"))
                val _showEff: String < Sync = Tasty.show(sym)
                for
                    _owner   <- Tasty.owner(sym)
                    _parents <- Tasty.parents(sym.asInstanceOf[Tasty.Symbol.ClassLike])
                yield succeed
                end for
    }

end SymbolResolutionTest
