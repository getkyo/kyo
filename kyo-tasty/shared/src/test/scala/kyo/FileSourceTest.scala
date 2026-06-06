package kyo

import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** Tests for FileSource.list(dir, suffixes: Chunk[String]) multi-suffix API.
  *
  * Tests F1-F4 per execution-plan-perf.md.
  *
  * Uses an in-memory FileSource to remain cross-platform. The multi-suffix variant is tested for correctness and consistency with the
  * single-suffix delegate.
  */
class FileSourceTest extends Test:

    /** Minimal in-memory FileSource for multi-suffix list testing. */
    final class MultiSuffixMemorySource(
        files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty
    ) extends FileSource:

        def add(path: String, content: Array[Byte]): Unit =
            files(path) = content

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
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(
                    files.keys
                        .filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith))
                        .toSeq
                        .sorted
                )

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MultiSuffixMemorySource

    private val emptyBytes: Array[Byte] = Array.emptyByteArray

    // F1: list(dir, Chunk(".tasty", ".class")) returns merged results in deterministic order
    "F1: list with multiple suffixes returns merged results in deterministic order" in run {
        val src = MultiSuffixMemorySource()
        src.add("root/kyo/Foo.tasty", emptyBytes)
        src.add("root/kyo/Foo.class", emptyBytes)
        src.add("root/kyo/Bar.tasty", emptyBytes)
        src.add("root/kyo/Bar.class", emptyBytes)
        src.add("root/kyo/Something.java", emptyBytes)
        Abort.run[TastyError](
            src.list("root", Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(entries) =>
                assert(
                    entries.length == 4,
                    s"Expected 4 entries but got: ${entries.length}: $entries"
                )
                assert(
                    entries.forall(e => e.endsWith(".tasty") || e.endsWith(".class")),
                    s"All entries should end with .tasty or .class: $entries"
                )
                assert(
                    !entries.exists(_.endsWith(".java")),
                    s".java entry should be excluded: $entries"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // F2: list(dir, Chunk.empty) returns Chunk.empty
    "F2: list with empty suffix list returns Chunk.empty" in run {
        val src = MultiSuffixMemorySource()
        src.add("root/kyo/Foo.tasty", emptyBytes)
        src.add("root/kyo/Foo.class", emptyBytes)
        Abort.run[TastyError](
            src.list("root", Chunk.empty[String])
        ).map:
            case Result.Success(entries) =>
                assert(entries.isEmpty, s"Expected empty Chunk but got: $entries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // F3: list(dir, Chunk(".tasty")) matches existing single-suffix list(dir, ".tasty") behavior
    "F3: list with single-element Chunk matches single-suffix list behavior" in run {
        val src = MultiSuffixMemorySource()
        src.add("root/kyo/Foo.tasty", emptyBytes)
        src.add("root/kyo/Bar.tasty", emptyBytes)
        src.add("root/kyo/Baz.class", emptyBytes)
        Abort.run[TastyError](
            src.list("root", Chunk(".tasty")).flatMap: multiResult =>
                src.list("root", ".tasty").map: singleResult =>
                    (multiResult, singleResult)
        ).map:
            case Result.Success((multiResult, singleResult)) =>
                assert(
                    multiResult.toSeq.sorted == singleResult.toSeq.sorted,
                    s"Multi-suffix single-element should match single-suffix: multi=$multiResult single=$singleResult"
                )
                assert(
                    multiResult.length == 2,
                    s"Expected 2 .tasty entries but got: ${multiResult.length}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // F4: ordering of returned paths is deterministic across two consecutive calls on the same root
    "F4: result ordering is deterministic across two consecutive calls on the same root" in run {
        val src = MultiSuffixMemorySource()
        src.add("root/kyo/Alpha.tasty", emptyBytes)
        src.add("root/kyo/Beta.tasty", emptyBytes)
        src.add("root/kyo/Gamma.class", emptyBytes)
        src.add("root/kyo/Delta.class", emptyBytes)
        src.add("root/kyo/Epsilon.tasty", emptyBytes)
        Abort.run[TastyError](
            src.list("root", Chunk(".tasty", ".class")).flatMap: first =>
                src.list("root", Chunk(".tasty", ".class")).map: second =>
                    (first, second)
        ).map:
            case Result.Success((first, second)) =>
                assert(
                    first.toSeq == second.toSeq,
                    s"Two consecutive calls should return identical ordering: first=$first second=$second"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end FileSourceTest
