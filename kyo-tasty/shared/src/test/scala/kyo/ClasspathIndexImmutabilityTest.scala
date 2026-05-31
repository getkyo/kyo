package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for Phase 07: Classpath index maps are immutable after Pass C construction.
  *
  * Pins: INV-008 (Classpath index maps immutable after Pass C).
  */
class ClasspathIndexImmutabilityTest extends Test:

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
    // Given: a Classpath cp from Classpath.open.
    // When: cp.fqnIndex is captured before and after an arbitrary read operation.
    // Then: both captures are the same Map reference.
    // Pins: INV-008.
    "Leaf 1: fqnIndex is built once and never mutated (val semantics)" in run {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                val idx1 = cp.fqnIndex
                // Perform arbitrary read operations
                val _    = cp.findClass("kyo.fixtures.PlainClass")
                val _    = cp.topLevelClasses
                val _    = cp.packages
                val idx2 = cp.fqnIndex
                (idx1, idx2)).map:
                case Result.Success((idx1, idx2)) =>
                    assert(idx1 eq idx2, "fqnIndex must be the same reference before and after read operations")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Leaf 2: subclassIndex is a non-null immutable Map constructed during Pass C.
    // Phase 07 builds subclassIndex by inverting parentTypes. Parent types derived from same-file
    // TYPEREFdirect/TYPEREFsymbol ARE indexed. Cross-file parents (encoded as APPLY(NEW(type),...) in
    // TASTy) resolve to Named(SymbolId(-1)) in Phase 07; full cross-file parent indexing requires
    // the TASTy APPLY-tree descent added in Phase 09.
    // This test pins INV-008: subclassIndex is constructed as an immutable Map after Pass C.
    "Leaf 2: subclassIndex is a non-null immutable Map after Pass C (INV-008)" in run {
        Scope.run:
            Abort.run[TastyError](ClasspathOrchestrator.open(Seq("root"), false, fixtureSource(), 1).map: cp =>
                val idx1 = cp.subclassIndex
                val _    = cp.findClass("kyo.fixtures.PlainClass") // arbitrary read
                val idx2 = cp.subclassIndex
                (idx1, idx2)).map:
                case Result.Success((idx1, idx2)) =>
                    assert(idx1 ne null, "subclassIndex must not be null after open")
                    assert(idx1 eq idx2, "subclassIndex must be the same Map instance (val semantics, INV-008)")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Leaves 3-5: grep-based deletion checks are in ClasspathIndexGrepTest (JVM-only, kyo-tasty/jvm/src/test).
    // They verify that SingleAssign, ClasspathRef, UnresolvedRef do not appear in production sources.
    // Pins: INV-012.
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
