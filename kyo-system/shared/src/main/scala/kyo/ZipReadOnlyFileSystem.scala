package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.ZipArchive

/** [[FileSystem.zipReadOnly]]'s implementor: reads the archive's full bytes once at construction
  * (through the already-cross-platform [[Path.Unsafe.readBytes]] surface, no platform-specific
  * seam), parses its central directory into an in-memory entry index via
  * [[kyo.internal.ZipArchive.parse]], and serves every read directly from the retained bytes. The
  * resulting [[FileSystem.Read]] value has no write or channel members. Uses
  * [[kyo.internal.ZipArchive]] and [[kyo.internal.ZipInflate]], a uniform pure-Scala codec identical
  * on every platform: no `java.util.zip` reference anywhere in this file.
  */
private[kyo] object ZipReadOnlyFileSystem:

    final case class LockClaim(mode: Path.LockMode, owners: Chunk[Path.LockOwnership])

    /** The archive's full entry set, keyed by its zip-relative name (`/`-joined, no leading or
      * trailing `/`; the archive root is `""`). `kinds` maps every entry name AND every implied
      * ancestor directory (a zip archive commonly omits explicit directory entries) to
      * `isDirectory`; `entryStats` carries real size/mtime for explicit entries, while an implied
      * directory has no archive-level metadata; `children` maps every directory name to its
      * immediate child names, so [[ZipReadOnlyFileSystem.list]] is a direct lookup; `entries` and
      * `bytes` retain the parsed [[ZipArchive.Entry]] records and the archive's own raw bytes, so a
      * later [[ZipArchive.readEntry]] call needs no re-read of the backing archive file.
      */
    final case class Index(
        kinds: Map[String, Boolean],
        entryStats: Map[String, Path.PathStat],
        children: Map[String, Chunk[String]],
        entries: Map[String, ZipArchive.Entry],
        bytes: Array[Byte]
    )

    private[kyo] def buildIndex(rawEntries: Chunk[ZipArchive.Entry], bytes: Array[Byte]): Index =
        val kindsInit   = rawEntries.map(e => (e.name, e.isDirectory)).toMap
        val statsInit   = rawEntries.map(e => (e.name, Path.PathStat(e.lastModifiedMs, e.uncompSize.toLong))).toMap
        val entriesInit = rawEntries.map(e => (e.name, e)).toMap

        def segsOf(name: String): Chunk[String] = Chunk.from(name.split("/", -1).toIndexedSeq)
        def ancestorsOf(name: String): Chunk[String] =
            val segs = segsOf(name)
            Chunk.from((0 until segs.length).map(i => segs.take(i).mkString("/")))

        val impliedDirs = rawEntries.flatMap(e => ancestorsOf(e.name)).toSet
        val kinds       = impliedDirs.foldLeft(kindsInit)((m, d) => if m.contains(d) then m else m.updated(d, true))

        val childrenAcc = scala.collection.mutable.Map.empty[String, scala.collection.mutable.LinkedHashSet[String]]
        def parentOf(name: String): String =
            val i = name.lastIndexOf('/')
            if i < 0 then "" else name.substring(0, i)
        def leafOf(name: String): String =
            val i = name.lastIndexOf('/')
            if i < 0 then name else name.substring(i + 1)
        kinds.keys.foreach { entryName =>
            if entryName.nonEmpty then
                childrenAcc.getOrElseUpdate(parentOf(entryName), scala.collection.mutable.LinkedHashSet.empty) += leafOf(entryName)
        }
        val children = childrenAcc.view.mapValues(s => Chunk.from(s.toIndexedSeq.sorted)).toMap
        Index(kinds, statsInit, children, entriesInit, bytes)
    end buildIndex

    /** Reads `archive`'s full bytes once, parses its central directory, and builds its entry index.
      * A malformed archive surfaces as a typed `FileIOException` rather than a raw exception.
      */
    def init(archive: Path)(using Frame): FileSystem.Read[Sync] < (Sync & Scope & Abort[FileReadException | FileStructureException]) =
        // Unsafe: reads the archive's full bytes through the cross-platform Path surface
        Sync.Unsafe.defer(Abort.get(archive.unsafe.readBytes())).map { span =>
            val bytes = span.toArrayUnsafe
            // Unsafe: parses the archive's central directory and builds the read index once at open time.
            Sync.Unsafe.defer {
                try
                    val index = buildIndex(ZipArchive.parse(bytes), bytes)
                    new ZipReadOnlyFileSystem(index, AtomicRef.Unsafe.init(Map.empty[Chunk[String], LockClaim]).safe)
                catch
                    case e: ZipArchive.ZipFormatException =>
                        Abort.fail(FileIOException(archive, FileSystemOperation.Read, new IOException(e.getMessage)))
            }
        }
