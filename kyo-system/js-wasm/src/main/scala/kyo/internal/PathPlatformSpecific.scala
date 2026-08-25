package kyo.internal

import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

// --- Node.js facades ---

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object NodeFs extends js.Object:
    def existsSync(path: String): Boolean                                                      = js.native
    def realpathSync(path: String): String                                                     = js.native
    def statSync(path: String): NodeStats                                                      = js.native
    def lstatSync(path: String): NodeStats                                                     = js.native
    def readFileSync(path: String, encoding: String): String                                   = js.native
    def readFileSync(path: String): Uint8Array                                                 = js.native
    def writeFileSync(path: String, data: String, options: js.Dynamic): Unit                   = js.native
    def writeFileSync(path: String, data: Uint8Array): Unit                                    = js.native
    def appendFileSync(path: String, data: String, options: js.Dynamic): Unit                  = js.native
    def appendFileSync(path: String, data: Uint8Array): Unit                                   = js.native
    def mkdirSync(path: String, options: js.Dynamic): Unit                                     = js.native
    def readdirSync(path: String): js.Array[String]                                            = js.native
    def renameSync(oldPath: String, newPath: String): Unit                                     = js.native
    def copyFileSync(src: String, dest: String, flags: Int): Unit                              = js.native
    def unlinkSync(path: String): Unit                                                         = js.native
    def rmSync(path: String, options: js.Dynamic): Unit                                        = js.native
    def rmdirSync(path: String): Unit                                                          = js.native
    def truncateSync(path: String, len: Double): Unit                                          = js.native
    def openSync(path: String, flags: String): Int                                             = js.native
    def readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int = js.native
    // Declared without a trailing position argument, which is the whole point: Node then writes at the
    // file description's own cursor, the only offset `O_APPEND` acts on. `NodeWriteHandle` must call this
    // overload, since a positioned write on a descriptor opened to append is silent data loss.
    def writeSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int): Int     = js.native
    def writeSync(fd: Int, data: String, position: Double, encoding: String): Int = js.native
    def closeSync(fd: Int): Unit                                                  = js.native
    def fsyncSync(fd: Int): Unit                                                  = js.native
    def fstatSync(fd: Int): NodeStats                                             = js.native
    def symlinkSync(target: String, path: String): Unit                           = js.native
    def readlinkSync(path: String): String                                        = js.native
    def mkdtempSync(prefix: String): String                                       = js.native
    def writeFileSync(path: String, data: String): Unit                           = js.native
    def utimesSync(path: String, atime: Double, mtime: Double): Unit              = js.native
    def lutimesSync(path: String, atime: Double, mtime: Double): Unit             = js.native
end NodeFs

@js.native
trait NodeStats extends js.Object:
    def isFile(): Boolean         = js.native
    def isDirectory(): Boolean    = js.native
    def isSymbolicLink(): Boolean = js.native
    def size: Double              = js.native
    def mtimeMs: Double           = js.native
    def dev: Double               = js.native
    def ino: Double               = js.native
end NodeStats

@js.native
@JSImport("node:path", JSImport.Namespace)
private[kyo] object NodePath extends js.Object:
    def normalize(path: String): String   = js.native
    def resolve(paths: String*): String   = js.native
    def isAbsolute(path: String): Boolean = js.native
    def join(paths: String*): String      = js.native
    def dirname(path: String): String     = js.native
    def sep: String                       = js.native
    def delimiter: String                 = js.native
end NodePath

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object NodeOs extends js.Object:
    def tmpdir(): String   = js.native
    def homedir(): String  = js.native
    def platform(): String = js.native
end NodeOs

@js.native
@JSImport("node:crypto", JSImport.Namespace)
private[kyo] object NodeCrypto extends js.Object:
    def randomBytes(size: Int): js.Dynamic = js.native
end NodeCrypto

// --- Exception translation helpers ---

