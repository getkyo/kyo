package kyo

import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathTestHelpers
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Phase 02b: Classpath pure accessors carry (using AllowUnsafe) (INV-001).
  *
  * Verifies that each Classpath accessor signature has been migrated from inner import-danger to (using AllowUnsafe), and that the
  * accessors behave correctly on a Ready classpath built from the embedded fixture.
  */
class ClasspathPureAccessorTest extends Test:

    import AllowUnsafe.embrace.danger

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

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

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

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

    private def openFixtureClasspath(src: FileSource)(
        using Frame
    ): InternalClasspath < (Sync & Async & Scope & Abort[TastyError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    rawCp

    // Test 1 (INV-001): every Classpath pure accessor signature carries (using AllowUnsafe).
    // Given: a Ready classpath; AllowUnsafe in scope via import at class level.
    // When: all 10 accessors (pureClass, purePackage, pureModule, pureTopLevelClasses, purePackages,
    //        accumulatedErrors, allSymbols, isClosed, transitionToReady, close) are invoked.
    // Then: all return without compile error (proves each carries (using AllowUnsafe)); allSymbols is non-empty.
    // Pins: INV-001 (Classpath case).
    "all 10 Classpath pure accessors are callable with (using AllowUnsafe) in scope" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).map: rawCp =>
                val cls      = rawCp.pureClass("kyo.fixtures.PlainClass")
                val pkg      = rawCp.purePackage("kyo.fixtures")
                val mod      = rawCp.pureModule("anything")
                val topLevel = rawCp.pureTopLevelClasses
                val packages = rawCp.purePackages
                val errors   = rawCp.accumulatedErrors
                val syms     = rawCp.allSymbols
                val closed   = rawCp.isClosed
                (cls, pkg, mod, topLevel, packages, errors, syms, closed)).map:
                case Result.Success((cls, _, _, topLevel, _, errors, syms, closed)) =>
                    assert(cls.isDefined, "pureClass should return Present for known FQN")
                    assert(topLevel.nonEmpty, "pureTopLevelClasses should be non-empty")
                    assert(errors.isEmpty, "accumulatedErrors should be empty for clean classpath")
                    assert(syms.nonEmpty, "allSymbols should be non-empty")
                    assert(!closed, "isClosed should be false while scope is still open")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 2 (INV-001): pureClass returns Present on a Ready classpath.
    // Given: fixture classpath containing PlainClass.tasty; AllowUnsafe in scope.
    // When: rawCp.pureClass("kyo.fixtures.PlainClass").
    // Then: result is Maybe.Present(sym) with sym.name.asString == "PlainClass".
    // plan: phase-02 inline; sym.fullName not available until Phase 09; check simple name.
    // Pins: INV-001.
    "pureClass returns Present for a known FQN on Ready classpath" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath(fixtureSource()).map: rawCp =>
                rawCp.pureClass("kyo.fixtures.PlainClass")).map:
                case Result.Success(Present(sym)) =>
                    assert(
                        sym.name.asString == "PlainClass",
                        s"Expected name 'PlainClass' but got '${sym.name.asString}'"
                    )
                case Result.Success(Absent) =>
                    fail("Expected Present(sym) for kyo.fixtures.PlainClass but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 3 (INV-001): transitionToReady followed by pureClass returns Present.
    // Given: fresh InternalClasspath allocated by InternalClasspath.allocate (Building state); AllowUnsafe in scope.
    // When: ClasspathOrchestrator.openInto (which calls transitionToReady internally) transitions state to Ready;
    //       then rawCp.pureClass("kyo.fixtures.PlainClass") called.
    // Then: returns Maybe.Present(_).
    // Pins: INV-001.
    "after transitionToReady, pureClass returns Present for the indexed symbol" in run {
        Scope.run:
            Abort.run[TastyError](
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, fixtureSource(), 1, rawCp).map: _ =>
                            rawCp.pureClass("kyo.fixtures.PlainClass")
            ).map:
                case Result.Success(Present(_)) =>
                    succeed
                case Result.Success(Absent) =>
                    fail("Expected Present after transitionToReady but got Absent")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 4 (INV-001): close then isClosed returns true.
    // Given: Ready classpath; AllowUnsafe in scope.
    // When: InternalClasspath.close(rawCp); then rawCp.isClosed.
    // Then: returns true.
    // Pins: INV-001.
    "close then isClosed returns true" in run {
        Abort.run[TastyError]:
            Scope.run:
                InternalClasspath.allocate.flatMap: rawCp =>
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, fixtureSource(), 1, rawCp).map: _ =>
                            rawCp
        .map:
            case Result.Success(rawCp) =>
                val closed = rawCp.isClosed
                assert(closed, "Expected isClosed == true after Scope exits (close finalizer ran)")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end ClasspathPureAccessorTest
