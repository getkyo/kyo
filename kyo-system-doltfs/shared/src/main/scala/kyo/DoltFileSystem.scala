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

    /** `path` with the links in its parent followed, but not a link at `path` itself: the entry an operation on `path`
      * acts on. Creating, removing, moving, and linking act on that entry, as they do on a host.
      */
    private def entry(path: Path)(using Frame): Path < (Async & Abort[FileReadException]) =
        path.parent match
            case Absent          => path
            case Present(parent) =>
                resolved(parent).map { resolvedParent =>
                    if resolvedParent == parent then path else path.name.fold(resolvedParent)(resolvedParent / _)
                }

    /** Every read failure is also a write failure, so a lookup's failure passes through a write unchanged. */
    private def writing[A, S](v: A < (S & Abort[FileReadException]))(using Frame): A < (S & Abort[FileWriteException]) =
        Abort.recover[FileReadException] { case e: FileWriteException => Abort.fail(e) }(v)

    /** The missing parents of `path`, outermost first, or the failure naming `subject` when an existing parent is not a
      * directory. Only reads, so a caller can decide before it writes anything.
      */
    private def missingParents(subject: Path, path: Path, operation: FileSystemOperation)(using
        Frame
    ): Result[FileIOException, Chunk[Path]] < (Async & Abort[FileIOException]) =
        path.parent match
            case Absent          => Result.succeed(Chunk.empty)
            case Present(parent) =>
                store.node(parent).map {
                    case Present(node) if node.isDirectory => Result.succeed(Chunk.empty)
                    case Present(_)                        => Result.fail(FileIOException(subject, operation, NotADirectory(parent)))
                    case Absent                            => missingParents(subject, parent, operation).map(_.map(_.append(parent)))
                }

    /** Creates the missing parents of `path` when `create` allows, failing as the host does: a missing parent without it
      * is not found, and an existing parent that is not a directory refuses the entry.
      */
    private def parents(path: Path, create: Boolean, operation: FileSystemOperation)(using
        Frame
    ): Unit < (Async & Abort[FileNotFoundException | FileIOException]) =
        missingParents(path, path, operation).map {
            case Result.Success(missing) =>
                if missing.isEmpty then ()
                else if !create then Abort.fail(FileNotFoundException(missing.last))
                else now.map(at => createDirectories(missing, at))
            case Result.Failure(refused) => Abort.fail(refused)
            case Result.Panic(error)     => Abort.panic(error)
        }

    /** Creates directories by insert rather than replacement, so a directory another writer created meanwhile is kept. */
    private def createDirectories(paths: Chunk[Path], at: Long)(using Frame): Unit < (Async & Abort[FileIOException]) =
        Kyo.foreachDiscard(paths)(path => store.insertNode(DoltVfsNode(path, Kind.Directory, Absent, 0L, at)).unit)

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
            writing(resolved(path)).map { target =>
                parents(target, options.createFolders, FileSystemOperation.Write).andThen {
                    now.map { at =>
                        store.node(target).map { existing =>
                            if existing.exists(_.isDirectory) then Abort.fail(FileIsADirectoryException(path))
                            else
                                store.putNode(DoltVfsNode(target, Kind.File, Absent, value.size.toLong, at))
                                    .andThen(store.writeAll(target, value, at))
                        }
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
            resolved(path).map { target =>
                parents(target, options.createFolders, FileSystemOperation.Write).andThen {
                    now.map { at =>
                        store.node(target).map {
                            case Present(node) if node.isDirectory => Abort.fail(FileIsADirectoryException(path))
                            case Present(node)                     => store.writeAt(target, node.sizeBytes, node.sizeBytes, value, at)
                            case Absent                            =>
                                store.putNode(DoltVfsNode(target, Kind.File, Absent, value.size.toLong, at))
                                    .andThen(store.writeAll(target, value, at))
                        }
                    }
                }
            }
        }

    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)

    def truncate(path: Path, size: Long)(using Frame): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        resolved(path).map { target =>
            requiredFile(target).map(node => now.map(at => drained(store.truncate(target, node.sizeBytes, size, at))))
        }

    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Async & Abort[FileReadException | FileWriteException]) =
        resolved(path).map(target => required(target).map(node => drained(store.touch(target, node.sizeBytes, epochMs))))

    // --- Write handles ---

    /** Opens a write handle. Content reaches the database through [[writeChunk]] and [[writeString]], the only points
      * in the handle's life that carry an effect.
      */
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (Async & Abort[FileReadException | FileWriteException]) =
        drained {
            resolved(path).map { target =>
                parents(target, options.createFolders, FileSystemOperation.Write).andThen {
                    now.map { at =>
                        store.node(target).map {
                            case Present(node) if node.isDirectory => Abort.fail(FileIsADirectoryException(path))
                            case Present(node) if append           => Sync.Unsafe.defer(newWriteHandle(target, node.sizeBytes))
                            case _                                 =>
                                store.putNode(DoltVfsNode(target, Kind.File, Absent, 0L, at))
                                    .andThen(store.writeAll(target, Span.empty[Byte], at))
                                    .andThen(Sync.Unsafe.defer(newWriteHandle(target, 0L)))
                        }
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
            entry(path).map { directory =>
                store.node(directory).map {
                    case Present(node) if node.isDirectory => ()
                    case Present(_)                        => Abort.fail(FileAlreadyExistsException(path))
                    case Absent                            =>
                        parents(directory, create = true, FileSystemOperation.Create).andThen {
                            now.map(at => store.insertNode(DoltVfsNode(directory, Kind.Directory, Absent, 0L, at))).map { created =>
                                // Another writer created the entry first: a directory is what was asked for, anything else is not.
                                if created then ()
                                else
                                    store.node(directory).map { node =>
                                        if node.exists(_.isDirectory) then () else Abort.fail(FileAlreadyExistsException(path))
                                    }
                            }
                        }
                }
            }
        }

    def mkFile(path: Path)(using Frame): Unit < (Async & Abort[FileWriteException | FileStructureException]) =
        drained {
            writing(entry(path)).map { file =>
                created(path, file, DoltVfsNode(file, Kind.File, Absent, 0L, _), FileSystemOperation.Create)
            }
        }

    /** Creates a symbolic link at `link` denoting `target`. Outside `FileSystem.Write`, which has no link-creation
      * operation, so this is reachable only through [[DoltFileSystem]] itself.
      */
    def symlink(link: Path, target: Path)(using Frame): Unit < (Async & Abort[FileWriteException | FileStructureException]) =
        drained {
            writing(entry(link)).map { placed =>
                created(
                    link,
                    placed,
                    DoltVfsNode(placed, Kind.Symlink, Present(DoltVfsStore.key(target)), 0L, _),
                    FileSystemOperation.Create
                )
            }
        }

    /** Creates the entry `node` describes at `placed` exclusively: one insert keyed by the path, so of several concurrent
      * creators exactly one succeeds and every other is refused with [[FileAlreadyExistsException]] naming `path`.
      */
    private def created(path: Path, placed: Path, node: Long => DoltVfsNode, operation: FileSystemOperation)(using
        Frame
    ): Unit < (Async & Abort[FileNotFoundException | FileIOException | FileAlreadyExistsException]) =
        store.node(placed).map {
            case Present(_) => Abort.fail(FileAlreadyExistsException(path))
            case Absent     =>
                parents(placed, create = true, operation).andThen {
                    now.map(at => store.insertNode(node(at))).map(won => if won then () else Abort.fail(FileAlreadyExistsException(path)))
                }
        }

    /** Moves `from` to `to` in one transaction, so a reader sees the previous entry at `to` or the moved one and never
      * neither, which satisfies [[Path.Atomicity.Required]]. Every check runs before the first write and reports its
      * failure as a value, since the pool discards a session a failure crosses.
      */
    def move(from: Path, to: Path, options: Path.MoveOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
        drained {
            entry(from).map { source =>
                entry(to).map { destination =>
                    // Moving an entry onto itself changes nothing, as on a host; replacing the target first would delete it.
                    if source == destination then required(source).unit
                    else
                        store.atomically(to, FileSystemOperation.Move) {
                            placement(from, to, source, destination, options.replace, options.createFolders, FileSystemOperation.Move).map {
                                case Result.Success(missing) =>
                                    now.map { at =>
                                        createDirectories(missing, at)
                                            .andThen(store.removeNode(destination))
                                            .andThen(store.descendants(source))
                                            .map(nodes => store.relocate(source, destination, nodes, at))
                                            .andThen(Result.unit)
                                    }
                                case Result.Failure(refused) => Result.fail(refused)
                                case Result.Panic(error)     => Result.panic(error)
                            }
                        }.map(Abort.get(_))
                }
            }
        }

    def copy(from: Path, to: Path, options: Path.CopyOptions)(using
        Frame
    ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
        drained {
            val source = if options.followLinks then resolved(from) else entry(from)
            source.map { origin =>
                entry(to).map { destination =>
                    // Copying an entry onto itself changes nothing, as on a host; replacing the target first would delete it.
                    if origin == destination then required(origin).unit
                    else
                        placement(from, to, origin, destination, options.replace, options.createFolders, FileSystemOperation.Copy).map {
                            case Result.Success(missing) =>
                                now.map { at =>
                                    createDirectories(missing, at).andThen(store.removeNode(destination)).andThen {
                                        store.descendants(origin).map { nodes =>
                                            Kyo.foreachDiscard(nodes) { node =>
                                                val copied = DoltVfsStore.rebase(node.path, origin, destination)
                                                val stamp  = if options.copyAttributes then node.modifiedMs else at
                                                store.putNode(node.copy(path = copied, modifiedMs = stamp)).andThen {
                                                    if !node.isFile then ()
                                                    else store.readAll(node.path, node.sizeBytes).map(store.writeAll(copied, _, stamp))
                                                }
                                            }
                                        }
                                    }
                                }
                            case Result.Failure(refused) => Abort.fail(refused)
                            case Result.Panic(error)     => Abort.panic(error)
                        }
                }
            }
        }

    /** Decides whether `source` can be placed at `destination`, answering the parents to create or why it cannot, before
      * anything is written: the source must exist and must not be placed inside itself, an existing target is replaced
      * only when `replace` allows and never when it is a directory with entries, and a missing parent is created only when
      * `createFolders` allows.
      */
    private def placement(
        from: Path,
        to: Path,
        source: Path,
        destination: Path,
        replace: Path.Replace,
        createFolders: Boolean,
        operation: FileSystemOperation
    )(using
        Frame
    ): Result[FileReadException | FileWriteException | FileStructureException, Chunk[Path]] < (Async & Abort[FileIOException]) =
        store.node(source).map {
            case Absent                                                   => Result.fail(FileNotFoundException(from))
            case Present(_) if destination.parts.startsWith(source.parts) =>
                Result.fail(FileIOException(to, operation, IntoItself(from, to)))
            case Present(_) =>
                store.node(destination).map { existing =>
                    val occupied = existing match
                        case Absent                                      => Kyo.lift(Maybe.empty[FileStructureException])
                        case Present(_) if replace == Path.Replace.Never => Kyo.lift(Maybe(FileAlreadyExistsException(to)))
                        case Present(node) if node.isDirectory           =>
                            store.children(destination).map(kids =>
                                if kids.nonEmpty then Maybe(FileDirectoryNotEmptyException(to)) else Absent
                            )
                        case Present(_) => Kyo.lift(Maybe.empty[FileStructureException])
                    occupied.map {
                        case Present(refused) => Result.fail(refused)
                        case Absent           =>
                            missingParents(to, destination, operation).map {
                                case Result.Success(missing) if missing.nonEmpty && !createFolders =>
                                    Result.fail(FileNotFoundException(missing.last))
                                case other => other
                            }
                    }
                }
        }

    def remove(path: Path)(using Frame): Boolean < (Async & Abort[FileReadException | FileStructureException]) =
        drained {
            entry(path).map { removed =>
                store.node(removed).map {
                    case Absent        => false
                    case Present(node) =>
                        val guard =
                            if !node.isDirectory then Kyo.lift(())
                            else
                                store.children(removed).map(kids =>
                                    if kids.nonEmpty then Abort.fail(FileDirectoryNotEmptyException(path)) else ()
                                )
                        guard.andThen(store.removeNode(removed)).andThen(true)
                }
            }
        }

    def removeExisting(path: Path)(using Frame): Unit < (Async & Abort[FileReadException | FileStructureException]) =
        remove(path).map(removed => if removed then () else Abort.fail(FileNotFoundException(path)))

    def removeAll(path: Path)(using Frame): Unit < (Async & Abort[FileReadException | FileStructureException]) =
        drained {
            entry(path).map { removed =>
                store.descendants(removed).map { nodes =>
                    // Deepest first, so a directory is never removed while a child still refers to it.
                    Kyo.foreachDiscard(nodes.sortBy(-_.path.parts.size))(node => store.removeNode(node.path))
                }
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
            // Create-new refuses a link at the path, as an exclusive host open does; every other open follows it.
            val opened = if open == FileSystem.WriteOpen.CreateNew then entry(path) else resolved(path)
            opened.map { target =>
                store.node(target).map { existing =>
                    val prepared =
                        (open, existing) match
                            case (FileSystem.WriteOpen.Existing, Absent)      => Abort.fail(FileNotFoundException(path))
                            case (FileSystem.WriteOpen.CreateNew, Present(_)) => Abort.fail(FileAlreadyExistsException(path))
                            case (_, Present(node)) if node.isDirectory       => Abort.fail(FileIsADirectoryException(path))
                            case (_, Present(_))                              => Kyo.lift(())
                            case _                                            =>
                                parents(target, create = true, FileSystemOperation.Channel).andThen {
                                    now.map { at =>
                                        store.insertNode(DoltVfsNode(target, Kind.File, Absent, 0L, at)).map { won =>
                                            if won then store.writeAll(target, Span.empty[Byte], at)
                                            // Another opener created it first: only create-new must refuse an existing file.
                                            else if open == FileSystem.WriteOpen.CreateNew then Abort.fail(FileAlreadyExistsException(path))
                                            else ()
                                        }
                                    }
                                }
                    prepared.andThen {
                        Sync.Unsafe.defer(new DoltChannel(target, store, AtomicBoolean.Unsafe.init(true))).map { channel =>
                            (channel, () => channel.close)
                        }
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

    final private[kyo] case class NotADirectory(path: Path)
        extends RuntimeException(s"Not a directory: $path")

    final private[kyo] case class IntoItself(source: Path, target: Path)
        extends RuntimeException(s"Cannot place $source inside itself at $target")

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