private[kyo] object NodeError:

    /** Extracts the Node.js error code from a js.JavaScriptException */
    private[kyo] def codeOf(e: js.JavaScriptException): String =
        val err = e.exception.asInstanceOf[js.Dynamic]
        val c   = err.code
        if js.isUndefined(c) then "UNKNOWN" else c.asInstanceOf[String]
    end codeOf

    def isMissing(e: js.JavaScriptException): Boolean =
        val code = codeOf(e)
        code == "ENOENT" || code == "ENOTDIR"

    def translateRead(path: Path, e: js.JavaScriptException)(using Frame): FileReadException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EISDIR"           => FileIsADirectoryException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Read)
            case _                  => FileIOException(path, FileSystemOperation.Read, e)

    def translateExists(path: Path, e: js.JavaScriptException)(using
        Frame
    )
        : FileInvalidPathException | FileAccessDeniedException | FileIOException =
        codeOf(e) match
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Exists)
            case _                  => FileIOException(path, FileSystemOperation.Exists, e)

    def translateMove(source: Path, target: Path, atomicity: Path.Atomicity, e: js.JavaScriptException)(using
        Frame
    ): FileStructureException =
        if atomicity == Path.Atomicity.Required && codeOf(e) == "EXDEV" then FileAtomicMoveUnsupportedException(source, target)
        else translateFs(source, FileSystemOperation.Move, e)

    def translateWrite(path: Path, e: js.JavaScriptException)(using Frame): FileWriteException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EISDIR"           => FileIsADirectoryException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Write)
            case _                  => FileIOException(path, FileSystemOperation.Write, e)

    def translateFs(path: Path, operation: FileSystemOperation, e: js.JavaScriptException)(using Frame): FileStructureException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "ENOTDIR"          => FileNotADirectoryException(path)
            case "EEXIST"           => FileAlreadyExistsException(path)
            case "ENOTEMPTY"        => FileDirectoryNotEmptyException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, operation)
            case _                  => FileIOException(path, operation, e)

end NodeError

// --- NodePathUnsafe ---

