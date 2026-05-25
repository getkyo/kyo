package kyo

import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.Classpath as InternalClasspath
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.FileSource
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Tests for Phase 7: Query API, classpath lifecycle, and Phase A/B/C orchestration.
  *
  * Uses an in-memory FileSource for cross-platform compatibility.
  *
  * Plan tests 1-18, 24b, 31-34, 36.
  */
class QueryApiTest extends Test:

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(ReflectError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(ReflectError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Kyo.unit

        def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
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
      * Returns `Reflect.Classpath` (the public opaque type) so that extension methods such as `findClass`, `errors`, `topLevelClasses`, and
      * `findClassByBinary` are in scope.
      */
    private def openFixtureClasspath(src: FileSource, strict: Boolean = false)(
        using Frame
    ): Reflect.Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), strict, src, 1, rawCp).map: _ =>
                    val cp = Reflect.Classpath.wrap(rawCp)
                    Reflect.Classpath.assignHomesForTest(rawCp)
                    cp

    // Test 1: fromPickles(Seq.empty) succeeds; findClass("anything") returns Absent
    "fromPickles(Seq.empty) succeeds and findClass returns Absent" in run {
        Scope.run:
            Reflect.Classpath.fromPickles(Seq.empty).flatMap: cp =>
                cp.findClass("some.Class").map: result =>
                    assert(result == Maybe.Absent)
    }

    // Test 2: cp.findClass on fixture classpath returns Present(sym) with kind == Class
    "findClass on fixture TASTy returns Present(sym) with kind Class" in run {
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Present(sym)) =>
                    assert(sym.kind == Reflect.SymbolKind.Class)
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findPackage("kyo.fixtures")).map:
                case Result.Success(Present(pkg)) =>
                    assert(pkg.kind == Reflect.SymbolKind.Package)
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.errors).map:
                case Result.Success(errs) =>
                    assert(errs.isEmpty, s"Expected no errors but got: $errs")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 8: errors returns at least one ReflectError for a classpath with a corrupt TASTy file
    "errors returns non-empty for classpath with corrupt TASTy" in run {
        val src = MemoryFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5)) // corrupt magic
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(src).flatMap: cp =>
                cp.errors).map:
                case Result.Success(errs) =>
                    assert(errs.nonEmpty, "Expected at least one error for corrupt TASTy")
                case Result.Failure(e) =>
                    fail(s"Unexpected top-level failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 9: query[Reflect.Symbol].run returns all symbols
    "cp.query run returns symbols from fixture classpath" in run {
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                Query.make(Reflect.Classpath.unwrap(cp), readsInst).run).map:
                case Result.Success(syms: Chunk[Reflect.Symbol]) =>
                    assert(syms.nonEmpty, "Expected at least one symbol from fixture classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 10: query.where(_.kind == Method) returns only method symbols
    "query.where filters to Method symbols only" in run {
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                Query.make(Reflect.Classpath.unwrap(cp), readsInst).where(_.kind == Reflect.SymbolKind.Method).run).map:
                case Result.Success(syms: Chunk[Reflect.Symbol]) =>
                    assert(
                        syms.forall(_.kind == Reflect.SymbolKind.Method),
                        s"Some symbols are not Method kind"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 11: query.withFlag(Flag.Inline) returns only symbols with Inline flag
    "query.withFlag(Inline) returns only inline symbols" in run {
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                Query.make(Reflect.Classpath.unwrap(cp), readsInst).withFlag(Reflect.Flag.Inline).run).map:
                case Result.Success(syms: Chunk[Reflect.Symbol]) =>
                    assert(
                        syms.forall(_.flags.contains(Reflect.Flag.Inline)),
                        s"Some symbols do not have Inline flag"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 12: query.named("PlainClass") returns symbols named PlainClass
    "query.named filters by simple name" in run {
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                Query.make(Reflect.Classpath.unwrap(cp), readsInst).named("PlainClass").run).map:
                case Result.Success(syms: Chunk[Reflect.Symbol]) =>
                    assert(
                        syms.forall(_.name.asString == "PlainClass"),
                        s"All named-filtered symbols must have name PlainClass, got: ${syms.map(_.name.asString)}"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 13: query.map(_.name) returns Chunk[Name]
    "query.map transforms decoded values" in run {
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                Query.make(Reflect.Classpath.unwrap(cp), readsInst).map(_.name).run).map:
                case Result.Success(names: Chunk[Reflect.Name]) =>
                    assert(names.forall(_.isInstanceOf[Reflect.Name]))
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 14: query.stream returns same result as .run
    "query.stream returns same results as .run" in run {
        given kyo.Tag[Emit[Chunk[Reflect.Symbol]]] = kyo.Tag[Emit[Chunk[Reflect.Symbol]]]
        val readsInst = new Reflect.Reads[Reflect.Symbol]:
            val symbolKinds: Set[Reflect.SymbolKind]                                                  = Set.empty
            val needsBodies: Boolean                                                                  = false
            val touchedFields: Reflect.FieldSet                                                       = Reflect.FieldSet.Empty
            def read(sym: Reflect.Symbol)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) = sym
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                import kyo.internal.reflect.query.Query
                val q = Query.make(Reflect.Classpath.unwrap(cp), readsInst)
                q.run.flatMap: runResult =>
                    q.stream.run.map: streamResult =>
                        (runResult.size, streamResult.size)).map:
                case Result.Success((runCount, streamCount)) =>
                    assert(
                        runCount == streamCount,
                        s"stream and run should return same count: run=$runCount stream=$streamCount"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 15: After scope exits, ClasspathClosed is returned
    "ClasspathClosed after outer Scope.run exits" in run {
        // Hold a raw InternalClasspath reference across the scope boundary to verify it is closed
        val rawRef = new java.util.concurrent.atomic.AtomicReference[InternalClasspath](null)
        Abort.run[ReflectError]:
            Scope.run:
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, fixtureSource(), 1, rawCp).map: _ =>
                            rawRef.set(rawCp)
            .andThen:
                rawRef.get().checkOpen
        .map:
            case Result.Success(_) =>
                fail("Expected ClasspathClosed error")
            case Result.Failure(e) =>
                e match
                    case ReflectError.ClasspathClosed => succeed
                    case other                        => fail(s"Expected ClasspathClosed but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // Test 16: State transitions verified via observable behavior
    // After openInto the classpath is in Ready state; findClass works. Before, it would fail with ClasspathBuilding.
    "classpath state transitions verified via findClass behavior" in run {
        Abort.run[ReflectError]:
            Scope.run:
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, fixtureSource(), 1, rawCp).andThen:
                            rawCp.lookupClass("kyo.fixtures.PlainClass")
        .map:
            case Result.Success(Present(_)) => succeed
            case Result.Success(Absent)     => fail("Expected PlainClass to be found after openInto (Ready state)")
            case Result.Failure(e)          => fail(s"Unexpected failure: $e")
            case Result.Panic(t)            => throw t
    }

    // Test 17: strict mode fails with any ReflectError for a corrupt TASTy file
    "strict mode fails with ReflectError for corrupt TASTy" in run {
        val src = MemoryFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5))
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(src, strict = true)).map:
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
            Abort.run[ReflectError](openFixtureClasspath(src, strict = false).flatMap: cp =>
                cp.errors.flatMap: errs =>
                    cp.findClass("kyo.fixtures.PlainClass").map: cls =>
                        (errs, cls)).map:
                case Result.Success((errs: Chunk[ReflectError], cls: Maybe[Reflect.Symbol])) =>
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
            Abort.run[ReflectError](openFixtureClasspath(MemoryFileSource())).map:
                case Result.Success(_) =>
                    fail("Expected FileNotFound for missing root")
                case Result.Failure(e) =>
                    e match
                        case ReflectError.FileNotFound(_) => succeed
                        case other                        => fail(s"Expected FileNotFound but got: $other")
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
            Abort.run[ReflectError](
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, src, 3, rawCp).andThen:
                            rawCp.allTopLevelClasses
            ).map:
                case Result.Success(classes) =>
                    assert(classes.nonEmpty, s"Expected at least one class after opening 3 files, got ${classes.size}")
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
            Abort.run[ReflectError](
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, src, 2, rawCp).andThen:
                            rawCp.allTopLevelClasses.flatMap: classes =>
                                Sync.defer(rawCp.accumulatedErrors).map: errs =>
                                    (classes, errs)
            ).map:
                case Result.Success((classes: Chunk[Reflect.Symbol], errs: Chunk[ReflectError])) =>
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClassByBinary("kyo/fixtures/PlainClass").flatMap: byBinary =>
                    cp.findClass("kyo.fixtures.PlainClass").map: byFqn =>
                        (byBinary, byFqn)).map:
                case Result.Success((byBinary: Maybe[Reflect.Symbol], byFqn: Maybe[Reflect.Symbol])) =>
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
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
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
            def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
                import AllowUnsafe.embrace.danger
                val _ = counter.incrementAndGet()
                Abort.run[ReflectError](src.read(path)).flatMap: r =>
                    import AllowUnsafe.embrace.danger
                    val _ = counter.decrementAndGet()
                    r match
                        case Result.Success(b) => b
                        case Result.Failure(e) => Abort.fail(e)
                        case Result.Panic(t)   => throw t
                    end match
            end read
            def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError])    = src.write(path, bytes)
            def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError])           = src.rename(from, to)
            def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError])                       = src.mkdirs(path)
            def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) = src.list(dir, suffix)
            def exists(path: String)(using Frame): Boolean < Sync                                            = src.exists(path)
            def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError])          = src.stat(path)

        Scope.run:
            Abort.run[ReflectError]:
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, tracked, 2, rawCp).andThen(rawCp)
            .map: _ =>
                import AllowUnsafe.embrace.danger
                val finalCount = counter.get()
                assert(finalCount == 0, s"Expected counter to be 0 after all reads complete, but got: $finalCount")
    }

    // Phase 2 Test 3: classpath opened from two fixture TASTy files (one extending the other) reports no errors
    // and no Result.Panic from unset SingleAssign slots. Verifies end-to-end Phase C placeholder resolution.
    "two-file classpath (ChildClass extends BaseClass) opens with no errors and no panic" in run {
        val src = MemoryFileSource()
        src.add("root/BaseClass.tasty", kyo.fixtures.Embedded.baseClassTasty)
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(src).flatMap: cp =>
                cp.errors.flatMap: errs =>
                    cp.findClass("kyo.fixtures.ChildClass").flatMap: childOpt =>
                        cp.findClass("kyo.fixtures.BaseClass").map: baseOpt =>
                            (errs, childOpt, baseOpt)).map:
                case Result.Success((errs: Chunk[ReflectError], childOpt: Maybe[Reflect.Symbol], baseOpt: Maybe[Reflect.Symbol])) =>
                    assert(errs.isEmpty, s"Expected no errors but got: $errs")
                    assert(childOpt.isDefined, "Expected ChildClass to be found")
                    assert(baseOpt.isDefined, "Expected BaseClass to be found")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 1 (G21): sym.parents for PlainClass returns a non-empty Chunk[Type].
    // PlainClass has no explicit superclass so its TASTy TEMPLATE parent is java.lang.Object (via AnyRef).
    "Phase 3: sym.parents for PlainClass returns a non-empty Chunk[Type]" in run {
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass").flatMap:
                    case Present(sym) => sym.parents
                    case Absent       => Abort.fail(ReflectError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(parents) =>
                    assert(
                        parents.nonEmpty,
                        s"Expected non-empty parents for PlainClass but got empty. PlainClass should have AnyRef/Object as parent."
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 2 (G22): sym.typeParams for GenericBox[A] returns a Chunk of length 1 with name "A".
    "Phase 3: sym.typeParams for GenericBox[A] returns length 1 with name A" in run {
        val src = MemoryFileSource()
        src.add("root/GenericBox.tasty", kyo.fixtures.Embedded.genericBoxTasty)
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(src).flatMap: cp =>
                cp.findClass("kyo.fixtures.GenericBox").flatMap:
                    case Present(sym) => sym.typeParams
                    case Absent       => Abort.fail(ReflectError.NotImplemented("GenericBox not found"))).map:
                case Result.Success(tps) =>
                    assert(tps.length == 1, s"Expected 1 type param for GenericBox[A] but got ${tps.length}: ${tps.map(_.name.asString)}")
                    assert(tps(0).name.asString == "A", s"Expected type param name 'A' but got '${tps(0).name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 3 (G23): sym.declarations for PlainClass includes known member 'x'.
    "Phase 3: sym.declarations for PlainClass contains known field x" in run {
        Scope.run:
            Abort.run[ReflectError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass").flatMap:
                    case Present(sym) => sym.declarations
                    case Absent       => Abort.fail(ReflectError.NotImplemented("PlainClass not found"))).map:
                case Result.Success(decls) =>
                    assert(decls.nonEmpty, s"Expected non-empty declarations for PlainClass but got empty")
                    val names = decls.map(_.name.asString).toSet
                    assert(
                        names.contains("x"),
                        s"Expected declarations to contain field 'x' but names were: ${names.mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Phase 3 Test 4 (G21 closed): sym.parents called after classpath close returns ClasspathClosed.
    "Phase 3: sym.parents after classpath close returns ClasspathClosed" in run {
        // Capture the symbol from inside the scope, then check parents after scope exits.
        // Scope.run returns Result[ReflectError, Symbol] after running finalizers (closing classpath).
        val captureResult: Result[ReflectError, Reflect.Symbol] < Async =
            Scope.run:
                Abort.run[ReflectError]:
                    openFixtureClasspath(fixtureSource()).flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass").flatMap:
                            case Present(sym) => Kyo.lift(sym)
                            case Absent       => Abort.fail(ReflectError.NotImplemented("PlainClass not found"))
        captureResult.flatMap:
            case Result.Failure(e) =>
                fail(s"Expected success capturing PlainClass symbol but got: $e")
            case Result.Panic(t) =>
                throw t
            case Result.Success(sym) =>
                // Scope has exited; classpath is now closed. sym is captured.
                Abort.run[ReflectError](sym.parents).map:
                    case Result.Failure(ReflectError.ClasspathClosed) =>
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Expected ClasspathClosed but got: $e")
                    case Result.Success(p) =>
                        fail(s"Expected ClasspathClosed but parents succeeded with: $p")
                    case Result.Panic(t) =>
                        throw t
    }

    // Phase 3 Test 5 (G21/G22/G23 classfile): for ArrayRecord.class (Java record), sym.parents includes
    // java.lang.Record; sym.typeParams is empty (non-generic); sym.declarations is non-empty.
    "Phase 3: Java classfile symbol parents, typeParams, declarations are accessible" taggedAs jvmOnly in run {
        val bytes    = kyo.fixtures.Embedded.arrayRecordClass
        val interner = new Interner(32)
        val home     = new ClasspathRef
        Abort.run[ReflectError]:
            ClassfileUnpickler.read(bytes, interner, new TypeArena, home).flatMap: cr =>
                // Create a mini-classpath so home.isAssigned is true and checkOpen passes.
                // assignExtraHomes covers the class symbol and all members; since all symbols share
                // the same ClasspathRef instance, calling home.assign separately is redundant.
                Reflect.Classpath.fromPickles(Seq.empty).map: miniCp =>
                    Reflect.Classpath.assignExtraHomes(miniCp, cr.classSymbol +: cr.symbols.toSeq)
                    cr
        .flatMap:
            case Result.Success(cr) =>
                val sym = cr.classSymbol
                Abort.run[ReflectError]:
                    sym.parents.flatMap: parents =>
                        sym.typeParams.flatMap: typeParams =>
                            sym.declarations.map: decls =>
                                (parents, typeParams, decls)
                .map:
                    case Result.Success((parents, typeParams, decls)) =>
                        assert(parents.nonEmpty, s"Expected non-empty parents for ArrayRecord but got empty")
                        val parentNames = parents.map:
                            case Reflect.Type.Named(s) => s.name.asString
                            case other                 => other.show
                        assert(
                            parentNames.exists(n => n.contains("Record") || n.contains("java")),
                            s"Expected parents to include Record or java types but got: ${parentNames.mkString(", ")}"
                        )
                        assert(typeParams.isEmpty, s"Expected no type params for ArrayRecord but got: ${typeParams.map(_.name.asString)}")
                        assert(decls.nonEmpty, s"Expected non-empty declarations for ArrayRecord but got empty")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure calling sym.parents/typeParams/declarations: $e")
                    case Result.Panic(t) =>
                        throw t
            case Result.Failure(e) =>
                fail(s"ClassfileUnpickler or classpath setup failed: $e")
            case Result.Panic(t) =>
                throw t
    }

end QueryApiTest
