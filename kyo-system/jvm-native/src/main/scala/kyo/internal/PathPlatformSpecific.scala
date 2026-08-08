package kyo.internal

import java.io.InterruptedIOException
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kyo.*
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Concrete `Path.Unsafe` implementation backed by `java.nio.file.Path`. Used on both JVM and Scala Native. */
final private[kyo] class NioPathUnsafe(val jpath: java.nio.file.Path) extends Path.Unsafe:

    // --- Pure accessors ---

    def parts: Chunk[String] =
        if !jpath.isAbsolute && jpath.getNameCount == 1 && jpath.getName(0).toString.isEmpty then
            Chunk.empty // Path.of("") is the current-directory empty path
        else if jpath.isAbsolute then
            // The leading segment encodes the filesystem root: "" for a POSIX root ("/")
            // and the drive designator (e.g. "C:") for a Windows drive root ("C:\"). Carrying
            // the drive here is what lets parts -> make round-trip without dropping the volume,
            // which otherwise re-anchors the path to the process working directory's drive.
            val root = jpath.getRoot
            val rootSegment =
                if root == null then ""
                else root.toString.replace('\\', '/').stripSuffix("/")
            Chunk.from(rootSegment +: (0 until jpath.getNameCount).map(jpath.getName(_).toString))
        else
            Chunk.from((0 until jpath.getNameCount).map(jpath.getName(_).toString))
        end if
    end parts

    def show: String        = jpath.toString.replace('\\', '/')
    def isAbsolute: Boolean = jpath.isAbsolute || parts.headOption.contains("")

    override def equals(other: Any): Boolean = other match
        case that: NioPathUnsafe => this.jpath.equals(that.jpath)
        case _                   => false

    override def hashCode(): Int = jpath.hashCode()

    // --- Inspection ---

    def exists()(using AllowUnsafe, Frame): Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean] =
        exists(followLinks = true)

    def exists(followLinks: Boolean)(using
        AllowUnsafe,
        Frame
    )
        : Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean] =
        try
            val options = if followLinks then Array.empty[LinkOption] else Array(LinkOption.NOFOLLOW_LINKS)
            discard(Files.readAttributes(jpath, classOf[BasicFileAttributes], options*))
            Result.succeed(true)
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e)  => Result.panic(e)
            case e: IOException if NioExceptionBoundary.isFileNotFound(e) => Result.succeed(false)
            case _: NotDirectoryException                                 => Result.succeed(false)
            case e: AccessDeniedException                                 => Result.fail(FileAccessDeniedException(safe))
            case e: IOException          => Result.fail(FileIOException(safe, FileSystemOperation.Exists, e))
            case e: InvalidPathException => Result.fail(FileInvalidPathException(e.getInput, FileSystemOperation.Exists))
            case e: Throwable            => Result.panic(e)

    def isDirectory()(using AllowUnsafe): Boolean    = Files.isDirectory(jpath)
    def isRegularFile()(using AllowUnsafe): Boolean  = Files.isRegularFile(jpath)
    def isSymbolicLink()(using AllowUnsafe): Boolean = Files.isSymbolicLink(jpath)

    def realPath()(using
        AllowUnsafe,
        Frame
    )
        : Result[FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException, Path] =
        try Result.succeed(Path.of(jpath.toRealPath()))
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e)  => Result.panic(e)
            case e: IOException if NioExceptionBoundary.isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException                                 => Result.fail(FileAccessDeniedException(safe))
            case e: IOException          => Result.fail(FileIOException(safe, FileSystemOperation.RealPath, e))
            case e: InvalidPathException => Result.fail(FileInvalidPathException(e.getInput, FileSystemOperation.RealPath))
            case e: Throwable            => Result.panic(e)

    // --- Read ---

    def read()(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead(Files.readString(jpath))

    def read(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead(Files.readString(jpath, charset))

    def readBytes()(using AllowUnsafe, Frame): Result[FileReadException, Span[Byte]] =
        catchRead(Span.from(Files.readAllBytes(jpath)))

    def readLines()(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead(Chunk.from(Files.readAllLines(jpath).asScala))

    def readLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead(Chunk.from(Files.readAllLines(jpath, charset).asScala))

    // --- Streaming read handles ---

    def openRead()(using AllowUnsafe, Frame): Result[FileReadException, Path.ReadHandle] =
        catchRead(new NioReadHandle(FileChannel.open(jpath, StandardOpenOption.READ), safe))

    def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle] =
        catchRead(new NioLineReadHandle(Files.newBufferedReader(jpath, charset)))

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        catchRead(Files.size(jpath))

    def stat()(using AllowUnsafe, Frame): Result[FileReadException, kyo.Path.PathStat] =
        catchRead {
            val attrs = Files.readAttributes(jpath, classOf[BasicFileAttributes])
            kyo.Path.PathStat(attrs.lastModifiedTime.toMillis, attrs.size)
        }

    // --- Write ---

    def write(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.writeString(
                jpath,
                value,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def writeBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.write(
                jpath,
                value.toArray,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def writeLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.write(
                jpath,
                value.toSeq.asJava,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def append(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.writeString(jpath, value, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE))
        }

    def appendBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.write(jpath, value.toArray, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE))
        }

    def appendLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            discard(Files.write(
                jpath,
                value.toSeq.asJava,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            ))
        }

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val ch = FileChannel.open(jpath, StandardOpenOption.WRITE)
            try discard(ch.truncate(size))
            finally ch.close()
        }

    def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            import java.nio.file.attribute.FileTime
            discard(Files.setLastModifiedTime(jpath, FileTime.fromMillis(epochMs)))
        }

    // --- Directory / structure ---

    def mkDir()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create)(discard(Files.createDirectories(jpath)))

    def mkFile()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create) {
            ensureParent(jpath)
            if !Files.exists(jpath) then discard(Files.createFile(jpath))
        }

    def list()(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]] =
        catchFs(FileSystemOperation.List) {
            val jstream = Files.list(jpath)
            try Chunk.from(jstream.iterator().asScala.map(p => NioPathUnsafe(p).safe).toList)
            finally jstream.close()
        }

    def list(glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]] =
        list().map(_.filter(path => glob.matches(Chunk(path.parts.last), caseSensitivity)))

    def move(to: Path, options: Path.MoveOptions)(using
        AllowUnsafe,
        Frame
    ): Result[FileStructureException, Unit] =
        try
            val target = toNioPath(to)
            if options.createFolders then ensureParentOf(target)
            if options.atomicity == Path.Atomicity.Required then
                NioAtomicMovePlatform.move(jpath, target, options.replace)
            else
                val opts = buildMoveOptions(options.replace)
                discard(Files.move(jpath, target, opts*))
            end if
            Result.succeed(())
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e) => Result.panic(e)
            case e: IOException if options.atomicity == Path.Atomicity.Required && NioExceptionBoundary.isAtomicMoveUnsupported(e) =>
                Result.fail(FileAtomicMoveUnsupportedException(safe, to))
            case e: InvalidPathException => Result.fail(FileInvalidPathException(e.getInput, FileSystemOperation.Move))
            case e: IOException          => Result.fail(translateIoe(safe, FileSystemOperation.Move, e))
            case e: Throwable            => Result.panic(e)

    def copy(to: Path, options: Path.CopyOptions)(using
        AllowUnsafe,
        Frame
    ): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Copy) {
            val target = toNioPath(to)
            if options.createFolders then ensureParentOf(target)
            val opts = buildCopyOptions(options)
            discard(Files.copy(jpath, target, opts*))
        }

    def remove()(using AllowUnsafe, Frame): Result[FileStructureException, Boolean] =
        catchFs(FileSystemOperation.Remove)(Files.deleteIfExists(jpath))

    def removeExisting()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove)(Files.delete(jpath))

    def removeAll()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove) {
            if Files.exists(jpath) then
                val visitor = new SimpleFileVisitor[java.nio.file.Path]:
                    override def visitFile(p: java.nio.file.Path, a: BasicFileAttributes): FileVisitResult =
                        Files.delete(p); FileVisitResult.CONTINUE
                    override def postVisitDirectory(p: java.nio.file.Path, e: IOException): FileVisitResult =
                        if e != null then throw e // Re-throw to propagate through Java's FileVisitor API
                        Files.delete(p); FileVisitResult.CONTINUE
                discard(Files.walkFileTree(jpath, visitor))
        }

    // --- Walk handle ---

    def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileStructureException, Path.WalkHandle] =
        val followOpts: Array[FileVisitOption] =
            if followLinks then Array(FileVisitOption.FOLLOW_LINKS) else Array.empty
        catchFs(FileSystemOperation.Walk) {
            val jstream  = Files.walk(jpath, maxDepth, followOpts*)
            val iterator = jstream.iterator()
            new NioWalkHandle(iterator, jstream)
        }
    end openWalk

    // --- Open write handle ---

    def openWrite(append: Boolean, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle] =
        catchWrite {
            if options.createFolders then ensureParent(jpath)
            val opts =
                if append then
                    Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                else
                    Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            new NioWriteHandle(FileChannel.open(jpath, opts*), safe)
        }

    // --- Helpers ---

    private def ensureParent(p: java.nio.file.Path): Unit =
        val parent = p.getParent
        if parent != null && !Files.exists(parent) then
            discard(Files.createDirectories(parent))
    end ensureParent

    private def ensureParentOf(target: java.nio.file.Path): Unit =
        val parent = target.getParent
        if parent != null && !Files.exists(parent) then
            discard(Files.createDirectories(parent))
    end ensureParentOf

    private def buildMoveOptions(replace: Path.Replace): Array[CopyOption] =
        val buf = ListBuffer.empty[CopyOption]
        if replace == Path.Replace.Existing then buf += StandardCopyOption.REPLACE_EXISTING
        buf.toArray
    end buildMoveOptions

    private def buildCopyOptions(options: Path.CopyOptions): Array[CopyOption] =
        val buf = ListBuffer.empty[CopyOption]
        if options.replace == Path.Replace.Existing then buf += StandardCopyOption.REPLACE_EXISTING
        if options.copyAttributes then buf += StandardCopyOption.COPY_ATTRIBUTES
        if !options.followLinks then buf += LinkOption.NOFOLLOW_LINKS
        buf.toArray
    end buildCopyOptions

    private def toNioPath(p: Path): java.nio.file.Path =
        p.unsafe match
            case n: NioPathUnsafe => n.jpath
            case other            => java.nio.file.Paths.get(other.show)

    // Exception translation helpers

    private def translateIoe(path: Path, operation: FileSystemOperation, e: IOException)(using Frame): FileStructureException =
        e match
            case _: NoSuchFileException                      => FileNotFoundException(path)
            case _: AccessDeniedException                    => FileAccessDeniedException(path)
            case _: NotDirectoryException                    => FileNotADirectoryException(path)
            case _: java.nio.file.FileAlreadyExistsException => FileAlreadyExistsException(path)
            case _: DirectoryNotEmptyException               => FileDirectoryNotEmptyException(path)
            case _                                           => FileIOException(path, operation, e)

    private def catchRead[A](expr: => A)(using Frame): Result[FileReadException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e)  => Result.panic(e)
            case e: IOException if NioExceptionBoundary.isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException                                 =>
                // On Windows, reading a directory raises AccessDeniedException instead of "Is a directory"
                if java.nio.file.Files.isDirectory(jpath) then Result.fail(FileIsADirectoryException(safe))
                else Result.fail(FileAccessDeniedException(safe))
            case e: IOException if e.getMessage != null && e.getMessage.contains("Is a directory") =>
                Result.fail(FileIsADirectoryException(safe))
            case e: IOException => Result.fail(FileIOException(safe, FileSystemOperation.Read, e))
            case e: Throwable   => Result.panic(e)

    private def catchWrite[A](expr: => A)(using Frame): Result[FileWriteException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e)  => Result.panic(e)
            case e: IOException if NioExceptionBoundary.isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException                                 =>
                // On some platforms (Scala Native / macOS), writing to a directory raises
                // AccessDeniedException (EACCES) rather than an IOException with "Is a directory".
                // Detect this case by checking whether the target path is actually a directory.
                if Files.isDirectory(jpath) then Result.fail(FileIsADirectoryException(safe))
                else Result.fail(FileAccessDeniedException(safe))
            case e: IOException if e.getMessage != null && e.getMessage.contains("Is a directory") =>
                Result.fail(FileIsADirectoryException(safe))
            case e: IOException => Result.fail(FileIOException(safe, FileSystemOperation.Write, e))
            case e: Throwable   => Result.panic(e)

    private def catchFs[A](operation: FileSystemOperation)(expr: => A)(using Frame): Result[FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e)  => Result.panic(e)
            case e: IOException if NioExceptionBoundary.isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException                                 => Result.fail(FileAccessDeniedException(safe))
            case e: java.nio.file.FileAlreadyExistsException              => Result.fail(FileAlreadyExistsException(safe))
            case e: DirectoryNotEmptyException                            => Result.fail(FileDirectoryNotEmptyException(safe))
            case e: IOException if NioExceptionBoundary.isNotDirectory(e) => Result.fail(FileNotADirectoryException(safe))
            case e: IOException                                           => Result.fail(FileIOException(safe, operation, e))
            case e: Throwable                                             => Result.panic(e)

