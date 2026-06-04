package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Phase 06 plan-mandated tests pinning the Classpath immutability contract.
  *
  * Leaves:
  *   1. Classpath constructor fields are immutable (INV-003).
  *
  * Pins: INV-003.
  */
class ClasspathImmutabilityTest extends Test:

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(b) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = b
                case None => Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

    // ── Leaf 1: Classpath constructor fields are immutable ───────────────────

    // Given: a Classpath cp returned from Classpath.init.
    // When: any cp field is accessed twice.
    // Then: both accesses return the same reference (val semantics); no reassignment is possible.
    // Pins: INV-003 (Classpath case class fields immutable post-construction).
    "Leaf 1: Classpath constructor fields are immutable (val semantics)" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Sync.defer:
                    // Access each field twice; both accesses must return reference-equal (or value-equal) results.
                    val syms1 = cp.symbols
                    val syms2 = cp.symbols
                    val errs1 = cp.errors
                    val errs2 = cp.errors
                    val fqn1  = cp.indices.byFqn
                    val fqn2  = cp.indices.byFqn
                    val top1  = cp.indices.topLevelClassIds
                    val top2  = cp.indices.topLevelClassIds
                    val root1 = cp.rootSymbolId
                    val root2 = cp.rootSymbolId
                    // canonical field removed in Phase 04: Classpath is now pure data with no TypeArena.
                    (syms1 eq syms2, errs1 eq errs2, fqn1 eq fqn2, top1 eq top2, root1 == root2)).map:
                case Result.Success((symsSame, errsSame, fqnSame, topSame, rootSame)) =>
                    assert(symsSame, "symbols field must return reference-equal value on repeated access")
                    assert(errsSame, "errors field must return reference-equal value on repeated access")
                    assert(fqnSame, "byFqn field must return reference-equal value on repeated access")
                    assert(topSame, "topLevelClassIds field must return reference-equal value on repeated access")
                    assert(rootSame, "rootSymbolId field must return equal value on repeated access")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end ClasspathImmutabilityTest
