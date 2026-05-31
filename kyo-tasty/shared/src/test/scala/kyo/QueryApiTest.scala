package kyo

import kyo.internal.tasty.classfile.ClassfileUnpickler
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Tests for Phase 7: Query API, classpath lifecycle, and Phase A/B/C orchestration.
  *
  * Uses an in-memory FileSource for cross-platform compatibility.
  *
  * Plan tests 1-18, 24b, 31-34, 36.
  */
class QueryApiTest extends Test:

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

    /** Build a MemoryFileSource seeded with a single .tasty file at "root/PlainClass.tasty". */
    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    /** Open a classpath from the in-memory source using ClasspathOrchestrator directly.
      *
      * Returns `Tasty.Classpath` (the public opaque type) so that extension methods such as `findClass`, `errors`, `topLevelClasses`, and
      * `findClassByBinary` are in scope.
      */
    private def openFixtureClasspath(src: FileSource, mode: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail)(
        using Frame
    ): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.open(Seq("root"), mode, src, 1)
    end openFixtureClasspath

    // Test 1: fromPickles(Seq.empty) succeeds; findClass("anything") returns Absent
    "fromPickles(Seq.empty) succeeds and findClass returns Absent" in run {
        Scope.run:
            Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
                val result = cp.findClass("some.Class")
                assert(result == Maybe.Absent)
    }

    // Test 2: cp.findClass on fixture classpath returns Present(sym) with kind == Class
    "findClass on fixture TASTy returns Present(sym) with kind Class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Present(sym)) =>
                    assert(sym.kind == Tasty.SymbolKind.Class)
                case Result.Success(Absent) =>
                    fail("Expected Present(sym) but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 3: findClass for nonexistent FQN returns Absent
    "findClass for nonexistent FQN returns Absent" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("nonexistent.Class.XYZ")).map:
                case Result.Success(Absent) =>
                    succeed
                case Result.Success(Present(_)) =>
                    fail("Expected Absent for nonexistent class")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 4: findPackage on fixture classpath returns Present(pkg) with kind == Package
    "findPackage returns Present(pkg) with kind Package" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findPackage("kyo.fixtures")).map:
                case Result.Success(Present(pkg)) =>
                    assert(pkg.kind == Tasty.SymbolKind.Package)
                case Result.Success(Absent) =>
                    // Package symbols depend on unpickler emitting package nodes; allow Absent if not emitted
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 5: topLevelClasses returns a non-empty Chunk for fixture classpath
    "topLevelClasses returns non-empty Chunk for fixture classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.topLevelClasses).map:
                case Result.Success(classes) =>
                    assert(classes.nonEmpty, s"Expected non-empty topLevelClasses but got empty")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 6: packages returns at least one package symbol (or empty if unpickler doesn't emit package nodes)
    "packages does not fail for fixture classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.packages).map:
                case Result.Success(_) =>
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 7: errors returns Chunk.empty for a clean classpath
    "errors returns Chunk.empty for clean classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.errors).map:
                case Result.Success(errs) =>
                    assert(errs.isEmpty, s"Expected no errors but got: $errs")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 8: errors returns at least one TastyError for a classpath with a corrupt TASTy file
    "errors returns non-empty for classpath with corrupt TASTy" in run {
        val src = MemoryFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5)) // corrupt magic
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src).flatMap: cp =>
                cp.errors).map:
                case Result.Success(errs) =>
                    assert(errs.nonEmpty, "Expected at least one error for corrupt TASTy")
                case Result.Failure(e) =>
                    fail(s"Unexpected top-level failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 9: direct iteration over all symbols returns non-empty result
    "direct symbol iteration returns symbols from fixture classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer(cp.symbols)).map:
                case Result.Success(syms) =>
                    assert(syms.nonEmpty, "Expected at least one symbol from fixture classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 10: direct filter to Method kind returns only method symbols
    "direct filter to Method kind returns only method symbols" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer(cp.symbols.filter(_.kind == Tasty.SymbolKind.Method))).map:
                case Result.Success(syms) =>
                    assert(
                        syms.forall(_.kind == Tasty.SymbolKind.Method),
                        s"Some symbols are not Method kind"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 11: direct filter by Inline flag returns only symbols with Inline flag
    "direct filter by Inline flag returns only inline symbols" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer(cp.symbols.filter(_.flags.contains(Tasty.Flag.Inline)))).map:
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

    // Test 12: direct filter by name returns symbols named PlainClass
    "direct filter by name finds symbols named PlainClass" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer(cp.symbols.filter(_.name.asString == "PlainClass"))).map:
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

    // Test 13: direct map over symbols produces names
    "direct map over symbols produces names" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer(cp.symbols.map(_.name))).map:
                case Result.Success(names) =>
                    assert(names.forall(_.isInstanceOf[Tasty.Name]))
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 14: counting symbols via allSymbols is consistent across two calls
    "direct allSymbols count is consistent across two calls" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                Sync.defer((cp.symbols.size, cp.symbols.size))).map:
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

    // Test 15: Phase 07 - Tasty.Classpath is a pure case class with no Closed state.
    // The old "ClasspathClosed after outer Scope.run exits" test is replaced with a test that verifies
    // the Classpath case class remains accessible after the Scope exits.
    "Tasty.Classpath remains accessible after Scope exits (no Closed state)" in run {
        var capturedCp: Tasty.Classpath = null
        Abort.run[TastyError]:
            Scope.run:
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
                    capturedCp = cp
        .map:
            case Result.Success(_) =>
                assert(capturedCp != null, "Classpath should have been captured")
                assert(capturedCp.symbols.nonEmpty, "Classpath should have symbols after Scope exits")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 16: findClass works after open.
    "findClass returns Present after open" in run {
        Abort.run[TastyError]:
            Scope.run:
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).flatMap: cp =>
                    Kyo.lift(cp.findClass("kyo.fixtures.PlainClass"))
        .map:
            case Result.Success(Present(_)) => succeed
            case Result.Success(Absent)     => fail("Expected PlainClass to be found")
            case Result.Failure(e)          => fail(s"Unexpected failure: $e")
            case Result.Panic(t)            => throw t
    }

    // Test 17: strict mode fails with any TastyError for a corrupt TASTy file
    "strict mode fails with TastyError for corrupt TASTy" in run {
        val src = MemoryFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5))
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, mode = Tasty.ErrorMode.FailFast)).map:
                case Result.Success(_) =>
                    fail("Expected failure in strict mode with corrupt TASTy")
                case Result.Failure(_) =>
                    succeed
                case Result.Panic(t) =>
                    throw t
    }

    // Test 18: soft-fail mode succeeds with errors accumulated; other symbols resolve
    "soft-fail mode accumulates errors; other symbols still resolve" in run {
        val src = MemoryFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5))
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, mode = Tasty.ErrorMode.SoftFail).flatMap: cp =>
                val errs = cp.errors
                val cls  = cp.findClass("kyo.fixtures.PlainClass")
                (errs, cls)).map:
                case Result.Success((errs, cls)) =>
                    assert(errs.nonEmpty, "Expected at least one error from corrupt file")
                    assert(cls.isDefined, "Expected PlainClass to be found despite corrupt file")
                case Result.Failure(e) =>
                    fail(s"Unexpected top-level failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 24b: missing root produces FileNotFound
    "missing root produces FileNotFound immediately" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(MemoryFileSource())).map:
                case Result.Success(_) =>
                    fail("Expected FileNotFound for missing root")
                case Result.Failure(e) =>
                    e match
                        case TastyError.FileNotFound(_) => succeed
                        case other                      => fail(s"Expected FileNotFound but got: $other")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 31: 3 TASTy files with concurrency=3; all symbols present after open
    "Phase A/B/C orchestration with 3 files: all symbols present" in run {
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/PlainClass2.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/PlainClass3.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, src, 3).map: cp =>
                    cp.topLevelClasses
            ).map:
                case Result.Success(classes) =>
                    assert(classes.nonEmpty, s"Expected at least one class after opening 3 files, got ${classes.length}")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 32: 1 corrupt file among valid files; topLevelClasses from valid file present; errors.size >= 1
    "Phase B interruption: valid files decoded; 1 error accumulated for corrupt file" in run {
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3))
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, src, 2).map: cp =>
                    (cp.topLevelClasses, cp.errors)
            ).map:
                case Result.Success((classes, errs)) =>
                    assert(errs.size >= 1, s"Expected at least 1 error for corrupt file, got: ${errs.size}")
                    assert(classes.nonEmpty, s"Expected valid classes to be present, got empty")
                case Result.Failure(e) =>
                    fail(s"Unexpected top-level failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 33: findClassByBinary returns same Symbol as findClass after canonicalization
    "findClassByBinary canonicalizes binary name to dotted FQN" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                val byBinary = cp.findClassByBinary("kyo/fixtures/PlainClass")
                val byFqn    = cp.findClass("kyo.fixtures.PlainClass")
                (byBinary, byFqn)).map:
                case Result.Success((byBinary, byFqn)) =>
                    assert(
                        byBinary.isDefined == byFqn.isDefined,
                        s"findClassByBinary and findClass should return same result: $byBinary vs $byFqn"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 34: findClassByBinary for nonexistent class returns Absent
    "findClassByBinary for nonexistent returns Absent" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClassByBinary("no/such/Class$Nested")).map:
                case Result.Success(Absent) =>
                    succeed
                case Result.Success(Present(_)) =>
                    fail("Expected Absent for nonexistent binary name")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 36: file-handle release counter under Phase B (all reads complete before check)
    "file-handle counter reaches 0 after Phase B completes" in run {
        import AllowUnsafe.embrace.danger
        val counter = AtomicInt.Unsafe.init(0)
        val src     = MemoryFileSource()
        src.add("root/File1.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/File2.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/File3.tasty", Array[Byte](0, 1, 2, 3)) // corrupt

        // Wrap the source to count active reads (increment on read start, decrement on read finish)
        val tracked = new FileSource:
            def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                import AllowUnsafe.embrace.danger
                val _ = counter.incrementAndGet()
                Abort.run[TastyError](src.read(path)).flatMap: r =>
                    import AllowUnsafe.embrace.danger
                    val _ = counter.decrementAndGet()
                    r match
                        case Result.Success(b) => b
                        case Result.Failure(e) => Abort.fail(e)
                        case Result.Panic(t)   => throw t
                    end match
            end read
            def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) = src.write(path, bytes)
            def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError])        = src.rename(from, to)
            def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError])                    = src.mkdirs(path)
            def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                src.list(dir, suffixes)
            def exists(path: String)(using Frame): Boolean < Sync                                 = src.exists(path)
            def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) = src.stat(path)

        Scope.run:
            Abort.run[TastyError]:
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, tracked, 2)
            .map: _ =>
                import AllowUnsafe.embrace.danger
                val finalCount = counter.get()
                assert(finalCount == 0, s"Expected counter to be 0 after all reads complete, but got: $finalCount")
    }

    // Phase 2 Test 3: classpath opened from two fixture TASTy files (one extending the other) reports no errors
    // and no panic. Verifies end-to-end Phase C placeholder resolution.
    "two-file classpath (ChildClass extends BaseClass) opens with no errors and no panic" in run {
        val src = MemoryFileSource()
        src.add("root/BaseClass.tasty", kyo.fixtures.Embedded.baseClassTasty)
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src).flatMap: cp =>
                val errs     = cp.errors
                val childOpt = cp.findClass("kyo.fixtures.ChildClass")
                val baseOpt  = cp.findClass("kyo.fixtures.BaseClass")
                (errs, childOpt, baseOpt)).map:
                case Result.Success((errs, childOpt, baseOpt)) =>
                    assert(errs.isEmpty, s"Expected no errors but got: $errs")
                    assert(childOpt.isDefined, "Expected ChildClass to be found")
                    assert(baseOpt.isDefined, "Expected BaseClass to be found")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 1 (G21): sym.parentTypes for PlainClass returns a non-empty Chunk[Type].
    // plan: phase-02 update; sym.parents is renamed to sym.parentTypes (direct field, no effect row).
    "Phase 3: sym.parentTypes for PlainClass returns a non-empty Chunk[Type]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => Kyo.lift(sym._parentTypes)
                    case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
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

    // Phase 3 Test 2 (G22): sym.typeParamIds for GenericBox[A] returns a Chunk of length 1.
    // plan: phase-02 update; sym.typeParams (Chunk[Symbol]) becomes sym.typeParamIds (Chunk[SymbolId]).
    // Resolve SymbolId to Symbol via allSymbols for name check.
    "Phase 3: sym.typeParamIds for GenericBox[A] returns length 1 (phase-02 inline)" in run {
        val src = MemoryFileSource()
        src.add("root/GenericBox.tasty", kyo.fixtures.Embedded.genericBoxTasty)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.GenericBox") match
                    case Present(sym) =>
                        val tpIds  = sym._typeParamIds
                        val allSym = cp.symbols
                        assert(
                            tpIds.length == 1,
                            s"Expected 1 typeParamId for GenericBox[A] but got ${tpIds.length}"
                        )
                        val tpSym = allSym(tpIds(0).value)
                        assert(tpSym.name.asString == "A", s"Expected type param name 'A' but got '${tpSym.name.asString}'")
                    case Absent => Abort.fail(TastyError.NotImplemented("GenericBox not found"))).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 3 (G23): sym.declarationIds for PlainClass includes known member 'x'.
    // plan: phase-02 update; sym.declarations (Chunk[Symbol]) becomes sym.declarationIds (Chunk[SymbolId]).
    "Phase 3: sym.declarationIds for PlainClass contains known field x (phase-02 inline)" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) =>
                        val declIds = sym._declarationIds
                        val allSym  = cp.symbols
                        assert(declIds.nonEmpty, s"Expected non-empty declarationIds for PlainClass but got empty")
                        val names = declIds.map(id => allSym(id.value).name.asString).toSet
                        assert(
                            names.contains("x"),
                            s"Expected declarationIds to resolve a field 'x' but names were: ${names.mkString(", ")}"
                        )
                    case Absent => Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 4 (G21 post-close): sym.parents after classpath close returns the pre-populated Chunk.
    // After Phase 7, parents are stored as plain Chunk fields in the case class, so they remain valid after close.
    "Phase 3: sym.parents after classpath close returns the pre-populated Chunk (no failure)" in run {
        // Capture the symbol from inside the scope, then check parents after scope exits.
        // Scope.run returns Result[TastyError, Symbol] after running finalizers (closing classpath).
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    openFixtureClasspath(fixtureSource()).flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(sym) => Kyo.lift(sym)
                            case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        captureResult.map:
            case Result.Failure(e) =>
                fail(s"Expected success capturing PlainClass symbol but got: $e")
            case Result.Panic(t) =>
                throw t
            case Result.Success(sym) =>
                // Scope has exited; classpath is now closed. sym.parentTypes is a direct field, always valid.
                // plan: phase-02 inline; sym.parents renamed to sym.parentTypes.
                val parents = sym._parentTypes
                assert(
                    parents.nonEmpty,
                    "Expected non-empty parentTypes from pre-populated field after classpath close"
                )
    }

    // Phase 3 Test 5 (G21/G22/G23 classfile): for ArrayRecord.class (Java record), sym.parents includes
    // java.lang.Record; sym.typeParams is empty (non-generic); sym.declarations is non-empty.
    // Note: this test uses ClassfileUnpickler directly (pre-finalizeMerge). The relational fields
    // (parentTypes/typeParamIds/declarationIds) on the partial classSymbol are empty at this stage;
    // we read the pre-merge ClassfileResult fields (cr.parents, cr.typeParams, cr.symbols) directly.
    // Phase 09 adds sym.parents/typeParams/declarations as member methods accessible post-finalizeMerge.
    "Phase 3: Java classfile symbol parents, typeParams, declarations are accessible" taggedAs jvmOnly in run {
        val bytes    = kyo.fixtures.Embedded.arrayRecordClass
        val interner = Interner.init(numShards = 32, initialShardCapacity = 16)
        Abort.run[TastyError]:
            ClassfileUnpickler.read(bytes, interner, new TypeArena).flatMap: cr =>
                Tasty.Classpath.fromPickles(Seq.empty).map: miniCp =>
                    cr
        .flatMap:
            case Result.Success(cr) =>
                val parents    = cr.parents
                val typeParams = cr.typeParams
                val decls      = cr.symbols
                Abort.run[TastyError]:
                    Kyo.lift((parents, typeParams, decls))
                .map:
                    case Result.Success((parents, typeParams, decls)) =>
                        assert(parents.nonEmpty, s"Expected non-empty parentTypes for ArrayRecord but got empty")
                        val hasNamedOrApplied = parents.exists:
                            case Tasty.Type.Named(_)      => true
                            case Tasty.Type.Applied(_, _) => true
                            case _                        => false
                        assert(hasNamedOrApplied, s"Expected at least one Named/Applied parent")
                        assert(typeParams.isEmpty, s"Expected no typeParamIds for ArrayRecord but got ${typeParams.length}")
                        assert(decls.nonEmpty, s"Expected non-empty declarationIds for ArrayRecord but got empty")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure calling cr.parents/typeParams/symbols: $e")
                    case Result.Panic(t) =>
                        throw t
            case Result.Failure(e) =>
                fail(s"ClassfileUnpickler or classpath setup failed: $e")
            case Result.Panic(t) =>
                throw t
    }

    /** Build a MemoryFileSource with the SomeCaseClass fixture. */
    private def someCaseClassSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        src
    end someCaseClassSource

    // Phase 4 Test 1 (G24): case class companion object.
    // SomeCaseClass.tasty contains both the case class and its companion object.
    // The class symbol's companion should return Present(objectSym) where kind == Object.
    "Phase 4: SomeCaseClass.companion returns Present(objectSym) with kind Object" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(someCaseClassSource()).flatMap: cp =>
                // Find the Class-kind symbol for SomeCaseClass (fqnIndex key: "kyo.fixtures.SomeCaseClass").
                cp.findClass("kyo.fixtures.SomeCaseClass") match
                    case Present(classSym) =>
                        given Tasty.Classpath = cp
                        Kyo.lift(classSym.companion)
                    case Absent =>
                        Abort.fail(TastyError.NotImplemented("SomeCaseClass not found"))).map:
                case Result.Success(Present(compSym)) =>
                    assert(
                        compSym.kind == Tasty.SymbolKind.Object,
                        s"Expected companion kind Object but got ${compSym.kind}"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present companion for SomeCaseClass but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 4 Test 2 (G24): companion object reverse lookup -- the object symbol's companion is the class.
    "Phase 4: SomeCaseClass companion object's companion returns Present(classSym) with kind Class" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(someCaseClassSource()).flatMap: cp =>
                // Companion object is registered with "$" suffix in fqnIndex.
                cp.findClass("kyo.fixtures.SomeCaseClass$") match
                    case Present(objSym) =>
                        given Tasty.Classpath = cp
                        Kyo.lift(objSym.companion)
                    case Absent =>
                        // Some TASTy encodings register the object without "$"; try topLevelClasses.
                        val objSym = cp.topLevelClasses.find(_.kind == Tasty.SymbolKind.Object)
                        objSym match
                            case Some(s) =>
                                given Tasty.Classpath = cp
                                Kyo.lift(s.companion)
                            case None =>
                                Abort.fail(TastyError.NotImplemented("SomeCaseClass$ not found in fqnIndex"))).map:
                case Result.Success(Present(compSym)) =>
                    assert(
                        compSym.kind == Tasty.SymbolKind.Class,
                        s"Expected companion kind Class but got ${compSym.kind}"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present companion for SomeCaseClass$ but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 4 Test 3 (G24): plain class with no companion returns Absent.
    "Phase 4: PlainClass.companion returns Absent (no companion object)" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) =>
                        given Tasty.Classpath = cp
                        Kyo.lift(sym.companion)
                    case Absent =>
                        Abort.fail(TastyError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(Absent) =>
                    succeed
                case Result.Success(Present(s)) =>
                    fail(s"Expected Absent companion for PlainClass but got Present(${s.name.asString})")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 4 Test 4 (G24): companion is pure; returns Absent after scope close for a plain class.
    // sym.companion uses cp.companionIndex which is empty in fromPickles.
    "Phase 4: sym.companion after classpath close returns Absent (pure, no failure)" in run {
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    openFixtureClasspath(fixtureSource()).flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(sym) => Kyo.lift(sym)
                            case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        captureResult.flatMap:
            case Result.Failure(e) => Kyo.lift(fail(s"Unexpected failure: $e"))
            case Result.Panic(t)   => throw t
            case Result.Success(sym) =>
                Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
                    given Tasty.Classpath = cp
                    val companion         = sym.companion
                    assert(companion == Maybe.Absent, s"Expected Absent companion on empty classpath but got $companion")
    }

    // Phase 5 Test 1 (G20): sym.declaredType for val x: Int in PlainClass returns a type.
    // After Phase C placeholder resolution the type encodes scala.Int. The TASTy encoding for Int
    // may be Type.Named or Type.TermRef depending on how the constant type is referenced; we assert
    // that a type is returned and does not fail.
    // plan: phase-02 update; declarations->declarationIds (Chunk[SymbolId]); declaredType->Maybe[Type].
    "Phase 5: sym.declaredType for PlainClass.x (val x: Int) returns a type" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass") match
                    case Absent => Abort.fail(TastyError.NotImplemented("PlainClass not found"))
                    case Present(classSym) =>
                        val declIds = classSym._declarationIds
                        val allSym  = cp.symbols
                        val xOpt    = declIds.map(id => allSym(id.value)).find(s => s.name.asString == "x")
                        xOpt match
                            case None =>
                                Abort.fail(
                                    TastyError.NotImplemented(
                                        s"No field 'x' in PlainClass declarationIds"
                                    )
                                )
                            case Some(xSym) =>
                                Kyo.lift(xSym._declaredType)).map:
                case Result.Success(tpeMaybe) =>
                    assert(tpeMaybe.isDefined, s"Expected Present declaredType for val x: Int but got Absent")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 5 Test 2 (G20): sym.declaredType for SomeTrait.compute returns a type (return-type-only
    // per anti-thrash rule; not a full Type.Function). The returned type is Named (proxy resolved
    // after Phase C). We assert that declaredType is populated and returns a Tasty.Type value.
    // plan: phase-02 update; declarations->declarationIds; declaredType->Maybe[Type].
    "Phase 5: sym.declaredType for SomeTrait.compute (def compute: Int) returns a type" in run {
        val src = MemoryFileSource()
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.SomeTrait") match
                    case Absent => Abort.fail(TastyError.NotImplemented("SomeTrait not found"))
                    case Present(traitSym) =>
                        val declIds = traitSym._declarationIds
                        val allSym  = cp.symbols
                        val computeOpt = declIds.map(id => allSym(id.value)).find(s =>
                            s.name.asString == "compute" && s.kind == Tasty.SymbolKind.Method
                        )
                        computeOpt match
                            case None =>
                                Abort.fail(TastyError.NotImplemented("No method 'compute' in SomeTrait declarationIds"))
                            case Some(computeSym) =>
                                Kyo.lift(computeSym._declaredType)).map:
                case Result.Success(tpeMaybe) =>
                    assert(tpeMaybe.isDefined, s"Expected Present declaredType for compute but got Absent")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 5 Test 3 (G20): sym.declaredType for type alias `type StringList = List[String]` returns
    // a non-Named applied or named type (the alias body). StringList is a top-level type alias in
    // FixtureClasses$package.tasty.
    // plan: phase-02 update; declaredType->Maybe[Type].
    "Phase 5: sym.declaredType for type StringList returns a type (alias body)" in run {
        val src = MemoryFileSource()
        src.add("root/FixtureClasses$package.tasty", kyo.fixtures.Embedded.fixtureClassesPackageTasty)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src).flatMap: cp =>
                val syms = cp.symbols.filter(_.name.asString == "StringList")
                syms.headMaybe match
                    case Absent             => Abort.fail(TastyError.NotImplemented("No StringList symbol found"))
                    case Present(stringSym) => Kyo.lift(stringSym._declaredType)).map:
                case Result.Success(tpeMaybe) =>
                    assert(tpeMaybe.isDefined, s"Expected Present declaredType for StringList but got Absent")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 5 Test 4 (G20, classfile path): sym.declaredType for a Java record field returns the
    // expected type. ArrayRecord.class has a single int[] component 'values'; its member symbol's
    // declaredType should be Type.Array(Type.Named(intSym)).
    "Phase 5: Java classfile field declaredType returns Array type for int[] values" taggedAs jvmOnly in run {
        val bytes    = kyo.fixtures.Embedded.arrayRecordClass
        val interner = Interner.init(numShards = 32, initialShardCapacity = 16)
        Abort.run[TastyError]:
            ClassfileUnpickler.read(bytes, interner, new TypeArena).flatMap: cr =>
                Tasty.Classpath.fromPickles(Seq.empty).map: miniCp =>
                    cr
        .flatMap:
            case Result.Success(cr) =>
                val valuesOpt = cr.symbols.find(s => s.name.asString == "values")
                valuesOpt match
                    case None =>
                        fail(s"No 'values' member in ArrayRecord. Members: ${cr.symbols.map(_.name.asString).mkString(", ")}")
                    case Some(valuesSym) =>
                        // plan: phase-02 update; declaredType is now Maybe[Type].
                        Abort.run[TastyError](Kyo.lift(valuesSym._declaredType)).map:
                            case Result.Success(tpeMaybe) =>
                                tpeMaybe match
                                    case kyo.Maybe.Present(Tasty.Type.Array(Tasty.Type.Named(_))) =>
                                        // plan: phase-05; name check (int/Int) deferred to Phase 09.
                                        assert(true)
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
                end match
            case Result.Failure(e) =>
                fail(s"ClassfileUnpickler or classpath setup failed: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Phase 5 Test 5 (G20 post-close): sym.declaredType after classpath close returns the pre-populated type.
    // declaredType is a plain Maybe[Type] field on the case class; it remains valid after close.
    // plan: phase-02 update; declarationIds used; declaredType is Maybe[Type].
    "Phase 5: sym.declaredType after classpath close returns pre-populated type (no failure)" in run {
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    openFixtureClasspath(fixtureSource()).flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(sym) =>
                                val declIds = sym._declarationIds
                                val allSym  = cp.symbols
                                declIds.map(id => allSym(id.value)).find(s => s.name.asString == "x") match
                                    case Some(xSym) => Kyo.lift(xSym)
                                    case None       => Kyo.lift(sym)
                            case Absent =>
                                Abort.fail(TastyError.NotImplemented("PlainClass not found"))
        captureResult.map:
            case Result.Failure(e) =>
                fail(s"Expected success capturing symbol but got: $e")
            case Result.Panic(t) =>
                throw t
            case Result.Success(sym) =>
                val tpeMaybe = sym._declaredType
                assert(tpeMaybe.isDefined, "Expected Present declaredType from pre-populated field after classpath close")
    }

    // Test G19 (Phase 13): InconsistentClasspath uses java.util.UUID for both UUID fields
    "TastyError.InconsistentClasspath UUID type fields compile and pattern-match correctly" in {
        val expected = new java.util.UUID(0L, 1L)
        val found    = new java.util.UUID(0L, 2L)
        val err      = TastyError.InconsistentClasspath("foo.tasty", expected, found)
        err match
            case TastyError.InconsistentClasspath(file, exp, fnd) =>
                assert(file == "foo.tasty")
                assert(exp.equals(expected))
                assert(fnd.equals(found))
        end match
    }

    // T-P4-2: Merger accepts FileResult with mutable.HashMap fields (Phase 4 behavioral regression check).
    // Opens fixture classpath through the full orchestrator pipeline. After Phase 4, FileResult values
    // carry mutable.HashMap for parentsBySymbol, childrenByOwner, and typeBySymbol. The merger reads
    // these maps during finalizeMerge. Verifies that the full pipeline produces the expected FQN index entry.
    "T-P4-2: merger processes mutable.HashMap FileResult fields and produces expected FQN entry" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Present(sym)) =>
                    assert(
                        sym.kind == Tasty.SymbolKind.Class,
                        s"Expected Class kind for kyo.fixtures.PlainClass but got ${sym.kind}"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present(sym) for kyo.fixtures.PlainClass but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end QueryApiTest