end NioPathUnsafe

private[kyo] object NioExceptionBoundary:
    private[kyo] def isFileNotFound(exception: IOException): Boolean =
        exception.isInstanceOf[NoSuchFileException]

    private[kyo] def isNotDirectory(exception: IOException): Boolean =
        exception.isInstanceOf[NotDirectoryException]

    private[kyo] def isAtomicMoveUnsupported(exception: IOException): Boolean =
        exception.isInstanceOf[NioAtomicMoveUnsupportedException] ||
            exception.getClass.getName == "java.nio.file.AtomicMoveNotSupportedException"

    private[kyo] def isInterrupted(exception: IOException): Boolean =
        exception.isInstanceOf[InterruptedIOException] || exception.isInstanceOf[ClosedByInterruptException]
end NioExceptionBoundary

final private[kyo] class NioAtomicMoveUnsupportedException extends IOException

/** Concrete write handle backed by a `java.nio.channels.FileChannel`. */
final private[kyo] class NioWriteHandle(channel: FileChannel, path: Path) extends Path.WriteHandle:

    private var finished = false

    def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val arr = chunk.toArray
            val buf = java.nio.ByteBuffer.wrap(arr)
            while buf.hasRemaining do discard(channel.write(buf))
            Result.unit
        catch
            case e: IOException if NioExceptionBoundary.isInterrupted(e) => Result.panic(e)
            case e: IOException                                          => Result.fail(FileIOException(path, FileSystemOperation.Write, e))
            case e: Throwable                                            => Result.panic(e)

    def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        writeBytes(Chunk.from(s.getBytes(charset)))

    def finish()(using AllowUnsafe): Unit =
        channel.force(true) // fsync: bytes are durable before the logical-completion flag
        finished = true

    def close()(using AllowUnsafe): Unit =
        channel.close()
        if !finished then
            // Unsafe: removes the partially-written file if finish() was never called
            discard(java.nio.file.Files.deleteIfExists(path.unsafe.asInstanceOf[NioPathUnsafe].jpath))
    end close

