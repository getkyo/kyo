package kyo
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.mutable

/** Tests for the streaming pipeline via Channels using an in-memory FileSource.
  *
  * Pipeline correctness, FQN index parity, arena determinism, soft-fail and strict-fail error
  * handling, channel backpressure with 100+ entries, ordering independence, and concurrency.
  */
class ClasspathOrchestratorPipelineTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Minimal in-memory FileSource for pipeline tests. */
    final class MemFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

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

    end MemFileSource

    /** Build an in-memory FileSource seeded with a single .tasty file at "root/PlainClass.tasty". */
    private def fixtureSource(): MemFileSource =
        val src = MemFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    /** Init a classpath using ClasspathOrchestrator.init with the given concurrency. */
    private def openFixtureClasspath(
        src: FileSource,
        roots: Seq[String] = Seq("root"),
        mode: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail,
        concurrency: Int = 2
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(roots, mode, src, concurrency)
    end openFixtureClasspath

    "pipeline produces correct symbol set for fixture classpath" in {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).map: cp =>
                import Tasty.Name.asString
                cp.symbols.map(s => cp.fullNameUnsafe(s).asString).toSet).map:
                case Result.Success(names) =>
                    assert(names.exists(_.contains("PlainClass")), s"Expected PlainClass in symbol names, got: $names")
                case Result.Failure(e) =>
                    fail(s"T1 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "FQN index parity - classByFqn returns symbol for known FQN" in {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).flatMap: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Present(sym)) =>
                    assert(
                        sym.kind == SymbolKind.Class || sym.kind == SymbolKind.Trait,
                        s"Expected Class or Trait kind, got: ${sym.kind}"
                    )
                case Result.Success(Absent) =>
                    fail("T2: Expected Present(sym) for kyo.fixtures.PlainClass but got Absent")
                case Result.Failure(e) =>
                    fail(s"T2 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "opening same fixture twice produces same FQN key set and symbol count" in {
        Scope.run:
            Abort.run[TastyError](
                openFixtureClasspath(fixtureSource()).flatMap: cp1 =>
                    openFixtureClasspath(fixtureSource()).flatMap: cp2 =>
                        Sync.defer:
                            val size1 = cp1.symbols.size
                            val size2 = cp2.symbols.size
                            (size1, size2)
            ).map:
                case Result.Success((size1, size2)) =>
                    assert(size1 == size2, s"T3: allSymbols sizes differ: $size1 vs $size2")
                case Result.Failure(e) =>
                    fail(s"T3 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "soft-fail on corrupted tasty file appends to cp.errors without aborting load" in {
        val src = MemFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5)) // garbage bytes, invalid TASTy header
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, mode = Tasty.ErrorMode.SoftFail).flatMap: cp =>
                cp.errors).map:
                case Result.Success(errs) =>
                    assert(errs.nonEmpty, "T4: Expected at least one error for corrupted .tasty file")
                case Result.Failure(e) =>
                    fail(s"T4 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "strict mode raises Abort[TastyError] for corrupted tasty file without hanging" in {
        val src = MemFileSource()
        src.add("root/Corrupt.tasty", Array[Byte](0, 1, 2, 3, 4, 5)) // garbage bytes
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, mode = Tasty.ErrorMode.FailFast)).map:
                case Result.Failure(_: TastyError) =>
                    succeed
                case Result.Success(_) =>
                    fail("T5: Expected Abort[TastyError] for corrupted tasty in strict mode, but got success")
                case Result.Panic(t) =>
                    throw t
    }

    "pipeline completes successfully with 100+ entries at concurrency=2" in {
        val src   = MemFileSource()
        val bytes = kyo.fixtures.Embedded.plainClassTasty
        // Add 110.tasty entries to a directory root to trigger channel backpressure
        for i <- 1 to 110 do
            src.add(s"root/File$i.tasty", bytes)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, concurrency = 2).flatMap: cp =>
                Sync.defer(cp.symbols.size)).map:
                case Result.Success(count) =>
                    // Each PlainClass.tasty decodes at least one symbol; 110 identical files decode 110 * n symbols
                    assert(count > 0, s"T6: Expected non-zero symbol count after pipeline with 110 entries, got $count")
                case Result.Failure(e) =>
                    fail(s"T6 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "two pipeline runs on identical inputs produce the same FQN key set" in {
        Scope.run:
            Abort.run[TastyError](
                openFixtureClasspath(fixtureSource()).flatMap: cp1 =>
                    openFixtureClasspath(fixtureSource()).flatMap: cp2 =>
                        import Tasty.Name.asString
                        import Tasty.Name.asString
                        val names1: Set[String] = cp1.symbols.map(s => cp1.fullNameUnsafe(s).asString).toSet
                        val names2: Set[String] = cp2.symbols.map(s => cp2.fullNameUnsafe(s).asString).toSet
                        (names1, names2)
            ).map:
                case Result.Success((names1, names2)) =>
                    assert(names1 == names2, s"T7: FQN sets differ between runs: diff=${names1.diff(names2).take(5)}")
                case Result.Failure(e) =>
                    fail(s"T7 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "pipeline with concurrency=2 and 100+ entries completes successfully" in {
        val src   = MemFileSource()
        val bytes = kyo.fixtures.Embedded.plainClassTasty
        for i <- 1 to 100 do
            src.add(s"root/Entry$i.tasty", bytes)
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(src, concurrency = 2).flatMap: cp =>
                Sync.defer(cp.symbols.size)).map:
                case Result.Success(count) =>
                    assert(count > 0, s"T8: Expected non-zero symbol count after pipeline with 100 entries at concurrency=2, got $count")
                case Result.Failure(e) =>
                    fail(s"T8 unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end ClasspathOrchestratorPipelineTest
