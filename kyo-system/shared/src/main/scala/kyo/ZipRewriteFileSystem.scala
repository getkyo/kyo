package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.ZipArchive

/** [[FileSystem.zip]]'s staged-write implementor whose reads fall through to
  * `archive`'s baseline entries (parsed once, lazily, on first touch, from the archive's own bytes
  * read through the cross-platform [[Path.Unsafe.readBytes]] surface) when a path is not staged,
  * whose writes always land in an in-memory upper (`FileSystem.inMemory`, the same staging primitive
  * [[FileSystem.overlay]] itself is built from), and whose commit serializes the whole archive from
  * the merged (baseline minus tombstoned minus shadowed, plus upper) view via
  * [[kyo.internal.ZipArchive.write]] (a uniform pure-Scala STORED writer, no `java.util.zip`
  * anywhere), atomically moved into place via [[FileSystem.host]]. There is no in-place
  * random-access write into a compressed entry and no live lower to validate a read-set against, so
  * commit always succeeds or fails on I/O alone, never raising a content conflict.
  */
private[kyo] object ZipRewriteFileSystem:

    def init(archive: Path)(using
        Frame
    ): (
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]] & FileSystem.Write[Sync]
    ) < (Sync & Scope) =
        for
            upper    <- FileSystem.inMemory
            baseline <- AtomicRef.init(Maybe.empty[ZipReadOnlyFileSystem.Index])
            deleted  <- AtomicRef.init(Set.empty[String])
            active <- Scope.acquireRelease(AtomicBoolean.init(true)) { active =>
                active.compareAndSet(true, false).map { wasOpen =>
                    if wasOpen then deleted.set(Set.empty).andThen(Abort.run(upper.removeAll(Path())).map(_ => ())) else ()
                }
            }
        yield new ZipRewriteFileSystem(archive, upper, baseline, deleted, active)
end ZipRewriteFileSystem

