package kyo

import java.nio.charset.Charset

/** Filesystem backend capabilities, effect-polymorphic in the backend effect `S`. [[Read]] exposes
  * inspection, content reads, channels, and locks. [[Write]] extends it with mutation and structure
  * operations. Each public operation records the applicable typed failure category in its effect
  * row and accepts a call-site [[Frame]].
  *
  * @see [[Path.runReadOnlyWith]] for installing a read capability
  * @see [[Path.runWith]] for installing a write capability
  */
object FileSystem:

    // Retry pacing for the waiting lock modes. Async.sleep, not Clock.sleep: the latter hands back
    // the timer Fiber rather than suspending on it, so discarding it would turn these retry loops
    // into busy spins that contend with the very lock holder they are waiting on.
    private val lockRetryDelay = 1.millis

    private[kyo] def awaitLock[S](path: Path, wait: Path.LockWait)(
        attempt: => Maybe[Path.Lock] < (S & Sync & Scope & Abort[FileReadException | FileLockException])
    )(using Frame): Path.Lock < (S & Async & Scope & Abort[FileReadException | FileLockException]) =
        def unavailable: Path.Lock < Abort[FileLockException] = Abort.fail(FileLockUnavailableException(path))

        def untilAvailable: Path.Lock < (S & Async & Scope & Abort[FileReadException | FileLockException]) =
            attempt.map {
                case Present(lock) => lock
                case Absent        => Async.sleep(lockRetryDelay).andThen(untilAvailable)
            }

        def until(deadline: Clock.Deadline, timeout: Duration)
            : Path.Lock < (S & Async & Scope & Abort[FileReadException | FileLockException]) =
            deadline.isOverdue.map {
                case true => Abort.fail(FileLockTimeoutException(path, timeout))
                case false =>
                    attempt.map {
                        case Present(lock) => lock
                        case Absent        => Async.sleep(lockRetryDelay).andThen(until(deadline, timeout))
                    }
            }

        wait match
            case Path.LockWait.Immediate =>
                attempt.map {
                    case Present(lock) => lock
                    case Absent        => unavailable
                }
            case Path.LockWait.UntilAvailable  => untilAvailable
            case Path.LockWait.Until(deadline) => deadline.timeLeft.map(until(deadline, _))
        end match
    end awaitLock

    abstract class Read[S]:
        def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < S

        // inspection
        def exists(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (S & Abort[FileReadException])
        def isDirectory(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def isRegularFile(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def isSymbolicLink(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def realPath(path: Path)(using
            Frame
        ): Path < (S & Abort[
            FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ])

        /** Resolves the longest existing prefix of `path` and re-appends the segments below it.
          *
          * Unlike [[realPath]] this does not fail when `path` is absent. A path that does not exist
          * yet still has a determined location: the links on the part of it that does exist fix
          * where it will land. A path with no existing ancestor resolves to itself.
          *
          * This exists for callers that need the canonical location of a path they are about to
          * create: raising on the common case of "the target does not exist yet" would make the
          * method unusable for exactly the callers who need it, so absence is answered rather than
          * raised.
          *
          * The default resolves the deepest ancestor [[realPath]] accepts.
          */
        def realPathPrefix(path: Path)(using
            Frame
        ): Path < (S & Abort[
            FileInvalidPathException | FileAccessDeniedException | FileIOException
        ]) =
            Abort.run[FileNotFoundException](realPath(path)).map {
                case Result.Success(resolved) => resolved
                case _ =>
                    (path.parent, path.name) match
                        case (Present(parent), Present(name)) => realPathPrefix(parent).map(resolved => resolved / name)
                        case _                                => path
            }

        // read
        def read(path: Path)(using Frame): String < (S & Abort[FileReadException])
        def read(path: Path, charset: Charset)(using Frame): String < (S & Abort[FileReadException])
        def readBytes(path: Path)(using Frame): Span[Byte] < (S & Abort[FileReadException])
        def readLines(path: Path)(using Frame): Chunk[String] < (S & Abort[FileReadException])
        def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (S & Abort[FileReadException])
        def size(path: Path)(using Frame): Long < (S & Abort[FileReadException])
        def stat(path: Path)(using Frame): Path.PathStat < (S & Abort[FileReadException])
        private[kyo] def stableIdentity(path: Path)(using Frame): Maybe[String] < (S & Abort[FileReadException]) = Absent

        // read handles (internal handle types; back the streaming reads and walk)
        def openRead(path: Path)(using Frame): Path.ReadHandle < (S & Abort[FileReadException])
        def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (S & Abort[FileReadException])
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
            Frame
        ): Path.WalkHandle < (S & Abort[FileReadException | FileStructureException])
        def list(path: Path)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException])
        def list(path: Path, glob: Glob)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
            defaultCaseSensitivity.map(list(path, glob, _))
        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (S & Abort[FileReadException | FileStructureException])

        /** Opens `path` for positioned reads. The channel is closed when the current [[Scope]] exits. */
        def openReadChannel(path: Path)(using Frame): Path.ReadChannel[S] < (S & Scope & Abort[FileReadException])

        /** Attempts to acquire a scoped advisory lock without waiting for a conflicting holder.
          *
          * `Async` is in the row because "without waiting" is about conflicting holders, not about
          * suspension: a backend that merges same-process claims onto one platform lock has a span
          * in which a compatible claim is being taken but is not yet shareable, and answering during
          * it would deny a lock the contract grants. Waiting out that span is bounded and does not
          * depend on any holder releasing, unlike [[lock]], which waits for exactly that.
          */
        def tryLock(path: Path, mode: Path.LockMode)(using
            Frame
        ): Maybe[Path.Lock] < (S & Sync & Async & Scope & Abort[FileReadException | FileLockException])

        /** Acquires a scoped advisory lock according to `wait`. Waiting modes suspend the fiber. */
        def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait = Path.LockWait.UntilAvailable)(using
            Frame
        ): Path.Lock < (S & Async & Scope & Abort[FileReadException | FileLockException])

        private[kyo] def openReadChannelUnscoped(path: Path)(using
            Frame
        ): (Path.ReadChannel[S], () => Unit < (Sync & S)) < (S & Abort[FileReadException])
    end Read

    /** Optional filesystem tier for scoped change observation.
      *
      * Implementations register observation before returning a watcher,
      * so mutations made immediately after acquisition remain visible.
      * The returned watcher and its asynchronous stream are owned by the
      * surrounding [[Scope]]. Closing that scope releases backend resources.
      *
      * This tier is independent of [[Read]] and [[Write]]. A filesystem
      * advertises it only when it can provide the complete watch contract.
      */
    trait Watch[S]:
        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (S & Async & Scope & Abort[FileWatchException])
    end Watch

    abstract class Write[S] extends Read[S]:

        // write
        def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def append(path: Path, value: String, options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def truncate(path: Path, size: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException])
        def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException])

        // write handle
        def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
            Frame
        ): Path.WriteHandle < (S & Abort[FileReadException | FileWriteException])
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (S & Abort[FileWriteException])
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (S & Abort[FileWriteException])

        // directory / structure
        def mkDir(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])
        def mkFile(path: Path)(using Frame): Unit < (S & Abort[FileWriteException | FileStructureException])
        def move(
            from: Path,
            to: Path,
            options: Path.MoveOptions
        )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])
        def copy(
            from: Path,
            to: Path,
            options: Path.CopyOptions
        )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])
        def remove(path: Path)(using Frame): Boolean < (S & Abort[FileReadException | FileStructureException])
        def removeExisting(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])
        def removeAll(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])

        // scoped temp: vends a service-correct removal handle so cleanup runs through the creating service
        def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (S & Abort[FileStructureException])
        def temp(prefix: String, suffix: String)(using Frame): Path.TempFileHandle < (S & Abort[FileStructureException])

        /** Opens `path` for positioned writes according to `open`. The channel is closed when the
          * current [[Scope]] exits.
          */
        def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.WriteChannel[S] < (S & Scope & Abort[FileWriteException | FileStructureException])

        /** Opens `path` for positioned reads and writes according to `open`. The channel is closed
          * when the current [[Scope]] exits.
          */
        def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): Path.ReadWriteChannel[S] < (S & Scope & Abort[FileReadException | FileWriteException | FileStructureException])

        /** Synchronizes the directory entry state for `path`. Volatile backends may implement this
          * as a successful no-op, but persistent backends must not report success when the platform
          * cannot provide the requested guarantee.
          */
        def syncDirectory(path: Path)(using Frame): Unit < (S & Abort[FileWriteException])

        /** Reserves a create-new sibling file in `target`'s containing directory. The returned
          * service-owned handle supports scoped cleanup without exposing the file channel.
          */
        def siblingTemporary(target: Path)(using
            Frame
        ): Path.TempFileHandle < (S & Abort[FileWriteException | FileStructureException])

        private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle

        /** Replaces `target` with `bytes` using a synchronized sibling file and a required atomic
          * move. The file channel is released before the move and the containing directory is
          * synchronized only after the move succeeds.
          */
        def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])

        /** Acquires a channel WITHOUT a `Scope` wrap, pairing it with its own fork-free plain-`Sync`
          * release thunk instead of a collective `Scope`-managed release. `private[kyo]`: an internal
          * escape hatch for callers that need to release one vended resource independently of every
          * other resource this `FileSystem` has vended, never a public capability.
          */
        private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        )
            : (Path.WriteChannel[S], () => Unit < S, Path.ChannelCloseHandle) <
                (S & Abort[FileWriteException | FileStructureException])

        private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        )
            : (
                Path.ReadWriteChannel[S],
                () => Unit < (Sync & S)
            ) < (S & Abort[FileReadException | FileWriteException | FileStructureException])

    end Write

    private[kyo] def siblingTemporary[S](fileSystem: FileSystem.Write[S & Sync], target: Path)(using
        Frame
    ): Path.TempFileHandle < (S & Sync & Abort[FileWriteException | FileStructureException]) =
        val parent = target.parent.getOrElse(Path())
        val base   = target.name.getOrElse("target")
        def loop(attempt: Int): Path.TempFileHandle < (S & Sync & Abort[FileWriteException | FileStructureException]) =
            val temporary = parent / s".$base.kyo-temporary-$attempt"
            Abort.run[FileWriteException | FileStructureException](
                fileSystem.openWriteChannelUnscoped(temporary, FileSystem.WriteOpen.CreateNew)
            ).map {
                case Result.Success((_, release, close)) =>
                    val handle    = fileSystem.tempFileHandle(temporary)
                    var completed = false
                    Sync.ensure {
                        // Unsafe: interruption before ownership transfer closes and removes the reserved file.
                        // Cleanup panics are suppressed so both actions run and the primary failure is preserved.
                        Sync.Unsafe.defer {
                            if !completed then
                                try close.close()
                                catch case _: Throwable => ()
                                try handle.remove()
                                catch case _: Throwable => ()
                        }
                    } {
                        Abort.run[FileWriteException | FileStructureException](release()).map { result =>
                            completed = result.isSuccess
                            result
                        }
                    }.map {
                        case Result.Success(_)     => handle
                        case Result.Failure(error) => Abort.fail(error)
                    }
                case Result.Failure(_: FileAlreadyExistsException) => loop(attempt + 1)
                case Result.Failure(error)                         => Abort.fail(error)
            }
        end loop
        loop(0)
    end siblingTemporary

    private[kyo] def durableReplace[S](fileSystem: FileSystem.Write[S & Sync], target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (S & Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        val parent                        = target.parent.getOrElse(Path())
        val base                          = target.name.getOrElse("target")
        def candidate(attempt: Int): Path = parent / s".$base.kyo-durable-$attempt"
        def open(attempt: Int): (
            Path,
            Path.WriteChannel[S & Sync],
            () => Unit < (S & Sync),
            Path.ChannelCloseHandle
        ) < (S & Sync & Abort[FileWriteException | FileStructureException]) =
            val temporary = candidate(attempt)
            Abort.run[FileWriteException | FileStructureException](
                fileSystem.openWriteChannelUnscoped(temporary, FileSystem.WriteOpen.CreateNew)
            ).map {
                case Result.Success((channel, release, close))     => (temporary, channel, release, close)
                case Result.Failure(_: FileAlreadyExistsException) => open(attempt + 1)
                case Result.Failure(error)                         => Abort.fail(error)
            }
        end open
        open(0).map { (temporary, channel, release, close) =>
            val temporaryHandle = fileSystem.tempFileHandle(temporary)
            var channelClosed   = false
            var moved           = false
            val protocol = channel.writeAt(0L, bytes).andThen(channel.sync(metadata = true)).andThen {
                release().andThen {
                    channelClosed = true
                    fileSystem.move(
                        temporary,
                        target,
                        Path.MoveOptions(
                            replace = Path.Replace.Existing,
                            atomicity = Path.Atomicity.Required,
                            createFolders = false
                        )
                    ).andThen {
                        moved = true
                        fileSystem.syncDirectory(parent)
                    }
                }
            }
            Sync.ensure {
                // Unsafe: service-vended idempotent cleanup runs during cancellation finalization.
                // Cleanup panics are suppressed so both actions run and the primary failure is preserved.
                Sync.Unsafe.defer {
                    if !channelClosed then
                        try close.close()
                        catch case _: Throwable => ()
                    if !moved then
                        try temporaryHandle.remove()
                        catch case _: Throwable => ()
                }
            } {
                Abort.run[FileReadException | FileWriteException | FileStructureException](protocol)
            }.map {
                case Result.Success(_)     => ()
                case Result.Failure(error) => Abort.fail(error)
            }
        }
    end durableReplace

    private val local = Local.init[FileSystem.Write[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Write[Any]]
    )

    private val readLocal = Local.init[FileSystem.Read[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Read[Any]]
    )

    private val watchLocal = Local.init[FileSystem.Watch[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Watch[Any]]
    )

    /** Runs `value` with `fileSystem` selected as the backend used by [[Path.run]] and
      * [[Path.runReadOnly]]. The selection is inherited by child fibers and restored when the
      * dynamic scope exits.
      *
      * @tparam FS the backend's own effect, preserved in the result row
      */
    def let[A, S, FS](fileSystem: FileSystem.Write[FS])(value: A < S)(using Frame): A < (FS & S) =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)
        )

    /** Runs `value` with a coherent read, write, and watch backend selection. */
    @scala.annotation.targetName("letWatchable")
    def let[A, S, FS](fileSystem: FileSystem.Write[FS] & FileSystem.Watch[FS])(value: A < S)(using
        Frame
    ): A < (FS & S) =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(
                watchLocal.let(fileSystem.asInstanceOf[FileSystem.Watch[Any]])(value)
            )
        )

    private[kyo] def useErased[A, S](f: FileSystem.Write[Any] => A < S)(using Frame): A < S =
        local.use(f)

    private[kyo] def useReadErased[A, S](f: FileSystem.Read[Any] => A < S)(using Frame): A < S =
        readLocal.use(f)

    private[kyo] def useWatchErased[A, S](f: FileSystem.Watch[Any] => A < S)(using Frame): A < S =
        watchLocal.use(f)

    private[kyo] def letErased[A, S, FS](fileSystem: FileSystem.Write[FS])(value: A < S)(using Frame): A < S =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)
        )

    private[kyo] def letReadErased[A, S, FS](fileSystem: FileSystem.Read[FS])(value: A < S)(using Frame): A < S =
        readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)

    /** File existence policy for positioned write-channel acquisition.
      *
      * `Existing` requires an existing regular file. `Create` opens an existing file or creates
      * an absent one. `CreateNew` creates a new file and fails when the path already exists.
      * None of these policies truncates an existing file.
      *
      * The policy is separate from the channel capability: callers choose write-only or
      * read-write acquisition through distinct methods, and then choose how absence or prior
      * existence should be handled with this value.
      */
    enum WriteOpen derives CanEqual:
        case Existing
        case Create
        case CreateNew
    end WriteOpen

    /** Default host backend: delegates every op to [[Path.Unsafe]], translating the concrete
      * `Result[File*Exception, A]` into `Abort[FileSystemException]`, so it preserves current
      * `Path` behavior exactly.
      */
    def host: FileSystem.Write[Sync] & FileSystem.Watch[Sync] = HostFileSystem()

end FileSystem
