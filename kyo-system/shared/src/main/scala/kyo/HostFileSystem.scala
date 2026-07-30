package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.Platform

private[kyo] object HostFileSystem:

    def apply(): FileSystem.Write[Sync] & FileSystem.Watch[Sync] = new HostFileSystem

    /** Determines case sensitivity by asking the volume rather than the operating system.
      *
      * The two are not the same question. macOS default volumes are case-insensitive while the
      * platform is not Windows, so inferring from the operating system reports the wrong policy on
      * every such machine, and the value feeds `list(path, glob)` and every watcher's glob.
      *
      * Probed against the filesystem's own temporary directory, so a root-confined instance answers
      * for its own volume rather than for the process temp volume. The result is cached per
      * instance: it is a property of the volume, not of the call.
      *
      * A probe that cannot run falls back to the platform inference, which is right for Windows and
      * the best available guess elsewhere.
      */
    private def probeCaseSensitivity(fileSystem: FileSystem.Write[Sync])(using Frame): Glob.CaseSensitivity < Sync =
        val inferred = if Platform.isWindows then Glob.CaseSensitivity.Insensitive else Glob.CaseSensitivity.Sensitive
        Abort.run[FileSystemException] {
            fileSystem.tempDir("kyo-case-probe").map { handle =>
                val probe = handle.path / "KyoCaseProbe"
                fileSystem.write(probe, "probe", Path.WriteOptions()).andThen {
                    fileSystem.exists(handle.path / "kyocaseprobe").map { foundLowercased =>
                        // Unsafe: removes the probe directory created just above
                        Sync.Unsafe.defer(handle.remove()).andThen {
                            if foundLowercased then Glob.CaseSensitivity.Insensitive else Glob.CaseSensitivity.Sensitive
                        }
                    }
                }
            }
        }.map {
            case Result.Success(sensitivity) => sensitivity
            case _                           => inferred
        }
    end probeCaseSensitivity

    def rootConfined(root: Path)(using
        Frame
    ): (FileSystem.Write[Sync] & FileSystem.Watch[Sync]) < (Sync & Abort[FileSystemException]) =
        // Unsafe: resolves the confinement root's real path once at construction
        Sync.Unsafe.defer(Abort.get(root.unsafe.realPath())).map(rootReal => new RootConfinedHostFileSystem(rootReal))

    final class HostFileSystem extends FileSystem.Write[Sync], FileSystem.Watch[Sync]:

        private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
            new Path.TempFileHandle:
                def path: Path                        = temporary
                def remove()(using AllowUnsafe): Unit = discard(temporary.unsafe.remove())
        // Memo cell for the one-off volume probe below. A plain atomic rather than Kyo's: the cell
        // has to exist at construction, outside any Sync context, and it carries no effect of its
        // own. A racing pair of first calls both probe and agree, since they are asking the volume
        // the same question.
        private val caseSensitivity = new java.util.concurrent.atomic.AtomicReference[Glob.CaseSensitivity]()

        def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync =
            Sync.defer(caseSensitivity.get()).map { cached =>
                if cached ne null then cached
                else
                    probeCaseSensitivity(this).map { probed =>
                        Sync.defer {
                            caseSensitivity.compareAndSet(null, probed)
                            caseSensitivity.get()
                        }
                    }
            }

        def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.exists into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.exists()))
        def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.exists(followLinks) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.exists(followLinks)))
        def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.isDirectory into the safe tier
            Sync.Unsafe.defer(path.unsafe.isDirectory())
        def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.isRegularFile into the safe tier
            Sync.Unsafe.defer(path.unsafe.isRegularFile())
        def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.isSymbolicLink into the safe tier
            Sync.Unsafe.defer(path.unsafe.isSymbolicLink())
        def realPath(path: Path)(using
            Frame
        ): Path < (Sync & Abort[
            FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ]) =
            // Unsafe: bridges Path.Unsafe.realPath; the Result maps to Abort[FileSystemException]
            Sync.Unsafe.defer(Abort.get(path.unsafe.realPath()))
        def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.read into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read()))
        def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.read(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read(charset)))
        def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.readBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readBytes()))
        def readLines(path: Path)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.readLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines()))
        def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.readLines(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines(charset)))
        def size(path: Path)(using Frame): Long < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.size into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.size()))
        def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.stat into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.stat()))
        override private[kyo] def stableIdentity(path: Path)(using Frame): Maybe[String] < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges the platform file identity used only for polling move correlation
            Sync.Unsafe.defer(Abort.get(path.unsafe.stableIdentity()))
        def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.openRead into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openRead()))
        def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
            // Unsafe: bridges Path.Unsafe.openReadLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadLines(charset)))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
            Frame
        ): Path.WalkHandle < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.openWalk into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWalk(maxDepth, followLinks)))
        def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.write into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.write(value, options)))
        def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.writeBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeBytes(value, options)))
        def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.writeLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeLines(value, options)))
        def append(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.append into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.append(value, options)))
        def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.appendBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendBytes(value, options)))
        def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.appendLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendLines(value, options)))
        def truncate(path: Path, size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.truncate into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.truncate(size)))
        def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.setLastModified into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.setLastModified(epochMs)))
        def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
            Frame
        ): Path.WriteHandle < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.openWrite into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append, options)))
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeBytes(chunk)))
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeString(value, charset)))
        def mkDir(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.mkDir into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkDir()))
        def mkFile(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.mkFile into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkFile()))
        def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.list into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list()))
        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.list(glob) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list(glob, caseSensitivity)))
        def move(
            from: Path,
            to: Path,
            options: Path.MoveOptions
        )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.move into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.move(to, options)))
        def copy(
            from: Path,
            to: Path,
            options: Path.CopyOptions
        )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.copy into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.copy(to, options)))
        def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.remove into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.remove()))
        def removeExisting(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.removeExisting into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeExisting()))
        def removeAll(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            // Unsafe: bridges Path.Unsafe.removeAll into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeAll()))
        def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
            Path.tempDirUnscoped(prefix).map { dir =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the created temp dir at Scope exit
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
        private def invalid(path: Path, operation: FileSystemOperation, detail: String)(using Frame): FileIOException =
            FileIOException(path, operation, new IOException(detail))

        private def readChannelFrom(path: Path, raw: Path.RawChannel): Path.ReadChannel[Sync] =
            new Path.ReadChannel[Sync]:
                def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
                    if position < 0L || length < 0 then Abort.fail(invalid(path, FileSystemOperation.Read, "negative channel read bounds"))
                    else Sync.Unsafe.defer(Abort.get(raw.readAt(position, length))).map(Span.from)
                def size(using Frame): Long < (Sync & Abort[FileReadException]) =
                    Sync.Unsafe.defer(Abort.get(raw.size()))

        private def writeChannelFrom(path: Path, raw: Path.RawChannel): Path.WriteChannel[Sync] =
            new Path.WriteChannel[Sync]:
                def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                    if position < 0L then Abort.fail(invalid(path, FileSystemOperation.Write, "negative channel write position"))
                    else Sync.Unsafe.defer(Abort.get(raw.writeAt(position, bytes.toArray)))
                def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                    Sync.Unsafe.defer(Abort.get(raw.sync(metadata)))
                def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                    if size < 0L then Abort.fail(invalid(path, FileSystemOperation.Write, "negative channel truncate size"))
                    else Sync.Unsafe.defer(Abort.get(raw.truncate(size)))

        private def readWriteChannelFrom(path: Path, raw: Path.RawChannel): Path.ReadWriteChannel[Sync] =
            new Path.ReadWriteChannel[Sync]:
                private val read  = readChannelFrom(path, raw)
                private val write = writeChannelFrom(path, raw)
                def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
                    read.readAt(position, length)
                def size(using Frame): Long < (Sync & Abort[FileReadException]) = read.size
                def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                    write.writeAt(position, bytes)
                def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) = write.sync(metadata)
                def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException])    = write.truncate(size)

        def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
            Scope.acquireRelease(Sync.Unsafe.defer(Abort.get(path.unsafe.openReadChannelRaw())))(raw => Sync.Unsafe.defer(raw.close()))
                .map(readChannelFrom(path, _))
        def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.WriteChannel[Sync] < (Sync & Scope & Abort[FileWriteException | FileStructureException]) =
            Scope.acquireRelease(Sync.Unsafe.defer(Abort.get(path.unsafe.openWriteChannelRaw(open))))(raw => Sync.Unsafe.defer(raw.close()))
                .map(writeChannelFrom(path, _))
        def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.ReadWriteChannel[Sync] < (Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
            Scope.acquireRelease(Sync.Unsafe.defer(Abort.get(path.unsafe.openReadWriteChannelRaw(open))))(raw =>
                Sync.Unsafe.defer(raw.close())
            )
                .map(readWriteChannelFrom(path, _))
        private[kyo] def openReadChannelUnscoped(path: Path)(using
            Frame
        ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadChannelRaw())).map(raw =>
                (readChannelFrom(path, raw), () => Sync.Unsafe.defer(raw.close()))
            )
        private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
            (Sync & Abort[FileWriteException | FileStructureException]) =
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWriteChannelRaw(open))).map { raw =>
                val close = new Path.ChannelCloseHandle:
                    def close()(using AllowUnsafe): Unit = raw.close()
                (writeChannelFrom(path, raw), () => Sync.Unsafe.defer(raw.close()), close)
            }
        private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (
            Path.ReadWriteChannel[Sync],
            () => Unit < Sync
        ) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadWriteChannelRaw(open))).map(raw =>
                (readWriteChannelFrom(path, raw), () => Sync.Unsafe.defer(raw.close()))
            )

        def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: bridges Path.Unsafe.syncDirectory into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.syncDirectory()))

        def siblingTemporary(target: Path)(using
            Frame
        ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
            FileSystem.siblingTemporary[Any](this, target)

        def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Any](this, target, bytes)

        private[kyo] def lockFrom(path: Path, raw: Path.RawLock, grantedMode: Path.LockMode, state: AtomicInt): Path.Lock =
            val owner = Path.LockOwnership.fresh()
            new Path.Lock:
                def mode: Path.LockMode           = grantedMode
                def ownership: Path.LockOwnership = owner

                def check(using Frame): Unit < (Sync & Abort[FileLockException]) =
                    state.get.map {
                        case 0 => Sync.Unsafe.defer(Abort.get(raw.check()))
                        case _ => Abort.fail(FileLockOwnershipLostException(path))
                    }

                def release(candidate: Path.LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException]) =
                    if !Path.LockOwnership.same(owner, candidate) then Abort.fail(FileLockOwnershipLostException(path))
                    else
                        // Unsafe: claiming the release and performing it happen in one node, so no
                        // interrupt can land between them.
                        //
                        // The previous form moved through an intermediate "releasing" state across
                        // several suspensions. An interrupt in that window left the state there
                        // permanently: the lock was neither released nor releasable, and a concurrent
                        // releaser looped on it with no yield and no exit, so a Scope finalizer
                        // waiting on that release never returned. Claiming straight to "released"
                        // removes the state that could be stranded and the loop that spun on it.
                        //
                        // Release stays single-shot and idempotent: whoever wins the compare-and-set
                        // performs it, and a later or concurrent caller finds it already claimed and
                        // returns. A failed release restores the held state so it can be retried.
                        Sync.Unsafe.defer {
                            if state.unsafe.compareAndSet(0, 2) then
                                val outcome = raw.release()
                                if !outcome.isSuccess then state.unsafe.set(0)
                                outcome
                            else Result.succeed(())
                        }.map(Abort.get(_))
            end new
        end lockFrom

        def tryLock(path: Path, mode: Path.LockMode)(using
            Frame
        ): Maybe[Path.Lock] < (Sync & Scope & Abort[FileLockException]) =
            // Deliberately not Scope.acquireRelease. That runs acquire, then registers the finalizer
            // in a continuation, and an interrupt landing between the two leaves the OS lock held and
            // the registry entry installed with nothing in existence able to release either: the path
            // is unacquirable for the life of the process and a descriptor leaks.
            //
            // Registering first inverts the problem. The finalizer exists before anything is claimed
            // and releases whatever it finds recorded, so an interrupt before the claim finds nothing
            // to do, and one after it finds a lock the finalizer already owns. The claim and its
            // recording sit in a single unsafe node with no safepoint between them, which is the only
            // interval that would otherwise be exposed.
            //
            // The general form of this belongs in Scope.acquireRelease, which has the same window for
            // every caller in kyo. Widening that signature to carry Async.mask is a kyo-core change
            // with a far wider blast radius than this defect, so it is left for its own work.
            val acquire =
                Sync.defer(new java.util.concurrent.atomic.AtomicReference[Maybe[Path.Lock]](Absent)).map { holder =>
                    Scope.ensure {
                        Sync.Unsafe.defer(holder.getAndSet(Absent)).map {
                            case Present(lock) => lock.release(lock.ownership)
                            case Absent        => ()
                        }
                    }.andThen {
                        // Unsafe: the raw claim and the record of it are plain statements in one node,
                        // so no interrupt can observe a claim that the finalizer cannot see.
                        Sync.Unsafe.defer {
                            path.unsafe.lock(mode) match
                                case Result.Success(raw) =>
                                    val lock = lockFrom(path, raw, mode, AtomicInt.Unsafe.init(0).safe)
                                    holder.set(Present(lock))
                                    Abort.get(Result.succeed(lock))
                                case failed => Abort.get(failed.map(_ => null.asInstanceOf[Path.Lock]))
                        }
                    }
                }.map(Maybe(_))
            Abort.recover[FileLockUnavailableException](_ => Absent)(acquire)
        end tryLock

        def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
            Frame
        ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
            FileSystem.awaitLock(path, wait)(tryLock(path, mode))

        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            PathWatch.polling(this, path, options)
    end HostFileSystem

    final class RootConfinedHostFileSystem(rootReal: Path) extends FileSystem.Write[Sync], FileSystem.Watch[Sync]:

        private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
            host.tempFileHandle(temporary)
        private val host = new HostFileSystem
        // Memo cell for the one-off volume probe below. A plain atomic rather than Kyo's: the cell
        // has to exist at construction, outside any Sync context, and it carries no effect of its
        // own. A racing pair of first calls both probe and agree, since they are asking the volume
        // the same question.
        private val caseSensitivity = new java.util.concurrent.atomic.AtomicReference[Glob.CaseSensitivity]()

        def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync =
            Sync.defer(caseSensitivity.get()).map { cached =>
                if cached ne null then cached
                else
                    probeCaseSensitivity(this).map { probed =>
                        Sync.defer {
                            caseSensitivity.compareAndSet(null, probed)
                            caseSensitivity.get()
                        }
                    }
            }

        private def confined(path: Path, operation: FileSystemOperation)(using
            Frame
        ): Path < (Sync & Abort[
            FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ]) =
            // Unsafe: probes target existence to choose between realpath and nearest-parent checks
            Sync.Unsafe.defer(Abort.get(path.unsafe.exists())).map {
                case true  => Sync.Unsafe.defer(Abort.get(path.unsafe.realPath())).map(real => check(path, real, operation))
                case false => nearestExistingParent(path).map(real => check(path, real, operation)).andThen(path)
            }
        private def check(path: Path, real: Path, operation: FileSystemOperation)(using Frame): Path < Abort[FileOutsideRootException] =
            if real.parts.take(rootReal.parts.size) == rootReal.parts then real
            else Abort.fail(FileOutsideRootException(rootReal, path, operation))
        private def nearestExistingParent(path: Path)(using
            Frame
        ): Path < (Sync & Abort[FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException]) =
            path.parent match
                case Absent     => Abort.fail(FileNotFoundException(path))
                case Present(p) =>
                    // Unsafe: probes parent existence to walk to the nearest real ancestor
                    Sync.Unsafe.defer(Abort.get(p.unsafe.exists())).map {
                        case true  => Sync.Unsafe.defer(Abort.get(p.unsafe.realPath()))
                        case false => nearestExistingParent(p)
                    }

        private def confinedEntry(path: Path, operation: FileSystemOperation)(using
            Frame
        ): Path < (Sync & Abort[
            FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ]) =
            path.parent match
                case Absent => check(path, path, operation)
                case Present(parent) =>
                    Sync.Unsafe.defer(Abort.get(parent.unsafe.realPath())).map(real => check(path, real, operation)).andThen(path)

        def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Exists).andThen(host.exists(path))
        def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Exists).andThen(host.exists(path, followLinks))
        def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Inspect).andThen(host.isDirectory(path))
        def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Inspect).andThen(host.isRegularFile(path))
        def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            confinedEntry(path, FileSystemOperation.Inspect).andThen(host.isSymbolicLink(path))
        def realPath(path: Path)(using
            Frame
        ): Path < (Sync & Abort[
            FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ]) =
            confined(path, FileSystemOperation.RealPath).andThen(host.realPath(path))
        def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.read(path))
        def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.read(path, charset))
        def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.readBytes(path))
        def readLines(path: Path)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.readLines(path))
        def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.readLines(path, charset))
        def size(path: Path)(using Frame): Long < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Inspect).andThen(host.size(path))
        def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Inspect).andThen(host.stat(path))
        def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.openRead(path))
        def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Read).andThen(host.openReadLines(path, charset))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
            Frame
        ): Path.WalkHandle < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Walk).andThen(host.openWalk(path, maxDepth, followLinks))
        def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.write(path, value, options))
        def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.writeBytes(path, value, options))
        def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.writeLines(path, value, options))
        def append(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.append(path, value, options))
        def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.appendBytes(path, value, options))
        def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.appendLines(path, value, options))
        def truncate(path: Path, size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.truncate(path, size))
        def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.setLastModified(path, epochMs))
        def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
            Frame
        ): Path.WriteHandle < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.Write).andThen(host.openWrite(path, append, options))
        // writeChunk/writeString carry only a handle (no path), so confinement is not applicable; forward directly.
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            host.writeChunk(handle, chunk)
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            host.writeString(handle, value, charset)
        def mkDir(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Create).andThen(host.mkDir(path))
        def mkFile(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Create).andThen(host.mkFile(path))
        def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.List).andThen(host.list(path))
        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.List).andThen(host.list(path, glob, caseSensitivity))
        def move(
            from: Path,
            to: Path,
            options: Path.MoveOptions
        )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(from, FileSystemOperation.Move)
                .andThen(confined(to, FileSystemOperation.Move))
                .andThen(host.move(from, to, options))
        def copy(
            from: Path,
            to: Path,
            options: Path.CopyOptions
        )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(from, FileSystemOperation.Copy)
                .andThen(confined(to, FileSystemOperation.Copy))
                .andThen(host.copy(from, to, options))
        def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Remove).andThen(host.remove(path))
        def removeExisting(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Remove).andThen(host.removeExisting(path))
        def removeAll(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
            confined(path, FileSystemOperation.Remove).andThen(host.removeAll(path))
        // tempDir creates inside rootReal, not in the OS temp dir. Creating within root keeps
        // staged paths confined: the overlay's commit protocol calls lower.move(stagingDir/eN.dat,
        // target) through this service; if stagingDir were in OS temp the confinement check on
        // the source path would fail. Creating in root also lets recoverFromDisk(root) scan for
        // kyo-commit-* dirs without cross-filesystem access. Uniqueness: nanoTime XOR identityHash
        // provides negligible collision probability for the expected number of concurrent commits.
        def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
            val uniqueSuffix =
                java.lang.Long.toHexString(java.lang.System.nanoTime() ^ java.lang.System.identityHashCode(this).toLong)
            val dir = rootReal / s"$prefix-$uniqueSuffix"
            host.mkDir(dir).map { _ =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the temp dir created within rootReal
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
        end tempDir
        def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openReadChannel(path))
        def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.WriteChannel[Sync] < (Sync & Scope & Abort[FileWriteException | FileStructureException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openWriteChannel(path, open))
        def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.ReadWriteChannel[Sync] < (Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openReadWriteChannel(path, open))
        private[kyo] def openReadChannelUnscoped(path: Path)(using
            Frame
        ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openReadChannelUnscoped(path))
        private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
            (Sync & Abort[FileWriteException | FileStructureException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openWriteChannelUnscoped(path, open))
        private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (
            Path.ReadWriteChannel[Sync],
            () => Unit < Sync
        ) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            confined(path, FileSystemOperation.Channel).andThen(host.openReadWriteChannelUnscoped(path, open))
        def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            confined(path, FileSystemOperation.SyncDirectory).andThen(host.syncDirectory(path))
        def siblingTemporary(target: Path)(using
            Frame
        ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
            FileSystem.siblingTemporary[Any](this, target)
        def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Any](this, target, bytes)
        def tryLock(path: Path, mode: Path.LockMode)(using
            Frame
        ): Maybe[Path.Lock] < (Sync & Scope & Abort[FileReadException | FileLockException]) =
            confined(path, FileSystemOperation.Lock).andThen(host.tryLock(path, mode))
        def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
            Frame
        ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
            confined(path, FileSystemOperation.Lock).andThen(host.lock(path, mode, wait))
        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            Abort.run[FileSystemException](confined(path, FileSystemOperation.Watch)).map {
                case Result.Success(_)                         => PathWatch.polling(this, path, options)
                case Result.Failure(error: FileWatchException) => Abort.fail(error)
                case Result.Failure(error)                     => Abort.fail(FileIOException(path, FileSystemOperation.Watch, error))
                case Result.Panic(error)                       => Abort.panic(error)
            }
    end RootConfinedHostFileSystem
end HostFileSystem
