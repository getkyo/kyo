package kyo

import java.io.IOException
import java.nio.charset.Charset
import kyo.internal.ZipArchive

/** [[FileSystem.zipReadOnly]]'s implementor: reads the archive's full bytes once at construction
  * (through the already-cross-platform [[Path.Unsafe.readBytes]] surface, no platform-specific
  * seam), parses its central directory into an in-memory entry index via
  * [[kyo.internal.ZipArchive.parse]], and serves every read directly from the retained bytes. Every
  * write-family method and every `ReadWrite`/`ReadWriteCreate` [[FileSystem.openChannel]] fails a
  * typed `FileIOException`; the backing archive file is never mutated by this service. Uses
  * [[kyo.internal.ZipArchive]] and [[kyo.internal.ZipInflate]], a uniform pure-Scala codec identical
  * on every platform: no `java.util.zip` reference anywhere in this file.
  */
private[kyo] object ZipReadOnlyFileSystem:

    /** The archive's full entry set, keyed by its zip-relative name (`/`-joined, no leading or
      * trailing `/`; the archive root is `""`). `kinds` maps every entry name AND every implied
      * ancestor directory (a zip archive commonly omits explicit directory entries) to
      * `isDirectory`; `fileStats` carries real size/mtime for regular-file entries only, since an
      * implied directory has no archive-level metadata; `children` maps every directory name to its
      * immediate child names, so [[ZipReadOnlyFileSystem.list]] is a direct lookup; `entries` and
      * `bytes` retain the parsed [[ZipArchive.Entry]] records and the archive's own raw bytes, so a
      * later [[ZipArchive.readEntry]] call needs no re-read of the backing archive file.
      */
    final case class Index(
        kinds: Map[String, Boolean],
        fileStats: Map[String, Path.PathStat],
        children: Map[String, Chunk[String]],
        entries: Map[String, ZipArchive.Entry],
        bytes: Array[Byte]
    )

    private[kyo] def buildIndex(rawEntries: Chunk[ZipArchive.Entry], bytes: Array[Byte]): Index =
        val kindsInit   = rawEntries.map(e => (e.name, e.isDirectory)).toMap
        val statsInit   = rawEntries.filterNot(_.isDirectory).map(e => (e.name, Path.PathStat(e.lastModifiedMs, e.uncompSize.toLong))).toMap
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
    def init(archive: Path)(using Frame): FileSystem[Sync] < (Sync & Scope & Abort[FileException]) =
        // Unsafe: reads the archive's full bytes through the cross-platform Path surface
        Sync.Unsafe.defer(Abort.get(archive.unsafe.readBytes())).map { span =>
            val bytes = span.toArrayUnsafe
            // Unsafe: parses the archive's central directory and builds the read index once at open time.
            Sync.Unsafe.defer {
                try
                    val index = buildIndex(ZipArchive.parse(bytes), bytes)
                    AtomicRef.init(Map.empty[Chunk[String], Int]).map(locks => new ZipReadOnlyFileSystem(archive, index, locks))
                catch
                    case e: ZipArchive.ZipFormatException => Abort.fail(FileIOException(archive, new IOException(e.getMessage)))
            }
        }
end ZipReadOnlyFileSystem

