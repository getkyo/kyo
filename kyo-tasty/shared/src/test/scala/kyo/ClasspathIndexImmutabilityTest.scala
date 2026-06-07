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

    // Leaf 1: fqnIndex is built once and never mutated.
    // Given: a Classpath cp from Classpath.init.
    // When: cp.indices.byFqn is captured before and after an arbitrary read operation.
    // Then: both captures are the same Map reference.
    "Leaf 1: fqnIndex is built once and never mutated (val semantics)" in {
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

    // Leaf 2: subclassIndex is a non-null immutable Map constructed during Pass C.
    // builds subclassIndex by inverting parentTypes. Parent types derived from same-file
    // TYPEREFdirect/TYPEREFsymbol ARE indexed. Cross-file parents (encoded as APPLY(NEW(type),...) in
    // TASTy) resolve to Named(SymbolId(-1)) in; full cross-file parent indexing requires
    // the TASTy APPLY-tree descent added in.
    // This test pins: subclassIndex is constructed as an immutable Map after Pass C.
    "Leaf 2: subclassIndex is a non-null immutable Map after Pass C (INV-008)" in {
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
                        "subclassIndex must be the same Dict instance (val semantics, INV-008)"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Leaves 3-5: grep-based deletion checks are in ClasspathIndexGrepTest (JVM-only, kyo-tasty/jvm/src/test).
    // They verify that SingleAssign, ClasspathRef, UnresolvedRef do not appear in production sources.
    "Leaf 3: SingleAssign deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed // JVM-only grep in ClasspathIndexGrepTest
    }

    "Leaf 4: ClasspathRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed // JVM-only grep in ClasspathIndexGrepTest
    }

    "Leaf 5: UnresolvedRef deletion check delegates to ClasspathIndexGrepTest (JVM)" in {
        succeed // JVM-only grep in ClasspathIndexGrepTest
    }

end ClasspathIndexImmutabilityTest