final private[kyo] class NodePathUnsafe(raw: String) extends Path.Unsafe:

    // Normalize to forward slashes for consistency across platforms.
    // Node.js on Windows handles '/' in all fs APIs.
    val pathStr: String = raw.replace('\\', '/')

    // --- Pure accessors ---

    def parts: Chunk[String] =
        if pathStr.isEmpty then Chunk.empty
        else if NodePath.isAbsolute(pathStr) then
            if pathStr.startsWith("/") then
                // POSIX root: the leading "" segment marks the root.
                val segs = pathStr.substring(1).split("/", -1).filter(_.nonEmpty)
                Chunk.from("" +: segs.toSeq)
            else
                // Windows drive root (e.g. "C:/Windows"): the drive designator segment marks
                // the root, matching the JVM/Native representation so parts round-trip uniformly.
                Chunk.from(pathStr.split("/", -1).filter(_.nonEmpty).toSeq)
        else
            Chunk.from(pathStr.split("/", -1).filter(_.nonEmpty).toSeq)
        end if
    end parts

    def show: String        = pathStr
    def isAbsolute: Boolean = NodePath.isAbsolute(pathStr)

    override def equals(other: Any): Boolean = other match
        case that: NodePathUnsafe => this.pathStr == that.pathStr
        case _                    => false

    override def hashCode(): Int = pathStr.hashCode

    // --- Inspection ---

    def exists()(using AllowUnsafe, Frame): Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean] =
        exists(followLinks = true)

    def exists(followLinks: Boolean)(using
        AllowUnsafe,
        Frame
    )
        : Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean] =
        try
            if followLinks then
                discard(NodeFs.statSync(pathStr))
            else
                discard(NodeFs.lstatSync(pathStr))
            end if
            Result.succeed(true)
        catch
            case e: js.JavaScriptException if NodeError.isMissing(e) => Result.succeed(false)
            case e: js.JavaScriptException                           => Result.fail(NodeError.translateExists(safe, e))
            case e: Throwable                                        => Result.panic(e)

    def isDirectory()(using AllowUnsafe): Boolean =
        try NodeFs.statSync(pathStr).isDirectory()
        catch case _: js.JavaScriptException => false

    def isRegularFile()(using AllowUnsafe): Boolean =
        try NodeFs.statSync(pathStr).isFile()
        catch case _: js.JavaScriptException => false

    def isSymbolicLink()(using AllowUnsafe): Boolean =
        try NodeFs.lstatSync(pathStr).isSymbolicLink()
        catch case _: js.JavaScriptException => false

    def realPath()(using
        AllowUnsafe,
        Frame
    )
        : Result[FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException, Path] =
        try Result.succeed(Path(NodeFs.realpathSync(pathStr)))
        catch
            case e: js.JavaScriptException =>
                val failure: FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException =
                    NodeError.translateRead(safe, e) match
                        case value: FileNotFoundException     => value
                        case value: FileAccessDeniedException => value
                        case value: FileInvalidPathException  => value
                        case value: FileIOException           => value
                        case _                                => FileIOException(safe, FileSystemOperation.RealPath, e)
                Result.fail(failure)
            case e: Throwable => Result.panic(e)

    // --- Read ---

    def read()(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead {
            NodeFs.readFileSync(pathStr, "utf8")
        }

    def read(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead {
            val bytes = NodeFs.readFileSync(pathStr)
            new String(uint8ArrayToBytes(bytes), charset)
        }

    def readBytes()(using AllowUnsafe, Frame): Result[FileReadException, Span[Byte]] =
        catchRead {
            val arr = uint8ArrayToBytes(NodeFs.readFileSync(pathStr))
            Span.from(arr)
        }

    def readLines()(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead {
            val content = NodeFs.readFileSync(pathStr, "utf8")
            Chunk.from(splitLines(content))
        }

    def readLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead {
            val bytes   = NodeFs.readFileSync(pathStr)
            val content = new String(uint8ArrayToBytes(bytes), charset)
            Chunk.from(splitLines(content))
        }

    // --- Streaming read handles ---

    def openRead()(using AllowUnsafe, Frame): Result[FileReadException, Path.ReadHandle] =
        catchRead {
            val fd = NodeFs.openSync(pathStr, "r")
            new NodeReadHandle(fd, safe)
        }

    def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle] =
        catchRead {
            val bytes   = NodeFs.readFileSync(pathStr)
            val content = new String(uint8ArrayToBytes(bytes), charset)
            val lines   = splitLines(content).toArray
            new NodeLineReadHandle(lines, 0)
        }

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        catchRead {
            NodeFs.statSync(pathStr).size.toLong
        }

    def stat()(using AllowUnsafe, Frame): Result[FileReadException, kyo.Path.PathStat] =
        catchRead {
            val s = NodeFs.statSync(pathStr)
            // Rounded, not truncated. Node converts a modification time to a double count of
            // seconds inside libuv before the syscall, so a value the caller set as 987654 ms is
            // stored as 987653999000 ns and read back as 987653.999. Truncating reports 987653, a
            // millisecond before both the instant the filesystem holds and the one that was asked
            // for. Rounding reports the nearest millisecond to what is stored, which recovers it.
            kyo.Path.PathStat(math.round(s.mtimeMs), s.size.toLong)
        }

    // --- Write ---

    def write(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeFs.writeFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def writeBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeFs.writeFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def writeLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeFs.writeFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def append(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeFs.appendFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def appendBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeFs.appendFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def appendLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeFs.appendFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val currentSize = NodeFs.lstatSync(pathStr).size.asInstanceOf[Double].toLong
            if size < currentSize then
                NodeFs.truncateSync(pathStr, size.toDouble)
        }

    def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val epochSec = epochMs / 1000.0
            NodeFs.utimesSync(pathStr, epochSec, epochSec)
        }

    // --- Directory / structure ---

    def mkDir()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create) {
            NodeFs.mkdirSync(pathStr, js.Dynamic.literal(recursive = true))
        }

    def mkFile()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create) {
            ensureParent()
            if !NodeFs.existsSync(pathStr) then
                NodeFs.writeFileSync(pathStr, "")
        }

    def list()(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]] =
        catchFs(FileSystemOperation.List) {
            val entries = NodeFs.readdirSync(pathStr)
            val sep     = NodePath.sep
            Chunk.from(entries.toSeq.map { name =>
                new NodePathUnsafe(pathStr + sep + name).safe
            })
        }

    def list(glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]] =
        list().map(_.filter(path => glob.matches(Chunk(path.parts.last), caseSensitivity)))

    def move(to: Path, options: Path.MoveOptions)(using
        AllowUnsafe,
        Frame
    ): Result[FileStructureException, Unit] =
        try
            val toStr = to.unsafe.show
            if options.createFolders then ensureParentOf(toStr)
            if options.replace == Path.Replace.Never && NodeFs.existsSync(toStr) then
                // Throw to trigger catchFs error translation
                throw js.JavaScriptException(
                    js.Dynamic.literal(code = "EEXIST", message = s"File already exists: $toStr")
                )
            end if
            NodeFs.renameSync(pathStr, toStr)
            Result.succeed(())
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateMove(safe, to, options.atomicity, e))
            case e: Throwable              => Result.panic(e)

    def copy(to: Path, options: Path.CopyOptions)(using
        AllowUnsafe,
        Frame
    ): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Copy) {
            val toStr = to.unsafe.show
            if options.createFolders then ensureParentOf(toStr)
            val targetExists = existsNoFollow(toStr)
            if targetExists && options.replace == Path.Replace.Never then
                throw js.JavaScriptException(js.Dynamic.literal(code = "EEXIST", message = s"File already exists: $toStr"))
            val linkStat   = NodeFs.lstatSync(pathStr)
            val sourceStat = if options.followLinks then NodeFs.statSync(pathStr) else linkStat
            if linkStat.isSymbolicLink() && !options.followLinks then
                if targetExists then
                    val targetStat = NodeFs.lstatSync(toStr)
                    if targetStat.isDirectory() then NodeFs.rmdirSync(toStr)
                    else NodeFs.unlinkSync(toStr)
                end if
                NodeFs.symlinkSync(NodeFs.readlinkSync(pathStr), toStr)
                if options.copyAttributes then
                    val epochSec = linkStat.mtimeMs / 1000.0
                    NodeFs.lutimesSync(toStr, epochSec, epochSec)
            else if sourceStat.isDirectory() then
                if !targetExists then NodeFs.mkdirSync(toStr, js.Dynamic.literal(recursive = false))
                else if !NodeFs.lstatSync(toStr).isDirectory() then
                    NodeFs.unlinkSync(toStr)
                    NodeFs.mkdirSync(toStr, js.Dynamic.literal(recursive = false))
            else
                NodeFs.copyFileSync(pathStr, toStr, 0)
                // Windows copies file times along with the bytes (copyFileSync goes through CopyFileExW), so the
                // target keeps the source's mtime even when the caller asked not to preserve attributes. POSIX gives
                // the new file the current time. Stamp `now` so `copyAttributes = false` means the same thing on
                // every host; the copyAttributes = true branch below overwrites this with the source's mtime.
                if !options.copyAttributes then
                    val nowSec = js.Date.now() / 1000.0
                    NodeFs.utimesSync(toStr, nowSec, nowSec)
            end if
            if options.copyAttributes && !(linkStat.isSymbolicLink() && !options.followLinks) then
                val epochSec = sourceStat.mtimeMs / 1000.0
                NodeFs.utimesSync(toStr, epochSec, epochSec)
            end if
        }

    def remove()(using AllowUnsafe, Frame): Result[FileStructureException, Boolean] =
        try
            if !NodeFs.existsSync(pathStr) then Result.succeed(false)
            else
                val stat = NodeFs.lstatSync(pathStr)
                if stat.isDirectory() then
                    // Use rmdirSync for directories because it throws ENOTEMPTY for non-empty dirs.
                    // rmSync without recursive raises EISDIR on some platforms.
                    NodeFs.rmdirSync(pathStr)
                else
                    NodeFs.unlinkSync(pathStr)
                end if
                Result.succeed(true)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateFs(safe, FileSystemOperation.Remove, e))
            case e: Throwable              => Result.panic(e)

    def removeExisting()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove) {
            val stat = NodeFs.lstatSync(pathStr)
            if stat.isDirectory() then
                // Use rmdirSync for directories because it throws ENOTEMPTY for non-empty dirs.
                NodeFs.rmdirSync(pathStr)
            else
                NodeFs.unlinkSync(pathStr)
            end if
        }

    def removeAll()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove) {
            if NodeFs.existsSync(pathStr) then
                val stat = NodeFs.lstatSync(pathStr)
                if stat.isDirectory() then
                    NodeFs.rmSync(pathStr, js.Dynamic.literal(recursive = true, force = true))
                else
                    NodeFs.unlinkSync(pathStr)
                end if
        }

    // --- Walk handle ---

    def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileStructureException, Path.WalkHandle] =
        catchFs(FileSystemOperation.Walk) {
            // Validate that the root path exists before opening the walk handle.
            // lstatSync throws ENOENT if the path does not exist.
            discard(NodeFs.lstatSync(pathStr))
            new NodeWalkHandle(pathStr, maxDepth, followLinks)
        }

    // --- Open write handle ---

    def openWrite(append: Boolean, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle] =
        catchWrite {
            if options.createFolders then ensureParent()
            val flags = if append then "a" else "w"
            val fd    = NodeFs.openSync(pathStr, flags)
            new NodeWriteHandle(fd, safe)
        }

    // --- Private helpers ---

    /** Splits content by newlines, dropping a single trailing empty element if the content ends with '\n'. This matches the behaviour of
      * java.nio.file.Files.readAllLines.
      */
    private def splitLines(content: String): Seq[String] =
        val parts = content.split("\n", -1).toSeq
        if parts.nonEmpty && parts.last.isEmpty then parts.init else parts
    end splitLines

    private def ensureParent(): Unit =
        val parent = NodePath.dirname(pathStr)
        if parent.nonEmpty && parent != pathStr then
            NodeFs.mkdirSync(parent, js.Dynamic.literal(recursive = true))
    end ensureParent

    private def ensureParentOf(target: String): Unit =
        val parent = NodePath.dirname(target)
        if parent.nonEmpty && parent != target then
            NodeFs.mkdirSync(parent, js.Dynamic.literal(recursive = true))
    end ensureParentOf

    private def existsNoFollow(target: String): Boolean =
        try
            discard(NodeFs.lstatSync(target))
            true
        catch
            case e: js.JavaScriptException =>
                val code = e.exception.asInstanceOf[js.Dynamic].selectDynamic("code")
                if !js.isUndefined(code) && code.asInstanceOf[String] == "ENOENT" then false
                else throw e
    end existsNoFollow

    private def catchRead[A](expr: => A)(using Frame): Result[FileReadException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateRead(safe, e))
            case e: Throwable              => Result.panic(e)

    private def catchWrite[A](expr: => A)(using Frame): Result[FileWriteException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateWrite(safe, e))
            case e: Throwable              => Result.panic(e)

    private def catchFs[A](operation: FileSystemOperation)(expr: => A)(using Frame): Result[FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateFs(safe, operation, e))
            case e: Throwable              => Result.panic(e)

