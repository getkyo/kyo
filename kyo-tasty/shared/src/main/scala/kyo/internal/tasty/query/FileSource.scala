package kyo.internal.tasty.query

import kyo.*

/** Platform abstraction for file I/O during classpath loading.
  *
  * Three implementations: JvmFileSource (JVM), JsFileSource (JS/Node.js), NativeFileSource (Scala Native POSIX). The shared trait carries
  * no platform-specific imports.
  *
  * Design note: `exists` returns `Boolean < Sync` (no Abort) because a non-existent path is a valid false result, not an error. Callers
  * that need an error on absence use `read` directly. This keeps the call-site effect row lighter for common use as a short-circuit guard.
  */
trait FileSource:

    /** Read all bytes from the file at the given path.
      *
      * On browser JS (no filesystem), returns `Abort.fail(TastyError.FileNotFound("browser: use fromPickles"))`.
      */
    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError])

    /** Write bytes to the file at the given path, creating parent directories as needed.
      *
      * Used by `SnapshotWriter` to write snapshot tmp files. On browser JS, returns `Abort.fail(TastyError.SnapshotIoError("browser: no
      * filesystem"))`.
      */
    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError])

    /** Atomically rename `from` to `to`. On POSIX, delegates to `Files.move` with ATOMIC_MOVE (or equivalent). Last writer wins.
      *
      * Used by `SnapshotWriter` for the tmp-to-final atomic step.
      */
    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError])

    /** Delete the file at the given path.
      *
      * Trait-body default delegates to `kyo.Path.remove`, which is cross-platform (JVM and Native via NioPathUnsafe,
      * JS via Node fs unlink). A missing path is a no-op (`kyo.Path.remove` returns false). Any `FileFsException` raised
      * by the platform layer is mapped to `TastyError.SnapshotIoError` so call sites see a uniform error type.
      *
      * INV-009 site-4: realised by `Tasty.Snapshot.deleteFile` after F-001.
      */
    def delete(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Abort.recover[FileFsException](err => Abort.fail(TastyError.SnapshotIoError(s"delete $path: ${err.getMessage}"))):
            kyo.Path(path).remove.unit

    /** Create directory and all ancestors at the given path. No-op if already exists. */
    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError])

    /** List all files in the directory whose names end with the given suffix.
      *
      * On browser JS, returns `Abort.fail(TastyError.FileNotFound("browser: use fromPickles"))`. Does not recurse into subdirectories;
      * callers that need recursive walk iterate over sub-paths returned by the top-level listing.
      *
      * Delegates to the multi-suffix variant with a single-element Chunk.
      */
    def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        list(dir, Chunk(suffix))

    /** List all files in the directory whose names end with any of the given suffixes.
      *
      * Performs a single directory or JAR walk and returns all entries matching any suffix. An empty suffix list returns Chunk.empty
      * without touching the filesystem. On browser JS, returns `Abort.fail(TastyError.FileNotFound("browser: use fromPickles"))`.
      */
    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError])

    /** Test whether a file or directory exists at the given path.
      *
      * A missing path produces `false`, not an error. Any I/O exception is absorbed into `false`.
      */
    def exists(path: String)(using Frame): Boolean < Sync

    /** Return the last-modified time in milliseconds and size in bytes for the given file.
      *
      * Used by `DigestComputer.compute` to hash the (path, mtime, size) tuple set.
      */
    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError])

    /** Execute `body` within a read-batch context.
      *
      * The default no-op implementation simply runs `body` unchanged. The JVM implementation overrides this to install a
      * JarMappedReaderPool for the duration of `body`, so that repeated jar reads within one `initInto` call share memory-mapped buffers
      * instead of constructing a new JarFile per read.
      *
      * Called by ClasspathOrchestrator.initInto to wrap the entire scan+decode pipeline.
      */
    def withReadBatch[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        body

    /** Open a zip or jar root for entry-level reads.
      *
      * Returns `Maybe.Absent` when the platform cannot open the zip (e.g. browser JS, or when the root is a directory rather than a jar).
      * The returned `ZipHandle` is Scope-bound; its backing resources are released when the enclosing Scope exits.
      *
      * The default implementation returns `Maybe.Absent`. Platform-specific overrides (JVM) open the jar bytes and serve entries from an
      * in-memory map. JS and Native implementations return `Maybe.Absent`; transparent zip parsing may be added in a later
      * phase.
      */
    def openZip(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
        Maybe.Absent

end FileSource

object FileSource:
    final case class FileStat(mtimeMs: Long, size: Long)
end FileSource
