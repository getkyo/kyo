package kyo

import java.nio.charset.Charset
import kyo.internal.dolt.DoltVfsNode
import kyo.internal.dolt.DoltVfsStore
import scala.annotation.tailrec

/** A filesystem whose storage is a version-controlled database.
  *
  * A file is a row, so ordinary [[Path]] code runs unchanged against a tree that can be branched, committed, diffed
  * and merged with the same [[Dolt]] operations any other table gets. It is built on [[Dolt]] rather than
  * on either engine, so it runs against a Dolt server or a local DoltLite file, and reads and writes land on whichever
  * branch is checked out: [[Dolt.onBranch]] scopes filesystem work exactly as it scopes queries.
  *
  * Advisory locks are held in memory per filesystem instance, not in the database, so two clients against one database
  * do not see each other's locks.
  *
  * A write handle abandoned without finishing has its partial entry removed on the next filesystem operation or at
  * this filesystem's scope exit rather than the instant it is closed: `Path.WriteHandle.close` is synchronous and
  * takes no effect, so there is nowhere to run the removal. See [[pendingRemovals]].
  */
final class DoltFileSystem private (
    private val store: DoltVfsStore,
    private val locks: AtomicRef.Unsafe[Map[String, DoltFileSystem.Claim]],
    private val pendingRemovals: AtomicRef.Unsafe[Chunk[Path]]
) extends FileSystem.Write[Async]:

    import DoltFileSystem.*
    import DoltFileSystemSchema.Kind

    // --- Shared helpers ---

    private def now(using Frame): Long < Sync = Clock.now.map(_.toDuration.toMillis)

    /** Runs `body` after clearing any entry a closed-but-unfinished write handle left behind. Every operation that
      * observes or changes the tree goes through this, which is what bounds how long an abandoned partial entry stays
      * visible.
      */
    private def drained[A, S](body: => A < S)(using Frame): A < (S & Async) =
        Sync.Unsafe.defer(pendingRemovals.getAndSet(Chunk.empty)).map { pending =>
            if pending.isEmpty then body
            else
                Abort.run[FileIOException](Kyo.foreachDiscard(pending)(store.removeNode)).andThen(body)
        }

    private def required(path: Path)(using Frame): DoltVfsNode < (Async & Abort[FileReadException]) =
        drained(store.node(path)).map {
            case Present(node) => node
            case Absent        => Abort.fail(FileNotFoundException(path))
        }

    private def requiredFile(path: Path)(using Frame): DoltVfsNode < (Async & Abort[FileReadException]) =
        resolved(path).map { resolvedPath =>
            required(resolvedPath).map { node =>
                if node.isDirectory then Abort.fail(FileIsADirectoryException(path)) else node
            }
        }

    /** Follows symbolic links on `path` and on each of its ancestors, to the node the name finally denotes. Bounded by
      * [[LinkDepth]]: a link pointing at itself is representable, and without the bound reading one hangs instead of
      * failing.
      */
    private def resolved(path: Path)(using Frame): Path < (Async & Abort[FileReadException]) =
        def loop(current: Path, depth: Int): Path < (Async & Abort[FileReadException]) =
            if depth > LinkDepth then Abort.fail(FileIOException(path, FileSystemOperation.RealPath, TooManyLinks(path)))
            else
                store.node(current).map {
                    case Present(node) if node.isSymlink =>
                        node.target match
                            case Present(target) => loop(DoltVfsStore.path(target), depth + 1)
                            case Absent          => current
                    case _ =>
                        current.parent match
                            case Present(parent) =>
                                loop(parent, depth + 1).map { resolvedParent =>
                                    if resolvedParent == parent then current
                                    else current.name.fold(resolvedParent)(resolvedParent / _)
                                }
                            case Absent => current
                }
        drained(loop(path, 0))
    end resolved

    private def parents(path: Path, create: Boolean)(using Frame): Unit < (Async & Abort[FileNotFoundException | FileIOException]) =
        path.parent match
            case Absent          => ()
            case Present(parent) =>
                store.node(parent).map {
                    case Present(_) => ()
                    case Absent     =>
                        if !create then Abort.fail(FileNotFoundException(parent))
                        else
                            parents(parent, create).andThen {
                                now.map(at => store.putNode(DoltVfsNode(parent, Kind.Directory, Absent, 0L, at)))
                            }
                }

    // --- Inspection ---

    /** Sensitive: a stored path is a primary key, and both engines compare those keys byte for byte. */
    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Async = Glob.CaseSensitivity.Sensitive

    def exists(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException]) = exists(path, followLinks = true)

    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Async & Abort[FileReadException]) =
        if followLinks then
            Abort.run[FileReadException](resolved(path).map(store.node)).map {
                case Result.Success(node) => node.isDefined
                case _                    => false
            }
        else drained(store.node(path)).map(_.isDefined)

    def isDirectory(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException]) =
        resolved(path).map(store.node).map(_.exists(_.isDirectory))

    def isRegularFile(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException]) =
        resolved(path).map(store.node).map(_.exists(_.isFile))

    /** Answered from the node at `path` itself, never through the link, unlike [[isDirectory]] and [[isRegularFile]]. */
    def isSymbolicLink(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException]) =
        drained(store.node(path)).map(_.exists(_.isSymlink))

    def realPath(path: Path)(using
        Frame
    ): Path < (Async & Abort[FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException]) =
        Abort.recover[FileReadException] {
            case e: (FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException) => Abort.fail(e)
            case e => Abort.fail(FileIOException(path, FileSystemOperation.RealPath, e))
        } {
            resolved(path).map { target =>
                store.node(target).map {
                    case Present(_) => target
                    case Absent     => Abort.fail(FileNotFoundException(path))
                }
            }
        }

    def size(path: Path)(using Frame): Long < (Async & Abort[FileReadException]) = requiredFile(path).map(_.sizeBytes)

    def stat(path: Path)(using Frame): Path.PathStat < (Async & Abort[FileReadException]) =
        resolved(path).map(required).map(node => Path.PathStat(node.modifiedMs, node.sizeBytes))

    // --- Reads ---

    def readBytes(path: Path)(using Frame): Span[Byte] < (Async & Abort[FileReadException]) =
        resolved(path).map { target =>
            requiredFile(target).map(node => store.readAll(target, node.sizeBytes))
        }

    def read(path: Path)(using Frame): String < (Async & Abort[FileReadException]) = read(path, Charset.forName("UTF-8"))

    def read(path: Path, charset: Charset)(using Frame): String < (Async & Abort[FileReadException]) =
        readBytes(path).map(bytes => new String(bytes.toArray, charset))

    def readLines(path: Path)(using Frame): Chunk[String] < (Async & Abort[FileReadException]) =
        readLines(path, Charset.forName("UTF-8"))

    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Async & Abort[FileReadException]) =
        read(path, charset).map(splitLines)

    def list(path: Path)(using Frame): Chunk[Path] < (Async & Abort[FileReadException | FileStructureException]) =
        resolved(path).map { target =>
            required(target).map { node =>
                if !node.isDirectory then Abort.fail(FileNotADirectoryException(path))
                else store.children(target).map(_.map(_.path))
            }
        }

    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (Async & Abort[FileReadException | FileStructureException]) =
        list(path).map(_.filter(child => child.name.exists(name => glob.matches(name, caseSensitivity))))

    // --- Read handles ---

    /** Opens a read handle over the file's content, read in full at open time. [[Path.ReadHandle.readChunk]] is
      * synchronous and takes no effect, so there is no later point at which a query could run; opening a read handle
      * here costs the whole file, where the host's costs a descriptor.
      */
    def openRead(path: Path)(using Frame): Path.ReadHandle < (Async & Abort[FileReadException]) =
        readBytes(path).map(bytes => Sync.Unsafe.defer(new MaterializedReadHandle(bytes.toArray)))

    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Async & Abort[FileReadException]) =
        readLines(path, charset).map(lines => Sync.Unsafe.defer(new MaterializedLineReadHandle(lines)))

    /** Opens a walk over the subtree, materialized at open time for the same reason as [[openRead]]. `followLinks` is
      * ignored: a link's target may sit outside the walked subtree, and following it would let a walk escape the root
      * it was given.
      */
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
        Frame
    ): Path.WalkHandle < (Async & Abort[FileReadException | FileStructureException]) =
        resolved(path).map { target =>
            required(target).andThen {
                drained(store.descendants(target)).map { nodes =>
                    val depth = target.parts.size
                    val kept  = nodes.map(_.path).filter(p => p.parts.size - depth <= maxDepth)
                    Sync.Unsafe.defer(new MaterializedWalkHandle(kept))
                }
            }
        }

    // --- Writes ---

    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Async & Abort[FileWriteException]) =
        writeBytes(path, Span.fromUnsafe(value.getBytes("UTF-8")), options)

    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileWriteException]) =
        drained {
            parents(path, options.createFolders).andThen {
                now.map { at =>
                    store.node(path).map { existing =>
                        if existing.exists(_.isDirectory) then Abort.fail(FileIsADirectoryException(path))
                        else
                            store.putNode(DoltVfsNode(path, Kind.File, Absent, value.size.toLong, at))
                                .andThen(store.writeAll(path, value, at))
                    }
                }
            }
        }

    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileWriteException]) =
        write(path, value.mkString("\n"), options)

    def append(path: Path, value: String, options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        appendBytes(path, Span.fromUnsafe(value.getBytes("UTF-8")), options)

    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        drained {
            parents(path, options.createFolders).andThen {
                now.map { at =>
                    store.node(path).map {
                        case Present(node) if node.isDirectory => Abort.fail(FileIsADirectoryException(path))
                        case Present(node)                     => store.writeAt(path, node.sizeBytes, node.sizeBytes, value, at)
                        case Absent                            =>
                            store.putNode(DoltVfsNode(path, Kind.File, Absent, value.size.toLong, at))
                                .andThen(store.writeAll(path, value, at))
                    }
                }
            }
        }

    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)

    def truncate(path: Path, size: Long)(using Frame): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        requiredFile(path).map(node => now.map(at => drained(store.truncate(path, node.sizeBytes, size, at))))

    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        required(path).map(node => drained(store.touch(path, node.sizeBytes, epochMs)))

    // --- Write handles ---

    /** Opens a write handle. Content reaches the database through [[writeChunk]] and [[writeString]], the only points
      * in the handle's life that carry an effect.
      */
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (Async & Abort[FileReadException | FileWriteException]) =
        drained {
            parents(path, options.createFolders).andThen {
                now.map { at =>
                    store.node(path).map {
                        case Present(node) if node.isDirectory => Abort.fail(FileIsADirectoryException(path))
                        case Present(node) if append           => Sync.Unsafe.defer(newWriteHandle(path, node.sizeBytes))
                        case _                                 =>
                            store.putNode(DoltVfsNode(path, Kind.File, Absent, 0L, at))
                                .andThen(store.writeAll(path, Span.empty[Byte], at))
                                .andThen(Sync.Unsafe.defer(newWriteHandle(path, 0L)))
                    }
                }
            }
        }

    private def newWriteHandle(path: Path, at: Long)(using AllowUnsafe): DoltWriteHandle =
        new DoltWriteHandle(path, AtomicLong.Unsafe.init(at), pendingRemovals)

    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
        handle match
            case h: DoltWriteHandle => appendThrough(h, Span.from(chunk))
            case other              => Abort.fail(FileIOException(Path(), FileSystemOperation.Write, ForeignHandle(other)))

    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using
        Frame
    ): Unit < (Async & Abort[FileWriteException]) =
        handle match
            case h: DoltWriteHandle => appendThrough(h, Span.fromUnsafe(value.getBytes(charset)))
            case other              => Abort.fail(FileIOException(Path(), FileSystemOperation.Write, ForeignHandle(other)))

    private def appendThrough(handle: DoltWriteHandle, bytes: Span[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
        if bytes.isEmpty then ()
        else
            Sync.Unsafe.defer(handle.claimRange(bytes.size.toLong)).map { at =>
                now.map(stamp => drained(store.writeAt(handle.path, at, at, bytes, stamp)))
            }

    // --- Structure ---

    def mkDir(path: Path)(using Frame): Unit < (Async & Abort[FileReadException | FileStructureException]) =
        drained {
            store.node(path).map {
                case Present(node) if node.isDirectory => ()
                case Present(_)                        => Abort.fail(FileAlreadyExistsException(path))
                case Absent                            =>
                    parents(path, create = true).andThen {
                        now.map(at => store.putNode(DoltVfsNode(path, Kind.Directory, Absent, 0L, at)))
                    }
            }
        }

    def mkFile(path: Path)(using Frame): Unit < (Async & Abort[FileWriteException | FileStructureException]) =
        drained {
            store.node(path).map {
                case Present(_) => Abort.fail(FileAlreadyExistsException(path))
                case Absent     =>
                    parents(path, create = true).andThen {
                        now.map(at => store.putNode(DoltVfsNode(path, Kind.File, Absent, 0L, at)))
                    }
            }
        }

    /** Creates a symbolic link at `link` denoting `target`. Outside `FileSystem.Write`, which has no link-creation
      * operation, so this is reachable only through [[DoltFileSystem]] itself.
      */
    def symlink(link: Path, target: Path)(using Frame): Unit < (Async & Abort[FileWriteException | FileStructureException]) =
        drained {
            store.node(link).map {
                case Present(_) => Abort.fail(FileAlreadyExistsException(link))
                case Absent     =>
                    parents(link, create = true).andThen {
                        now.map(at => store.putNode(DoltVfsNode(link, Kind.Symlink, Present(DoltVfsStore.key(target)), 0L, at)))
                    }
            }
        }

    def move(from: Path, to: Path, options: Path.MoveOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
        drained {
            required(from).andThen {
                store.node(to).map { target =>
                    val cleared =
                        if target.isEmpty then Kyo.lift(())
                        else if options.replace == Path.Replace.Never then Abort.fail(FileAlreadyExistsException(to))
                        else removeAll(to)
                    cleared.andThen {
                        parents(to, options.createFolders).andThen {
                            now.map(at => store.descendants(from).map(nodes => store.relocate(from, to, nodes, at)))
                        }
                    }
                }
            }
        }

    def copy(from: Path, to: Path, options: Path.CopyOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
        drained {
            val source = if options.followLinks then resolved(from) else Kyo.lift(from)
            source.map { origin =>
                required(origin).andThen {
                    store.node(to).map { target =>
                        val cleared =
                            if target.isEmpty then Kyo.lift(())
                            else if options.replace == Path.Replace.Never then Abort.fail(FileAlreadyExistsException(to))
                            else removeAll(to)
                        cleared.andThen {
                            parents(to, options.createFolders).andThen {
                                now.map { at =>
                                    store.descendants(origin).map { nodes =>
                                        Kyo.foreachDiscard(nodes) { node =>
                                            val moved = DoltVfsStore.rebase(node.path, origin, to)
                                            val stamp = if options.copyAttributes then node.modifiedMs else at
                                            store.putNode(node.copy(path = moved, modifiedMs = stamp)).andThen {
                                                if !node.isFile then ()
                                                else store.readAll(node.path, node.sizeBytes).map(store.writeAll(moved, _, stamp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    def remove(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException | FileStructureException]) =
        drained {
            store.node(path).map {
                case Absent        => false
                case Present(node) =>
                    val guard =
                        if !node.isDirectory then Kyo.lift(())
                        else
                            store.children(path).map(kids => if kids.nonEmpty then Abort.fail(FileDirectoryNotEmptyException(path)) else ())
                    guard.andThen(store.removeNode(path)).andThen(true)
            }
        }

    def removeExisting(path: Path)(using Frame): Unit < (Async & Abort[FileReadException | FileStructureException]) =
        remove(path).map(removed => if removed then () else Abort.fail(FileNotFoundException(path)))

    def removeAll(path: Path)(using Frame): Unit < (Async & Abort[FileReadException | FileStructureException]) =
        drained {
            store.descendants(path).map { nodes =>
                // Deepest first, so a directory is never removed while a child still refers to it.
                Kyo.foreachDiscard(nodes.sortBy(-_.path.parts.size))(node => store.removeNode(node.path))
            }
        }

    // --- Temporaries ---

    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Async & Abort[FileStructureException]) =
        uniqueTemp(prefix, "").map { path =>
            Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Create, e)))(mkDir(path))
                .andThen(Sync.Unsafe.defer(new DoltTempDirHandle(path, pendingRemovals)))
        }

    def temp(prefix: String, suffix: String)(using Frame): Path.TempFileHandle < (Async & Abort[FileStructureException]) =
        uniqueTemp(prefix, suffix).map { path =>
            Abort.recover[FileWriteException](e => Abort.fail(FileIOException(path, FileSystemOperation.Create, e)))(mkFile(path))
                .andThen(Sync.Unsafe.defer(new DoltTempFileHandle(path, pendingRemovals)))
        }

    /** A fresh temporary name under a fixed root. The counter is what keeps two calls with the same prefix distinct;
      * without it the first handle's removal destroys the second caller's directory.
      */
    private def uniqueTemp(prefix: String, suffix: String)(using Frame): Path < (Async & Abort[FileStructureException]) =
        Sync.Unsafe.defer(tempCounter.incrementAndGet()).map { n =>
            now.map(at => Path(TempRoot, s"$prefix-$at-$n$suffix"))
        }

    // --- Channels ---

    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Async] < (Async & Scope & Abort[FileReadException]) =
        openReadChannelUnscoped(path).map { (channel, release) =>
            Scope.ensure(release()).andThen(channel)
        }

    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[Async], () => Unit < (Sync & Async)) < (Async & Abort[FileReadException]) =
        resolved(path).map { target =>
            requiredFile(target).andThen {
                Sync.Unsafe.defer(new DoltChannel(target, store, AtomicBoolean.Unsafe.init(true))).map { channel =>
                    (channel, () => channel.close)
                }
            }
        }

    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[Async] < (Async & Scope & Abort[FileWriteException | FileStructureException]) =
        Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Channel, e))) {
            openReadWriteChannel(path, open)
        }

    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[Async] < (Async & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        openReadWriteChannelUnscoped(path, open).map { (channel, release) =>
            Scope.ensure(release()).andThen(channel)
        }

    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.ReadWriteChannel[Async], () => Unit < (Sync & Async)) < (
        Async & Abort[FileReadException | FileWriteException | FileStructureException]
    ) =
        drained {
            store.node(path).map { existing =>
                val prepared =
                    (open, existing) match
                        case (FileSystem.WriteOpen.Existing, Absent)      => Abort.fail(FileNotFoundException(path))
                        case (FileSystem.WriteOpen.CreateNew, Present(_)) => Abort.fail(FileAlreadyExistsException(path))
                        case (_, Present(node)) if node.isDirectory       => Abort.fail(FileIsADirectoryException(path))
                        case (_, Present(_))                              => Kyo.lift(())
                        case _                                            =>
                            parents(path, create = true).andThen {
                                now.map(at =>
                                    store.putNode(DoltVfsNode(path, Kind.File, Absent, 0L, at))
                                        .andThen(store.writeAll(path, Span.empty[Byte], at))
                                )
                            }
                prepared.andThen {
                    Sync.Unsafe.defer(new DoltChannel(path, store, AtomicBoolean.Unsafe.init(true))).map { channel =>
                        (channel, () => channel.close)
                    }
                }
            }
        }

    // --- Locks ---

    /** Attempts an advisory lock, held in memory rather than in the tree. `sentinelSuffix` is accepted and ignored: it
      * names the sidecar file a host backend creates, and there is no sidecar here.
      */
    def tryLock(path: Path, mode: Path.LockMode, sentinelSuffix: String = Path.defaultLockSuffix)(using
        Frame
    ): Maybe[Path.Lock] < (Async & Sync & Scope & Abort[FileReadException | FileLockException]) =
        Sync.Unsafe.defer {
            val owner = Path.LockOwnership.fresh()
            // Ascribed so the branches unify as one Maybe, which is what makes the match below exhaustive.
            val claimed: Maybe[DoltLock] = if claim(path, mode, owner) then Present(new DoltLock(path, mode, owner, this)) else Absent
            claimed
        }.map {
            case Present(lock) =>
                Scope.ensure(Sync.Unsafe.defer(discard(surrender(path, lock.ownership)))).andThen(Present(lock: Path.Lock))
            case Absent => Absent
        }

    def lock(
        path: Path,
        mode: Path.LockMode,
        wait: Path.LockWait = Path.LockWait.UntilAvailable,
        sentinelSuffix: String = Path.defaultLockSuffix
    )(using Frame): Path.Lock < (Async & Scope & Abort[FileReadException | FileLockException]) =
        FileSystem.awaitLock[Async](path, wait)(tryLock(path, mode, sentinelSuffix))

    private[kyo] def claim(path: Path, mode: Path.LockMode, owner: Path.LockOwnership)(using AllowUnsafe): Boolean =
        val slot                     = DoltVfsStore.key(path)
        @tailrec def loop(): Boolean =
            val current = locks.get()
            current.get(slot) match
                case None =>
                    if locks.compareAndSet(current, current.updated(slot, Claim(mode, Set(owner)))) then true else loop()
                case Some(held) if held.mode == Path.LockMode.Shared && mode == Path.LockMode.Shared =>
                    if locks.compareAndSet(current, current.updated(slot, held.copy(owners = held.owners + owner))) then true
                    else loop()
                case Some(_) => false
            end match
        end loop
        loop()
    end claim

    /** Drops one owner's claim, answering whether the owner still held it. */
    private[kyo] def surrender(path: Path, owner: Path.LockOwnership)(using AllowUnsafe): Boolean =
        val slot                     = DoltVfsStore.key(path)
        @tailrec def loop(): Boolean =
            val current = locks.get()
            current.get(slot) match
                case Some(held) if held.owners.contains(owner) =>
                    val remaining = held.owners - owner
                    val next = if remaining.isEmpty then current.removed(slot) else current.updated(slot, held.copy(owners = remaining))
                    if locks.compareAndSet(current, next) then true else loop()
                case _ => false
            end match
        end loop
        loop()
    end surrender

    private[kyo] def holds(path: Path, owner: Path.LockOwnership)(using AllowUnsafe): Boolean =
        locks.get().get(DoltVfsStore.key(path)).exists(_.owners.contains(owner))

end DoltFileSystem

object DoltFileSystem:

    /** The directory temporary entries are created under. A plain directory in the tree, so it is branched and
      * committed like anything else.
      */
    private[kyo] val TempRoot: String = "tmp"

    /** How many links a resolution may follow before it is called a cycle. Matches the usual host limit. */
    private[kyo] val LinkDepth: Int = 40

    private[kyo] val tempCounter: AtomicLong.Unsafe =
        // Unsafe: a process-wide counter for temporary names, which needs no effect and no scope
        import AllowUnsafe.embrace.danger
        AtomicLong.Unsafe.init(0L)
    end tempCounter

    /** Who holds a path's advisory lock, and in which mode. Shared claims accumulate owners; an exclusive one has one. */
    final private[kyo] case class Claim(mode: Path.LockMode, owners: Set[Path.LockOwnership])

    final private[kyo] case class TooManyLinks(path: Path)
        extends RuntimeException(s"Too many symbolic links resolving $path")

    final private[kyo] case class ForeignHandle(handle: Path.WriteHandle)
        extends RuntimeException("Write handle was not created by this filesystem")

    /** Splits stored content into lines on either line ending, dropping a single trailing terminator. The `-1` limit
      * keeps trailing empty strings, without which a file ending in one newline and one ending in three read alike.
      */
    private[kyo] def splitLines(value: String): Chunk[String] =
        if value.isEmpty then Chunk.empty
        else
            val trimmed = if value.endsWith("\r\n") then value.dropRight(2) else if value.endsWith("\n") then value.dropRight(1) else value
            Chunk.from(trimmed.split("\r\n|\n", -1).toSeq)

    /** Opens a filesystem over `client`, creating its tables when they are absent. Idempotent, so opening one over a
      * database that already holds a tree is safe.
      */
    def init(client: Dolt)(using Frame): DoltFileSystem < (Async & Abort[FileIOException]) =
        val store = new DoltVfsStore(client)
        store.install.andThen {
            Sync.Unsafe.defer {
                new DoltFileSystem(store, AtomicRef.Unsafe.init(Map.empty), AtomicRef.Unsafe.init(Chunk.empty))
            }
        }
    end init

    /** Opens a filesystem and runs `program` against it, so ordinary [[Path]] code inside reaches the database.
      *
      * `Path.runWith` rather than `FileSystem.let`: the latter only selects the backend, leaving the `PathWrite` that
      * every [[Path]] operation suspends undischarged in the caller's effect row.
      */
    def let[A, S](client: Dolt)(program: A < (PathWrite & S))(using
        Frame
    ): A < (S & Async & Abort[FileSystemException]) =
        init(client).map(files => Path.runWith(files)(program))

end DoltFileSystem