end NodePathUnsafe

// --- NodeReadHandle ---

/** Concrete read handle backed by a Node file descriptor.
  *
  * Carries the `Path` it was opened under only to name the file in a `FileReadException`, the same reason `NodeWriteHandle` carries one.
  * Nothing here re-resolves it: every measurement goes through the descriptor.
  */
final private[kyo] class NodeReadHandle(fd: Int, path: Path) extends Path.ReadHandle:

    // Current read position (Node.js readSync with explicit position)
    private var pos: Long = 0L

    // Single-owner scan buffer for readLong, reused across calls; grows once to fit. Confined to this
    // handle instance.
    private var scan: Array[Byte] = new Array[Byte](512)

    // The typed-array view readSync fills, retained across calls alongside `scan`: a fresh Uint8Array per
    // fill step allocates on every read, which is the per-read allocation readLong exists to avoid.
    // Reallocated only when `scan` grows.
    private var scanView: Uint8Array = new Uint8Array(scan.length)

    def readLong()(using AllowUnsafe): Long =
        @scala.annotation.tailrec
        def fill(offset: Long, total: Int): Int =
            if total == scan.length then
                val grown = new Array[Byte](scan.length * 2)
                java.lang.System.arraycopy(scan, 0, grown, 0, total)
                scan = grown
                scanView = new Uint8Array(grown.length)
            end if
            val n = NodeFs.readSync(fd, scanView, total, scan.length - total, offset.toDouble)
            if n == 0 then total
            else
                // A hot-path byte copy out of the JS typed array, the same shape readChunk above uses:
                // a per-byte closure would allocate on every read, which is what this primitive exists
                // to avoid.
                var i = 0
                while i < n do
                    scan(total + i) = scanView(total + i).toByte
                    i += 1
                fill(offset + n, total + n)
            end if
        end fill
        val len = fill(0L, 0)
        Path.ReadHandle.parseLeadingLong(scan, len)
    end readLong

    def readChunk(buffer: Array[Byte])(using AllowUnsafe): Path.ReadResult =
        val uint8 = new Uint8Array(buffer.length)
        val n     = NodeFs.readSync(fd, uint8, 0, buffer.length, pos.toDouble)
        if n == 0 then Path.ReadResult.Eof
        else
            var i = 0
            while i < n do
                buffer(i) = uint8(i).toByte
                i += 1
            pos += n
            Path.ReadResult(n)
        end if
    end readChunk

    def position(offset: Long)(using AllowUnsafe): Unit =
        pos = offset

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        // fstat on the descriptor, not stat on the path: it answers for the file this handle holds
        // even once the name has been renamed away or unlinked. Same translation catchRead applies.
        try Result.succeed(NodeFs.fstatSync(fd).size.toLong)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable              => Result.panic(e)

    def close()(using AllowUnsafe): Unit =
        NodeFs.closeSync(fd)

