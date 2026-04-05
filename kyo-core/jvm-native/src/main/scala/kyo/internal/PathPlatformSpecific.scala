package kyo.internal

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
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
            // Preserve the leading "" representing the root
            Chunk.from("" +: (0 until jpath.getNameCount).map(jpath.getName(_).toString))
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

    def exists()(using AllowUnsafe): Boolean =
        Files.exists(jpath)

    def exists(followLinks: Boolean)(using AllowUnsafe): Boolean =
        if followLinks then Files.exists(jpath)
        else Files.exists(jpath, LinkOption.NOFOLLOW_LINKS)

    def isDirectory()(using AllowUnsafe): Boolean    = Files.isDirectory(jpath)
    def isRegularFile()(using AllowUnsafe): Boolean  = Files.isRegularFile(jpath)
    def isSymbolicLink()(using AllowUnsafe): Boolean = Files.isSymbolicLink(jpath)

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
        catchRead(new NioReadHandle(FileChannel.open(jpath, StandardOpenOption.READ)))

    def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle] =
        catchRead(new NioLineReadHandle(Files.newBufferedReader(jpath, charset)))

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        catchRead(Files.size(jpath))

    // --- Write ---

    def write(value: String, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
            discard(Files.writeString(
                jpath,
                value,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def writeBytes(value: Span[Byte], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
            discard(Files.write(
                jpath,
                value.toArray,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def writeLines(value: Chunk[String], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
            discard(Files.write(
                jpath,
                value.toSeq.asJava,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ))
        }

    def append(value: String, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
            discard(Files.writeString(jpath, value, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE))
        }

    def appendBytes(value: Span[Byte], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
            discard(Files.write(jpath, value.toArray, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE))
        }

    def appendLines(value: Chunk[String], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent(jpath)
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

    // --- Directory / structure ---

    def mkDir()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs(discard(Files.createDirectories(jpath)))

    def mkFile()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
            ensureParent(jpath)
            if !Files.exists(jpath) then discard(Files.createFile(jpath))
        }

    def list()(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]] =
        catchFs {
            val jstream = Files.list(jpath)
            try Chunk.from(jstream.iterator().asScala.map(p => NioPathUnsafe(p).safe).toList)
            finally jstream.close()
        }

    def list(glob: String)(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]] =
        catchFs {
            val pattern = PathDirectories.globToRegex(glob)
            val jstream = Files.list(jpath)
            try
                Chunk.from(
                    jstream.iterator().asScala
                        .filter { p => pattern.matches(p.getFileName.toString) }
                        .map(p => NioPathUnsafe(p).safe)
                        .toList
                )
            finally jstream.close()
            end try
        }

    def move(to: Path, replaceExisting: Boolean, atomicMove: Boolean, createFolders: Boolean)(using
        AllowUnsafe,
        Frame
    ): Result[FileFsException, Unit] =
        catchFs {
            val target = toNioPath(to)
            if createFolders then ensureParentOf(target)
            val opts = buildMoveOptions(replaceExisting, atomicMove)
            discard(Files.move(jpath, target, opts*))
        }

    def copy(to: Path, followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean, createFolders: Boolean)(using
        AllowUnsafe,
        Frame
    ): Result[FileFsException, Unit] =
        catchFs {
            val target = toNioPath(to)
            if createFolders then ensureParentOf(target)
            val opts = buildCopyOptions(followLinks, replaceExisting, copyAttributes)
            discard(Files.copy(jpath, target, opts*))
        }

    def remove()(using AllowUnsafe, Frame): Result[FileFsException, Boolean] =
        catchFs(Files.deleteIfExists(jpath))

    def removeExisting()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs(Files.delete(jpath))

    def removeAll()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
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

    def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileFsException, Path.WalkHandle] =
        val followOpts: Array[FileVisitOption] =
            if followLinks then Array(FileVisitOption.FOLLOW_LINKS) else Array.empty
        catchFs {
            val jstream  = Files.walk(jpath, maxDepth, followOpts*)
            val iterator = jstream.iterator()
            new NioWalkHandle(iterator, jstream)
        }
    end openWalk

    // --- Open write handle ---

    def openWrite(append: Boolean, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle] =
        catchWrite {
            if createFolders then ensureParent(jpath)
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

    private def buildMoveOptions(replaceExisting: Boolean, atomicMove: Boolean): Array[CopyOption] =
        val buf = ListBuffer.empty[CopyOption]
        if replaceExisting then buf += StandardCopyOption.REPLACE_EXISTING
        if atomicMove then buf += StandardCopyOption.ATOMIC_MOVE
        buf.toArray
    end buildMoveOptions

    private def buildCopyOptions(followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean): Array[CopyOption] =
        val buf = ListBuffer.empty[CopyOption]
        if replaceExisting then buf += StandardCopyOption.REPLACE_EXISTING
        if copyAttributes then buf += StandardCopyOption.COPY_ATTRIBUTES
        if !followLinks then buf += LinkOption.NOFOLLOW_LINKS
        buf.toArray
    end buildCopyOptions

    private def toNioPath(p: Path): java.nio.file.Path =
        p.unsafe match
            case n: NioPathUnsafe => n.jpath
            case other            => java.nio.file.Paths.get(other.show)

    // Exception translation helpers

    private def translateIoe(path: Path, e: IOException)(using Frame): FileFsException =
        e match
            case _: NoSuchFileException                      => FileNotFoundException(path)
            case _: AccessDeniedException                    => FileAccessDeniedException(path)
            case _: NotDirectoryException                    => FileNotADirectoryException(path)
            case _: java.nio.file.FileAlreadyExistsException => kyo.FileAlreadyExistsException(path)
            case _: DirectoryNotEmptyException               => FileDirectoryNotEmptyException(path)
            case _                                           => FileIOException(path, e)

    // On Scala Native Windows ARM64, java.nio.file throws generic IOException
    // instead of specific subclasses (NoSuchFileException, NotDirectoryException).
    // Detect these via Windows error codes in the exception message.
    private def isFileNotFound(e: IOException): Boolean =
        e.isInstanceOf[NoSuchFileException] ||
            (e.getMessage != null && (e.getMessage.contains("(2)") || e.getMessage.contains("(3)")))

    private def isNotDirectory(e: IOException): Boolean =
        e.isInstanceOf[NotDirectoryException] ||
            (e.getMessage != null && e.getMessage.contains("(267)"))

    private def catchRead[A](expr: => A)(using Frame): Result[FileReadException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException            =>
                // On Windows, reading a directory raises AccessDeniedException instead of "Is a directory"
                if java.nio.file.Files.isDirectory(jpath) then Result.fail(FileIsADirectoryException(safe))
                else Result.fail(FileAccessDeniedException(safe))
            case e: IOException if e.getMessage != null && e.getMessage.contains("Is a directory") =>
                Result.fail(FileIsADirectoryException(safe))
            case e: IOException => Result.fail(FileIOException(safe, e))
            case e: Throwable   => Result.panic(e)

    private def catchWrite[A](expr: => A)(using Frame): Result[FileWriteException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if isFileNotFound(e) => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException            =>
                // On some platforms (Scala Native / macOS), writing to a directory raises
                // AccessDeniedException (EACCES) rather than an IOException with "Is a directory".
                // Detect this case by checking whether the target path is actually a directory.
                if Files.isDirectory(jpath) then Result.fail(FileIsADirectoryException(safe))
                else Result.fail(FileAccessDeniedException(safe))
            case e: IOException if e.getMessage != null && e.getMessage.contains("Is a directory") =>
                Result.fail(FileIsADirectoryException(safe))
            case e: IOException => Result.fail(FileIOException(safe, e))
            case e: Throwable   => Result.panic(e)

    private def catchFs[A](expr: => A)(using Frame): Result[FileFsException, A] =
        try Result.succeed(expr)
        catch
            case e: IOException if isFileNotFound(e)         => Result.fail(FileNotFoundException(safe))
            case e: AccessDeniedException                    => Result.fail(FileAccessDeniedException(safe))
            case e: java.nio.file.FileAlreadyExistsException => Result.fail(kyo.FileAlreadyExistsException(safe))
            case e: DirectoryNotEmptyException               => Result.fail(FileDirectoryNotEmptyException(safe))
            case e: IOException if isNotDirectory(e)         => Result.fail(FileNotADirectoryException(safe))
            case e: IOException                              => Result.fail(FileIOException(safe, e))
            case e: Throwable                                => Result.panic(e)

end NioPathUnsafe

/** Concrete write handle backed by a `java.nio.channels.FileChannel`. */
final private[kyo] class NioWriteHandle(channel: FileChannel, path: Path) extends Path.WriteHandle:

    def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val arr = chunk.toArray
            val buf = java.nio.ByteBuffer.wrap(arr)
            while buf.hasRemaining do discard(channel.write(buf))
            Result.unit
        catch
            case e: IOException => Result.fail(FileIOException(path, e))
            case e: Throwable   => Result.panic(e)

    def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        writeBytes(Chunk.from(s.getBytes(charset)))

    def close()(using AllowUnsafe): Unit =
        channel.close()

end NioWriteHandle

/** Concrete read handle backed by a `java.nio.channels.FileChannel`. */
final private[kyo] class NioReadHandle(channel: FileChannel) extends Path.ReadHandle:

    def readChunk(buffer: Array[Byte])(using AllowUnsafe): Path.ReadResult =
        val bb = java.nio.ByteBuffer.wrap(buffer)
        Path.ReadResult(channel.read(bb))

    def position(offset: Long)(using AllowUnsafe): Unit =
        discard(channel.position(offset))

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

    /** Wraps an existing `java.nio.file.Path` as a kyo `Path`.
      *
      * The path is normalised. Available on JVM and Scala Native only.
      */
    def of(path: java.nio.file.Path): Path =
        new NioPathUnsafe(path.normalize()).safe

    /** Creates a new temporary file with the given prefix and suffix.
      *
      * The file is created in the system default temporary directory. Available on JVM and Scala Native only.
      *
      * @param prefix
      *   prefix for the temp file name (default `"kyo"`)
      * @param suffix
      *   suffix for the temp file name (default `".tmp"`)
      */
    def temp(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Abort[FileFsException]) =
        Sync.Unsafe.defer(
            Abort.get(
                try
                    Result.succeed(
                        new NioPathUnsafe(
                            java.nio.file.Files.createTempFile(prefix, suffix)
                        ).safe
                    )
                catch
                    case e: java.io.IOException =>
                        Result.fail(FileIOException(make(Chunk(prefix + suffix)), e))
            )
        )

    /** Creates a new temporary directory with the given prefix.
      *
      * The directory is created in the system default temporary directory. Available on JVM and Scala Native only.
      *
      * @param prefix
      *   prefix for the temp directory name (default `"kyo"`)
      */
    def tempDir(
        prefix: String = "kyo"
    )(using Frame): Path < (Sync & Abort[FileFsException]) =
        Sync.Unsafe.defer(
            Abort.get(
                try
                    Result.succeed(
                        new NioPathUnsafe(
                            java.nio.file.Files.createTempDirectory(prefix)
                        ).safe
                    )
                catch
                    case e: java.io.IOException =>
                        Result.fail(FileIOException(make(Chunk(prefix)), e))
            )
        )

    /** Creates a temporary file and registers it for deletion when the enclosing Scope closes.
      *
      * @param prefix
      *   prefix for the temp file name (default `"kyo"`)
      * @param suffix
      *   suffix for the temp file name (default `".tmp"`)
      */
    override def tempScoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Scope & Abort[FileFsException]) =
        super.tempScoped(prefix, suffix)

    private[kyo] def make(parts: Chunk[String]): Path =
        val isAbsolute = parts.headOption.contains("")
        val nonEmpty   = parts.filter(_.nonEmpty)
        if nonEmpty.isEmpty then
            if isAbsolute then new NioPathUnsafe(java.nio.file.Path.of("/")).safe
            else new NioPathUnsafe(java.nio.file.Path.of("")).safe
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

    private[kyo] def envOrEmpty(name: String): String =
        val v = java.lang.System.getenv(name)
        if v == null then "" else v

    private[kyo] def homePath: Path =
        val h = java.lang.System.getProperty("user.home", java.lang.System.getenv("HOME"))
        if h == null || h.isEmpty then make(Chunk(""))
        else make(Chunk(h))
    end homePath

    private[kyo] def osPlatform: String =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        if os.contains("mac") then "mac"
        else if os.contains("win") then "win"
        else "linux"
    end osPlatform

end PathPlatformSpecific
