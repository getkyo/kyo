package kyo.internal

import kyo.*
import kyo.kernel.ContextEffect

/** Adapts a [[kyo.FileSystem]] capability into a [[StoreSeam]], so [[FileJournalCore]]'s shared
  * orchestration (framing, rotation, group-commit, recovery, SWMR, and the physical directory/
  * MANIFEST bookkeeping) runs unchanged over any `FileSystem[Sync]` backend, not only the
  * platform-specific [[SegmentStore]] fast path. [[kyo.Journal.Backend.fileOver]] is the public
  * entry point this seam backs.
  *
  * `open` and `acquireLock` acquire their vended channel/lock through
  * [[kyo.FileSystem.openChannelUnscoped]] / [[kyo.FileSystem.lockUnscoped]] instead of
  * `FileSystem.openChannel`/`lock`'s own `Scope`-managed acquisition: those two release every
  * vended resource COLLECTIVELY, when the enclosing `Scope.run` completes, but
  * [[StoreSeam.Handle]] and [[SegmentStore.Lock]] release INDIVIDUALLY, on demand, potentially
  * while sibling resources this same journal opened stay live (a losing handle-open race in
  * `FileJournalCore.registerHandle` closes only the loser, immediately, while the race winner and
  * every other open segment handle stay open). The unscoped acquire pairs the vended resource with
  * its OWN fork-free plain-`Sync` release thunk: `Handle.close` returns that thunk's `Unit < Sync`
  * directly, and `SegmentStore.Lock.release` runs it via [[Sync.Unsafe.evalOrThrow]]. Neither ever
  * forks a fiber or parks a thread, so both are sound on every platform, including the
  * single-threaded cooperative schedulers (JS, Wasm).
  *
  * `exists`/`isDirectory`/`mkDir`/`list`/`readMarker`/`writeMarker` route [[FileJournalCore]]'s
  * directory- and `MANIFEST`-level bookkeeping through this same injected `fs`, so a
  * `fileOver(FileSystem.inMemory, ...)` or `fileOver(overlay, ...)` journal creates no host-disk
  * entry anywhere in its structure, not only in its segment file content.
  *
  * Locks the same `"LOCK"` sibling path [[kyo.internal.FileJournalCore]]'s existing platform
  * `SegmentStore` implementations already use, so a host journal opened through this seam locks
  * the identical path a `SegmentStore`-backed host journal locks.
  */
private[kyo] object FileSystemStoreSeam:

    def apply(fs: FileSystem[Sync]): StoreSeam[Sync] = new StoreSeam[Sync]:

        def open(path: Path)(using Frame): StoreSeam.Handle[Sync] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.openChannelUnscoped(path, FileSystem.ChannelMode.ReadWriteCreate)).map((channel, releaseThunk) =>
                channelHandle(channel, releaseThunk)
            )

        def acquireLock(lockRoot: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.lockUnscoped(lockRoot / "LOCK", exclusive = true)).map { (_, releaseThunk) =>
                new SegmentStore.Lock:
                    def release()(using AllowUnsafe): Unit =
                        // Unsafe: runs the fork-free plain-Sync release thunk synchronously, bridging it
                        // into SegmentStore.Lock's own synchronous release contract.
                        Sync.Unsafe.evalOrThrow(releaseThunk())
            }

        def syncDir(dir: Path)(using Frame): Unit < Sync =
            Abort.run[FileException](fs.syncDir(dir)).map:
                case Result.Success(_)  => ()
                case Result.Failure(fe) => throw fe
                case Result.Panic(e)    => throw e

        override def exists(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path))

        override def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.isDirectory(path))

        override def mkDir(path: Path)(using Frame): Unit < Sync =
            Abort.run[FileException](fs.mkDir(path)).map(_ => ())

        override def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.list(path))

        override def readMarker(path: Path)(using Frame): Maybe[Span[Byte]] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path)).map { found =>
                if !found then Absent
                else mapFileError(fs.readBytes(path)).map(Present(_))
            }

        override def writeMarker(path: Path, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.writeBytes(path, bytes, createFolders = true))
    end apply

    private def channelHandle(channel: Path.Channel[Sync], releaseThunk: () => Unit < Sync): StoreSeam.Handle[Sync] =
        new StoreSeam.Handle[Sync]:
            def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Sync =
                mapChannelError(channel.readAt(pos, len)).map(_.toArray)
            def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Sync =
                mapChannelError(channel.writeAt(pos, Span.from(bytes)))
            def sync()(using Frame): Unit < Sync               = mapChannelError(channel.sync())
            def truncate(size: Long)(using Frame): Unit < Sync = mapChannelError(channel.truncate(size))
            def size()(using Frame): Long < Sync               = mapChannelError(channel.size())
            def close()(using Frame): Unit < Sync              = releaseThunk()
    end channelHandle

    private def mapChannelError[A](v: A < (Sync & Abort[FileException]))(using Frame): A < Sync =
        Abort.run[FileException](v).map:
            case Result.Success(a)  => a
            case Result.Failure(fe) => throw fe
            case Result.Panic(e)    => throw e

    // Converts a FileSystem-level Abort[FileException] into this seam's own Abort[JournalStorageError]
    // channel, used by every directory/marker bookkeeping override above.
    private def mapFileError[A](v: A < (Sync & Abort[FileException]))(using Frame): A < (Sync & Abort[JournalStorageError]) =
        Abort.run[FileException](v).map:
            case Result.Success(a)  => a
            case Result.Failure(fe) => Abort.fail(JournalStorageError(s"FileSystem operation failed: ${fe.getMessage}", Present(fe)))
            case Result.Panic(e)    => throw e

end FileSystemStoreSeam