end NodeReadHandle

// --- NodeLineReadHandle ---

final private[kyo] class NodeLineReadHandle(lines: Array[String], private var idx: Int) extends Path.LineReadHandle:

    def readLine()(using AllowUnsafe): Maybe[String] =
        if idx >= lines.length then Absent
        else
            val line = lines(idx)
            idx += 1
            Present(line)

    def close()(using AllowUnsafe): Unit = ()

end NodeLineReadHandle

// --- NodeWalkHandle ---

final private[kyo] class NodeWalkHandle(root: String, maxDepth: Int, followLinks: Boolean) extends Path.WalkHandle:

    // Stack of (path, depth) entries to visit; populated lazily
    private val stack   = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]
    private var started = false

    private def init(): Unit =
        started = true
        // Push the root itself at depth 0 (will be emitted and then expanded)
        stack += ((root, 0))
    end init

    def next()(using AllowUnsafe): Maybe[Path] =
        if !started then init()
        if stack.isEmpty then Absent
        else
            val (pathStr, depth) = stack.remove(stack.length - 1)
            // Expand directory contents if within maxDepth
            val statFn: String => NodeStats =
                if followLinks then NodeFs.statSync else NodeFs.lstatSync
            val isDir =
                try statFn(pathStr).isDirectory()
                catch case _: js.JavaScriptException => false
            if isDir && depth < maxDepth then
                val children =
                    try NodeFs.readdirSync(pathStr).toSeq
                    catch case _: js.JavaScriptException => Seq.empty
                val sep = NodePath.sep
                // Add children in reverse order so first child is popped first
                children.reverseIterator.foreach { name =>
                    stack += ((pathStr + sep + name, depth + 1))
                }
            end if
            Present(new NodePathUnsafe(pathStr).safe)
        end if
    end next

    def close()(using AllowUnsafe): Unit = stack.clear()

