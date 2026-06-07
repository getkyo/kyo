package kyo.internal

import kyo.*
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** In-memory FileSource for tests.
  *
  * Pre-loads byte arrays into a mutable hash map keyed by path string, then serves them through the FileSource trait. Used by shared tests
  * that bypass the platform file system entirely (e.g. ClasspathOrchestrator tests on JS/Native, snapshot round-trip tests).
  *
  * The `exists` predicate returns true for a path if the path itself is a key OR if the path is a directory prefix with at least one keyed
  * child. This satisfies ClasspathOrchestrator's root-existence guard:
  *
  * {{{
  * source.exists(root) // must return true for "root" when "root/Foo.tasty" is present
  * }}}
  *
  * Per the edge-case note: omitting the directory check causes FileNotFound before any TASTy files are read.
  */
final class MemoryFileSource(
    files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty,
    mtimes: mutable.HashMap[String, Long] = mutable.HashMap.empty
) extends FileSource:

    /** Register a path-to-bytes entry. Overwrites any prior entry for the same path. */
    def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

    /** Return a snapshot of all currently registered paths. Used by tests to verify post-eviction state. */
    def allPaths: Set[String] = files.keySet.toSet

    /** Set the mtime in milliseconds for a path. If unset, `stat` reports `mtimeMs = 0L`.
      *
      * Provided for tests that exercise `DigestComputer.compute` semantics (mtime-driven cache invalidation) without depending on a real
      * filesystem. The in-memory mtime is hand-controlled; tests can simulate "+1 hour" by calling `setMtime(path, prior + 3600000)`.
      */
    def setMtime(path: String, mtimeMs: Long): Unit = mtimes(path) = mtimeMs

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

    override def delete(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        // override the trait-body default so the in-memory test source honours delete locally
        // without crossing into the platform kyo.Path layer (which would attempt a real filesystem op).
        Sync.defer:
            val _ = files.remove(path)
            val _ = mtimes.remove(path)
            ()

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq))

    def exists(path: String)(using Frame): Boolean < Sync =
        Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        Sync.defer(FileSource.FileStat(mtimes.getOrElse(path, 0L), files.get(path).map(_.length.toLong).getOrElse(0L)))

end MemoryFileSource