end NioWriteHandle

/** Concrete read handle backed by a `java.nio.channels.FileChannel`.
  *
  * Carries the `Path` it was opened under only to name the file in a `FileReadException`, the same reason `NioWriteHandle` carries one.
  * Nothing here re-resolves it: every measurement goes through the channel.
  */
final private[kyo] class NioReadHandle(channel: FileChannel, path: Path) extends Path.ReadHandle:

    // Single-owner cache of the last wrapped buffer: a repeated read into the same reused array reuses
    // this ByteBuffer instead of allocating a fresh wrapper per call. Confined to this handle instance.
    private var cached: java.nio.ByteBuffer = null

    // Single-owner scan buffer for readLong: refilled from position 0 on each call and reused across
    // calls. It grows once to fit a larger file; the steady-state re-read of a small /proc snapshot then
    // allocates nothing. Confined to this handle instance.
    private var scan: Array[Byte] = new Array[Byte](512)

    // The ByteBuffer view of `scan`, retained across calls exactly as `cached` is retained for readChunk:
    // wrapping the array on each fill step allocates a wrapper per read, which is the per-read allocation
    // readLong exists to avoid. Re-wrapped only when `scan` grows.
    private var scanBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(scan)

    def readLong()(using AllowUnsafe): Long =
        // readChunk and readLong share this channel's single OS position, so readLong reads from the start
        // and then restores the caller's position, leaving an interleaved readChunk cursor undisturbed.
        val resume = channel.position()
        position(0L)
        @scala.annotation.tailrec
        def fill(total: Int): Int =
            if total == scan.length then
                val grown = new Array[Byte](scan.length * 2)
                java.lang.System.arraycopy(scan, 0, grown, 0, total)
                scan = grown
                scanBuffer = java.nio.ByteBuffer.wrap(grown)
            end if
            discard(scanBuffer.clear())
            discard(scanBuffer.position(total))
            val n = channel.read(scanBuffer)
            if n <= 0 then total else fill(total + n)
        end fill
        val len = fill(0)
        position(resume)
        Path.ReadHandle.parseLeadingLong(scan, len)
    end readLong

    def readChunk(buffer: Array[Byte])(using AllowUnsafe): Path.ReadResult =
        val bb =
            if (cached ne null) && (cached.array eq buffer) then
                cached.clear()
                cached
            else
                val fresh = java.nio.ByteBuffer.wrap(buffer)
                cached = fresh
                fresh
        Path.ReadResult(channel.read(bb))
    end readChunk

    def position(offset: Long)(using AllowUnsafe): Unit =
        discard(channel.position(offset))

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        // SeekableByteChannel.size reports the channel's file, so a rename or a replacement of the
        // path this channel was opened under leaves the answer unchanged. The path-resolution
        // failures catchRead separates out (not found, denied, is a directory) cannot arise from a
        // measurement of an already-open channel, which leaves the same shape NioWriteHandle uses.
        try Result.succeed(channel.size())
        catch
            case e: IOException => Result.fail(FileIOException(path, FileSystemOperation.Inspect, e))
            case e: Throwable   => Result.panic(e)

    def close()(using AllowUnsafe): Unit =
        channel.close()

