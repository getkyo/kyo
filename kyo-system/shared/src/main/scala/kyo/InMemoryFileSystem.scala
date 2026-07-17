package kyo

import java.nio.charset.Charset

private[kyo] object InMemoryFileSystem:
    final case class FileBody(bytes: Span[Byte])
    final case class Node(children: Map[String, Node], file: Maybe[FileBody], stat: Path.PathStat)
    object Node:
        def dir(now: Long): Node                     = Node(Map.empty, Absent, Path.PathStat(now, 0L))
        def file(bytes: Span[Byte], now: Long): Node = Node(Map.empty, Present(FileBody(bytes)), Path.PathStat(now, bytes.size.toLong))
    final case class State(root: Node)

    def init(using Frame): FileSystem[Sync] < Sync =
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

final private[kyo] class InMemoryFileSystem(state: AtomicRef[InMemoryFileSystem.State])(using Frame) extends FileSystem[Sync]:
    import InMemoryFileSystem.*
    val commitStrategy: FileSystem.CommitStrategy = FileSystem.CommitStrategy.Auto

    private def now: Long < Sync = Sync.defer(java.lang.System.currentTimeMillis())

    // Optimistic CAS write, the InMemoryJournal.modify shape (immutable state behind AtomicRef).
    private def modify[A](op: State => Result[FileException, (State, A)]): A < (Sync & Abort[FileException]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                Abort.get(op(cur)).map { (next, v) =>
                    state.compareAndSet(cur, next).map {
                        case true  => Loop.done(v)
                        case false => Loop.continue(())
                    }
                }
            }
        }

    def exists(path: Path): Boolean < (Sync & Abort[FileException]) =
        state.use(s => lookup(s.root, path.parts).isDefined)
    def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) = exists(path)
    def isDirectory(path: Path): Boolean < (Sync & Abort[FileException]) =
        state.use(s => lookup(s.root, path.parts).exists(_.file.isEmpty))
    def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException]) =
        state.use(s => lookup(s.root, path.parts).exists(_.file.isDefined))
    def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException]) = false
    def realPath(path: Path): Path < (Sync & Abort[FileException])          = path
    def read(path: Path): String < (Sync & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))
    def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))
    def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) =>
                    n.file match
                        case Present(body) => body.bytes
                        case Absent        => Abort.fail(FileNotFoundException(path))
                case Absent => Abort.fail(FileNotFoundException(path))
        }
    def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def size(path: Path): Long < (Sync & Abort[FileException]) = readBytes(path).map(_.size.toLong)
    def stat(path: Path): Path.PathStat < (Sync & Abort[FileException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) => n.stat
                case Absent     => Abort.fail(FileNotFoundException(path))
        }
    def list(path: Path): Chunk[Path] < (Sync & Abort[FileException]) =
        state.use { s =>
            lookup(s.root, path.parts) match
                case Present(n) if n.file.isEmpty => Chunk.from(n.children.keys.toIndexedSeq.sorted).map(seg => path / seg)
                case Present(_)                   => Abort.fail(FileNotADirectoryException(path))
                case Absent                       => Abort.fail(FileNotFoundException(path))
        }
    def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) =
        list(path).map(_.filter(p => p.name.exists(InMemoryHandles.matchesGlob(_, glob))))
    def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        writeBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), createFolders)
    // createFolders = false fails with FileNotFoundException when the immediate parent does not exist.
    // When true (the default), upsert creates all intermediate parent directories automatically
    // (mkdir -p behavior). mkDir unconditionally uses mkdir -p; there is no per-mkDir createFolders flag.
    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        now.map(t =>
            modify { s =>
                if !createFolders && !parentsExist(s.root, path.parts) then
                    Result.fail(FileNotFoundException(path))
                else
                    Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(value, t), t)), ()))
            }
        )
    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        write(path, value.mkString("", "\n", "\n"), createFolders)
    def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        appendBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), createFolders)
    // appendBytes is a single CAS modify (atomic read-modify-write) so concurrent appends to the same
    // path are serialized by the CAS loop and no update is lost. The non-atomic read-then-write pattern
    // is invalid here: a concurrent write between the read and the CAS attempt would be silently dropped.
    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        now.map(t =>
            modify { s =>
                if !createFolders && !parentsExist(s.root, path.parts) then
                    Result.fail(FileNotFoundException(path))
                else
                    val existing = lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte])
                    val merged   = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                    Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(merged, t), t)), ()))
            }
        )
    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        append(path, value.mkString("", "\n", "\n"), createFolders)
    // truncate is a single CAS modify (atomic read-modify-write) for the same reason as appendBytes.
    def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) =
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
    def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
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
    def mkDir(path: Path): Unit < (Sync & Abort[FileException]) =
        now.map(t => modify(s => Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.dir(t), t)), ()))))
    def mkFile(path: Path): Unit < (Sync & Abort[FileException]) = writeBytes(path, Span.empty[Byte], createFolders = true)
    // The in-memory backend honors replaceExisting (aborts FileAlreadyExistsException when the target
    // exists and replaceExisting is false) and createFolders (aborts FileNotFoundException when the
    // destination parent is absent and createFolders is false). atomicMove is inherent to the CAS, and
    // followLinks and copyAttributes are moot here (no symlinks; the stat travels with the node), so
    // those flags are documented no-ops in this reference backend.
    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (Sync & Abort[FileException]) =
        now.map(t =>
            modify { s =>
                lookup(s.root, from.parts) match
                    case Absent                                                               => Result.fail(FileNotFoundException(from))
                    case Present(_) if lookup(s.root, to.parts).isDefined && !replaceExisting => Result.fail(FileAlreadyExistsException(to))
                    case Present(_) if !createFolders && !parentsExist(s.root, to.parts)      => Result.fail(FileNotFoundException(to))
                    case Present(n) => Result.succeed((s.copy(root = delete(upsert(s.root, to.parts, n, t), from.parts)), ()))
            }
        )
    def copy(
        from: Path,
        to: Path,
        followLinks: Boolean,
        replaceExisting: Boolean,
        copyAttributes: Boolean,
        createFolders: Boolean
    ): Unit < (Sync & Abort[FileException]) =
        now.map(t =>
            modify { s =>
                lookup(s.root, from.parts) match
                    case Absent                                                               => Result.fail(FileNotFoundException(from))
                    case Present(_) if lookup(s.root, to.parts).isDefined && !replaceExisting => Result.fail(FileAlreadyExistsException(to))
                    case Present(_) if !createFolders && !parentsExist(s.root, to.parts)      => Result.fail(FileNotFoundException(to))
                    case Present(n) => Result.succeed((s.copy(root = upsert(s.root, to.parts, n, t)), ()))
            }
        )
    def remove(path: Path): Boolean < (Sync & Abort[FileException]) =
        modify(s => Result.succeed((s.copy(root = delete(s.root, path.parts)), lookup(s.root, path.parts).isDefined)))
    def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))
    def removeAll(path: Path): Unit < (Sync & Abort[FileException]) =
        modify(s => Result.succeed((s.copy(root = delete(s.root, path.parts)), ())))
    def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
        state.use(s => InMemoryHandles.walk(path, lookup(s.root, path.parts), maxDepth))
    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
        state.use { s =>
            if !createFolders && !parentsExist(s.root, path.parts) then
                Abort.fail(FileNotFoundException(path))
            else
                val seed =
                    if append then lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte]) else Span.empty[Byte]
                InMemoryHandles.write(this, path, seed)
        }
    // writeChunk and writeString are abstract on FileSystem[S]; every concrete service must implement
    // them. The in-memory form delegates into the handle's buffer accumulator.
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) =
        // Unsafe: delegates to the in-memory write handle's buffer accumulator
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeBytes(chunk)))
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
        // Unsafe: delegates to the in-memory write handle's buffer accumulator
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeString(value, charset)))
    def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) =
        now.map { t =>
            val dir = Path(prefix + "-" + java.lang.Long.toHexString(t) + "-" + java.lang.System.identityHashCode(prefix))
            modify(s => Result.succeed((s.copy(root = upsert(s.root, dir.parts, Node.dir(t), t)), ()))).andThen {
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: removes only the in-memory subtree; never touches the host filesystem.
                    // AtomicRef.Unsafe exposes updateAndGet(f): A (not update(f): Unit); discard the result.
                    def remove()(using AllowUnsafe): Unit =
                        discard(state.unsafe.updateAndGet(s => s.copy(root = delete(s.root, dir.parts))))
            }
        }

    // --- Positioned channel (mode gates writeAt/truncate at the call site) ---

    def openChannel(path: Path, mode: FileSystem.ChannelMode): Path.Channel[Sync] < (Sync & Scope & Abort[FileException]) =
        val ensureTarget: Unit < (Sync & Abort[FileException]) =
            mode match
                case FileSystem.ChannelMode.ReadWriteCreate =>
                    exists(path).map(found => if found then () else mkFile(path))
                case FileSystem.ChannelMode.Read | FileSystem.ChannelMode.ReadWrite =>
                    exists(path).map(found => if found then () else Abort.fail(FileNotFoundException(path)))
        Scope.acquireRelease(ensureTarget)(_ => ()).andThen(mkChannel(path, mode))
    end openChannel

    private def mkChannel(path: Path, mode: FileSystem.ChannelMode): Path.Channel[Sync] =
        new Path.Channel[Sync]:
            def readAt(pos: Long, len: Int)(using Frame): Span[Byte] < (Sync & Abort[FileException]) =
                readBytes(path).map(_.drop(pos.toInt).take(len))
            def writeAt(pos: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileException]) =
                mode match
                    case FileSystem.ChannelMode.Read => Abort.fail(FileAccessDeniedException(path))
                    case _ =>
                        now.map(t =>
                            modify { s =>
                                val existing = lookup(s.root, path.parts).flatMap(_.file).map(_.bytes).getOrElse(Span.empty[Byte])
                                val p        = pos.toInt
                                // Span.fill zero-fills the gap when writeAt lands past the current length.
                                val padded =
                                    if p <= existing.size then existing else existing ++ Span.fill[Byte](p - existing.size)(0.toByte)
                                val tail    = padded.drop(p + bytes.size)
                                val spliced = padded.take(p) ++ bytes ++ tail
                                Result.succeed((s.copy(root = upsert(s.root, path.parts, Node.file(spliced, t), t)), ()))
                            }
                        )
            def sync()(using Frame): Unit < (Sync & Abort[FileException]) = ()
            def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileException]) =
                mode match
                    case FileSystem.ChannelMode.Read => Abort.fail(FileAccessDeniedException(path))
                    case _                           => InMemoryFileSystem.this.truncate(path, size)
            def size()(using Frame): Long < (Sync & Abort[FileException]) = InMemoryFileSystem.this.size(path)

    def syncDir(path: Path): Unit < (Sync & Abort[FileException]) = ()

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

    def read(bytes: Span[Byte]): Path.ReadHandle =
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

    // Reference glob matcher over a single path segment; mirrors the platform list(glob) semantics.
    private[kyo] def matchesGlob(name: String, glob: String): Boolean =
        val sb = new StringBuilder("^")
        glob.foreach {
            case '*'                                   => sb.append("[^/]*")
            case '?'                                   => sb.append("[^/]")
            case c if "\\.[]{}()+-^$|".indexOf(c) >= 0 => sb.append('\\').append(c)
            case c                                     => sb.append(c)
        }
        sb.append('$')
        name.matches(sb.toString)
    end matchesGlob
end InMemoryHandles