final private[kyo] class ZipRewriteFileSystem(
    archive: Path,
    upper: FileSystem.Write[Sync],
    baselineRef: AtomicRef[Maybe[ZipReadOnlyFileSystem.Index]],
    deletedRef: AtomicRef[Set[String]],
    active: AtomicBoolean
) extends FileSystem.Write[Sync]
    with FileSystem.StagedChanges[Sync & Abort[FileSystemException]]:

    final private case class TransferPlan(
        baselineParents: Chunk[Path],
        sourceWasInUpper: Boolean,
        targetWasInUpper: Boolean
    )

    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync = Glob.CaseSensitivity.Sensitive

    private def name(path: Path): String      = path.parts.mkString("/")
    private def pathFromName(n: String): Path = if n.isEmpty then Path() else Path(n.split("/", -1).toIndexedSeq*)

    private def untombstone(n: String)(using Frame): Unit < Sync     = deletedRef.get.map(cur => deletedRef.set(cur - n))
    private def tombstone(n: String)(using Frame): Unit < Sync       = deletedRef.get.map(cur => deletedRef.set(cur + n))
    private def isTombstoned(n: String)(using Frame): Boolean < Sync = deletedRef.get.map(_.contains(n))

    // Loads and memoizes archive's baseline entry index on first use. A missing archive is an
    // empty baseline (the fresh-archive case, item 8's file->zip migrate target), never a
    // construction-time or first-use error; a present-but-malformed archive surfaces its typed
    // FileSystemException here, at first touch.
    private def baseline(using Frame): ZipReadOnlyFileSystem.Index < (Sync & Abort[FileReadException]) =
        baselineRef.get.map {
            case Present(idx) => idx
            case Absent       => loadBaseline
        }
    private def loadBaseline(using Frame): ZipReadOnlyFileSystem.Index < (Sync & Abort[FileReadException]) =
        Sync.Unsafe.defer(Abort.get(archive.unsafe.exists())).map { present =>
            if !present then
                val empty = ZipReadOnlyFileSystem.Index(Map.empty, Map.empty, Map("" -> Chunk.empty), Map.empty, Array.emptyByteArray)
                baselineRef.set(Present(empty)).andThen(empty)
            else
                // Unsafe: reads the archive's full bytes through the cross-platform Path surface
                Sync.Unsafe.defer(Abort.get(archive.unsafe.readBytes())).map { span =>
                    val bytes = span.toArrayUnsafe
                    Sync.Unsafe.defer {
                        try
                            val idx = ZipReadOnlyFileSystem.buildIndex(ZipArchive.parse(bytes), bytes)
                            baselineRef.set(Present(idx)).andThen(idx)
                        catch
                            case e: ZipArchive.ZipFormatException =>
                                Abort.fail(FileIOException(archive, FileSystemOperation.Read, new IOException(e.getMessage)))
                    }
                }
        }
    private def readBaselineEntry(n: String)(using Frame): Array[Byte] < (Sync & Abort[FileReadException]) =
        baseline.map { idx =>
            idx.entries.get(n) match
                case Some(entry) =>
                    Sync.Unsafe.defer {
                        try ZipArchive.readEntry(idx.bytes, entry)
                        catch
                            case e: ZipArchive.ZipFormatException =>
                                Abort.fail(FileIOException(archive, FileSystemOperation.Read, new IOException(e.getMessage)))
                    }
                case None => Abort.fail(FileNotFoundException(pathFromName(n)))
        }

    // Seeds the upper with a baseline-only file's content before the first mutation, so an
    // append / openWrite(append = true) / move against a path staged nowhere yet reads the
    // archive's own prior bytes rather than starting from empty. A no-op for a path already
    // staged, already tombstoned, or that is a directory.
    private def seedFromBaselineIfAbsent(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        val n = name(path)
        upper.exists(path).map { inUpper =>
            if inUpper then ()
            else
                isTombstoned(n).map { td =>
                    if td then ()
                    else
                        baseline.map { idx =>
                            idx.kinds.get(n) match
                                case Some(false) =>
                                    readBaselineEntry(n).map { bytes =>
                                        upper.writeBytes(path, Span.from(bytes), Path.WriteOptions()).andThen {
                                            idx.entryStats.get(n) match
                                                case Some(stat) => upper.setLastModified(path, stat.lastModifiedMs)
                                                case None       => ()
                                        }
                                    }
                                case _ => ()
                        }
                }
        }
    end seedFromBaselineIfAbsent

    private def resolveKind(path: Path)(using Frame): Maybe[Boolean] < (Sync & Abort[FileReadException]) =
        val n = name(path)
        upper.exists(path).map { inUpper =>
            if inUpper then upper.isDirectory(path).map(Present(_))
            else
                isTombstoned(n).map { td =>
                    if td then Absent
                    else baseline.map(idx => idx.kinds.get(n).fold(Maybe.empty[Boolean])(Present(_)))
                }
        }
    end resolveKind

    def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = resolveKind(path).map(_.isDefined)
    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) = exists(path)
    def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])    = resolveKind(path).map(_.contains(true))
    def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])  = resolveKind(path).map(_.contains(false))
    def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = false
    def realPath(path: Path)(using
        Frame
    ): Path < (Sync & Abort[
        FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
    ]) = path

    def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
        val n = name(path)
        upper.exists(path).map { inUpper =>
            if inUpper then upper.readBytes(path)
            else
                isTombstoned(n).map { td =>
                    if td then Abort.fail(FileNotFoundException(path))
                    else
                        baseline.map { idx =>
                            idx.kinds.get(n) match
                                case Some(true)  => Abort.fail(FileIsADirectoryException(path))
                                case Some(false) => readBaselineEntry(n).map(Span.from)
                                case None        => Abort.fail(FileNotFoundException(path))
                        }
                }
        }
    end readBytes
    def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))
    def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))
    def readLines(path: Path)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def size(path: Path)(using Frame): Long < (Sync & Abort[FileReadException]) = readBytes(path).map(_.size.toLong)
    def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
        val n = name(path)
        upper.exists(path).map { inUpper =>
            if inUpper then upper.stat(path)
            else
                isTombstoned(n).map { td =>
                    if td then Abort.fail(FileNotFoundException(path))
                    else
                        baseline.map { idx =>
                            idx.kinds.get(n) match
                                case Some(_) => idx.entryStats.getOrElse(n, Path.PathStat(0L, 0L))
                                case None    => Abort.fail(FileNotFoundException(path))
                        }
                }
        }
    end stat

    def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    // The upper's own walk only; a baseline-only subtree not yet touched via list()/exists() on this
    // handle is not enumerated by a raw walk. list() below already merges both views for the
    // direct-children case its callers depend on (including materialize's own walkAll, which merges
    // independently).
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
        Frame
    ): Path.WalkHandle < (Sync & Abort[FileReadException | FileStructureException]) =
        upper.openWalk(path, maxDepth, followLinks)

    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        untombstone(name(path)).andThen(upper.write(path, value, options))
    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        untombstone(name(path)).andThen(upper.writeBytes(path, value, options))
    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        untombstone(name(path)).andThen(upper.writeLines(path, value, options))
    def append(path: Path, value: String, options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.append(path, value, options))
    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.appendBytes(path, value, options))
    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.appendLines(path, value, options))
    def truncate(path: Path, size: Long)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.truncate(path, size))
    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        untombstone(name(path)).andThen(upper.setLastModified(path, epochMs))
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (Sync & Abort[FileReadException | FileWriteException]) =
        (if append then seedFromBaselineIfAbsent(path) else Sync.defer(()))
            .andThen(untombstone(name(path))).andThen(upper.openWrite(path, append, options))
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        upper.writeChunk(handle, chunk)
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        upper.writeString(handle, value, charset)
    def mkDir(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        untombstone(name(path)).andThen(upper.mkDir(path))
    def mkFile(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException | FileStructureException]) =
        untombstone(name(path)).andThen(upper.mkFile(path))
    def move(
        from: Path,
        to: Path,
        options: Path.MoveOptions
    )(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        preflightTransfer(from, to, options.replace, options.createFolders).map { plan =>
            runTransfer(plan, from, to) {
                upper.move(from, to, options)
            } {
                tombstone(name(from)).andThen(untombstone(name(to)))
            }
        }
    def copy(from: Path, to: Path, options: Path.CopyOptions)(using
        Frame
    )
        : Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        preflightTransfer(from, to, options.replace, options.createFolders).map { plan =>
            runTransfer(plan, from, to) {
                upper.copy(from, to, options)
            } {
                untombstone(name(to))
            }
        }

    private def preflightTransfer(from: Path, to: Path, replace: Path.Replace, createFolders: Boolean)(using
        Frame
    ): TransferPlan < (Sync & Abort[FileReadException | FileStructureException]) =
        resolveKind(from).map {
            case Absent => Abort.fail(FileNotFoundException(from))
            case Present(_) =>
                resolveKind(to).map { target =>
                    if target.isDefined && replace == Path.Replace.Never then Abort.fail(FileAlreadyExistsException(to))
                    else
                        val parentChain = Chunk.from(
                            (1 until to.parts.size).map(size => Path(to.parts.take(size).toIndexedSeq*))
                        )
                        parentChain.foldLeft[Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException])](Chunk.empty) {
                            (parentsKyo, parent) =>
                                parentsKyo.map { parents =>
                                    resolveKind(parent).map {
                                        case Present(true) =>
                                            upper.exists(parent).map(inUpper => if inUpper then parents else parents :+ parent)
                                        case Present(false) => Abort.fail(FileNotADirectoryException(parent))
                                        case Absent =>
                                            if createFolders then parents
                                            else Abort.fail(FileNotFoundException(parent))
                                    }
                                }
                        }.map { baselineParents =>
                            upper.exists(from).map { sourceWasInUpper =>
                                upper.exists(to).map { targetWasInUpper =>
                                    TransferPlan(baselineParents, sourceWasInUpper, targetWasInUpper)
                                }
                            }
                        }
                }
        }
    end preflightTransfer

    private def materializeBaselineParents(parents: Chunk[Path])(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        baseline.map { idx =>
            parents.foldLeft[Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException])](()) {
                (effect, parent) =>
                    effect.andThen {
                        upper.mkDir(parent).andThen {
                            idx.entryStats.get(name(parent)) match
                                case Some(stat) => upper.setLastModified(parent, stat.lastModifiedMs)
                                case None       => ()
                        }
                    }
            }
        }
    end materializeBaselineParents

    private def rollbackTransfer(plan: TransferPlan, from: Path, to: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        val removeTarget: Unit < (Sync & Abort[FileReadException | FileStructureException]) =
            if plan.targetWasInUpper then () else upper.removeAll(to)
        val removeSource: Unit < (Sync & Abort[FileReadException | FileStructureException]) =
            if plan.sourceWasInUpper then () else upper.removeAll(from)
        plan.baselineParents.reverse.foldLeft[Unit < (Sync & Abort[FileReadException | FileStructureException])](
            removeTarget.andThen(removeSource)
        ) { (effect, parent) =>
            effect.andThen(upper.removeAll(parent))
        }
    end rollbackTransfer

    private def runTransfer(
        plan: TransferPlan,
        from: Path,
        to: Path
    )(
        transfer: => Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException])
    )(
        complete: => Unit < Sync
    )(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        val attempt = materializeBaselineParents(plan.baselineParents)
            .andThen(seedFromBaselineIfAbsent(from))
            .andThen(transfer)
        Abort.run[FileReadException | FileWriteException | FileStructureException](attempt).map {
            case Result.Success(_) => complete
            case Result.Failure(e) => rollbackTransfer(plan, from, to).andThen(Abort.fail(e))
        }
    end runTransfer

    def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException | FileStructureException]) =
        resolveKind(path).map(k => upper.remove(path).andThen(tombstone(name(path))).andThen(k.isDefined))
    def removeExisting(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))
    def removeAll(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        upper.removeAll(path).andThen(tombstone(name(path)))
    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) = upper.tempDir(prefix)
    override def siblingTemporary(target: Path)(using
        Frame
    ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
        upper.siblingTemporary(target)

    override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
        upper.tempFileHandle(temporary)

    def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
        val n = name(path)
        resolveKind(path).map {
            case Absent         => Abort.fail(FileNotFoundException(path))
            case Present(false) => Abort.fail(FileNotADirectoryException(path))
            case Present(true) =>
                upper.exists(path).map { upperHasDir =>
                    (if upperHasDir then upper.list(path) else Sync.defer(Chunk.empty[Path])).map { fromUpper =>
                        baseline.map { idx =>
                            deletedRef.get.map { tomb =>
                                val fromBaseline = idx.children.getOrElse(n, Chunk.empty)
                                    .filterNot(seg => tomb.contains(if n.isEmpty then seg else s"$n/$seg"))
                                    .map(seg => path / seg)
                                Chunk.from((fromUpper.toIndexedSeq ++ fromBaseline.toIndexedSeq).distinct.sortBy(p =>
                                    p.parts.mkString("/")
                                ))
                            }
                        }
                    }
                }
        }
    end list
    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
        list(path).map(_.filter(p => glob.matches(Chunk(p.parts.last), caseSensitivity)))

    private def readChannelSnapshot(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        readBytes(path).map { bytes =>
            val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
            val channel = new Path.ReadChannel[Sync]:
                private def check(using Frame): Unit < Abort[FileReadException] =
                    if closed.get() then Abort.fail(FileIOException(path, FileSystemOperation.Read, new IOException("channel is closed")))
                    else ()
                def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
                    check.andThen {
                        if position < 0L || position > Int.MaxValue || length < 0 then
                            Abort.fail(FileIOException(path, FileSystemOperation.Read, new IOException("invalid channel read bounds")))
                        else bytes.drop(position.toInt).take(length)
                    }
                def size(using Frame): Long < (Sync & Abort[FileReadException]) = check.andThen(bytes.size.toLong)
            val release: () => Unit < Sync = () =>
                Sync.defer {
                    closed.compareAndSet(false, true)
                    ()
                }
            (channel, release)
        }
    private def seedForWrite(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Channel, e)))(seedFromBaselineIfAbsent(
            path
        ))

    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
        readChannelSnapshot(path).map { case (channel, release) => Scope.acquireRelease(channel)(_ => release()) }
    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[Sync] < (Sync & Scope & Abort[FileWriteException | FileStructureException]) =
        seedForWrite(path).andThen(untombstone(name(path))).andThen(upper.openWriteChannel(path, open))
    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[Sync] < (Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.openReadWriteChannel(path, open))
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        readChannelSnapshot(path)
    private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
        (Sync & Abort[FileWriteException | FileStructureException]) =
        seedForWrite(path).andThen(untombstone(name(path))).andThen(upper.openWriteChannelUnscoped(path, open))
    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.ReadWriteChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.openReadWriteChannelUnscoped(path, open))
    def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) = upper.syncDirectory(path)
    def durableReplace(target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        FileSystem.durableReplace[Any](this, target, bytes)
    def tryLock(path: Path, mode: Path.LockMode)(using
        Frame
    ): Maybe[Path.Lock] < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        upper.tryLock(path, mode)
    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
        Frame
    ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        upper.lock(path, mode, wait)

    // --- Commit / rollback ---

    private def walkUpperAll(using Frame): Chunk[ZipArchive.WriteEntry] < (Sync & Abort[FileSystemException]) =
        upper.openWalk(Path(), Int.MaxValue, followLinks = false).map { handle =>
            Loop(Chunk.empty[ZipArchive.WriteEntry]) { acc =>
                Sync.Unsafe.defer(handle.next()).map {
                    case Absent => Sync.Unsafe.defer(handle.close()).andThen(Loop.done(acc))
                    case Present(p) =>
                        upper.isDirectory(p).map { isDir =>
                            upper.stat(p).map { stat =>
                                if isDir then
                                    Loop.continue(acc :+ ZipArchive.WriteEntry(name(p), true, Array.emptyByteArray, stat.lastModifiedMs))
                                else
                                    upper.readBytes(p).map(bytes =>
                                        Loop.continue(acc :+ ZipArchive.WriteEntry(
                                            name(p),
                                            false,
                                            bytes.toArrayUnsafe,
                                            stat.lastModifiedMs
                                        ))
                                    )
                            }
                        }
                }
            }
        }
    private def walkAll(using Frame): Chunk[ZipArchive.WriteEntry] < (Sync & Abort[FileSystemException]) =
        baseline.map { idx =>
            deletedRef.get.map { tomb =>
                val baselineNames = Chunk.from(idx.kinds.keys.filter(n => n.nonEmpty && !tomb.contains(n)).toIndexedSeq)
                Loop(baselineNames, Chunk.empty[ZipArchive.WriteEntry]) { (remaining, acc) =>
                    remaining.headMaybe match
                        case Absent => Loop.done(acc)
                        case Present(n) =>
                            val rest = remaining.dropLeft(1)
                            upper.exists(pathFromName(n)).map { shadowed =>
                                if shadowed then Loop.continue(rest, acc)
                                else
                                    val stat = idx.entryStats.getOrElse(n, Path.PathStat(0L, 0L))
                                    if idx.kinds(n) then
                                        Loop.continue(
                                            rest,
                                            acc :+ ZipArchive.WriteEntry(n, true, Array.emptyByteArray, stat.lastModifiedMs)
                                        )
                                    else
                                        readBaselineEntry(n).map(bytes =>
                                            Loop.continue(rest, acc :+ ZipArchive.WriteEntry(n, false, bytes, stat.lastModifiedMs))
                                        )
                                    end if
                            }
                }.map(fromBaseline => walkUpperAll.map(fromUpper => fromBaseline ++ fromUpper))
            }
        }

    // Injection hooks marking the three points at which a commit can be cut short, mirroring the
    // set OverlayFileSystem carries. Defaults are no-ops; a test replaces one with a function that
    // throws. Single-writer semantics: only one test sets and clears a hook at a time.
    // private[kyo] so tests (same package) can reach them.
    //
    // The points are chosen by what survives at each. After the temporary directory exists but
    // before the archive bytes are written, nothing durable has changed. After those bytes are
    // written but before the move, the archive is still the old one and a full second copy is on
    // disk. After the move, the new archive is in place and the temporary must be gone. Cutting
    // between them is what decides whether the archive survives a commit that does not finish.
    // afterTempDirHook receives the staging directory so a test can assert it was removed on the
    // way out; the other two need no argument.
    private[kyo] var afterTempDirHook: Path => Unit    = _ => ()
    private[kyo] var afterArchiveWriteHook: () => Unit = () => ()
    private[kyo] var afterMoveHook: () => Unit         = () => ()

    private def materialize(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        walkAll.map { entries =>
            Path.tempDirUnscoped("kyo-zip-rewrite").map { tmpDir =>
                val tmpArchive = tmpDir / "archive.zip"
                val bytes      = ZipArchive.write(entries)
                // Removed on every exit, not only on the success path. The directory holds a complete
                // second copy of the archive, so an abort or interrupt between creating it and
                // completing the move would leak both the directory and that copy for the life of
                // the process, once per failed commit.
                Sync.ensure {
                    // Unsafe: recursive delete of the directory created immediately above
                    Sync.Unsafe.defer {
                        val _ = tmpDir.unsafe.removeAll()
                        ()
                    }
                } {
                    Sync.defer(afterTempDirHook(tmpDir))
                        .andThen(FileSystem.host.writeBytes(tmpArchive, Span.fromUnsafe(bytes), Path.WriteOptions()))
                        .andThen(Sync.defer(afterArchiveWriteHook()))
                        .andThen(FileSystem.host.move(
                            tmpArchive,
                            archive,
                            Path.MoveOptions(replace = Path.Replace.Existing, atomicity = Path.Atomicity.Required)
                        ))
                        .andThen(Sync.defer(afterMoveHook()))
                }
            }
        }

    private def terminate(action: String)(using Frame): Unit < (Sync & Abort[CommitConflict]) =
        active.compareAndSet(true, false).map { claimed =>
            if claimed then () else Abort.fail(FileSystem.StagedChanges.AlreadyTerminated(action))
        }

    def commit(using Frame): Unit < (Sync & Abort[FileSystemException] & Abort[CommitConflict]) =
        terminate("commit").andThen(materialize)

    def commitWith(resolve: FileSystem.Conflict => FileSystem.Resolution)(using
        Frame
    ): Unit < (Sync & Abort[FileSystemException] & Abort[CommitConflict]) =
        terminate("commitWith").andThen(materialize)

    def discard(using Frame): Unit < (Sync & Abort[FileSystem.StagedChanges.TerminalState]) =
        active.compareAndSet(true, false).map { claimed =>
            if claimed then deletedRef.set(Set.empty).andThen(Abort.run(upper.removeAll(Path())).map(_ => ()))
            else Abort.fail(FileSystem.StagedChanges.AlreadyTerminated("discard"))
        }
end ZipRewriteFileSystem
