package kyo.internal

import kyo.*
import kyo.kernel.ContextEffect

/** Adapts a [[kyo.FileSystem]] capability into a [[StoreSeam]], so [[FileJournalCore]]'s shared
  * orchestration (framing, rotation, group-commit, recovery, SWMR, and the physical directory/
  * MANIFEST bookkeeping) runs unchanged over a `FileSystem.Write[Sync]` or read-only
  * `FileSystem.Read[Sync]` backend, not only the platform-specific [[SegmentStore]] fast path.
  * [[kyo.Journal.Backend.fileOver]] is the public entry point this seam backs.
  *
  * `open` acquires its vended channel through the filesystem's private unscoped channel tier.
  * `acquireLock` uses the scoped lock tier, so the journal's enclosing scope owns the claim while
  * [[SegmentStore.Lock.release]] may release that same claim earlier on backend close. Channels
  * release individually, on demand, potentially while sibling resources this same journal opened
  * stay live (a losing handle-open race in
  * `FileJournalCore.registerHandle` closes only the loser immediately, while the race winner and
  * every other open segment handle stay open). The unscoped acquire pairs the vended resource with
  * its own fork-free plain-`Sync` release thunk: `Handle.close` returns that thunk's `Unit < Sync`
  * directly. Neither release path forks a fiber or parks a thread, so both are sound on every platform, including the
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

    def apply(fs: FileSystem.Write[Sync]): StoreSeam[Sync] = new StoreSeam[Sync]:

        def open(path: Path)(using Frame): StoreSeam.Handle[Sync] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.openReadWriteChannelUnscoped(path, FileSystem.WriteOpen.Create)).map {
                case (channel, releaseThunk) => channelHandle(channel, releaseThunk)
            }

        def acquireLock(lockRoot: Path)(using Frame): SegmentStore.Lock < (Sync & Scope & Abort[JournalStorageError]) =
            lockHandle(fs, lockRoot)

        def syncDir(dir: Path)(using Frame): Unit < Sync =
            Abort.run[FileSystemException](fs.syncDirectory(dir)).map:
                case Result.Success(_)  => ()
                case Result.Failure(fe) => throw fe
                case Result.Panic(e)    => throw e

        override def exists(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path))

        override def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.isDirectory(path))

        override def mkDir(path: Path)(using Frame): Unit < Sync =
            Abort.run[FileSystemException](fs.mkDir(path)).map(_ => ())

        override def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.list(path))

        override def readMarker(path: Path)(using Frame): Maybe[Span[Byte]] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path)).map { found =>
                if !found then Absent
                else mapFileError(fs.readBytes(path)).map(Present(_))
            }

        override def writeMarker(path: Path, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.writeBytes(path, bytes, Path.WriteOptions(createFolders = true)))
    end apply

    def readOnly(fs: FileSystem.Read[Sync]): StoreSeam[Sync] = new StoreSeam[Sync]:
        override def readOnly: Boolean = true

        def open(path: Path)(using Frame): StoreSeam.Handle[Sync] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.openReadChannelUnscoped(path)).map { case (channel, releaseThunk) =>
                readChannelHandle(channel, releaseThunk)
            }

        def acquireLock(lockRoot: Path)(using Frame): SegmentStore.Lock < (Sync & Scope & Abort[JournalStorageError]) =
            lockHandle(fs, lockRoot)

        def syncDir(dir: Path)(using Frame): Unit < Sync = ()

        override def exists(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path))

        override def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.isDirectory(path))

        override def mkDir(path: Path)(using Frame): Unit < Sync = ()

        override def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.list(path))

        override def readMarker(path: Path)(using Frame): Maybe[Span[Byte]] < (Sync & Abort[JournalStorageError]) =
            mapFileError(fs.exists(path)).map { found =>
                if !found then Absent
                else mapFileError(fs.readBytes(path)).map(Present(_))
            }

        override def writeMarker(path: Path, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[JournalStorageError]) =
            Abort.fail(JournalStorageError(s"Cannot write marker file '$path' through a read-only filesystem", Absent))
    end readOnly

    private def lockHandle(fs: FileSystem.Read[Sync], lockRoot: Path)(using
        Frame
    ): SegmentStore.Lock < (Sync & Scope & Abort[JournalStorageError]) =
        Abort.run[FileSystemException](fs.tryLock(lockRoot / "LOCK", Path.LockMode.Exclusive)).map {
            case Result.Success(Present(lock)) =>
                new SegmentStore.Lock:
                    def release()(using AllowUnsafe): Unit =
                        Sync.Unsafe.evalOrThrow(Abort.run[FileLockException](lock.release(lock.ownership)).map(_.getOrThrow))
            case Result.Success(Absent) =>
                Abort.fail(JournalStorageError(s"Lock unavailable for '${lockRoot / "LOCK"}'", Absent))
            case Result.Failure(error) =>
                Abort.fail(JournalStorageError(s"FileSystem operation failed: ${error.getMessage}", Present(error)))
            case Result.Panic(error) => throw error
        }

    private def channelHandle(channel: Path.ReadWriteChannel[Sync], releaseThunk: () => Unit < Sync): StoreSeam.Handle[Sync] =
        new StoreSeam.Handle[Sync]:
            def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Sync =
                mapChannelError(channel.readAt(pos, len)).map(_.toArray)
            def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Sync =
                mapChannelError(channel.writeAt(pos, Span.from(bytes)))
            def sync()(using Frame): Unit < Sync               = mapChannelError(channel.sync(metadata = false))
            def truncate(size: Long)(using Frame): Unit < Sync = mapChannelError(channel.truncate(size))
            def size()(using Frame): Long < Sync               = mapChannelError(channel.size)
            def close()(using Frame): Unit < Sync              = releaseThunk()
    end channelHandle

    private def readChannelHandle(channel: Path.ReadChannel[Sync], releaseThunk: () => Unit < Sync): StoreSeam.Handle[Sync] =
        new StoreSeam.Handle[Sync]:
            def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Sync =
                mapChannelError(channel.readAt(pos, len)).map(_.toArray)
            def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Sync = readOnlyFailure()
            def sync()(using Frame): Unit < Sync                                 = ()
            def truncate(size: Long)(using Frame): Unit < Sync                   = readOnlyFailure()
            def size()(using Frame): Long < Sync                                 = mapChannelError(channel.size)
            def close()(using Frame): Unit < Sync                                = releaseThunk()
    end readChannelHandle

    private def readOnlyFailure[A]()(using Frame): A < Sync =
        Sync.defer(throw new UnsupportedOperationException("read-only filesystem channel"))

    private def mapChannelError[A](v: A < (Sync & Abort[FileSystemException]))(using Frame): A < Sync =
        Abort.run[FileSystemException](v).map:
            case Result.Success(a)  => a
            case Result.Failure(fe) => throw fe
            case Result.Panic(e)    => throw e

    // Converts a FileSystem-level Abort[FileSystemException] into this seam's own Abort[JournalStorageError]
    // channel, used by every directory/marker bookkeeping override above.
    private def mapFileError[A](v: A < (Sync & Abort[FileSystemException]))(using Frame): A < (Sync & Abort[JournalStorageError]) =
        Abort.run[FileSystemException](v).map:
            case Result.Success(a)  => a
            case Result.Failure(fe) => Abort.fail(JournalStorageError(s"FileSystem operation failed: ${fe.getMessage}", Present(fe)))
            case Result.Panic(e)    => throw e

end FileSystemStoreSeam
