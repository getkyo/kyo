package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.ZipArchive

/** [[FileSystem.zip]]'s staged-write implementor whose reads fall through to
  * `archive`'s baseline entries (parsed once, lazily, on first touch, from the archive's own bytes
  * read through the cross-platform [[Path.Unsafe.readBytes]] surface) when a path is not staged,
  * whose writes always land in an in-memory upper (`FileSystem.inMemory`), and whose commit
  * serializes the whole archive from
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
            active   <- Scope.acquireRelease(AtomicBoolean.init(true)) { active =>
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

    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync = Glob.CaseSensitivity.Sensitive

    private def name(path: Path): String      = path.parts.mkString("/")
    private def pathFromName(n: String): Path = if n.isEmpty then Path() else Path(n.split("/", -1).toIndexedSeq*)

    private def tombstone(n: String)(using Frame): Unit < Sync = deletedRef.updateAndGet(_ + n).unit

    // A removed entry hides every baseline entry beneath it as well, and keeps hiding them once the entry is created
    // again in the upper: the new entry starts empty, as it would on a host.
    private def hidden(n: String, tomb: Set[String]): Boolean =
        tomb.contains("") || tomb.contains(n) || n.indices.exists(i => n.charAt(i) == '/' && tomb.contains(n.substring(0, i)))

    private def ancestors(path: Path): Chunk[Path] =
        Chunk.from((1 until path.parts.size).map(size => Path(path.parts.take(size).toIndexedSeq*)))

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

    /** The kind of the baseline entry named `n` (`true` for a directory) as the merged view shows it, absent when a removal
      * hides it.
      */
    private def baselineKind(n: String)(using Frame): Maybe[Boolean] < (Sync & Abort[FileReadException]) =
        deletedRef.get.map { tomb =>
            if hidden(n, tomb) then Absent
            else baseline.map(idx => idx.kinds.get(n).fold(Maybe.empty[Boolean])(Present(_)))
        }

    /** The kind of the entry at `path` in the merged view: the upper's when it holds one, the baseline's otherwise. */
    private def resolveKind(path: Path)(using Frame): Maybe[Boolean] < (Sync & Abort[FileReadException]) =
        upper.exists(path).map { inUpper =>
            if inUpper then upper.isDirectory(path).map(Present(_))
            else baselineKind(name(path))
        }

    /** The entries directly inside the directory at `path` in the merged view, in name order. */
    private def children(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
        upper.isDirectory(path).map { inUpper =>
            (if inUpper then upper.list(path) else Sync.defer(Chunk.empty[Path])).map { fromUpper =>
                baseline.map { idx =>
                    deletedRef.get.map { tomb =>
                        val fromBaseline = idx.children.getOrElse(name(path), Chunk.empty)
                            .map(segment => path / segment)
                            .filterNot(child => hidden(name(child), tomb))
                        Chunk.from((fromUpper.toIndexedSeq ++ fromBaseline.toIndexedSeq).distinct.sortBy(name))
                    }
                }
            }
        }

    // Every read failure is also a write failure, so a read the write makes first reports in the write's row as it is.
    private def writing[A](v: A < (Sync & Abort[FileReadException | FileWriteException]))(using
        Frame
    ): A < (Sync & Abort[FileWriteException]) =
        Abort.recover[FileReadException] {
            case error: (FileNotFoundException | FileAccessDeniedException | FileIsADirectoryException |
                    FileInvalidPathException | FileOutsideRootException | FileIOException) => Abort.fail(error)
        }(v)

    // A structural change stages through writes; a write failure that is neither a read nor a structural one reports as
    // an I/O failure of the change.
    private def structural[A](path: Path, operation: FileSystemOperation)(
        v: A < (Sync & Abort[FileReadException | FileWriteException | FileStructureException])
    )(using Frame): A < (Sync & Abort[FileReadException | FileStructureException]) =
        Abort.recover[FileWriteException] {
            case error: FileReadException      => Abort.fail(error)
            case error: FileStructureException => Abort.fail(error)
            case error                         => Abort.fail(FileIOException(path, operation, error))
        }(v)

    /** Refuses an entry at `path` against the merged view, as the host does: an entry beneath a regular file is not a
      * directory, and a missing parent is not found unless `createFolders` allows creating it. The upper checks only its
      * own entries, so it cannot see a baseline file above `path`.
      */
    private def placeable(path: Path, createFolders: Boolean)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileNotADirectoryException | FileNotFoundException]) =
        Loop(ancestors(path)) { remaining =>
            remaining.headMaybe match
                case Absent            => Loop.done(())
                case Present(ancestor) =>
                    resolveKind(ancestor).map {
                        case Present(true)  => Loop.continue(remaining.dropLeft(1))
                        case Present(false) => Abort.fail(FileNotADirectoryException(path))
                        case Absent         => if createFolders then Loop.done(()) else Abort.fail(FileNotFoundException(path))
                    }
        }

    /** [[placeable]] for a file write, which also refuses a directory at `path`. */
    private def fileTarget(path: Path, createFolders: Boolean)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        resolveKind(path).map {
            case Present(true) => Abort.fail(FileIsADirectoryException(path))
            case _             => placeable(path, createFolders)
        }

    /** Copies the baseline entry at `path` into the upper with its baseline stat, when the merged view shows it and the
      * upper does not hold it yet, and answers whether it did. Its parent must already be in the upper. The copy holds what
      * the baseline does, so the merged view is unchanged by it.
      */
    private def copyUp(path: Path)(using
        Frame
    ): Boolean < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        upper.exists(path).map {
            case true  => false
            case false =>
                val n = name(path)
                baselineKind(n).map {
                    case Absent         => false
                    case Present(isDir) =>
                        val copied =
                            if isDir then upper.mkDir(path)
                            else
                                readBaselineEntry(n).map(bytes =>
                                    upper.writeBytes(path, Span.from(bytes), Path.WriteOptions(createFolders = false))
                                )
                        copied.andThen(baseline).map { idx =>
                            idx.entryStats.get(n) match
                                case Some(stat) => upper.setLastModified(path, stat.lastModifiedMs).andThen(true)
                                case None       => true
                        }
                }
        }

    /** Copies the baseline directories above `path` into the upper, top down, so the upper places `path` among the archive's
      * own directories and keeps their stats. Answers what it copied.
      */
    private def stageAncestors(path: Path)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        Kyo.filter(ancestors(path))(copyUp)

    /** [[stageAncestors]], then the baseline entry at `path` itself, so a change to it starts from the archive's content. */
    private def stage(path: Path)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        stageAncestors(path).map(staged => copyUp(path).map(copied => if copied then staged.append(path) else staged))

    /** [[stage]], then every baseline entry beneath `path` the merged view shows, so the upper can move or copy the whole tree.
      * Names sort a directory before its entries, so each entry's parent is copied first.
      */
    private def stageTree(path: Path)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        stage(path).map { staged =>
            baseline.map { idx =>
                val n       = name(path)
                val prefix  = if n.isEmpty then "" else n + "/"
                val beneath = Chunk.from(idx.kinds.keys.filter(k => k.nonEmpty && k != n && k.startsWith(prefix)).toIndexedSeq.sorted)
                Kyo.filter(beneath.map(pathFromName))(copyUp).map(staged ++ _)
            }
        }

    def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = resolveKind(path).map(_.isDefined)
    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) = exists(path)
    def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])    = resolveKind(path).map(_.contains(true))
    def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])  = resolveKind(path).map(_.contains(false))
    def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = false
    def realPath(path: Path)(using
        Frame
    ): Path <
        (Sync &
            Abort[
                FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
            ]) = path

    def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
        upper.exists(path).map { inUpper =>
            if inUpper then upper.readBytes(path)
            else
                baselineKind(name(path)).map {
                    case Present(true)  => Abort.fail(FileIsADirectoryException(path))
                    case Present(false) => readBaselineEntry(name(path)).map(Span.from)
                    case Absent         => Abort.fail(FileNotFoundException(path))
                }
        }
    def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))
    def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))
    def readLines(path: Path)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))
    def size(path: Path)(using Frame): Long < (Sync & Abort[FileReadException])          = readBytes(path).map(_.size.toLong)
    def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
        upper.exists(path).map { inUpper =>
            if inUpper then upper.stat(path)
            else
                val n = name(path)
                baselineKind(n).map {
                    case Present(_) => baseline.map(idx => idx.entryStats.getOrElse(n, Path.PathStat(0L, 0L)))
                    case Absent     => Abort.fail(FileNotFoundException(path))
                }
        }
    end stat

    def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))

    /** Walks the merged view in preorder, siblings in name order, as the in-memory walk does. A path that is not a
      * directory has nothing beneath it to walk.
      */
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
        Frame
    ): Path.WalkHandle < (Sync & Abort[FileReadException | FileStructureException]) =
        def preorder(directory: Path, depth: Int): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
            if depth <= 0 then Chunk.empty
            else
                children(directory).map { entries =>
                    Kyo.foreach(entries) { entry =>
                        resolveKind(entry).map {
                            case Present(true) => preorder(entry, depth - 1).map(beneath => Chunk(entry) ++ beneath)
                            case _             => Chunk(entry)
                        }
                    }.map(_.flattenChunk)
                }
        resolveKind(path).map {
            case Present(true) => preorder(path, maxDepth)
            case _             => Chunk.empty[Path]
        }.map { walked =>
            new Path.WalkHandle:
                private var remaining                                                                                 = walked
                def next()(using AllowUnsafe, Frame): Result[FileReadException | FileStructureException, Maybe[Path]] =
                    val head = remaining.headMaybe
                    remaining = remaining.dropLeft(1)
                    Result.succeed(head)
                end next
                def close()(using AllowUnsafe): Unit = ()
        }
    end openWalk

    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        writeBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), options)
    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        writing(fileTarget(path, options.createFolders).andThen(structuralWrite(path)(stageAncestors(path))))
            .andThen(upper.writeBytes(path, value, options))
    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        write(path, value.mkString("", "\n", "\n"), options)
    def append(path: Path, value: String, options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        appendBytes(path, Span.from(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), options)
    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        fileTarget(path, options.createFolders).andThen(structuralWrite(path)(stage(path))).andThen(upper.appendBytes(path, value, options))
    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)
    def truncate(path: Path, size: Long)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        resolveKind(path).map {
            case Absent         => Abort.fail(FileNotFoundException(path))
            case Present(true)  => Abort.fail(FileIsADirectoryException(path))
            case Present(false) => structuralWrite(path)(stage(path)).andThen(upper.truncate(path, size))
        }
    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException]) =
        resolveKind(path).map {
            case Absent     => Abort.fail(FileNotFoundException(path))
            case Present(_) => structuralWrite(path)(stage(path)).andThen(upper.setLastModified(path, epochMs))
        }
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (Sync & Abort[FileReadException | FileWriteException]) =
        fileTarget(path, options.createFolders)
            .andThen(structuralWrite(path)(if append then stage(path) else stageAncestors(path)))
            .andThen(upper.openWrite(path, append, options))
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        upper.writeChunk(handle, chunk)
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        upper.writeString(handle, value, charset)
    def mkDir(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        resolveKind(path).map {
            case Present(true)  => ()
            case Present(false) => Abort.fail(FileAlreadyExistsException(path))
            case Absent         =>
                placeable(path, createFolders = true)
                    .andThen(structural(path, FileSystemOperation.Create)(stageAncestors(path)))
                    .andThen(upper.mkDir(path))
        }
    // An existing entry of either kind is left as it stands, as the host's mkFile does.
    def mkFile(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException | FileStructureException]) =
        writing(resolveKind(path)).map {
            case Present(_) => ()
            case Absent     =>
                writing(placeable(
                    path,
                    createFolders = true
                ).andThen(structuralWrite(path)(stageAncestors(path)))).andThen(upper.mkFile(path))
        }
    def move(
        from: Path,
        to: Path,
        options: Path.MoveOptions
    )(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        transfer(from, to, options.replace, options.createFolders, FileSystemOperation.Move)(upper.move(from, to, options))(
            tombstone(name(from))
        )
    def copy(from: Path, to: Path, options: Path.CopyOptions)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        transfer(from, to, options.replace, options.createFolders, FileSystemOperation.Copy)(upper.copy(from, to, options))(())

    /** Moves or copies in the merged view. Every check runs against that view before anything is staged, as the host would
      * decide: an entry onto itself is left as it stands, an entry inside itself is refused, an existing target is replaced
      * only when `replace` allows and never when it is a directory with entries, and [[placeable]] holds for the target.
      * Then the source tree and the target's baseline parents are copied into the upper, which carries the transfer out, and
      * the target hides whatever baseline entry it replaced.
      *
      * The upper's own move or copy is one step, so a transfer that still fails there (another fiber changed the tree after
      * the checks) changed nothing but the copies, and those are taken back out. A copy that fails partway leaves copies that
      * hold what the baseline does, so the merged view is unchanged by them.
      */
    private def transfer(from: Path, to: Path, replace: Path.Replace, createFolders: Boolean, operation: FileSystemOperation)(
        run: => Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException])
    )(
        complete: => Unit < Sync
    )(using Frame): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        resolveKind(from).map {
            case Absent                                        => Abort.fail(FileNotFoundException(from))
            case Present(_) if to.parts == from.parts          => ()
            case Present(_) if to.parts.startsWith(from.parts) =>
                Abort.fail(FileIOException(to, operation, new IOException(s"Cannot place $from inside itself at $to")))
            case Present(_) =>
                resolveKind(to).map {
                    case Present(_) if replace == Path.Replace.Never => Abort.fail(FileAlreadyExistsException(to))
                    case Present(true)                               =>
                        children(to).map(entries => if entries.nonEmpty then Abort.fail(FileDirectoryNotEmptyException(to)) else ())
                    case _ => ()
                }.andThen(placeable(to, createFolders)).andThen {
                    upper.exists(to).map { targetWasInUpper =>
                        stageAncestors(to).map { parents =>
                            stageTree(from).map { sources =>
                                Abort.run[FileReadException | FileWriteException | FileStructureException](run).map {
                                    case Result.Success(_)     => complete.andThen(tombstone(name(to)))
                                    case Result.Failure(error) =>
                                        unstage(parents ++ sources, to, targetWasInUpper).andThen(Abort.fail(error))
                                    case Result.Panic(error) =>
                                        unstage(parents ++ sources, to, targetWasInUpper).andThen(Abort.panic(error))
                                }
                            }
                        }
                    }
                }
        }
    end transfer

    // Takes a failed transfer's copies back out of the upper, entries before the directories that hold them.
    private def unstage(staged: Chunk[Path], to: Path, targetWasInUpper: Boolean)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        val target: Unit < (Sync & Abort[FileReadException | FileStructureException]) = if targetWasInUpper then () else upper.removeAll(to)
        target.andThen(Kyo.foreachDiscard(staged.reverse)(upper.removeAll))
    end unstage

    // A write stages through structural changes; a structural failure that is not a write failure reports as an I/O
    // failure of the write.
    private def structuralWrite[A](path: Path)(
        v: A < (Sync & Abort[FileReadException | FileWriteException | FileStructureException])
    )(using Frame): A < (Sync & Abort[FileReadException | FileWriteException]) =
        Abort.recover[FileStructureException] {
            case error: FileWriteException => Abort.fail(error)
            case error                     => Abort.fail(FileIOException(path, FileSystemOperation.Write, error))
        }(v)

    // A directory with entries in the merged view is refused, as the host's remove does; removeAll is the recursive form.
    def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException | FileStructureException]) =
        resolveKind(path).map {
            case Absent         => false
            case Present(isDir) =>
                val occupied: Unit < (Sync & Abort[FileReadException | FileStructureException]) =
                    if !isDir then ()
                    else children(path).map(entries => if entries.nonEmpty then Abort.fail(FileDirectoryNotEmptyException(path)) else ())
                occupied.andThen(upper.remove(path)).andThen(tombstone(name(path))).andThen(true)
        }
    def removeExisting(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))
    def removeAll(path: Path)(using Frame): Unit < (Sync & Abort[FileReadException | FileStructureException]) =
        upper.removeAll(path).andThen(tombstone(name(path)))
    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) = upper.tempDir(prefix)
    def temp(prefix: String, suffix: String)(using Frame): Path.TempFileHandle < (Sync & Abort[FileStructureException]) =
        upper.temp(prefix, suffix)
    private[kyo] def siblingTemporary(target: Path, onAcquire: Path.TempFileHandle => Unit)(using
        Frame
    ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
        upper.siblingTemporary(target, onAcquire)

    override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
        upper.tempFileHandle(temporary)

    def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
        resolveKind(path).map {
            case Absent         => Abort.fail(FileNotFoundException(path))
            case Present(false) => Abort.fail(FileNotADirectoryException(path))
            case Present(true)  => children(path)
        }
    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
        list(path).map(_.filter(p => glob.matches(Chunk(p.parts.last), caseSensitivity)))

    private def readChannelSnapshot(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        readBytes(path).map { bytes =>
            val closed  = new java.util.concurrent.atomic.AtomicBoolean(false)
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

    // A channel writes into the file the merged view shows, so its baseline content is copied up first. The upper creates
    // a missing parent when a channel opens, so the merged view allows that too.
    private def channelTarget(path: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        fileTarget(path, createFolders = true).andThen(stage(path)).unit

    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
        readChannelSnapshot(path).map { case (channel, release) => Scope.acquireRelease(channel)(_ => release()) }
    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[Sync] < (Sync & Scope & Abort[FileWriteException | FileStructureException]) =
        channelWriting(channelTarget(path)).andThen(upper.openWriteChannel(path, open))
    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[Sync] < (Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        channelTarget(path).andThen(upper.openReadWriteChannel(path, open))
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        readChannelSnapshot(path)
    private[kyo] def openWriteChannelUnscoped(
        path: Path,
        open: FileSystem.WriteOpen,
        onAcquire: Path.ChannelCloseHandle => Unit,
        preservePermissionsFrom: Maybe[Path] = Absent
    )(using
        Frame
    ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
        (Sync & Abort[FileWriteException | FileStructureException]) =
        channelWriting(replacementTarget(
            path,
            preservePermissionsFrom
        ).andThen(channelTarget(path))).andThen(upper.openWriteChannelUnscoped(
            path,
            open,
            onAcquire,
            preservePermissionsFrom
        ))
    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.ReadWriteChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        channelTarget(path).andThen(upper.openReadWriteChannelUnscoped(path, open))

    /** The checks the in-memory upper makes before a replacement protocol acquires its temporary, made against the merged
      * view: the target must be absent or a file, and the temporary's parent must not be a file.
      */
    private def replacementTarget(temporary: Path, target: Maybe[Path])(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        target match
            case Absent          => ()
            case Present(target) =>
                resolveKind(target).map {
                    case Present(true) => Abort.fail(FileIsADirectoryException(target))
                    case _             =>
                        Abort.recover[FileNotADirectoryException](_ =>
                            Abort.fail(FileNotADirectoryException(temporary.parent.getOrElse(Path())))
                        )(
                            placeable(temporary, createFolders = true)
                        )
                }

    // A write channel's row has no read failures; every read failure is also a write failure, so it reports as it is.
    private def channelWriting[A](v: A < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]))(using
        Frame
    ): A < (Sync & Abort[FileWriteException | FileStructureException]) =
        Abort.recover[FileReadException] {
            case error: (FileNotFoundException | FileAccessDeniedException | FileIsADirectoryException |
                    FileInvalidPathException | FileOutsideRootException | FileIOException) => Abort.fail(error)
        }(v)

    def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        val validate = Loop(0) { depth =>
            resolveKind(Path(path.parts.take(depth)*)).map {
                case Present(false) => Abort.fail(FileNotADirectoryException(path))
                case Absent         => Abort.fail(FileNotFoundException(path))
                case Present(true)  => if depth == path.parts.size then Loop.done else Loop.continue(depth + 1)
            }
        }
        writing(validate)
    end syncDirectory
    def durableReplace(target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        FileSystem.durableReplace[Sync](this, target, bytes)

    def tryLock(path: Path, mode: Path.LockMode, sentinelSuffix: String)(using
        Frame
    ): Maybe[Path.Lock] < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        upper.tryLock(path, mode, sentinelSuffix)
    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait, sentinelSuffix: String)(using
        Frame
    ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        upper.lock(path, mode, wait, sentinelSuffix)

    // --- Commit / rollback ---

    private def walkUpperAll(using Frame): Chunk[ZipArchive.WriteEntry] < (Sync & Abort[FileSystemException]) =
        upper.openWalk(Path(), Int.MaxValue, followLinks = false).map { handle =>
            Loop(Chunk.empty[ZipArchive.WriteEntry]) { acc =>
                Sync.Unsafe.defer(handle.next()).map(Abort.get(_)).map {
                    case Absent     => Sync.Unsafe.defer(handle.close()).andThen(Loop.done(acc))
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
                val baselineNames = Chunk.from(idx.kinds.keys.filter(n => n.nonEmpty && !hidden(n, tomb)).toIndexedSeq)
                Loop(baselineNames, Chunk.empty[ZipArchive.WriteEntry]) { (remaining, acc) =>
                    remaining.headMaybe match
                        case Absent     => Loop.done(acc)
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

    // Injection hooks marking the three points at which a commit can be cut short. Defaults are
    // no-ops; a test replaces one with a function that throws. Single-writer semantics: only one
    // test sets and clears a hook at a time.
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
