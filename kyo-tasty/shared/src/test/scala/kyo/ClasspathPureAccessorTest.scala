package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Tasty.Classpath pure accessors: findClass, symbols, topLevelClasses, and post-Scope access. */
class ClasspathPureAccessorTest extends kyo.test.Test[Any]:

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

    "findClass returns Present for a known FQN" in {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
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

    "symbols is non-empty after open" in {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
                cp.symbols).map:
                case Result.Success(syms) =>
                    assert(syms.nonEmpty, "symbols should be non-empty after loading a classpath")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "Tasty.Classpath is accessible after Scope exits (no Closed state)" in {
        var capturedCp: Tasty.Classpath = null
        Abort.run[TastyError]:
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
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

    "topLevelClasses is non-empty after open" in {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
                cp.topLevelClasses).map:
                case Result.Success(classes) =>
                    assert(classes.nonEmpty, "topLevelClasses should be non-empty")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end ClasspathPureAccessorTest
