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

/** Effect-polymorphic counterpart to [[SegmentStore]]: `open` and `syncDir` return `< S` instead
  * of requiring [[AllowUnsafe]] on the calling carrier, so [[FileJournalCore]] is generalized over
  * this seam so the same orchestration runs whether `S` is `Sync` (the synchronous
  * synchronous store, wrapped byte-identically) or `Async` (a platform adapter that genuinely
  * suspends). `acquireLock` is always `< Sync`: the cross-process root lock is acquired exactly
  * once at journal-open time (never on the per-record hot path), so it never needs the
  * blocking-offload treatment the per-handle operations get.
  */
private[kyo] trait StoreSeam[S]:
    def open(path: Path)(using Frame): StoreSeam.Handle[S] < (S & Abort[JournalStorageError])
    def acquireLock(root: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError])
    def syncDir(dir: Path)(using Frame): Unit < S
end StoreSeam

private[kyo] object StoreSeam:

    /** Positioned I/O for one open segment file, mirroring [[SegmentStore.Handle]] with every
      * operation returning `< S`.
      */
    trait Handle[S]:
        def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < S
        def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < S
        def sync()(using Frame): Unit < S
        def truncate(size: Long)(using Frame): Unit < S
        def size()(using Frame): Long < S
        def close()(using Frame): Unit < S
    end Handle

    /** Wraps a synchronous [[SegmentStore]] as a `StoreSeam[Sync]`: every call defers through
      * [[Sync.Unsafe.defer]] with no suspension point, forwarding to the underlying store.
      */
    def sync(store: SegmentStore): StoreSeam[Sync] = new StoreSeam[Sync]:
        def open(path: Path)(using Frame): Handle[Sync] < (Sync & Abort[JournalStorageError]) =
            Abort.catching[Exception](e => JournalStorageError(s"Cannot open segment '${path.unsafe.show}'", Present(e))) {
                Sync.Unsafe.defer(syncHandle(store.open(path)))
            }

        def acquireLock(root: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError]) =
            Sync.Unsafe.defer(Abort.get(store.acquireLock(root)))

        def syncDir(dir: Path)(using Frame): Unit < Sync =
            Sync.Unsafe.defer(store.syncDir(dir))
    end sync

    private def syncHandle(h: SegmentStore.Handle): Handle[Sync] = new Handle[Sync]:
        def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Sync     = Sync.Unsafe.defer(h.readAt(pos, len))
        def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Sync = Sync.Unsafe.defer(h.writeAt(pos, bytes))
        def sync()(using Frame): Unit < Sync                                 = Sync.Unsafe.defer(h.sync())
        def truncate(size: Long)(using Frame): Unit < Sync                   = Sync.Unsafe.defer(h.truncate(size))
        def size()(using Frame): Long < Sync                                 = Sync.Unsafe.defer(h.size())
        def close()(using Frame): Unit < Sync                                = Sync.Unsafe.defer(h.close())
    end syncHandle

end StoreSeam
