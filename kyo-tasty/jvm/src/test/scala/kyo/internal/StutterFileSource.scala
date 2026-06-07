package kyo.internal

import kyo.*
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.JvmFileSource

/** A test-only FileSource wrapper that delays reads until a latch is released.
  *
  * Wraps JvmFileSource (or any other FileSource) so that each read call suspends until the caller releases the latch by releasing the
  * semaphore. This enables deterministic testing of concurrent reader+writer scenarios: one fiber can hold a snapshot read at
  * a controlled point while a second fiber writes a new snapshot to the same path.
  *
  * Design note: FileSource.read returns `Array[Byte] < (Sync & Abort[TastyError])`, which does not include Async in its effect row. The
  * Channel-based latch (which requires Async) therefore cannot be used directly in the read method. Instead, the latch is a
  * java.util.concurrent.Semaphore(0) wrapped in Sync.defer. The blocking is intentional and bounded (the test releases the semaphore within
  * 50-100 ms). This is the only location in the test suite where a bounded block is acceptable because the alternative (widening FileSource's
  * effect row to Async) would require changing a core production interface for test-only purposes.
  *
  * All other FileSource operations delegate directly to the underlying source without any latch involvement.
  */
final class StutterFileSource private (delegate: FileSource, latch: java.util.concurrent.Semaphore) extends FileSource:

    /** Read from the delegate, but block (bounded) until latch is released. */
    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.defer(latch.acquire()).andThen:
            delegate.read(path)

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
        delegate.write(path, bytes)

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        delegate.rename(from, to)

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        delegate.mkdirs(path)

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        delegate.list(dir, suffixes)

    def exists(path: String)(using Frame): Boolean < Sync =
        delegate.exists(path)

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        delegate.stat(path)

    override def withReadBatch[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        delegate.withReadBatch(body)

end StutterFileSource

object StutterFileSource:

    /** Wrap a FileSource so that each read blocks until `release()` is called on the returned semaphore.
      *
      * The semaphore starts at 0 permits. Each call to `read` acquires one permit (blocking until one is available). The test fiber calls
      * `semaphore.release` to unblock the waiting read.
      *
      * @param delegate
      *   the underlying FileSource to delegate all non-read operations to
      * @return
      *   a pair of (StutterFileSource, Semaphore); the test fiber releases the semaphore to unblock reads
      */
    def wrapping(delegate: FileSource): (StutterFileSource, java.util.concurrent.Semaphore) =
        val semaphore = new java.util.concurrent.Semaphore(0)
        (new StutterFileSource(delegate, semaphore), semaphore)

end StutterFileSource
