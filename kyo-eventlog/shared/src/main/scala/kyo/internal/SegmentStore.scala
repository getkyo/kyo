package kyo.internal

import kyo.*

/** Platform I/O seam for segment files. Each platform supplies one concrete implementation
  * (jvm-native: FileChannel; js-wasm: node:fs synchronous API). The shared orchestration uses only
  * this interface: no `java.nio` channels, no `toJava`, no platform-specific types appear in the
  * shared core.
  *
  * `open` opens one segment file for positioned read+write (no implicit cursor, mirrors the
  * `FileChannel` usage). `acquireLock` acquires the journal-root cross-process lock (jvm-native:
  * `FileChannel.tryLock`; js-wasm: `O_EXCL` lockfile). `syncDir` fsyncs a directory entry (POSIX
  * durability for newly-created children); implementations that cannot open a directory fd (Windows)
  * or have no concept of directory sync make this a no-op.
  */
private[kyo] trait SegmentStore:
    def open(path: Path)(using AllowUnsafe): SegmentStore.Handle
    def acquireLock(root: Path)(using AllowUnsafe, Frame): Result[JournalStorageError, SegmentStore.Lock]
    def syncDir(dir: Path)(using AllowUnsafe): Unit
end SegmentStore

private[kyo] object SegmentStore:

    /** Positioned I/O for one open segment file. No implicit cursor; every operation names its byte
      * position explicitly. Short reads are tolerated on `readAt`: the caller must check the
      * returned array length.
      */
    trait Handle:
        /** Reads up to `len` bytes starting at `pos`. Returns fewer bytes when `pos + len` exceeds
          * the file size (short read). The caller checks the length.
          */
        def readAt(pos: Long, len: Int)(using AllowUnsafe): Array[Byte]

        /** Writes `bytes` at `pos`, extending the file if necessary. */
        def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe): Unit

        /** Flushes data to durable storage (`fdatasync` or stronger). Called only on the fsync path
          * (`Fsync.Always`).
          */
        def sync()(using AllowUnsafe): Unit

        def truncate(size: Long)(using AllowUnsafe): Unit
        def size()(using AllowUnsafe): Long
        def close()(using AllowUnsafe): Unit
    end Handle

    /** Cross-process lock token. `release` must be called on close, exactly once per acquired lock. */
    trait Lock:
        def release()(using AllowUnsafe): Unit
    end Lock

end SegmentStore
