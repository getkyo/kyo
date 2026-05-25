package kyo.internal.reflect.query

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
      * On browser JS (no filesystem), returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`.
      */
    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError])

    /** Write bytes to the file at the given path, creating parent directories as needed.
      *
      * Used by `SnapshotWriter` to write snapshot tmp files. On browser JS, returns `Abort.fail(ReflectError.SnapshotIoError("browser: no
      * filesystem"))`.
      */
    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError])

    /** Atomically rename `from` to `to`. On POSIX, delegates to `Files.move` with ATOMIC_MOVE (or equivalent). Last writer wins.
      *
      * Used by `SnapshotWriter` for the tmp-to-final atomic step.
      */
    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError])

    /** Create directory and all ancestors at the given path. No-op if already exists. */
    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError])

    /** List all files in the directory whose names end with the given suffix.
      *
      * On browser JS, returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))`. Does not recurse into subdirectories;
      * callers that need recursive walk iterate over sub-paths returned by the top-level listing.
      */
    def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError])

    /** Test whether a file or directory exists at the given path.
      *
      * A missing path produces `false`, not an error. Any I/O exception is absorbed into `false`.
      */
    def exists(path: String)(using Frame): Boolean < Sync

    /** Return the last-modified time in milliseconds and size in bytes for the given file.
      *
      * Used by `DigestComputer.compute` to hash the (path, mtime, size) tuple set.
      */
    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError])

end FileSource

object FileSource:
    final case class FileStat(mtimeMs: Long, size: Long)
end FileSource