final private[kyo] class ZipReadOnlyFileSystem(
    archive: Path,
    index: ZipReadOnlyFileSystem.Index,
    locks: AtomicRef[Map[Chunk[String], Int]]
)(using Frame) extends FileSystem[Sync]:

    val commitStrategy: FileSystem.CommitStrategy = FileSystem.CommitStrategy.Auto

    private def name(path: Path): String = path.parts.mkString("/")

    private def readOnlyFailure(path: Path): FileIOException =
        FileIOException(path, new IOException(s"$archive is a read-only zip archive"))

    private def readOnly[A](path: Path): A < Abort[FileException] = Abort.fail(readOnlyFailure(path))

    def exists(path: Path): Boolean < (Sync & Abort[FileException])                       = index.kinds.contains(name(path))
    def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) = exists(path)
    def isDirectory(path: Path): Boolean < (Sync & Abort[FileException])                  = index.kinds.get(name(path)).contains(true)
    def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException])                = index.kinds.get(name(path)).contains(false)
    def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException])               = false
    def realPath(path: Path): Path < (Sync & Abort[FileException])                        = path

    private def readEntryBytes(path: Path, entry: ZipArchive.Entry): Span[Byte] < (Sync & Abort[FileException]) =
        // Unsafe: inflates the requested entry's bytes on demand from the already-parsed archive.
        Sync.Unsafe.defer {
            try Span.fromUnsafe(ZipArchive.readEntry(index.bytes, entry))
            catch case e: ZipArchive.ZipFormatException => Abort.fail(FileIOException(path, new IOException(e.getMessage)))
        }

    def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException]) =
        val n = name(path)
        index.kinds.get(n) match
            case Some(true)  => Abort.fail(FileIsADirectoryException(path))
            case Some(false) => readEntryBytes(path, index.entries(n))
            case None        => Abort.fail(FileNotFoundException(path))
        end match
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
        index.kinds.get(name(path)) match
            case Some(true)  => Path.PathStat(0L, 0L)
            case Some(false) => index.fileStats.getOrElse(name(path), Path.PathStat(0L, 0L))
            case None        => Abort.fail(FileNotFoundException(path))

    def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))
    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
        index.kinds.get(name(path)) match
            case Some(_) => walkHandle(path, maxDepth)
            case None    => Abort.fail(FileNotFoundException(path))
    private def walkHandle(base: Path, maxDepth: Int): Path.WalkHandle =
        new Path.WalkHandle:
            private val pending: Iterator[Path]        = preorder(base, maxDepth).iterator
            def next()(using AllowUnsafe): Maybe[Path] = if pending.hasNext then Maybe(pending.next()) else Maybe.empty
            def close()(using AllowUnsafe): Unit       = ()
    private def preorder(base: Path, depth: Int): List[Path] =
        if depth <= 0 then Nil
        else
            index.children.getOrElse(name(base), Chunk.empty).toList.sorted.flatMap { seg =>
                val p = base / seg
                p :: (if index.kinds.get(name(p)).contains(true) then preorder(p, depth - 1) else Nil)
            }

    def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException])                   = readOnly(path)
    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException])          = readOnly(path)
    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException])       = readOnly(path)
    def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException])                  = readOnly(path)
    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException])         = readOnly(path)
    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException])      = readOnly(path)
    def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException])                                           = readOnly(path)
    def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException])                                 = readOnly(path)
    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) = readOnly(path)
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException])                   = readOnly(archive)
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException])     = readOnly(archive)
    def mkDir(path: Path): Unit < (Sync & Abort[FileException])                                                          = readOnly(path)
    def mkFile(path: Path): Unit < (Sync & Abort[FileException])                                                         = readOnly(path)
    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (Sync & Abort[FileException]) =
        readOnly(from)
    def copy(from: Path, to: Path, followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean, createFolders: Boolean)
        : Unit < (Sync & Abort[FileException]) = readOnly(from)
    def remove(path: Path): Boolean < (Sync & Abort[FileException])                 = readOnly(path)
    def removeExisting(path: Path): Unit < (Sync & Abort[FileException])            = readOnly(path)
    def removeAll(path: Path): Unit < (Sync & Abort[FileException])                 = readOnly(path)
    def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) = readOnly(archive)

    def list(path: Path): Chunk[Path] < (Sync & Abort[FileException]) =
        index.kinds.get(name(path)) match
            case Some(true)  => index.children.getOrElse(name(path), Chunk.empty).map(seg => path / seg)
            case Some(false) => Abort.fail(FileNotADirectoryException(path))
            case None        => Abort.fail(FileNotFoundException(path))
    def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) =
        list(path).map(_.filter(p => p.name.exists(InMemoryHandles.matchesGlob(_, glob))))

    private def mkChannel(path: Path, bytes: Span[Byte]): Path.Channel[Sync] =
        new Path.Channel[Sync]:
            def readAt(pos: Long, len: Int)(using Frame): Span[Byte] < (Sync & Abort[FileException])     = bytes.drop(pos.toInt).take(len)
            def writeAt(pos: Long, value: Span[Byte])(using Frame): Unit < (Sync & Abort[FileException]) = readOnly(path)
            def sync()(using Frame): Unit < (Sync & Abort[FileException])                                = ()
            def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileException])                  = readOnly(path)
            def size()(using Frame): Long < (Sync & Abort[FileException])                                = bytes.size.toLong

    def openChannel(path: Path, mode: FileSystem.ChannelMode): Path.Channel[Sync] < (Sync & Scope & Abort[FileException]) =
        mode match
            case FileSystem.ChannelMode.Read => Scope.acquireRelease(readBytes(path).map(mkChannel(path, _)))(_ => ())
            case _                           => Abort.fail(readOnlyFailure(path))

    private[kyo] def openChannelUnscoped(path: Path, mode: FileSystem.ChannelMode)(using
        Frame
    )
        : (Path.Channel[Sync], () => Unit < Sync) < (Sync & Abort[FileException]) =
        // Explicitly typed so the tuple's second element widens to `() => Unit < Sync`: kyo's
        // `<` auto-widening applies to a value position, not to a function's return type nested
        // inside a tuple (mirrors InMemoryFileSystem.openChannelUnscoped's own noRelease val).
        val noRelease: () => Unit < Sync = () => ()
        mode match
            case FileSystem.ChannelMode.Read => readBytes(path).map(bytes => (mkChannel(path, bytes), noRelease))
            case _                           => Abort.fail(readOnlyFailure(path))
    end openChannelUnscoped

    def syncDir(path: Path): Unit < (Sync & Abort[FileException]) = ()

    // Advisory locking is a coordination primitive orthogonal to content mutability, so this
    // read-only service still honors it (a zip journal reader still needs a shared lock to
    // coordinate with a concurrent writer of the same archive); the in-process CAS table mirrors
    // InMemoryFileSystem's own lock implementation.
    def lock(path: Path, exclusive: Boolean): Path.FileLock < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(acquireLock(path, exclusive))(_ => releaseLock(path, exclusive))

    private[kyo] def lockUnscoped(path: Path, exclusive: Boolean)(using
        Frame
    ): (Path.FileLock, () => Unit < Sync) < (Sync & Abort[FileException]) =
        acquireLock(path, exclusive).map(lock => (lock, () => releaseLock(path, exclusive)))

    private def acquireLock(path: Path, exclusive: Boolean): Path.FileLock < (Sync & Abort[FileException]) =
        Loop(()) { _ =>
            locks.get.map { cur =>
                val held = cur.getOrElse(path.parts, 0)
                val ok   = if exclusive then held == 0 else held >= 0
                val next = if exclusive then -1 else held + 1
                Abort.get(if ok then Result.succeed(cur.updated(path.parts, next)) else Result.fail(FileLockUnavailableException(path)))
                    .map { updated =>
                        locks.compareAndSet(cur, updated).map {
                            case true  => Loop.done(mkLock(exclusive))
                            case false => Loop.continue(())
                        }
                    }
            }
        }
    private def releaseLock(path: Path, exclusive: Boolean): Unit < Sync =
        Loop(()) { _ =>
            locks.get.map { cur =>
                val held      = cur.getOrElse(path.parts, 0)
                val next      = if exclusive then 0 else held - 1
                val nextLocks = if next <= 0 then cur - path.parts else cur.updated(path.parts, next)
                locks.compareAndSet(cur, nextLocks).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }
    private def mkLock(exclusive: Boolean): Path.FileLock = new Path.FileLock:
        def isExclusive: Boolean = exclusive
end ZipReadOnlyFileSystem
