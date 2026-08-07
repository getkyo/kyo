package kyo

import java.nio.charset.Charset

private[kyo] object InMemoryFileSystem:
    final case class FileBody(bytes: Span[Byte]) derives CanEqual
    final case class Node(children: Map[String, Node], file: Maybe[FileBody], stat: Path.PathStat, identity: Long) derives CanEqual
    object Node:
        private val nextIdentity          = new java.util.concurrent.atomic.AtomicLong(0L)
        private def freshIdentity(): Long = nextIdentity.incrementAndGet()
        def dir(now: Long): Node          = Node(Map.empty, Absent, Path.PathStat(now, 0L), freshIdentity())
        def file(bytes: Span[Byte], now: Long): Node =
            Node(Map.empty, Present(FileBody(bytes)), Path.PathStat(now, bytes.size.toLong), freshIdentity())

        def copied(node: Node, now: Long, copyAttributes: Boolean): Node =
            val stat = if copyAttributes then node.stat else Path.PathStat(now, node.stat.sizeBytes)
            Node(node.children.view.mapValues(copied(_, now, copyAttributes)).toMap, node.file, stat, freshIdentity())
    end Node
    final case class LockClaim(mode: Path.LockMode, owners: Chunk[Path.LockOwnership])
    final case class WatchRegistration(
        root: Path,
        options: WatchOptions,
        caseSensitivity: Glob.CaseSensitivity,
        channel: Channel[PathChange],
        overflowed: AtomicBoolean
    )
    final case class State(
        root: Node,
        locks: Map[Chunk[String], LockClaim] = Map.empty,
        watchers: Map[Long, WatchRegistration] = Map.empty
    )

    def init(using Frame): (FileSystem.Write[Sync] & FileSystem.Watch[Sync]) < Sync =
        Sync.defer(java.lang.System.currentTimeMillis()).map { now =>
            AtomicRef.init(State(Node.dir(now))).map(ref => new InMemoryFileSystem(ref))
        }

    // Pure tree navigation, keyed by Path.parts (platform normalization inherited from Path construction).
    private[kyo] def lookup(root: Node, parts: Chunk[String]): Maybe[Node] =
        if parts.isEmpty then Present(root)
        else
            root.children.get(parts.head) match
                case Some(child) => lookup(child, parts.tail)
                case None        => Absent

    private[kyo] def upsert(root: Node, parts: Chunk[String], leaf: Node, now: Long): Node =
        if parts.isEmpty then leaf
        else
            val seg   = parts.head
            val child = root.children.getOrElse(seg, Node.dir(now))
            root.copy(children = root.children.updated(seg, upsert(child, parts.tail, leaf, now)))

    private[kyo] def delete(root: Node, parts: Chunk[String]): Node =
        if parts.isEmpty then root
        else if parts.size == 1 then root.copy(children = root.children - parts.head)
        else
            root.children.get(parts.head) match
                case Some(child) => root.copy(children = root.children.updated(parts.head, delete(child, parts.tail)))
                case None        => root

    // True when the immediate parent directory of parts exists in the tree. The root always exists,
    // so single-segment paths (whose parent IS the root) always return true. Callers use this to
    // enforce the createFolders = false contract: fail with FileNotFoundException when a required
    // parent is absent rather than silently creating it.
    private[kyo] def parentsExist(root: Node, parts: Chunk[String]): Boolean =
        if parts.size <= 1 then true
        else
            lookup(root, parts.dropRight(1)) match
                case Present(n) if n.file.isEmpty => true
                case _                            => false
end InMemoryFileSystem

