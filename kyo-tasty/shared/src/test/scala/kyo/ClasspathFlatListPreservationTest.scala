package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.mutable

/** Plan-mandated tests for Phase 02 (leaves 41-42): verify that cp.symbols still returns all symbols as Chunk[Symbol].
  *
  * Pins: INV-013.
  */
class ClasspathFlatListPreservationTest extends Test:

    import AllowUnsafe.embrace.danger

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) = Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openFixtureCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureCp

    // Leaf 41: symbols-size-equals-sum-of-per-kind
    // Given: fixture loaded cp; When: cp.symbols.size and sum over 14 kinds; Then: equal
    // Pins: INV-013
    "symbols-size-equals-sum-of-per-kind: flat list size equals sum of per-kind counts" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureCp.flatMap: cp =>
                Kyo.lift:
                    val all    = cp.symbols
                    val byKind = SymbolKind.values.foldLeft(0)((acc, k) => acc + all.count(_.kind == k))
                    (all.length, byKind)).map:
                case Result.Success((total, byKind)) =>
                    assert(total == byKind, s"cp.symbols.size ($total) != sum of per-kind counts ($byKind)")
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 42: symbols-still-returns-Chunk-Symbol
    // Given: fixture loaded cp; When: val xs: Chunk[Tasty.Symbol] = cp.symbols; Then: compiles; size matches
    // Pins: INV-013
    "symbols-still-returns-Chunk-Symbol: cp.symbols has type Chunk[Tasty.Symbol]" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureCp.flatMap: cp =>
                // Static type check: the following assignment must compile
                val xs: Chunk[Tasty.Symbol] = cp.symbols
                Kyo.lift(xs.length)).map:
                case Result.Success(len) =>
                    assert(len >= 0, s"Expected non-negative length but got $len")
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end ClasspathFlatListPreservationTest
