package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.SymbolId
import scala.collection.mutable

/** Phase 06 plan-mandated tests pinning the Classpath immutability contract.
  *
  * Leaves:
  *   1. Classpath constructor fields are immutable (INV-003).
  *   5. No AllowUnsafe on Classpath public methods (INV-010).
  *   6. decodeBody is the only Classpath method returning a kyo effect (INV-005).
  *
  * Pins: INV-003, INV-005, INV-010.
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
        ClasspathOrchestrator.open(Seq("root"), false, src, 1)
    end openFixtureClasspath

    // ── Leaf 1: Classpath constructor fields are immutable ───────────────────

    // Given: a Classpath cp returned from Classpath.open.
    // When: any cp field is accessed twice.
    // Then: both accesses return the same reference (val semantics); no reassignment is possible.
    // Pins: INV-003 (Classpath case class fields immutable post-construction).
    "Leaf 1: Classpath constructor fields are immutable (val semantics)" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Sync.defer:
                    // Access each field twice; both accesses must return reference-equal (or value-equal) results.
                    val syms1  = cp.symbols
                    val syms2  = cp.symbols
                    val errs1  = cp.errors
                    val errs2  = cp.errors
                    val fqn1   = cp.fqnIndex
                    val fqn2   = cp.fqnIndex
                    val top1   = cp.topLevelClassIds
                    val top2   = cp.topLevelClassIds
                    val root1  = cp.rootSymbolId
                    val root2  = cp.rootSymbolId
                    val canon1 = cp.canonical
                    val canon2 = cp.canonical
                    (syms1 eq syms2, errs1 eq errs2, fqn1 eq fqn2, top1 eq top2, root1 == root2, canon1 eq canon2)).map:
                case Result.Success((symsSame, errsSame, fqnSame, topSame, rootSame, canonSame)) =>
                    assert(symsSame, "symbols field must return reference-equal value on repeated access")
                    assert(errsSame, "errors field must return reference-equal value on repeated access")
                    assert(fqnSame, "fqnIndex field must return reference-equal value on repeated access")
                    assert(topSame, "topLevelClassIds field must return reference-equal value on repeated access")
                    assert(rootSame, "rootSymbolId field must return equal value on repeated access")
                    assert(canonSame, "canonical field must return reference-equal value on repeated access")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 5: no AllowUnsafe on Classpath public methods ───────────────────

    // Given: the Tasty.scala source text, Classpath case class body block.
    // When: every public method signature in the case class is scanned.
    // Then: no method carries (using AllowUnsafe).
    // Pins: INV-010.
    "Leaf 5: no AllowUnsafe on Classpath public methods" in {
        val src   = TestResourceLoader.readText("kyo/Tasty.scala")
        val lines = src.split("\n").toSeq

        // Find the Classpath case class body between `final case class Classpath private[Tasty]` and `end Classpath`.
        val cpStart = lines.indexWhere(_.contains("final case class Classpath private[Tasty]"))
        val cpEnd   = lines.indexWhere(l => l.trim == "end Classpath", cpStart)
        assert(cpStart >= 0, "Could not find 'final case class Classpath private[Tasty]' in Tasty.scala")
        assert(cpEnd >= 0, "Could not find 'end Classpath' in Tasty.scala")

        val classpathLines = lines.slice(cpStart, cpEnd + 1)

        // Collect public def lines that have AllowUnsafe in their signature.
        val allowUnsafeDefs = classpathLines.filter: line =>
            val t = line.trim
            (t.startsWith("def ") || t.contains(" def ")) && t.contains("AllowUnsafe") && !t.startsWith("//") && !t.startsWith("private")

        assert(
            allowUnsafeDefs.isEmpty,
            s"No Classpath public method may carry AllowUnsafe; found: ${allowUnsafeDefs.mkString("; ")}"
        )
        succeed
    }

    // ── Leaf 6: decodeBody is the only Classpath method returning a kyo effect ─

    // Given: the Tasty.scala source text, Classpath case class body block.
    // When: every public method's return type is scanned for a kyo effect suffix.
    // Then: exactly one method (decodeBody) returns a kyo effect; all others return plain values.
    // Pins: INV-005.
    "Leaf 6: decodeBody is the only Classpath method returning a kyo effect" in {
        val src   = TestResourceLoader.readText("kyo/Tasty.scala")
        val lines = src.split("\n").toSeq

        val cpStart = lines.indexWhere(_.contains("final case class Classpath private[Tasty]"))
        val cpEnd   = lines.indexWhere(l => l.trim == "end Classpath", cpStart)
        assert(cpStart >= 0, "Could not find 'final case class Classpath private[Tasty]' in Tasty.scala")
        assert(cpEnd >= 0, "Could not find 'end Classpath' in Tasty.scala")

        val classpathLines = lines.slice(cpStart, cpEnd + 1)

        // Collect public def lines with a kyo effect return type marker.
        val effectDefs = classpathLines.filter: line =>
            val t = line.trim
            t.startsWith("def ") && t.contains("< ") && !t.startsWith("//") && !t.startsWith("private")

        assert(
            effectDefs.length == 1,
            s"Expected exactly 1 effect-bearing def in Classpath, found ${effectDefs.length}: ${effectDefs.mkString("; ")}"
        )
        assert(
            effectDefs.head.contains("def decodeBody(sym: Symbol)"),
            s"The single effect-bearing def must be 'def decodeBody(sym: Symbol)', got: ${effectDefs.head.trim}"
        )
        succeed
    }

end ClasspathImmutabilityTest