final private[kyo] class InMemoryFileSystem(state: AtomicRef[InMemoryFileSystem.State])
    extends FileSystem.Write[Sync], FileSystem.Watch[Sync]:
    import InMemoryFileSystem.*
    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync = Glob.CaseSensitivity.Sensitive

    private def now(using Frame): Long < Sync = Sync.defer(java.lang.System.currentTimeMillis())

    private val nextWatcherId = new java.util.concurrent.atomic.AtomicLong(0L)

    // Temporary directory names need a token that is unique per call. The wall clock is not one:
    // in-memory operations take microseconds, so two calls land in the same millisecond, and the
    // identity hash of the prefix argument is constant for the interned literal Path.tempDir passes
    // by default. A counter is unique by construction.
    private val nextTempId = new java.util.concurrent.atomic.AtomicLong(0L)

    private def relative(root: Path, path: Path): Maybe[Chunk[String]] =
        val rootParts = root.parts
        val parts     = path.parts
        if parts.size < rootParts.size || parts.take(rootParts.size) != rootParts then Absent
        else Present(parts.drop(rootParts.size))
    end relative

    private def matches(registration: WatchRegistration, path: Path): Boolean =
        relative(registration.root, path).exists { parts =>
            parts.nonEmpty &&
            (registration.options.depth == WatchDepth.Recursive || parts.size == 1) &&
            registration.options.glob.matches(parts, registration.caseSensitivity)
        }

    // The declared capacity is enforced against the channel's own occupancy rather than a counter
    // kept alongside it. Channel.init rounds capacity up to the next power of two, so the physical
    // channel is the wrong thing to ask "is this full"; its size is the right thing to ask "how many
    // are queued". A separate counter answering the same question drifts, because incrementing it
    // and offering are not one step and the consumer decrements it after taking: the count then
    // reports more than is queued and an Overflow is published for a channel that never filled, or
    // it goes negative after a drain and the bound stops being enforced at all.
    private def offer(registration: WatchRegistration, event: PathChange)(using Frame): Unit < Sync =
        def overflow: Unit < Sync =
            registration.overflowed.compareAndSet(false, true).map {
                case false => ()
                case true =>
                    Abort.run[Closed](registration.channel.drain).andThen {
                        Abort.run[Closed](registration.channel.offer(PathChange.Overflow(registration.root))).map {
                            case Result.Success(true) => ()
                            case _                    => registration.overflowed.set(false)
                        }
                    }
            }
        registration.overflowed.get.map {
            case true => ()
            case false =>
                Abort.run[Closed](registration.channel.size).map {
                    case Result.Success(queued) if queued >= registration.options.capacity => overflow
                    case Result.Success(_) =>
                        Abort.run[Closed](registration.channel.offer(event)).map {
                            case Result.Success(true)  => ()
                            case Result.Success(false) => overflow
                            case _                     => ()
                        }
                    case _ => ()
                }
        }
    end offer

    private def emit(registration: WatchRegistration, change: PathChange)(using Frame): Unit < Sync =
        val selected = change match
            case PathChange.Created(path)  => if matches(registration, path) then Present(change) else Absent
            case PathChange.Modified(path) => if matches(registration, path) then Present(change) else Absent
            case PathChange.Removed(path) =>
                if path == registration.root then Present(PathChange.Invalidated(registration.root))
                else if matches(registration, path) then Present(change)
                else Absent
            case PathChange.Moved(from, to) =>
                if relative(from, registration.root).isDefined then Present(PathChange.Invalidated(registration.root))
                else
                    (matches(registration, from), matches(registration, to)) match
                        case (true, true)  => Present(change)
                        case (true, false) => Present(PathChange.Removed(from))
                        case (false, true) => Present(PathChange.Created(to))
                        case _             => Absent
            case PathChange.Overflow(root)    => if root == registration.root then Present(change) else Absent
            case PathChange.Invalidated(root) => if root == registration.root then Present(change) else Absent
        selected.fold[Unit < Sync](())(offer(registration, _))
    end emit

    private def emitAll(registrations: Iterable[(Long, WatchRegistration)], changes: Chunk[PathChange])(using Frame): Unit < Sync =
        Kyo.foreachDiscard(registrations) { (id, registration) =>
            val invalidated = changes.exists {
                case PathChange.Removed(path)  => relative(path, registration.root).isDefined
                case PathChange.Moved(from, _) => relative(from, registration.root).isDefined
                case _                         => false
            }
            if invalidated then offer(registration, PathChange.Invalidated(registration.root)).andThen(unregister(id))
            else Kyo.foreachDiscard(changes)(emit(registration, _))
        }

    private def mutationChanges(before: Node, after: Node)(using Frame): Chunk[PathChange] =
        def flatten(node: Node, path: Path): Map[Path, Node] =
            node.children.foldLeft(Map.empty[Path, Node]) { case (acc, (name, child)) =>
                val childPath = path / name
                acc.updated(childPath, child) ++ flatten(child, childPath)
            }
        val oldEntries = flatten(before, Path())
        val newEntries = flatten(after, Path())
        val removed    = oldEntries.keySet.diff(newEntries.keySet)
        val created    = newEntries.keySet.diff(oldEntries.keySet)
        val modified = oldEntries.keySet.intersect(newEntries.keySet).filter { path =>
            val oldNode = oldEntries(path)
            val newNode = newEntries(path)
            oldNode.file != newNode.file || oldNode.stat != newNode.stat
        }
        val moves = removed.toSeq.sortBy(_.toString).flatMap { from =>
            val identity   = oldEntries(from).identity
            val candidates = created.filter(to => newEntries(to).identity == identity)
            val oldMatches = removed.count(candidate => oldEntries(candidate).identity == identity)
            if candidates.size == 1 && oldMatches == 1 then candidates.headOption.map(to => (from, to))
            else None
        }
        val movedFrom = moves.map(_._1).toSet
        val movedTo   = moves.map(_._2).toSet
        Chunk.from(moves.map(PathChange.Moved.apply)) ++
            Chunk.from(removed.diff(movedFrom).toSeq.sortBy(_.toString).map(PathChange.Removed.apply)) ++
            Chunk.from(created.diff(movedTo).toSeq.sortBy(_.toString).map(PathChange.Created.apply)) ++
            Chunk.from(modified.toSeq.sortBy(_.toString).map(PathChange.Modified.apply))
    end mutationChanges

    // Optimistic CAS write, the InMemoryJournal.modify shape (immutable state behind AtomicRef).
    private def modify[E <: FileSystemException, A](op: State => Result[E, (State, A)])(using Frame): A < (Sync & Abort[E]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                Abort.get(op(cur)).map { (next, v) =>
                    state.compareAndSet(cur, next).map {
                        case true =>
                            emitAll(cur.watchers, mutationChanges(cur.root, next.root)).map(_ => Loop.done(v))
                        case false => Loop.continue(())
                    }
                }
            }
        }

    def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
        state.use(s => lookup(s.root, path.parts).isDefined)
    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) = exists(path)
    def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
        state.use(s => lookup(s.root, path.parts).exists(_.file.isEmpty))
    def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
        state.use(s => lookup(s.root, path.parts).exists(_.file.isDefined))
    def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = false
    def realPath(path: Path)(using
        Frame
    ): Path < (Sync & Abort[
        FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
    ]) = path
    def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))
    def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))
    def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) =>
                    n.file match
                        case Present(body) => body.bytes
                        case Absent        => Abort.fail(FileNotFoundException(path))
                case Absent => Abort.fail(FileNotFoundException(path))
        }
    def readLines(path: Path)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def size(path: Path)(using Frame): Long < (Sync & Abort[FileReadException]) = readBytes(path).map(_.size.toLong)
    def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) => n.stat
                case Absent     => Abort.fail(FileNotFoundException(path))
        }
    def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileStructureException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) if n.file.isEmpty => Chunk.from(n.children.keys.toIndexedSeq.sorted).map(seg => path / seg)
                case Present(_)                   => Abort.fail(FileNotADirectoryException(path))
                case Absent                       => Abort.fail(FileNotFoundException(path))
        }
    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileStructureException]) =
        list(path).map(_.filter(p => glob.matches(Chunk(p.parts.last), caseSensitivity)))
    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        writeBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), options)
    // createFolders = false fails with FileNotFoundException when the immediate parent does not exist.
    // When true (the default), upsert creates all intermediate parent directories automatically
    // (mkdir -p behavior). mkDir unconditionally uses mkdir -p; there is no per-mkDir createFolders flag.
    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        now.map(t =>
            modify { s =>
                if !options.createFolders && !parentsExist(s.root, path.parts) then
                    Result.fail(FileNotFoundException(path))
                else
                    Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(value, t), t)), ()))
            }
        )
    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        write(path, value.mkString("", "\n", "\n"), options)
    def append(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        appendBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), options)
    // appendBytes is a single CAS modify (atomic read-modify-write) so concurrent appends to the same
    // path are serialized by the CAS loop and no update is lost. The non-atomic read-then-write pattern
    // is invalid here: a concurrent write between the read and the CAS attempt would be silently dropped.
    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        now.map(t =>
            modify { s =>
                if !options.createFolders && !parentsExist(s.root, path.parts) then
                    Result.fail(FileNotFoundException(path))
                else
                    val existing = lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte])
                    val merged   = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                    Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(merged, t), t)), ()))
            }
        )
    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)
    // truncate is a single CAS modify (atomic read-modify-write) for the same reason as appendBytes.
    def truncate(path: Path, size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        now.map(t =>
            modify { s =>
                lookup(s.root, path.parts) match
                    case Absent => Result.fail(FileNotFoundException(path))
                    case Present(n) =>
                        n.file match
                            case Absent => Result.fail(FileNotFoundException(path))
                            case Present(body) =>
                                val kept = Span.fromUnsafe(body.bytes.toArrayUnsafe.take(size.toInt))
                                Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(kept, t), t)), ()))
            }
        )
    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        modify { s =>
            lookup(s.root, path.parts) match
                case Present(n) => Result.succeed((
                        s.copy(root = upsert(s.root, path.parts, n.copy(stat = n.stat.copy(lastModifiedMs = epochMs)), epochMs)),
                        ()
                    ))
                case Absent => Result.fail(FileNotFoundException(path))
        }
    // mkDir always creates all intermediate parent directories (mkdir -p behavior): upsert creates
    // missing parent nodes automatically. No createFolders flag; the mkdir -p contract is invariant.
    def mkDir(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        now.map(t => modify(s => Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.dir(t), t)), ()))))
    def mkFile(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        now.map(t => modify(s => Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(Span.empty[Byte], t), t)), ()))))
    // The in-memory backend honors replaceExisting (aborts FileAlreadyExistsException when the target
    // exists and replaceExisting is false) and createFolders (aborts FileNotFoundException when the
    // destination parent is absent and createFolders is false). atomicMove is inherent to the CAS.
    // There are no symlinks, and copyAttributes selects whether the copied node keeps its source stat.
    def move(
        from: Path,
        to: Path,
        options: Path.MoveOptions
    )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        now.map(t =>
            modify { s =>
                lookup(s.root, from.parts) match
                    case Absent => Result.fail(FileNotFoundException(from))
                    case Present(_) if lookup(s.root, to.parts).isDefined && options.replace == Path.Replace.Never =>
                        Result.fail(FileAlreadyExistsException(to))
                    case Present(_) if !options.createFolders && !parentsExist(s.root, to.parts) => Result.fail(FileNotFoundException(to))
                    case Present(n) => Result.succeed((s.copy(root = delete(upsert(s.root, to.parts, n, t), from.parts)), ()))
            }
        )
    def copy(
        from: Path,
        to: Path,
        options: Path.CopyOptions
    )(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        now.map(t =>
            modify { s =>
                lookup(s.root, from.parts) match
                    case Absent => Result.fail(FileNotFoundException(from))
                    case Present(_) if lookup(s.root, to.parts).isDefined && options.replace == Path.Replace.Never =>
                        Result.fail(FileAlreadyExistsException(to))
                    case Present(_) if !options.createFolders && !parentsExist(s.root, to.parts) => Result.fail(FileNotFoundException(to))
                    case Present(n) =>
                        val copied = Node.copied(n, t, options.copyAttributes)
                        Result.succeed((s.copy(root = upsert(s.root, to.parts, copied, t)), ()))
            }
        )
    def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileStructureException]) =
        modify(s => Result.succeed((s.copy(root = delete(s.root, path.parts)), lookup(s.root, path.parts).isDefined)))
    def removeExisting(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))
    def removeAll(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        modify(s => Result.succeed((s.copy(root = delete(s.root, path.parts)), ())))
    def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using Frame): Path.WalkHandle < (Sync & Abort[FileStructureException]) =
        state.use(s => InMemoryHandles.walk(path, lookup(s.root, path.parts), maxDepth))
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (Sync & Abort[FileWriteException]) =
        state.use { s =>
            if !options.createFolders && !parentsExist(s.root, path.parts) then
                Abort.fail(FileNotFoundException(path))
            else
                val seed =
                    if append then lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte]) else Span.empty[Byte]
                InMemoryHandles.write(this, path, seed)
        }
    // writeChunk and writeString are abstract on FileSystem.Write[S]; every concrete service must implement
    // them. The in-memory form delegates into the handle's buffer accumulator.
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        // Unsafe: delegates to the in-memory write handle's buffer accumulator
        Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeBytes(chunk)))
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        // Unsafe: delegates to the in-memory write handle's buffer accumulator
        Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeString(value, charset)))
    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
        now.map { t =>
            val dir = Path(prefix + "-" + java.lang.Long.toHexString(t) + "-" + nextTempId.getAndIncrement().toHexString)
            modify(s => Result.succeed((s.copy(root = upsert(s.root, dir.parts, Node.dir(t), t)), ()))).andThen {
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: removes only the in-memory subtree; never touches the host filesystem.
                    // AtomicRef.Unsafe exposes updateAndGet(f): A (not update(f): Unit); discard the result.
                    def remove()(using AllowUnsafe): Unit =
                        discard(state.unsafe.updateAndGet(s => s.copy(root = delete(s.root, dir.parts))))
            }
        }

    override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
        new Path.TempFileHandle:
            def path: Path = temporary
            def remove()(using AllowUnsafe): Unit =
                discard(state.unsafe.updateAndGet(s => s.copy(root = delete(s.root, temporary.parts))))

    // --- Positioned channels ---

    private def ensureReadTarget(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException]) =
        exists(path).map(found => if found then () else Abort.fail(FileNotFoundException(path)))

    private def ensureWriteTarget(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Unit < (Sync & Abort[FileWriteException | FileStructureException]) =
        now.map(t =>
            modify { s =>
                val found = lookup(s.root, path.parts).isDefined
                open match
                    case FileSystem.WriteOpen.Existing if !found => Result.fail(FileNotFoundException(path))
                    case FileSystem.WriteOpen.CreateNew if found => Result.fail(FileAlreadyExistsException(path))
                    case FileSystem.WriteOpen.Create | FileSystem.WriteOpen.CreateNew if !found =>
                        Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(Span.empty[Byte], t), t)), ()))
                    case _ => Result.succeed((s, ()))
                end match
            }
        )

    final private class ChannelOps(path: Path):
        private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
        private def closedFailure(operation: FileSystemOperation)(using Frame): FileIOException =
            FileIOException(path, operation, new java.io.IOException("channel is closed"))
        private def requireOpenRead(using Frame): Unit < Abort[FileReadException] =
            if closed.get() then Abort.fail(closedFailure(FileSystemOperation.Read)) else ()
        private def requireOpenWrite(using Frame): Unit < Abort[FileWriteException] =
            if closed.get() then Abort.fail(closedFailure(FileSystemOperation.Write)) else ()
        def close(): Unit = discard(closed.compareAndSet(false, true))
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
            requireOpenRead.andThen {
                if position < 0L || position > Int.MaxValue || length < 0 then
                    Abort.fail(FileIOException(path, FileSystemOperation.Read, new java.io.IOException("invalid channel read bounds")))
                else readBytes(path).map(_.drop(position.toInt).take(length))
            }
        def size(using Frame): Long < (Sync & Abort[FileReadException]) = requireOpenRead.andThen(InMemoryFileSystem.this.size(path))
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            requireOpenWrite.andThen {
                if position < 0L || position > Int.MaxValue then
                    Abort.fail(FileIOException(path, FileSystemOperation.Write, new java.io.IOException("invalid channel write position")))
                else
                    now.map(t =>
                        modify { s =>
                            val existing = lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte])
                            val p        = position.toInt
                            val padded   = if p <= existing.size then existing else existing ++ Span.fill[Byte](p - existing.size)(0.toByte)
                            val spliced  = padded.take(p) ++ bytes ++ padded.drop(p + bytes.size)
                            Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(spliced, t), t)), ()))
                        }
                    )
            }
        def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) = requireOpenWrite
        def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            requireOpenWrite.andThen {
                if size < 0L || size > Int.MaxValue then
                    Abort.fail(FileIOException(path, FileSystemOperation.Write, new java.io.IOException("invalid channel truncate size")))
                else InMemoryFileSystem.this.truncate(path, size)
            }
    end ChannelOps

    private def readChannel(ops: ChannelOps): Path.ReadChannel[Sync] = new Path.ReadChannel[Sync]:
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) = ops.readAt(position, length)
        def size(using Frame): Long < (Sync & Abort[FileReadException])                                      = ops.size
    private def writeChannel(ops: ChannelOps): Path.WriteChannel[Sync] = new Path.WriteChannel[Sync]:
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            ops.writeAt(position, bytes)
        def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) = ops.sync(metadata)
        def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException])    = ops.truncate(size)
    private def readWriteChannel(ops: ChannelOps): Path.ReadWriteChannel[Sync] = new Path.ReadWriteChannel[Sync]:
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) = ops.readAt(position, length)
        def size(using Frame): Long < (Sync & Abort[FileReadException])                                      = ops.size
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            ops.writeAt(position, bytes)
        def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) = ops.sync(metadata)
        def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException])    = ops.truncate(size)

    private def scoped[A](ops: ChannelOps, channel: A)(using Frame): A < (Sync & Scope) =
        Scope.acquireRelease(ops)(_ => Sync.defer(ops.close())).map(_ => channel)
    private def unscopedWrite(ops: ChannelOps, channel: Path.WriteChannel[Sync])(using
        Frame
    ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) =
        val close = new Path.ChannelCloseHandle:
            def close()(using AllowUnsafe): Unit = ops.close()
        (channel, () => Sync.defer(ops.close()), close)
    end unscopedWrite

    private def unscoped[A](ops: ChannelOps, channel: A)(using Frame): (A, () => Unit < Sync) =
        (channel, () => Sync.defer(ops.close()))

    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
        ensureReadTarget(path).map { _ =>
            val ops = new ChannelOps(path); scoped(ops, readChannel(ops))
        }
    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[Sync] < (Sync & Scope & Abort[FileWriteException | FileStructureException]) =
        ensureWriteTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); scoped(ops, writeChannel(ops))
        }
    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[Sync] < (Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); scoped(ops, readWriteChannel(ops))
        }
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        ensureReadTarget(path).map { _ =>
            val ops = new ChannelOps(path); unscoped(ops, readChannel(ops))
        }
    private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
        (Sync & Abort[FileWriteException | FileStructureException]) =
        ensureWriteTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); unscopedWrite(ops, writeChannel(ops))
        }
    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.ReadWriteChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); unscoped(ops, readWriteChannel(ops))
        }

    /** Volatile memory has no persistence boundary, so synchronization succeeds as a no-op. */
    def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) = ()

    def siblingTemporary(target: Path)(using
        Frame
    ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
        FileSystem.siblingTemporary[Any](this, target)

    def durableReplace(target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        FileSystem.durableReplace[Any](this, target, bytes)

    // --- Advisory lock (in-process CAS lock table; deterministic within a single process,
    // matching the design's "inMemory: in-process AtomicRef lock table" placement) ---

    def tryLock(path: Path, mode: Path.LockMode)(using
        Frame
    ): Maybe[Path.Lock] < (Sync & Scope & Abort[FileLockException]) =
        val acquire = Scope.acquireRelease(acquireLock(path, mode))(lock => lock.release(lock.ownership)).map(Maybe(_))
        Abort.recover[FileLockUnavailableException](_ => Absent)(acquire)
    end tryLock

    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
        Frame
    ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        FileSystem.awaitLock(path, wait)(tryLock(path, mode))

    // --- Watching ---

    private def register(id: Long, registration: WatchRegistration)(using Frame): Unit < (Sync & Abort[FileWatchException]) =
        modify[FileWatchException, Unit] { current =>
            lookup(current.root, registration.root.parts) match
                case Absent => Result.fail(FileWatchInvalidatedException(registration.root))
                case Present(node) if node.file.isDefined =>
                    Result.fail(FileIOException(
                        registration.root,
                        FileSystemOperation.Watch,
                        new java.io.IOException("watched path is not a directory")
                    ))
                case Present(_) =>
                    Result.succeed((current.copy(watchers = current.watchers.updated(id, registration)), ()))
        }

    private def unregister(id: Long)(using Frame): Unit < Sync =
        state.updateAndGet(current => current.copy(watchers = current.watchers - id)).unit

    def openWatcher(path: Path, options: WatchOptions)(using
        Frame
    ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
        if options.capacity <= 0 then
            Abort.fail(FileIOException(path, FileSystemOperation.Watch, new IllegalArgumentException("watch capacity must be positive")))
        else
            defaultCaseSensitivity.map { defaultCase =>
                val caseSensitivity = options.caseSensitivity match
                    case MatchCase.FileSystemDefault => defaultCase
                    case MatchCase.Sensitive         => Glob.CaseSensitivity.Sensitive
                    case MatchCase.Insensitive       => Glob.CaseSensitivity.Insensitive
                Channel.init[PathChange](options.capacity).map { channel =>
                    AtomicBoolean.init(false).map { overflowed =>
                        val id = nextWatcherId.incrementAndGet()
                        val registration = WatchRegistration(
                            path,
                            options,
                            caseSensitivity,
                            channel,
                            overflowed
                        )
                        Scope.acquireRelease(register(id, registration))(_ => unregister(id)).map { _ =>
                            new Path.Watcher:
                                def events: Stream[PathChange, Async & Scope & Abort[FileWatchException]] =
                                    channel.streamUntilClosed().map { event =>
                                        val handled: Unit < Sync = event match
                                            case PathChange.Overflow(_)    => overflowed.set(false)
                                            case PathChange.Invalidated(_) => channel.close.unit
                                            case _                         => ()
                                        handled.map(_ => event)
                                    }
                        }
                    }
                }
            }
    end openWatcher

    private def acquireLock(path: Path, mode: Path.LockMode)(using Frame): Path.Lock < (Sync & Abort[FileLockException]) =
        val owner = Path.LockOwnership.fresh()
        AtomicBoolean.init(false).map { released =>
            modify { s =>
                s.locks.get(path.parts) match
                    case None =>
                        val claim = LockClaim(mode, Chunk(owner))
                        Result.succeed((s.copy(locks = s.locks.updated(path.parts, claim)), mkLock(path, mode, owner, released)))
                    case Some(claim) if mode == Path.LockMode.Shared && claim.mode == Path.LockMode.Shared =>
                        val next = claim.copy(owners = claim.owners.append(owner))
                        Result.succeed((s.copy(locks = s.locks.updated(path.parts, next)), mkLock(path, mode, owner, released)))
                    case _ => Result.fail(FileLockUnavailableException(path))
            }
        }
    end acquireLock

    private def releaseLock(path: Path, owner: Path.LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException]) =
        modify { current =>
            current.locks.get(path.parts) match
                case Some(claim) if claim.owners.exists(Path.LockOwnership.same(_, owner)) =>
                    val owners = claim.owners.filterNot(Path.LockOwnership.same(_, owner))
                    val locks = if owners.isEmpty then current.locks - path.parts
                    else current.locks.updated(path.parts, claim.copy(owners = owners))
                    Result.succeed((current.copy(locks = locks), ()))
                case _ => Result.fail(FileLockOwnershipLostException(path))
        }

    private def owns(path: Path, owner: Path.LockOwnership)(using Frame): Boolean < Sync =
        state.use(_.locks.get(path.parts).exists(_.owners.exists(Path.LockOwnership.same(_, owner))))

    private def mkLock(path: Path, grantedMode: Path.LockMode, owner: Path.LockOwnership, released: AtomicBoolean): Path.Lock =
        new Path.Lock:
            def mode: Path.LockMode           = grantedMode
            def ownership: Path.LockOwnership = owner
            def check(using Frame): Unit < (Sync & Abort[FileLockException]) =
                owns(path, owner).map {
                    case true  => ()
                    case false => Abort.fail(FileLockOwnershipLostException(path))
                }
            def release(candidate: Path.LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException]) =
                if !Path.LockOwnership.same(owner, candidate) then Abort.fail(FileLockOwnershipLostException(path))
                else
                    released.compareAndSet(false, true).map {
                        case true  => releaseLock(path, owner)
                        case false => ()
                    }

    // Unsafe: synchronous CAS write used by the in-memory write handle's finish(); the safe writeBytes
    // equivalent, without suspension, so it can run from the handle's AllowUnsafe finish().
    private[kyo] def commitBytesUnsafe(path: Path, bytes: Span[Byte])(using AllowUnsafe): Unit =
        val t = java.lang.System.currentTimeMillis()
        // AtomicRef.Unsafe exposes updateAndGet(f): A; discard the returned state.
        discard(state.unsafe.updateAndGet(s =>
            s.copy(root = InMemoryFileSystem.upsert(s.root, path.parts, InMemoryFileSystem.Node.file(bytes, t), t))
        ))
    end commitBytesUnsafe
end InMemoryFileSystem

// Backend-specific handles over the in-memory tree, and the glob matcher used by list(glob).
private[kyo] object InMemoryHandles:
    import InMemoryFileSystem.Node

    def read(bytes: Span[Byte])(using Frame): Path.ReadHandle =
        new Path.ReadHandle:
            private var pos = 0
            def readChunk(buffer: Array[Byte])(using AllowUnsafe): Path.ReadResult =
                if pos >= bytes.size then Path.ReadResult.Eof
                else
                    val n = math.min(buffer.length, bytes.size - pos)
                    var i = 0
                    while i < n do
                        buffer(i) = bytes(pos + i)
                        i += 1
                    pos += n
                    Path.ReadResult(n)
            def readLong()(using AllowUnsafe): Long =
                // Parses from the start of the content into a fresh buffer, leaving the readChunk cursor untouched.
                val len = bytes.size
                val buf = new Array[Byte](len)
                var i   = 0
                while i < len do
                    buf(i) = bytes(i)
                    i += 1
                Path.ReadHandle.parseLeadingLong(buf, len)
            end readLong
            def position(offset: Long)(using AllowUnsafe): Unit = pos = offset.toInt
            def close()(using AllowUnsafe): Unit                = ()

    def lines(text: String): Path.LineReadHandle =
        new Path.LineReadHandle:
            private val it =
                val raw = text.split("\n", -1)
                (if raw.nonEmpty && raw.last.isEmpty then raw.dropRight(1) else raw).iterator
            def readLine()(using AllowUnsafe): Maybe[String] =
                if it.hasNext then Maybe(it.next()) else Maybe.empty
            def close()(using AllowUnsafe): Unit = ()

    def walk(base: Path, node: Maybe[Node], maxDepth: Int)(using Frame): Path.WalkHandle =
        new Path.WalkHandle:
            private val pending: Iterator[Path] =
                node match
                    case Present(n) => preorder(base, n, maxDepth).iterator
                    case Absent     => Iterator.empty
            def next()(using AllowUnsafe): Maybe[Path] =
                if pending.hasNext then Maybe(pending.next()) else Maybe.empty
            def close()(using AllowUnsafe): Unit = ()

    private def preorder(base: Path, n: Node, depth: Int)(using Frame): List[Path] =
        if depth <= 0 then Nil
        else
            n.children.toList.sortBy(_._1).flatMap { case (seg, child) =>
                val p = base / seg
                p :: preorder(p, child, depth - 1)
            }

    def write(service: InMemoryFileSystem, path: Path, seed: Span[Byte])(using Frame): Path.WriteHandle =
        new Path.WriteHandle:
            private var acc = seed.toArrayUnsafe
            def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ chunk.toArray
                Result.succeed(())
            def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ s.getBytes(charset)
                Result.succeed(())
            def finish()(using AllowUnsafe): Unit =
                // Unsafe: commits the buffered bytes into the in-memory tree at finish()
                service.commitBytesUnsafe(path, Span.fromUnsafe(acc))
            end finish
            def close()(using AllowUnsafe): Unit = () // if finish() was never called, the buffered bytes are dropped

end InMemoryHandles