end NodeWalkHandle

// --- NodeWriteHandle ---

/** Concrete write handle backed by a Node file descriptor.
  *
  * Every write goes at the descriptor's own cursor: `writeSync` is called without a position, which is what makes the `"a"` flag
  * `openWrite(append = true)` opens with mean what it says. A position passed explicitly turns the call into a positioned write, and POSIX
  * leaves `O_APPEND` without effect on those, so a handle tracking its own offset appends on Linux (which appends regardless, against the
  * standard) and overwrites the file from that offset on macOS. Letting the descriptor carry the offset also keeps the append atomic
  * against another writer growing the file, which sampling the size once at open cannot.
  *
  * The truncating open needs no offset either: `"w"` starts the cursor at zero and each write advances it by what it wrote, which is
  * exactly what a hand-kept counter would have recomputed. This matches `NioWriteHandle`, which writes through the channel's own position
  * on JVM and Native.
  *
  * Carries the `Path` it was opened under only to name the file in a `FileWriteException`.
  */
final private[kyo] class NodeWriteHandle(fd: Int, path: Path) extends Path.WriteHandle:

    private var finished = false

    def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val arr   = chunk.toArray
            val uint8 = bytesToUint8Array(arr)
            // A short write leaves the tail of the buffer unwritten, so what is left is retried from where
            // it stopped. This is the loop NioWriteHandle runs against the channel, written against the
            // buffer offset because the file offset belongs to the descriptor.
            @scala.annotation.tailrec
            def loop(offset: Int): Unit =
                if offset < arr.length then
                    loop(offset + NodeFs.writeSync(fd, uint8, offset, arr.length - offset))
            loop(0)
            Result.unit
        catch
            case e: js.JavaScriptException =>
                Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable =>
                Result.panic(e)

    def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        writeBytes(Chunk.from(s.getBytes(charset)))

    def finish()(using AllowUnsafe): Unit =
        NodeFs.fsyncSync(fd) // fsync: bytes are durable before the logical-completion flag
        finished = true

    def close()(using AllowUnsafe): Unit =
        NodeFs.closeSync(fd)
        if !finished then
            // Unsafe: removes the partially-written file if finish() was never called
            if NodeFs.existsSync(path.unsafe.show) then
                NodeFs.unlinkSync(path.unsafe.show)
        end if
    end close

