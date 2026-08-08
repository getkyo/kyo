package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.Platform

private[kyo] object HostFileSystem:

    def apply(): FileSystem.Write[Sync] = new HostFileSystem

    // Fires inside the claim node in tryLock, once the OS lock is held and recorded and before
    // tryLock returns. Default no-op; private[kyo] so tests in this package can reach it, and
    // single-writer, meaning one test sets and clears it at a time.
    //
    // The position is the whole point. An interrupt requested here is delivered at the next
    // safepoint, which is after the acquisition has produced the lock, and that is the only
    // instant at which registering the finalizer before the claim differs observably from
    // registering it afterwards. Without an injection point here a test can only interrupt from
    // outside, where the interrupt lands before the claim is ever made and every ordering looks
    // alike.
    private[kyo] var afterClaimHook: () => Unit = () => ()

    // The window a pending reservation waits on is one channel open plus one platform lock call, so
    // it clears in well under a millisecond. The budget is generous against that rather than tuned:
    // its job is to terminate at all when an entry is never going to resolve, not to time the window.
    private val pendingLockRetries: Int         = 100
    private val pendingLockRetryDelay: Duration = 1.milli

    /** Determines case sensitivity by asking the volume rather than the operating system.
      *
      * The two are not the same question. macOS default volumes are case-insensitive while the
      * platform is not Windows, so inferring from the operating system reports the wrong policy on
      * every such machine, and the value feeds `list(path, glob)`.
      *
      * Probed against the filesystem's own temporary directory, so a host instance answers for its
      * own volume rather than for the process temp volume. The result is cached per instance: it is
      * a property of the volume, not of the call.
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

    final class HostFileSystem extends FileSystem.Write[Sync]:

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
            FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
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
        private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (
            Path.ReadWriteChannel[Sync],
            () => Unit < Sync
        ) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadWriteChannelRaw(open))).map(raw =>
                (readWriteChannelFrom(path, raw), () => Sync.Unsafe.defer(raw.close()))
            )

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
        ): Maybe[Path.Lock] < (Sync & Async & Scope & Abort[FileLockException]) =
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
                        // A shared claim can arrive while another shared claim is between reserving
                        // its registry entry and installing the platform lock behind it. The two are
                        // compatible, so refusing there denies a lock the contract grants; the entry
                        // simply has nothing to share yet. The backend reports that as pending and
                        // this retries, since it clears without anyone releasing anything.
                        //
                        // Bounded on purpose. An acquisition interrupted after it reserves but before
                        // it can withdraw leaves an entry nothing else clears, and an unbounded retry
                        // would turn a permanent denial into a permanent hang. Exhausting the budget
                        // reports the claim unavailable, which is what this returned before.
                        //
                        // The attempt returns a Result rather than branching after the node: the
                        // claim and its recording have to stay in one node with no safepoint between
                        // them, so the decision to retry has to leave the node as a value.
                        def attempt(remaining: Int): Path.Lock < (Sync & Async & Abort[FileLockException]) =
                            // Unsafe: the raw claim and the record of it are plain statements in one
                            // node, so no interrupt can observe a claim the finalizer cannot see.
                            Sync.Unsafe.defer {
                                path.unsafe.lockAttempt(mode) match
                                    case Path.LockAttempt.Acquired(raw) =>
                                        val lock = lockFrom(path, raw, mode, AtomicInt.Unsafe.init(0).safe)
                                        holder.set(Present(lock))
                                        afterClaimHook()
                                        Result.succeed(Present(lock))
                                    case Path.LockAttempt.Pending       => Result.succeed(Absent)
                                    case Path.LockAttempt.Failed(error) => error
                            }.map {
                                case Result.Success(Present(lock)) => lock
                                case Result.Success(Absent) =>
                                    if remaining <= 0 then Abort.fail(FileLockUnavailableException(path))
                                    else Async.sleep(pendingLockRetryDelay).andThen(attempt(remaining - 1))
                                case failed => Abort.get(failed.map(_ => null.asInstanceOf[Path.Lock]))
                            }
                        attempt(pendingLockRetries)
                    }
                }.map(Maybe(_))
            Abort.recover[FileLockUnavailableException](_ => Absent)(acquire)
        end tryLock

        def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
            Frame
        ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
            FileSystem.awaitLock(path, wait)(tryLock(path, mode))
    end HostFileSystem
end HostFileSystem
