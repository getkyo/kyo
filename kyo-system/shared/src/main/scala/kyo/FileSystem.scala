package kyo

import java.nio.charset.Charset
import kyo.Path.WatchOptions

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
            FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ])

        /** Resolves the longest existing prefix of `path` and re-appends the segments below it.
          *
          * Unlike [[realPath]] this does not fail when `path` is absent. A path that does not exist
          * yet still has a determined location: the links on the part of it that does exist fix
          * where it will land. A path with no existing ancestor resolves to itself.
          *
          * Staged-write layers use this to key a path they are about to create, which is why absence
          * is not an error here. The absent case is the normal one for them, and it has to be
          * answered without raising: a staging layer stacked on the [[Path]] capability reaches its
          * lower through an effect suspension, and a failure raised on the far side of that
          * suspension surfaces at the capability handler rather than inside the layer, where no
          * local handler can intercept it.
          *
          * The default resolves the deepest ancestor [[realPath]] accepts. Backends whose failures
          * are not raised locally must override it.
          */
        def realPathPrefix(path: Path)(using
            Frame
        ): Path < (S & Abort[
            FileOutsideRootException | FileInvalidPathException | FileAccessDeniedException | FileIOException
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
        def tryLock(path: Path, mode: Path.LockMode, sentinelSuffix: String = Path.defaultLockSuffix)(using
            Frame
        ): Maybe[Path.Lock] < (S & Sync & Async & Scope & Abort[FileReadException | FileLockException])

        /** Acquires a scoped advisory lock according to `wait`. Waiting modes suspend the fiber. */
        def lock(
            path: Path,
            mode: Path.LockMode,
            wait: Path.LockWait = Path.LockWait.UntilAvailable,
            sentinelSuffix: String = Path.defaultLockSuffix
        )(using
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
    trait Watch:
        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Async & Scope & Abort[FileWatchException])
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

        /** Creates a staging directory whose contents are accessible only to its creating user.
          * Access restrictions must be effective at creation, before any file is written inside it.
          */
        private[kyo] def privateTempDir(prefix: String)(using Frame): Path.TempDirHandle < (S & Abort[FileStructureException])

        /** Creates a private staging directory or verifies an existing directory without changing its contents. */
        private[kyo] def privateMkDir(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])
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
        ): Path.TempFileHandle < (S & Abort[FileWriteException | FileStructureException]) =
            siblingTemporary(target, _ => ())

        /** Transfers the released temporary to its owner synchronously before returning it. */
        private[kyo] def siblingTemporary(target: Path, onAcquire: Path.TempFileHandle => Unit)(using
            Frame
        ): Path.TempFileHandle < (S & Abort[FileWriteException | FileStructureException])

        private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle

        /** Replaces `target` with `bytes` using a synchronized sibling file and a required atomic
          * move. The file channel is released before the move and the containing directory is
          * synchronized only after the move succeeds. Entries for newly created parent directories
          * are synchronized through the first existing ancestor as well.
          *
          * Host replacement preserves the existing regular file's owner, group, permission mode,
          * and access ACLs, captured during temporary acquisition. The temporary is private until
          * those permissions are restored and verified. Unsupported access controls or a failure to
          * capture or restore them abort before replacement. A missing target uses normal new-file
          * permissions. Concurrent external permission changes are not serialized by this operation.
          */
        def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])

        /** Whether final placement must acquire the destination parent's normal creation permissions.
          * Overlays follow the retained permission source through their lower rather than treating
          * a volatile staged entry as an existing host file.
          */
        private[kyo] def replacementNeedsDefaultPermissions(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])

        /** Seals a replacement at `target` with the permissions of its eventual destination.
          * Staging protocols use this before publishing a replayable file.
          */
        private[kyo] def durableReplacePreserving(target: Path, bytes: Span[Byte], permissionSource: Path)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])

        /** Acquires an independently released channel. The backend invokes `onAcquire` synchronously
          * with successful acquisition, before any effect continuation can be interrupted. The caller
          * installs cleanup before calling this method; the callback transfers ownership to it.
          * `preservePermissionsFrom` is used with CreateNew by replacement protocols. Host backends
          * securely create the file and restore the source's access controls on sync; backends
          * without access controls retain their ordinary create-new behavior.
          */
        private[kyo] def openWriteChannelUnscoped(
            path: Path,
            open: FileSystem.WriteOpen,
            onAcquire: Path.ChannelCloseHandle => Unit,
            preservePermissionsFrom: Maybe[Path] = Absent
        )(using
            Frame
        ): (Path.WriteChannel[S], () => Unit < (Sync & S), Path.ChannelCloseHandle) <
            (S & Abort[FileWriteException | FileStructureException])

        private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        )
            : (
                Path.ReadWriteChannel[S],
                () => Unit < (Sync & S)
            ) < (S & Abort[FileReadException | FileWriteException | FileStructureException])

    end Write

    private def reportCleanupFailure(log: Log, primary: Maybe[Throwable], message: String, error: Throwable)(using
        Frame,
        AllowUnsafe
    ): Unit =
        primary.foreach { cause =>
            if cause ne error then cause.addSuppressed(error)
        }
        try log.unsafe.error(message, error)
        catch
            case reporting: Throwable =>
                // Preserve reporting failures too, even when the configured logger cannot emit.
                if reporting ne error then error.addSuppressed(reporting)
        end try
        if primary.isEmpty then throw error
    end reportCleanupFailure

    private[kyo] def siblingTemporary[S](
        fileSystem: FileSystem.Write[S & Sync],
        target: Path,
        onAcquire: Path.TempFileHandle => Unit
    )(using Frame): Path.TempFileHandle < (S & Sync & Abort[FileWriteException | FileStructureException]) =
        Log.get.map { log =>
            Random.uuid.map { identifier =>
                val parent                                                          = target.parent.getOrElse(Path())
                var acquired: Maybe[(Path.ChannelCloseHandle, Path.TempFileHandle)] = Absent
                var primaryFailure: Maybe[Throwable]                                = Absent
                var completed                                                       = false
                def loop(attempt: Int): Path.TempFileHandle < (S & Sync & Abort[FileWriteException | FileStructureException]) =
                    val temporary = parent / s".kyo-temporary-$identifier-$attempt"
                    if target.name.exists(_.equalsIgnoreCase(s".kyo-temporary-$identifier-$attempt")) then loop(attempt + 1)
                    else
                        val handle = fileSystem.tempFileHandle(temporary)
                        Abort.run[FileWriteException | FileStructureException](
                            fileSystem.openWriteChannelUnscoped(
                                temporary,
                                FileSystem.WriteOpen.CreateNew,
                                close =>
                                    acquired = Present((close, handle))
                            )
                        ).map {
                            case Result.Success((_, release, _)) =>
                                release().map { _ =>
                                    onAcquire(handle)
                                    completed = true
                                    handle
                                }
                            case Result.Failure(_: FileAlreadyExistsException) => loop(attempt + 1)
                            case Result.Failure(error)                         => Abort.fail(error)
                            case Result.Panic(error)                           => Abort.panic(error)
                        }
                    end if
                end loop
                Sync.ensure { finalizerError =>
                    val primary = finalizerError.map(_.failureOrPanic).collect { case error: Throwable => error }.orElse(primaryFailure)
                    // Unsafe: cleanup owns acquisition before any effect continuation can be interrupted.
                    Sync.Unsafe.defer {
                        if !completed then
                            acquired.foreach { (close, handle) =>
                                try close.close()
                                catch
                                    case error: Throwable =>
                                        reportCleanupFailure(log, primary, s"Failed to close temporary ${handle.path}", error)
                                finally
                                    try handle.remove()
                                    catch
                                        case error: Throwable =>
                                            reportCleanupFailure(log, primary, s"Failed to remove temporary ${handle.path}", error)
                            }
                    }
                } {
                    Abort.run[FileWriteException | FileStructureException](loop(0)).map { result =>
                        primaryFailure = result.failureOrPanic
                        result
                    }
                }.map(Abort.get(_))
            }
        }
    end siblingTemporary

    private[kyo] def durableReplace[S](
        fileSystem: FileSystem.Write[S],
        target: Path,
        bytes: Span[Byte],
        permissionSource: Maybe[Path] = Absent
    )(using
        Frame
    ): Unit < (S & Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        Log.get.map { log =>
            Random.uuid.map { identifier =>
                val parent                                                          = target.parent.getOrElse(Path())
                var acquired: Maybe[(Path.ChannelCloseHandle, Path.TempFileHandle)] = Absent
                var primaryFailure: Maybe[Throwable]                                = Absent
                var channelClosed                                                   = false
                var moved                                                           = false
                def open(attempt: Int): (Path, Path.WriteChannel[S], () => Unit < (S & Sync)) <
                    (S & Sync & Abort[FileWriteException | FileStructureException]) =
                    val temporary = parent / s".kyo-durable-$identifier-$attempt"
                    if target.name.exists(_.equalsIgnoreCase(s".kyo-durable-$identifier-$attempt")) then open(attempt + 1)
                    else
                        val handle = fileSystem.tempFileHandle(temporary)
                        Abort.run[FileWriteException | FileStructureException](
                            fileSystem.openWriteChannelUnscoped(
                                temporary,
                                FileSystem.WriteOpen.CreateNew,
                                close =>
                                    acquired = Present((close, handle)),
                                preservePermissionsFrom = Present(permissionSource.getOrElse(target))
                            )
                        ).map {
                            case Result.Success((channel, release, _))         => (temporary, channel, release)
                            case Result.Failure(_: FileAlreadyExistsException) => open(attempt + 1)
                            case Result.Failure(error)                         => Abort.fail(error)
                            case Result.Panic(error)                           => Abort.panic(error)
                        }
                    end if
                end open
                def directoriesToSync(path: Path): Chunk[Path] < (S & Sync & Abort[FileReadException]) =
                    fileSystem.exists(path).map { exists =>
                        val ancestor = path.parent.getOrElse(Path())
                        if exists || ancestor == path then Chunk(path)
                        else directoriesToSync(ancestor).map(path +: _)
                    }
                Sync.ensure { finalizerError =>
                    val primary = finalizerError.map(_.failureOrPanic).collect { case error: Throwable => error }.orElse(primaryFailure)
                    // Unsafe: acquisition transfers ownership before returning the channel. Cleanup reports
                    // both failures without replacing the original operation failure or interruption.
                    Sync.Unsafe.defer {
                        acquired.foreach { (close, handle) =>
                            try
                                if !channelClosed then close.close()
                            catch
                                case error: Throwable =>
                                    reportCleanupFailure(log, primary, s"Failed to close temporary ${handle.path}", error)
                            finally
                                if !moved then
                                    try handle.remove()
                                    catch
                                        case error: Throwable =>
                                            reportCleanupFailure(log, primary, s"Failed to remove temporary ${handle.path}", error)
                        }
                    }
                } {
                    Abort.run[FileReadException | FileWriteException | FileStructureException] {
                        directoriesToSync(parent).map { directories =>
                            open(0).map { (temporary, channel, release) =>
                                channel.writeAt(0L, bytes).andThen(channel.sync(metadata = true)).andThen {
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
                                            Kyo.foreachDiscard(directories)(fileSystem.syncDirectory(_))
                                        }
                                    }
                                }
                            }
                        }
                    }.map { result =>
                        primaryFailure = result.failureOrPanic
                        result
                    }
                }.map(Abort.get(_))
            }
        }
    end durableReplace

    private val local = Local.init[FileSystem.Write[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Write[Any]]
    )

    private val readLocal = Local.init[FileSystem.Read[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Read[Any]]
    )

    private val watchLocal = Local.init[FileSystem.Watch](
        FileSystem.host
    )

    private val stagedWatchLocal = Local.init[Maybe[FileSystem.Watch]](Absent)

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
    def let[A, S, FS](fileSystem: FileSystem.Write[FS] & FileSystem.Watch)(value: A < S)(using
        Frame
    ): A < (FS & S) =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(
                watchLocal.let(fileSystem)(value)
            )
        )

    private[kyo] def useErased[A, S](f: FileSystem.Write[Any] => A < S)(using Frame): A < S =
        local.use(f)

    private[kyo] def useReadErased[A, S](f: FileSystem.Read[Any] => A < S)(using Frame): A < S =
        readLocal.use(f)

    private[kyo] def useWatchErased[A, S](f: FileSystem.Watch => A < S)(using Frame): A < S =
        watchLocal.use(f)

    private[kyo] def useStagedWatchErased[A, S](f: Maybe[FileSystem.Watch] => A < S)(using Frame): A < S =
        stagedWatchLocal.use(f)

    private[kyo] def letStagedWatchErased[A, S, FS](fileSystem: FileSystem.Watch)(value: A < S)(using Frame): A < S =
        stagedWatchLocal.let(Present(fileSystem.asInstanceOf[FileSystem.Watch]))(value)

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
      * `Path` behavior exactly. Its commit strategy is `Auto`.
      */
    def host: FileSystem.Write[Sync] & FileSystem.Watch = HostFileSystem()

    /** Root-confined host backend: resolves `root.realPath` at construction and rejects any
      * op whose canonical path (following every symlink) escapes it; writes to missing
      * entries validate the nearest existing parent. Prefix-only checking without realpath is
      * a security defect.
      */
    def host(root: Path)(using
        Frame
    ): (FileSystem.Write[Sync] & FileSystem.Watch) < (Sync & Abort[FileSystemException]) =
        HostFileSystem.rootConfined(root)

    /** In-memory backend: an immutable node tree keyed by `Path.parts` behind one
      * `AtomicRef`, advanced by an optimistic CAS loop. `isSymbolicLink` always returns
      * `false`. Its commit strategy is `Auto`.
      */
    def inMemory(using Frame): (FileSystem.Write[Sync] & FileSystem.Watch) < Sync = InMemoryFileSystem.init

    /** Copy-on-write overlay over `lower`: reads fall through, writes stage in an upper
      * layer, and an explicit commit replays that staged layer onto `lower`.
      *
      * Persistent lowers must support directory synchronization. Windows host commits fail at the
      * staging-parent barrier before publishing any staged target; reads and discarded writes still
      * work. Recovery of an existing intent can fail after partially applying its plan and retains
      * the intent for another recovery attempt.
      *
      * Does not scan `lower` for a commit a previous process left half-applied. A scan is a
      * directory walk, and this constructor has no root to walk: the staged-write scopes in
      * [[Path]] build an overlay over a forwarding service that has no root at all. Use
      * [[overlayRecovering]] when the lower is a real filesystem whose staging directories can
      * outlive the process that made them.
      */
    def overlay[S, S2](lower: FileSystem.Write[S])(using
        Frame,
        Isolate[S, Sync, S2]
    ): (StagedChanges[S & Sync & Abort[FileSystemException]] & Write[S & Sync]) < (Sync & Scope) =
        OverlayFileSystem.init(lower)

    /** Overlay over a synchronous backend, with asynchronous observation of its staged view. */
    @scala.annotation.targetName("overlaySync")
    def overlay(lower: FileSystem.Write[Sync])(using
        Frame
    ): (StagedChanges[Sync & Abort[FileSystemException]] & Write[Sync] & Watch) < (Sync & Scope) =
        OverlayFileSystem.initSync(lower)

    /** Copy-on-write overlay over `lower` that first replays any commit a previous process left
      * half-applied under `root`.
      *
      * The durable commit protocol writes each staged file into a staging directory, records the
      * plan in an intent log, applies it, and only then writes a marker declaring the commit
      * complete. A process that dies partway through leaves that staging directory behind, and
      * nothing in the next process's memory refers to it. This constructor is how the next process
      * finds it: the scan runs before the overlay is returned, so a caller never stages work on top
      * of a lower that still holds a half-applied commit.
      */
    def overlayRecovering[S, S2](lower: FileSystem.Write[S], root: Path)(using
        Frame,
        Isolate[S, Sync, S2]
    ): (StagedChanges[S & Sync & Abort[FileSystemException]] & Write[S & Sync]) <
        (S & Sync & Scope & Abort[FileSystemException]) =
        OverlayFileSystem.initRecovering(lower, root)

    /** Recovers a synchronous overlay and exposes asynchronous observation of its staged view. */
    @scala.annotation.targetName("overlayRecoveringSync")
    def overlayRecovering(lower: FileSystem.Write[Sync], root: Path)(using
        Frame
    ): (StagedChanges[Sync & Abort[FileSystemException]] & Write[Sync] & Watch) <
        (Sync & Scope & Abort[FileSystemException]) =
        OverlayFileSystem.initSync(lower).map(overlay => overlay.recoverFromDisk(root).andThen(overlay))

    /** Read-only view over a zip/jar archive: entries are files and entry-path prefixes are
      * directories. The returned value has no mutation, channel, or lock surface. Its commit
      * strategy is `Auto`: there is nothing to stage, every read is served directly
      * from the archive index built at construction. Backed by a uniform pure-Scala codec
      * (`kyo.internal.ZipArchive`/`ZipInflate`), identical on every platform: no `java.util.zip`
      * anywhere.
      */
    def zipReadOnly(archive: Path)(using
        Frame
    ): FileSystem.Read[Sync] < (Sync & Scope & Abort[FileReadException | FileStructureException]) =
        ZipReadOnlyFileSystem.init(archive)

    /** Writable zip rewrite: reads serve from `archive`'s entries as
      * they stood when first observed (or from nothing, when `archive` does not yet exist),
      * writes stage in an in-memory upper, and [[StagedChanges.commit]] rewrites the whole archive
      * with the staged entries applied, atomically moved into place. There are no in-place
      * random-access writes into a compressed entry: unlike [[overlay]], a [[StagedChanges.commit]]
      * here never validates a read-set against a live lower and never raises `CommitConflict`;
      * every commit method rewrites the whole archive unconditionally, uniformly STORED
      * (uncompressed) on every platform via `kyo.internal.ZipArchive.write`.
      */
    def zip(archive: Path)(using
        Frame
    ): (StagedChanges[Sync & Abort[FileSystemException]] & Write[Sync]) < (Sync & Scope) =
        ZipRewriteFileSystem.init(archive)

    type Conflict = kyo.Conflict
    val Conflict: kyo.Conflict.type = kyo.Conflict

    type Resolution = kyo.Resolution
    val Resolution: kyo.Resolution.type = kyo.Resolution

    /** One-shot control over an isolated set of writes. */
    trait StagedChanges[S]:
        def commit(using Frame): Unit < (S & Abort[CommitConflict])
        def commitWith(resolve: FileSystem.Conflict => FileSystem.Resolution)(using Frame): Unit < (S & Abort[CommitConflict])
        def discard(using Frame): Unit < (S & Abort[FileSystem.StagedChanges.TerminalState])
    end StagedChanges

    object StagedChanges:
        sealed abstract class TerminalState(message: String)(using Frame)
            extends CommitConflict(Chunk.empty, message)

        final case class AlreadyTerminated(action: String)(using Frame)
            extends TerminalState(s"Staged changes already terminated before $action")
    end StagedChanges
end FileSystem
