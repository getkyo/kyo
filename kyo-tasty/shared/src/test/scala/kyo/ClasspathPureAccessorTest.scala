package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Tasty.Classpath pure accessors post Phase 07.
  *
  * The internal Classpath state machine (Building/Ready/Closed) was deleted in Phase 07. All tests here verify the public Tasty.Classpath
  * case class accessors behave correctly. The internal pureClass/purePackage/allSymbols methods are gone; the equivalents are findClass,
  * findPackage, and symbols on Tasty.Classpath.
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

    // Test 1: findClass returns Present on an open classpath.
    // Given: fixture classpath containing PlainClass.tasty.
    // When: cp.findClass("kyo.fixtures.PlainClass").
    // Then: result is Maybe.Present(sym) with sym.name.asString == "PlainClass".
    // Pins: INV-003 (Classpath case class fields immutable post-construction).
    "findClass returns Present for a known FQN" in run {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                cp.findClass("kyo.fixtures.PlainClass")).map:
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

    // Test 2: symbols is non-empty after open.
    // Given: fixture classpath.
    // When: cp.symbols.
    // Then: non-empty.
    // Pins: INV-003.
    "symbols is non-empty after open" in run {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                cp.symbols).map:
                case Result.Success(syms) =>
                    assert(syms.nonEmpty, "symbols should be non-empty after loading a classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test 3: Tasty.Classpath has no Closed state; the case class is accessible after Scope exits.
    // Given: classpath opened inside a Scope.
    // When: Scope exits (cleanup runs).
    // Then: the Tasty.Classpath case class is still accessible (immutable value).
    // Pins: Phase 07 (deletion of internal Classpath state machine; no Closed state on Tasty.Classpath).
    "Tasty.Classpath is accessible after Scope exits (no Closed state)" in run {
        var capturedCp: Tasty.Classpath = null
        Abort.run[TastyError]:
            Scope.run:
                ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                    capturedCp = cp
        .map:
            case Result.Success(_) =>
                assert(capturedCp != null, "Classpath should have been captured")
                assert(capturedCp.symbols.nonEmpty, "symbols should still be accessible after Scope exits")
                assert(capturedCp.errors.isEmpty, "errors should be empty for clean classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 4: topLevelClasses is non-empty.
    // Given: fixture classpath.
    // When: cp.topLevelClasses.
    // Then: non-empty.
    // Pins: INV-003.
    "topLevelClasses is non-empty after open" in run {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                cp.topLevelClasses).map:
                case Result.Success(classes) =>
                    assert(classes.nonEmpty, "topLevelClasses should be non-empty")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end ClasspathPureAccessorTest
