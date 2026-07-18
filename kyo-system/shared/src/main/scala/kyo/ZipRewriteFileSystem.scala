package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.ZipArchive

/** [[FileSystem.zip]]'s implementor: a [[FileSystem.CommitHandle]] whose reads fall through to
  * `archive`'s baseline entries (parsed once, lazily, on first touch, from the archive's own bytes
  * read through the cross-platform [[Path.Unsafe.readBytes]] surface) when a path is not staged,
  * whose writes always land in an in-memory upper (`FileSystem.inMemory`, the same staging primitive
  * [[FileSystem.overlay]] itself is built from), and whose commit serializes the whole archive from
  * the merged (baseline minus tombstoned minus shadowed, plus upper) view via
  * [[kyo.internal.ZipArchive.write]] (a uniform pure-Scala STORED writer, no `java.util.zip`
  * anywhere), atomically moved into place via [[FileSystem.host]]. There is no in-place
  * random-access write into a compressed entry and no live lower to validate a read-set against, so
  * `commit` and `commitOverwrite` are behaviorally identical: both always succeed or fail on I/O
  * alone, never raising `CommitConflict`.
  */
private[kyo] object ZipRewriteFileSystem:

    def init(archive: Path)(using Frame): FileSystem.CommitHandle[Sync] < (Sync & Scope) =
        for
            upper    <- FileSystem.inMemory
            baseline <- AtomicRef.init(Maybe.empty[ZipReadOnlyFileSystem.Index])
            deleted  <- AtomicRef.init(Set.empty[String])
        yield new ZipRewriteFileSystem(archive, upper, baseline, deleted)
end ZipRewriteFileSystem

