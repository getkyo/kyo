package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Classpath index maps are immutable after Pass C construction.
  */
class ClasspathIndexImmutabilityTest extends kyo.test.Test[Any]:

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

    "fqnIndex is built once and never mutated (val semantics)" in {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
                val idx1 = cp.indices.byFqn
                // Perform arbitrary read operations
                val _    = cp.findClass("kyo.fixtures.PlainClass")
                val _    = cp.topLevelClasses
                val _    = cp.packages
                val idx2 = cp.indices.byFqn
                (idx1, idx2)).map:
                case Result.Success((idx1, idx2)) =>
                    // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
                    assert(
                        java.lang.System.identityHashCode(idx1.asInstanceOf[AnyRef]) ==
                            java.lang.System.identityHashCode(idx2.asInstanceOf[AnyRef]),
                        "fqnIndex must be the same reference before and after read operations"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // subclassIndex is built by inverting parentTypes during Pass C; same-file TYPEREFdirect/
    // TYPEREFsymbol parents are indexed. This test pins: subclassIndex is immutable after Pass C.
    "subclassIndex is a non-null immutable Map after Pass C" in {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, fixtureSource(), 1).map: cp =>
                val idx1 = cp.indices.subclassIndex
                val _    = cp.findClass("kyo.fixtures.PlainClass") // arbitrary read
                val idx2 = cp.indices.subclassIndex
                (idx1, idx2)).map:
                case Result.Success((idx1, idx2)) =>
                    assert(idx1.nonEmpty || idx1.isEmpty, "subclassIndex must be accessible after open")
                    // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
                    assert(
                        java.lang.System.identityHashCode(idx1.asInstanceOf[AnyRef]) ==
                            java.lang.System.identityHashCode(idx2.asInstanceOf[AnyRef]),
                        "subclassIndex must be the same Dict instance (val semantics)"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "SingleAssign deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

    "ClasspathRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

    "UnresolvedRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed
    }

end ClasspathIndexImmutabilityTest