end ZipReadOnlyFileSystem

final private[kyo] class ZipReadOnlyFileSystem(
    index: ZipReadOnlyFileSystem.Index,
    locks: AtomicRef[Map[Chunk[String], ZipReadOnlyFileSystem.LockClaim]]
) extends FileSystem.Read[Sync]:

    import ZipReadOnlyFileSystem.LockClaim

    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync = Glob.CaseSensitivity.Sensitive

    private def name(path: Path): String = path.parts.mkString("/")

    def tryLock(path: Path, mode: Path.LockMode)(using
        Frame
    ): Maybe[Path.Lock] < (Sync & Scope & Abort[FileReadException | FileLockException]) =
        val acquire = Scope.acquireRelease(acquireLock(path, mode))(lock => lock.release(lock.ownership)).map(Maybe(_))
        Abort.recover[FileLockUnavailableException](_ => Absent)(acquire)
    end tryLock

    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
        Frame
    ): Path.Lock < (Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        FileSystem.awaitLock(path, wait)(tryLock(path, mode))

    private def acquireLock(path: Path, mode: Path.LockMode)(using Frame): Path.Lock < (Sync & Abort[FileLockException]) =
        val owner = Path.LockOwnership.fresh()
        AtomicBoolean.init(false).map { released =>
            modifyLocks { current =>
                current.get(path.parts) match
                    case None =>
                        val next = current.updated(path.parts, LockClaim(mode, Chunk(owner)))
                        Result.succeed((next, mkLock(path, mode, owner, released)))
                    case Some(claim) if mode == Path.LockMode.Shared && claim.mode == Path.LockMode.Shared =>
                        val next = current.updated(path.parts, claim.copy(owners = claim.owners.append(owner)))
                        Result.succeed((next, mkLock(path, mode, owner, released)))
                    case _ => Result.fail(FileLockUnavailableException(path))
            }
        }
    end acquireLock

    private def modifyLocks[A](
        f: Map[Chunk[String], LockClaim] => Result[FileLockException, (Map[Chunk[String], LockClaim], A)]
    )(using Frame): A < (Sync & Abort[FileLockException]) =
        Loop(()) { _ =>
            locks.get.map { current =>
                Abort.get(f(current)).map { (next, value) =>
                    locks.compareAndSet(current, next).map {
                        case true  => Loop.done(value)
                        case false => Loop.continue(())
                    }
                }
            }
        }

    private def releaseLock(path: Path, owner: Path.LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException]) =
        modifyLocks { current =>
            current.get(path.parts) match
                case Some(claim) if claim.owners.exists(Path.LockOwnership.same(_, owner)) =>
                    val owners = claim.owners.filterNot(Path.LockOwnership.same(_, owner))
                    val next = if owners.isEmpty then current - path.parts
                    else current.updated(path.parts, claim.copy(owners = owners))
                    Result.succeed((next, ()))
                case _ => Result.fail(FileLockOwnershipLostException(path))
        }

    private def owns(path: Path, owner: Path.LockOwnership)(using Frame): Boolean < Sync =
        locks.use(_.get(path.parts).exists(_.owners.exists(Path.LockOwnership.same(_, owner))))

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

    def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = index.kinds.contains(name(path))
    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) = exists(path)
    def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])    = index.kinds.get(name(path)).contains(true)
    def isRegularFile(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException])  = index.kinds.get(name(path)).contains(false)
    def isSymbolicLink(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = false
    def realPath(path: Path)(using
        Frame
    ): Path < (Sync & Abort[
        FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
    ]) = path

    private def readEntryBytes(path: Path, entry: ZipArchive.Entry)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
        // Unsafe: inflates the requested entry's bytes on demand from the already-parsed archive.
        Sync.Unsafe.defer {
            try Span.fromUnsafe(ZipArchive.readEntry(index.bytes, entry))
            catch
                case e: ZipArchive.ZipFormatException =>
                    Abort.fail(FileIOException(path, FileSystemOperation.Read, new IOException(e.getMessage)))
        }

    def readBytes(path: Path)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
        val n = name(path)
        index.kinds.get(n) match
            case Some(true)  => Abort.fail(FileIsADirectoryException(path))
            case Some(false) => readEntryBytes(path, index.entries(n))
            case None        => Abort.fail(FileNotFoundException(path))
        end match
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
        index.kinds.get(name(path)) match
            case Some(_) => index.entryStats.getOrElse(name(path), Path.PathStat(0L, 0L))
            case None    => Abort.fail(FileNotFoundException(path))

    def openRead(path: Path)(using Frame): Path.ReadHandle < (Sync & Abort[FileReadException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (Sync & Abort[FileReadException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[Sync] < (Sync & Scope & Abort[FileReadException]) =
        openReadChannelUnscoped(path).map { case (channel, release) =>
            Scope.acquireRelease(channel)(_ => release())
        }
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[Sync], () => Unit < Sync) < (Sync & Abort[FileReadException]) =
        readBytes(path).map { bytes =>
            val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
            val channel = new Path.ReadChannel[Sync]:
                private def checkOpen(using Frame): Unit < Abort[FileReadException] =
                    if closed.get() then
                        Abort.fail(FileIOException(path, FileSystemOperation.Read, new IOException("channel is closed")))
                    else ()
                def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
                    checkOpen.andThen {
                        if position < 0L || position > Int.MaxValue || length < 0 then
                            Abort.fail(FileIOException(path, FileSystemOperation.Read, new IOException("invalid channel read bounds")))
                        else bytes.drop(position.toInt).take(length)
                    }
                def size(using Frame): Long < (Sync & Abort[FileReadException]) = checkOpen.andThen(bytes.size.toLong)
            val release: () => Unit < Sync = () => Sync.defer(discard(closed.compareAndSet(false, true)))
            (channel, release)
        }
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using Frame): Path.WalkHandle < (Sync & Abort[FileStructureException]) =
        index.kinds.get(name(path)) match
            case Some(_) => walkHandle(path, maxDepth)
            case None    => Abort.fail(FileNotFoundException(path))
    private def walkHandle(base: Path, maxDepth: Int)(using Frame): Path.WalkHandle =
        new Path.WalkHandle:
            private val pending: Iterator[Path]        = preorder(base, maxDepth).iterator
            def next()(using AllowUnsafe): Maybe[Path] = if pending.hasNext then Maybe(pending.next()) else Maybe.empty
            def close()(using AllowUnsafe): Unit       = ()
    private def preorder(base: Path, depth: Int)(using Frame): List[Path] =
        if depth <= 0 then Nil
        else
            index.children.getOrElse(name(base), Chunk.empty).toList.sorted.flatMap { seg =>
                val p = base / seg
                p :: (if index.kinds.get(name(p)).contains(true) then preorder(p, depth - 1) else Nil)
            }

    def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileStructureException]) =
        index.kinds.get(name(path)) match
            case Some(true)  => index.children.getOrElse(name(path), Chunk.empty).map(seg => path / seg)
            case Some(false) => Abort.fail(FileNotADirectoryException(path))
            case None        => Abort.fail(FileNotFoundException(path))
    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (Sync & Abort[FileStructureException]) =
        list(path).map(_.filter(p => glob.matches(Chunk(p.parts.last), caseSensitivity)))

end ZipReadOnlyFileSystem