final private[kyo] class ZipRewriteFileSystem(
    archive: Path,
    upper: FileSystem[Sync],
    baselineRef: AtomicRef[Maybe[ZipReadOnlyFileSystem.Index]],
    deletedRef: AtomicRef[Set[String]]
)(using Frame) extends FileSystem.CommitHandle[Sync]:

    val commitStrategy: FileSystem.CommitStrategy = FileSystem.CommitStrategy.Manual

    private def name(path: Path): String      = path.parts.mkString("/")
    private def pathFromName(n: String): Path = if n.isEmpty then Path() else Path(n.split("/", -1).toIndexedSeq*)

    private def untombstone(n: String): Unit < Sync     = deletedRef.get.map(cur => deletedRef.set(cur - n))
    private def tombstone(n: String): Unit < Sync       = deletedRef.get.map(cur => deletedRef.set(cur + n))
    private def isTombstoned(n: String): Boolean < Sync = deletedRef.get.map(_.contains(n))

    // Loads and memoizes archive's baseline entry index on first use. A missing archive is an
    // empty baseline (the fresh-archive case, item 8's file->zip migrate target), never a
    // construction-time or first-use error; a present-but-malformed archive surfaces its typed
    // FileException here, at first touch.
    private def baseline: ZipReadOnlyFileSystem.Index < (Sync & Abort[FileException]) =
        baselineRef.get.map {
            case Present(idx) => idx
            case Absent       => loadBaseline
        }
    private def loadBaseline: ZipReadOnlyFileSystem.Index < (Sync & Abort[FileException]) =
        Sync.Unsafe.defer(archive.unsafe.exists()).map { present =>
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
                            case e: ZipArchive.ZipFormatException => Abort.fail(FileIOException(archive, new IOException(e.getMessage)))
                    }
                }
        }
    private def readBaselineEntry(n: String): Array[Byte] < (Sync & Abort[FileException]) =
        baseline.map { idx =>
            idx.entries.get(n) match
                case Some(entry) =>
                    Sync.Unsafe.defer {
                        try ZipArchive.readEntry(idx.bytes, entry)
                        catch case e: ZipArchive.ZipFormatException => Abort.fail(FileIOException(archive, new IOException(e.getMessage)))
                    }
                case None => Abort.fail(FileNotFoundException(pathFromName(n)))
        }

    // Seeds the upper with a baseline-only file's content before the first mutation, so an
    // append / openWrite(append = true) / move against a path staged nowhere yet reads the
    // archive's own prior bytes rather than starting from empty. A no-op for a path already
    // staged, already tombstoned, or that is a directory.
    private def seedFromBaselineIfAbsent(path: Path): Unit < (Sync & Abort[FileException]) =
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
                                    readBaselineEntry(n).map(bytes => upper.writeBytes(path, Span.from(bytes), createFolders = true))
                                case _ => ()
                        }
                }
        }
    end seedFromBaselineIfAbsent

    private def resolveKind(path: Path): Maybe[Boolean] < (Sync & Abort[FileException]) =
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

    def exists(path: Path): Boolean < (Sync & Abort[FileException])                       = resolveKind(path).map(_.isDefined)
    def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) = exists(path)
    def isDirectory(path: Path): Boolean < (Sync & Abort[FileException])                  = resolveKind(path).map(_.contains(true))
    def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException])                = resolveKind(path).map(_.contains(false))
    def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException])               = false
    def realPath(path: Path): Path < (Sync & Abort[FileException])                        = path

    def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException]) =
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
    def read(path: Path): String < (Sync & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))
    def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))
    def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def size(path: Path): Long < (Sync & Abort[FileException]) = readBytes(path).map(_.size.toLong)
    def stat(path: Path): Path.PathStat < (Sync & Abort[FileException]) =
        val n = name(path)
        upper.exists(path).map { inUpper =>
            if inUpper then upper.stat(path)
            else
                isTombstoned(n).map { td =>
                    if td then Abort.fail(FileNotFoundException(path))
                    else
                        baseline.map { idx =>
                            idx.kinds.get(n) match
                                case Some(true)  => Path.PathStat(0L, 0L)
                                case Some(false) => idx.fileStats.getOrElse(n, Path.PathStat(0L, 0L))
                                case None        => Abort.fail(FileNotFoundException(path))
                        }
                }
        }
    end stat

    def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    // The upper's own walk only; a baseline-only subtree not yet touched via list()/exists() on this
    // handle is not enumerated by a raw walk. list() below already merges both views for the
    // direct-children case its callers depend on (including materialize's own walkAll, which merges
    // independently).
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
        upper.openWalk(path, maxDepth, followLinks)

    def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        untombstone(name(path)).andThen(upper.write(path, value, createFolders))
    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        untombstone(name(path)).andThen(upper.writeBytes(path, value, createFolders))
    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        untombstone(name(path)).andThen(upper.writeLines(path, value, createFolders))
    def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.append(path, value, createFolders))
    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.appendBytes(path, value, createFolders))
    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.appendLines(path, value, createFolders))
    def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(path).andThen(untombstone(name(path))).andThen(upper.truncate(path, size))
    def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
        untombstone(name(path)).andThen(upper.setLastModified(path, epochMs))
    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
        (if append then seedFromBaselineIfAbsent(path) else Sync.defer(()))
            .andThen(untombstone(name(path))).andThen(upper.openWrite(path, append, createFolders))
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) = upper.writeChunk(handle, chunk)
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
        upper.writeString(handle, value, charset)
    def mkDir(path: Path): Unit < (Sync & Abort[FileException])  = untombstone(name(path)).andThen(upper.mkDir(path))
    def mkFile(path: Path): Unit < (Sync & Abort[FileException]) = untombstone(name(path)).andThen(upper.mkFile(path))
    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(from).andThen(tombstone(name(from))).andThen(untombstone(name(to)))
            .andThen(upper.move(from, to, replaceExisting, atomicMove, createFolders))
    def copy(from: Path, to: Path, followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean, createFolders: Boolean)
        : Unit < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(from).andThen(untombstone(name(to)))
            .andThen(upper.copy(from, to, followLinks, replaceExisting, copyAttributes, createFolders))
    def remove(path: Path): Boolean < (Sync & Abort[FileException]) =
        resolveKind(path).map(k => upper.remove(path).andThen(tombstone(name(path))).andThen(k.isDefined))
    def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))
    def removeAll(path: Path): Unit < (Sync & Abort[FileException]) =
        upper.removeAll(path).andThen(tombstone(name(path)))
    def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) = upper.tempDir(prefix)

    def list(path: Path): Chunk[Path] < (Sync & Abort[FileException]) =
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
    def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) =
        list(path).map(_.filter(p => p.name.exists(InMemoryHandles.matchesGlob(_, glob))))

    def openChannel(path: Path, mode: FileSystem.ChannelMode): Path.Channel[Sync] < (Sync & Scope & Abort[FileException]) =
        seedFromBaselineIfAbsent(path)
            .andThen(mode match
                case FileSystem.ChannelMode.Read => Sync.defer(())
                case _                           => untombstone(name(path)))
            .andThen(upper.openChannel(path, mode))
    private[kyo] def openChannelUnscoped(path: Path, mode: FileSystem.ChannelMode)(using
        Frame
    )
        : (Path.Channel[Sync], () => Unit < Sync) < (Sync & Abort[FileException]) =
        seedFromBaselineIfAbsent(path)
            .andThen(mode match
                case FileSystem.ChannelMode.Read => Sync.defer(())
                case _                           => untombstone(name(path)))
            .andThen(upper.openChannelUnscoped(path, mode))
    def syncDir(path: Path): Unit < (Sync & Abort[FileException])                                   = upper.syncDir(path)
    def lock(path: Path, exclusive: Boolean): Path.FileLock < (Sync & Scope & Abort[FileException]) = upper.lock(path, exclusive)
    private[kyo] def lockUnscoped(path: Path, exclusive: Boolean)(using
        Frame
    ): (Path.FileLock, () => Unit < Sync) < (Sync & Abort[FileException]) =
        upper.lockUnscoped(path, exclusive)

    // --- Commit / rollback ---

    private def walkUpperAll: Chunk[(String, Boolean, Array[Byte])] < (Sync & Abort[FileException]) =
        upper.openWalk(Path(), Int.MaxValue, followLinks = false).map { handle =>
            Loop(Chunk.empty[(String, Boolean, Array[Byte])]) { acc =>
                Sync.Unsafe.defer(handle.next()).map {
                    case Absent => Sync.Unsafe.defer(handle.close()).andThen(Loop.done(acc))
                    case Present(p) =>
                        upper.isDirectory(p).map { isDir =>
                            if isDir then Loop.continue(acc :+ ((name(p), true, Array.emptyByteArray)))
                            else upper.readBytes(p).map(bytes => Loop.continue(acc :+ ((name(p), false, bytes.toArrayUnsafe))))
                        }
                }
            }
        }
    private def walkAll: Chunk[(String, Boolean, Array[Byte])] < (Sync & Abort[FileException]) =
        baseline.map { idx =>
            deletedRef.get.map { tomb =>
                val baselineNames = Chunk.from(idx.kinds.keys.filter(n => n.nonEmpty && !tomb.contains(n)).toIndexedSeq)
                Loop(baselineNames, Chunk.empty[(String, Boolean, Array[Byte])]) { (remaining, acc) =>
                    remaining.headMaybe match
                        case Absent => Loop.done(acc)
                        case Present(n) =>
                            val rest = remaining.dropLeft(1)
                            upper.exists(pathFromName(n)).map { shadowed =>
                                if shadowed then Loop.continue(rest, acc)
                                else if idx.kinds(n) then Loop.continue(rest, acc :+ ((n, true, Array.emptyByteArray)))
                                else readBaselineEntry(n).map(bytes => Loop.continue(rest, acc :+ ((n, false, bytes))))
                            }
                }.map(fromBaseline => walkUpperAll.map(fromUpper => fromBaseline ++ fromUpper))
            }
        }

    private def materialize: Unit < (Sync & Abort[FileException]) =
        walkAll.map { entries =>
            Path.tempDirUnscoped("kyo-zip-rewrite").map { tmpDir =>
                val tmpArchive = tmpDir / "archive.zip"
                val bytes      = ZipArchive.write(entries)
                FileSystem.host.writeBytes(tmpArchive, Span.fromUnsafe(bytes), createFolders = true)
                    .andThen(FileSystem.host.move(tmpArchive, archive, replaceExisting = true, atomicMove = true, createFolders = true))
                    .andThen(Sync.Unsafe.defer(discard(tmpDir.unsafe.removeAll())))
            }
        }

    def commitOverwrite(using Frame): Unit < (Sync & Abort[FileException]) = materialize

    // The trait's own `commit` signature carries `Abort[CommitConflict]` (FileSystem.CommitHandle);
    // a zip rewrite never validates a read-set against a live lower (there is no lower, only a
    // baseline read once at first touch), so this never actually raises CommitConflict.
    def commit(using Frame): Unit < (Sync & Abort[FileException] & Abort[CommitConflict]) = materialize

    def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (Sync & Abort[FileException] & S2) = materialize

    def rollback(using Frame): Unit < Sync =
        deletedRef.set(Set.empty).andThen(Abort.run(upper.removeAll(Path())).map(_ => ()))
end ZipRewriteFileSystem