end NioReadHandle

/** Concrete buffered line reader backed by a `java.io.BufferedReader`. */
final private[kyo] class NioLineReadHandle(reader: java.io.BufferedReader) extends Path.LineReadHandle:

    def readLine()(using AllowUnsafe): Maybe[String] =
        val line = reader.readLine()
        if line == null then Absent else Present(line)

    def close()(using AllowUnsafe): Unit =
        reader.close()

end NioLineReadHandle

/** Concrete directory walk handle backed by a `java.util.stream.Stream[java.nio.file.Path]`. */
final private[kyo] class NioWalkHandle(
    iterator: java.util.Iterator[java.nio.file.Path],
    jstream: java.util.stream.Stream[java.nio.file.Path]
) extends Path.WalkHandle:

    def next()(using AllowUnsafe): Maybe[Path] =
        if iterator.hasNext then Present(new NioPathUnsafe(iterator.next()).safe)
        else Absent

    def close()(using AllowUnsafe): Unit =
        jstream.close()

end NioWalkHandle

/** Platform-specific `Path` factory and system-directory accessors for JVM and Scala Native. */
abstract private[kyo] class PathPlatformSpecific extends PathDirectories:

    private[kyo] val platformPathSeparator: String = java.io.File.pathSeparator
    private[kyo] val platformFileSeparator: String = java.io.File.separator

    /** Wraps an existing `java.nio.file.Path` as a kyo `Path`.
      *
      * The path is normalised. Available on JVM and Scala Native only.
      */
    def of(path: java.nio.file.Path): Path =
        new NioPathUnsafe(path.normalize()).safe

    /** Creates a new temporary file with the given prefix and suffix, leaving its removal to the caller.
      *
      * The file is created in the system default temporary directory. Use `temp` for a file the enclosing
      * `Scope` removes.
      *
      * @param prefix
      *   prefix for the temp file name (default `"kyo"`)
      * @param suffix
      *   suffix for the temp file name (default `".tmp"`)
      */
    def tempUnscoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Abort[FileStructureException]) =
        // Unsafe: bridges JVM temp-file creation into the Sync tier.
        Sync.Unsafe.defer(
            Abort.get(
                try
                    Result.succeed(
                        new NioPathUnsafe(
                            java.nio.file.Files.createTempFile(prefix, suffix)
                        ).safe
                    )
                catch
                    case e: java.io.IOException if NioExceptionBoundary.isInterrupted(e) => Result.panic(e)
                    case e: java.io.IOException =>
                        Result.fail(FileIOException(make(Chunk(prefix + suffix)), FileSystemOperation.Create, e))
            )
        )

    /** Creates a new temporary directory with the given prefix, leaving its removal to the caller.
      *
      * The directory is created in the system default temporary directory. Use `tempDir` for a directory the
      * enclosing `Scope` removes.
      *
      * @param prefix
      *   prefix for the temp directory name (default `"kyo"`)
      */
    def tempDirUnscoped(
        prefix: String = "kyo"
    )(using Frame): Path < (Sync & Abort[FileStructureException]) =
        // Unsafe: bridges JVM temp-directory creation into the Sync tier.
        Sync.Unsafe.defer(
            Abort.get(
                try
                    Result.succeed(
                        new NioPathUnsafe(
                            java.nio.file.Files.createTempDirectory(prefix)
                        ).safe
                    )
                catch
                    case e: java.io.IOException if NioExceptionBoundary.isInterrupted(e) => Result.panic(e)
                    case e: java.io.IOException =>
                        Result.fail(FileIOException(make(Chunk(prefix)), FileSystemOperation.Create, e))
            )
        )

    private[kyo] def make(parts: Chunk[String]): Path =
        val isAbsolute = parts.headOption.contains("")
        val nonEmpty   = parts.filter(_.nonEmpty)
        if nonEmpty.isEmpty then
            if isAbsolute then new NioPathUnsafe(java.nio.file.Path.of("/")).safe
            else new NioPathUnsafe(java.nio.file.Path.of("")).safe
        else if isDriveRoot(nonEmpty.head) then
            // Windows drive-rooted path: the leading segment is the volume root (e.g. "C:").
            // Rebuild as "C:/rest" so the drive is preserved instead of being re-anchored to
            // the process working directory's drive. Handles both ["C:", ...] and the
            // ["", "C:", ...] shape, since the leading "" is dropped by `nonEmpty`.
            val jpath = java.nio.file.Path.of(nonEmpty.head + "/" + nonEmpty.tail.mkString("/"))
            new NioPathUnsafe(jpath.normalize()).safe
        else
            val jpath =
                if isAbsolute then
                    val raw = java.nio.file.Path.of("/" + nonEmpty.mkString("/"))
                    // On Windows, Path.of("/foo") creates a root-relative path that
                    // lacks a drive letter, so isAbsolute returns false. Prepend the
                    // current drive letter (e.g. "C:") so that subsequent parts→make
                    // round-trips stay consistent.
                    if !raw.isAbsolute && raw.getRoot != null then
                        val drive = java.nio.file.Path.of("").toAbsolutePath()
                            .getRoot.toString.replaceAll("[/\\\\]", "")
                        java.nio.file.Path.of(drive + "/" + nonEmpty.mkString("/"))
                    else raw
                    end if
                else
                    java.nio.file.Path.of(nonEmpty.head, nonEmpty.tail.toSeq*)
            new NioPathUnsafe(jpath.normalize()).safe
        end if
    end make

    /** True on Windows when `segment` is a drive designator like `C:` (the volume root).
      *
      * Gated to Windows so a POSIX directory literally named `C:` is never mistaken for a drive
      * (e.g. `/C:/foo` must stay absolute on Unix rather than collapsing to a relative path).
      * Uses `Platform.isWindows` — the idiom already used by the sibling `ProcessPlatformSpecific`,
      * and a compile-time constant on Scala Native — rather than an ad-hoc separator check.
      */
    private def isDriveRoot(segment: String): Boolean =
        Platform.isWindows && segment.length == 2 && segment.charAt(1) == ':' && {
            val c = segment.charAt(0)
            (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
        }

    private[kyo] def envOrEmpty(name: String): String =
        val v = java.lang.System.getenv(name)
        if v == null then "" else v

    private[kyo] def homePath: Path =
        val h = java.lang.System.getProperty("user.home", java.lang.System.getenv("HOME"))
        if h == null || h.isEmpty then make(Chunk(""))
        else make(Chunk(h))
    end homePath

    private[kyo] def cwdPath: Path =
        val d = java.lang.System.getProperty("user.dir")
        if d == null || d.isEmpty then make(Chunk(""))
        else make(Chunk(d))
    end cwdPath

    private[kyo] def osPlatform: String =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        if os.contains("mac") then "mac"
        else if os.contains("win") then "win"
        else "linux"
    end osPlatform

end PathPlatformSpecific