end NodeWriteHandle

// --- Byte / Uint8Array conversion helpers ---

private[kyo] def uint8ArrayToBytes(arr: Uint8Array): Array[Byte] =
    val result = new Array[Byte](arr.length)
    var i      = 0
    while i < arr.length do
        result(i) = arr(i).toByte
        i += 1
    result
end uint8ArrayToBytes

private[kyo] def bytesToUint8Array(bytes: Array[Byte]): Uint8Array =
    val arr = new Uint8Array(bytes.length)
    var i   = 0
    while i < bytes.length do
        arr(i) = bytes(i).toShort
        i += 1
    arr
end bytesToUint8Array

// --- PathPlatformSpecific ---

abstract private[kyo] class PathPlatformSpecific extends PathDirectories:

    private[kyo] val platformPathSeparator: String = NodePath.delimiter
    private[kyo] val platformFileSeparator: String = NodePath.sep

    private[kyo] def make(parts: Chunk[String]): Path =
        if parts.isEmpty then new NodePathUnsafe("").safe
        else
            val isAbs    = parts.headOption.contains("")
            val nonEmpty = parts.filter(_.nonEmpty)
            if nonEmpty.isEmpty then
                if isAbs then new NodePathUnsafe("/").safe
                else new NodePathUnsafe("").safe
            else
                // Don't prepend separator for Windows drive-letter paths (e.g. "C:")
                val hasDrive = nonEmpty.headOption.exists(s => s.length == 2 && s(1) == ':')
                val raw =
                    if isAbs && !hasDrive then "/" + nonEmpty.mkString("/")
                    else if hasDrive then
                        // A drive designator head is the volume root, matching the JVM/Native
                        // representation. Render it rooted: a bare "C:" reads as Windows'
                        // drive-relative current directory, which win32 normalize turns into
                        // "C:." and corrupts every derived accessor.
                        nonEmpty.head + "/" + nonEmpty.tail.mkString("/")
                    else nonEmpty.mkString("/")
                // NodePath.normalize resolves .., ., redundant separators;
                // constructor normalizes \ to /
                new NodePathUnsafe(NodePath.normalize(raw)).safe
            end if
        end if
    end make

    def tempUnscoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Abort[FileStructureException]) =
        // Unsafe: bridges Node temp-file creation into the Sync tier.
        Sync.Unsafe.defer {
            Abort.get {
                try
                    val tmpDir  = NodeOs.tmpdir()
                    val name    = prefix + randomId() + suffix
                    val tmpPath = tmpDir + NodePath.sep + name
                    NodeFs.writeFileSync(tmpPath, "")
                    Result.succeed(new NodePathUnsafe(tmpPath).safe)
                catch
                    case e: js.JavaScriptException =>
                        Result.fail(FileIOException(make(Chunk(prefix + suffix)), FileSystemOperation.Create, e))
            }
        }

    def tempDirUnscoped(
        prefix: String = "kyo"
    )(using Frame): Path < (Sync & Abort[FileStructureException]) =
        // Unsafe: bridges Node temp-directory creation into the Sync tier.
        Sync.Unsafe.defer {
            Abort.get {
                try
                    val tmpDir  = NodeOs.tmpdir()
                    val created = NodeFs.mkdtempSync(tmpDir + NodePath.sep + prefix)
                    Result.succeed(new NodePathUnsafe(created).safe)
                catch
                    case e: js.JavaScriptException =>
                        Result.fail(FileIOException(make(Chunk(prefix)), FileSystemOperation.Create, e))
            }
        }

    /** Creates a temporary file and registers it for deletion when the enclosing Scope closes.
      *
      * @param prefix
      *   prefix for the temp file name (default `"kyo"`)
      * @param suffix
      *   suffix for the temp file name (default `".tmp"`)
      */
    override def temp(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Scope & Abort[FileStructureException]) =
        super.temp(prefix, suffix)

    /** Generates a random identifier using the Node.js crypto module (avoids java.security.SecureRandom). */
    private def randomId(): String =
        NodeCrypto.randomBytes(16).applyDynamic("toString")("hex").asInstanceOf[String]

    private[kyo] def envOrEmpty(name: String): String =
        val v = js.Dynamic.global.process.env.selectDynamic(name)
        if js.isUndefined(v) || v == null then "" else v.asInstanceOf[String]

    private[kyo] def homePath: Path =
        make(Chunk(NodeOs.homedir()))

    private[kyo] def cwdPath: Path =
        make(Chunk(js.Dynamic.global.process.applyDynamic("cwd")().asInstanceOf[String]))

    private[kyo] def osPlatform: String =
        NodeOs.platform() match
            case "darwin" => "mac"
            case "win32"  => "win"
            case _        => "linux"

end PathPlatformSpecific
