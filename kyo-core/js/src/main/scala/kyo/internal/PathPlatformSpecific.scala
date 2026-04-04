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
    def existsSync(path: String): Boolean                                                       = js.native
    def statSync(path: String): NodeStats                                                       = js.native
    def lstatSync(path: String): NodeStats                                                      = js.native
    def readFileSync(path: String, encoding: String): String                                    = js.native
    def readFileSync(path: String): Uint8Array                                                  = js.native
    def writeFileSync(path: String, data: String, options: js.Dynamic): Unit                    = js.native
    def writeFileSync(path: String, data: Uint8Array): Unit                                     = js.native
    def appendFileSync(path: String, data: String, options: js.Dynamic): Unit                   = js.native
    def appendFileSync(path: String, data: Uint8Array): Unit                                    = js.native
    def mkdirSync(path: String, options: js.Dynamic): Unit                                      = js.native
    def readdirSync(path: String): js.Array[String]                                             = js.native
    def renameSync(oldPath: String, newPath: String): Unit                                      = js.native
    def copyFileSync(src: String, dest: String, flags: Int): Unit                               = js.native
    def unlinkSync(path: String): Unit                                                          = js.native
    def rmSync(path: String, options: js.Dynamic): Unit                                         = js.native
    def rmdirSync(path: String): Unit                                                           = js.native
    def truncateSync(path: String, len: Double): Unit                                           = js.native
    def openSync(path: String, flags: String): Int                                              = js.native
    def readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int  = js.native
    def writeSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int = js.native
    def writeSync(fd: Int, data: String, position: Double, encoding: String): Int               = js.native
    def closeSync(fd: Int): Unit                                                                = js.native
    def fstatSync(fd: Int): NodeStats                                                           = js.native
    def symlinkSync(target: String, path: String): Unit                                         = js.native
    def mkdtempSync(prefix: String): String                                                     = js.native
    def writeFileSync(path: String, data: String): Unit                                         = js.native
end NodeFs

@js.native
trait NodeStats extends js.Object:
    def isFile(): Boolean         = js.native
    def isDirectory(): Boolean    = js.native
    def isSymbolicLink(): Boolean = js.native
    def size: Double              = js.native
end NodeStats

@js.native
@JSImport("node:path", JSImport.Namespace)
private[kyo] object NodePath extends js.Object:
    def normalize(path: String): String   = js.native
    def resolve(paths: String*): String   = js.native
    def isAbsolute(path: String): Boolean = js.native
    def join(paths: String*): String      = js.native
    def basename(path: String): String    = js.native
    def dirname(path: String): String     = js.native
    def sep: String                       = js.native
end NodePath

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object NodeOs extends js.Object:
    def tmpdir(): String   = js.native
    def homedir(): String  = js.native
    def platform(): String = js.native
end NodeOs

// --- Exception translation helpers ---

private[kyo] object NodeError:

    /** Extracts the Node.js error code from a js.JavaScriptException */
    private def codeOf(e: js.JavaScriptException): String =
        val err = e.exception.asInstanceOf[js.Dynamic]
        val c   = err.code
        if js.isUndefined(c) then "UNKNOWN" else c.asInstanceOf[String]
    end codeOf

    def translateRead(path: Path, e: js.JavaScriptException)(using Frame): FileReadException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EISDIR"           => FileIsADirectoryException(path)
            case _                  => FileIOException(path, new IOException(e.getMessage))

    def translateWrite(path: Path, e: js.JavaScriptException)(using Frame): FileWriteException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EISDIR"           => FileIsADirectoryException(path)
            case _                  => FileIOException(path, new IOException(e.getMessage))

    def translateFs(path: Path, e: js.JavaScriptException)(using Frame): FileFsException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "ENOTDIR"          => FileNotADirectoryException(path)
            case "EEXIST"           => FileAlreadyExistsException(path)
            case "ENOTEMPTY"        => FileDirectoryNotEmptyException(path)
            case _                  => FileIOException(path, new IOException(e.getMessage))

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
            // Absolute: prepend "" to signal root
            val stripped = if pathStr.startsWith("/") then pathStr.substring(1) else pathStr
            val segs     = stripped.split("/", -1).filter(_.nonEmpty)
            Chunk.from("" +: segs.toSeq)
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

    def exists()(using AllowUnsafe): Boolean =
        try NodeFs.existsSync(pathStr)
        catch case _: js.JavaScriptException => false

    def exists(followLinks: Boolean)(using AllowUnsafe): Boolean =
        if followLinks then
            try
                discard(NodeFs.statSync(pathStr))
                true
            catch case _: js.JavaScriptException => false
        else
            try
                discard(NodeFs.lstatSync(pathStr))
                true
            catch case _: js.JavaScriptException => false

    def isDirectory()(using AllowUnsafe): Boolean =
        try NodeFs.statSync(pathStr).isDirectory()
        catch case _: js.JavaScriptException => false

    def isRegularFile()(using AllowUnsafe): Boolean =
        try NodeFs.statSync(pathStr).isFile()
        catch case _: js.JavaScriptException => false

    def isSymbolicLink()(using AllowUnsafe): Boolean =
        try NodeFs.lstatSync(pathStr).isSymbolicLink()
        catch case _: js.JavaScriptException => false

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
            new NodeReadHandle(fd)
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

    // --- Write ---

    def write(value: String, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            NodeFs.writeFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def writeBytes(value: Span[Byte], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            NodeFs.writeFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def writeLines(value: Chunk[String], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeFs.writeFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def append(value: String, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            NodeFs.appendFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def appendBytes(value: Span[Byte], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            NodeFs.appendFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def appendLines(value: Chunk[String], createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeFs.appendFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val currentSize = NodeFs.lstatSync(pathStr).size.asInstanceOf[Double].toLong
            if size < currentSize then
                NodeFs.truncateSync(pathStr, size.toDouble)
        }

    // --- Directory / structure ---

    def mkDir()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
            NodeFs.mkdirSync(pathStr, js.Dynamic.literal(recursive = true))
        }

    def mkFile()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
            ensureParent()
            if !NodeFs.existsSync(pathStr) then
                NodeFs.writeFileSync(pathStr, "")
        }

    def list()(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]] =
        catchFs {
            val entries = NodeFs.readdirSync(pathStr)
            val sep     = NodePath.sep
            Chunk.from(entries.toSeq.map { name =>
                new NodePathUnsafe(pathStr + sep + name).safe
            })
        }

    def list(glob: String)(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]] =
        catchFs {
            val entries = NodeFs.readdirSync(pathStr)
            val sep     = NodePath.sep
            val pattern = PathDirectories.globToRegex(glob)
            Chunk.from(entries.toSeq.filter(pattern.matches).map { name =>
                (new NodePathUnsafe(pathStr + sep + name)).safe
            })
        }

    def move(to: Path, replaceExisting: Boolean, atomicMove: Boolean, createFolders: Boolean)(using
        AllowUnsafe,
        Frame
    ): Result[FileFsException, Unit] =
        catchFs {
            val toStr = to.unsafe.show
            if createFolders then ensureParentOf(toStr)
            if !replaceExisting && NodeFs.existsSync(toStr) then
                // Throw to trigger catchFs error translation
                throw js.JavaScriptException(
                    js.Dynamic.literal(code = "EEXIST", message = s"File already exists: $toStr")
                )
            end if
            NodeFs.renameSync(pathStr, toStr)
        }

    def copy(to: Path, followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean, createFolders: Boolean)(using
        AllowUnsafe,
        Frame
    ): Result[FileFsException, Unit] =
        catchFs {
            val toStr = to.unsafe.show
            if createFolders then ensureParentOf(toStr)
            val stat = NodeFs.lstatSync(pathStr)
            if stat.isDirectory() then
                if replaceExisting || !NodeFs.existsSync(toStr) then
                    NodeFs.mkdirSync(toStr, js.Dynamic.literal(recursive = false))
            else
                val flags = if replaceExisting then 0 else 1
                NodeFs.copyFileSync(pathStr, toStr, flags)
            end if
        }

    def remove()(using AllowUnsafe, Frame): Result[FileFsException, Boolean] =
        try
            if !NodeFs.existsSync(pathStr) then Result.succeed(false)
            else
                val stat = NodeFs.lstatSync(pathStr)
                if stat.isDirectory() then
                    // Use rmdirSync for directories — it throws ENOTEMPTY for non-empty dirs.
                    // rmSync without recursive raises EISDIR on some platforms.
                    NodeFs.rmdirSync(pathStr)
                else
                    NodeFs.unlinkSync(pathStr)
                end if
                Result.succeed(true)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateFs(safe, e))
            case e: Throwable              => Result.panic(e)

    def removeExisting()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
            val stat = NodeFs.lstatSync(pathStr)
            if stat.isDirectory() then
                // Use rmdirSync for directories — it throws ENOTEMPTY for non-empty dirs.
                NodeFs.rmdirSync(pathStr)
            else
                NodeFs.unlinkSync(pathStr)
            end if
        }

    def removeAll()(using AllowUnsafe, Frame): Result[FileFsException, Unit] =
        catchFs {
            if NodeFs.existsSync(pathStr) then
                val stat = NodeFs.lstatSync(pathStr)
                if stat.isDirectory() then
                    NodeFs.rmSync(pathStr, js.Dynamic.literal(recursive = true, force = true))
                else
                    NodeFs.unlinkSync(pathStr)
                end if
        }

    // --- Walk handle ---

    def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileFsException, Path.WalkHandle] =
        catchFs {
            // Validate that the root path exists before opening the walk handle.
            // lstatSync throws ENOENT if the path does not exist.
            discard(NodeFs.lstatSync(pathStr))
            new NodeWalkHandle(pathStr, maxDepth, followLinks)
        }

    // --- Open write handle ---

    def openWrite(append: Boolean, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle] =
        catchWrite {
            if createFolders then ensureParent()
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

    private def catchFs[A](expr: => A)(using Frame): Result[FileFsException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateFs(safe, e))
            case e: Throwable              => Result.panic(e)

end NodePathUnsafe

// --- NodeReadHandle ---

final private[kyo] class NodeReadHandle(fd: Int) extends Path.ReadHandle:

    // Current read position (Node.js readSync with explicit position)
    private var pos: Long = 0L

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

final private[kyo] class NodeWriteHandle(fd: Int, path: Path) extends Path.WriteHandle:

    private var pos: Long = 0L

    def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val arr   = chunk.toArray
            val uint8 = bytesToUint8Array(arr)
            val n     = NodeFs.writeSync(fd, uint8, 0, arr.length, pos.toDouble)
            pos += n
            Result.unit
        catch
            case e: js.JavaScriptException =>
                Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable =>
                Result.panic(e)

    def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        writeBytes(Chunk.from(s.getBytes(charset)))

    def close()(using AllowUnsafe): Unit =
        NodeFs.closeSync(fd)

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
                    else nonEmpty.mkString("/")
                // NodePath.normalize resolves .., ., redundant separators;
                // constructor normalizes \ to /
                new NodePathUnsafe(NodePath.normalize(raw)).safe
            end if
        end if
    end make

    def temp(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Abort[FileFsException]) =
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
                        Result.fail(FileIOException(
                            make(Chunk(prefix + suffix)),
                            new IOException(e.getMessage)
                        ))
            }
        }

    def tempDir(
        prefix: String = "kyo"
    )(using Frame): Path < (Sync & Abort[FileFsException]) =
        Sync.Unsafe.defer {
            Abort.get {
                try
                    val tmpDir  = NodeOs.tmpdir()
                    val created = NodeFs.mkdtempSync(tmpDir + NodePath.sep + prefix)
                    Result.succeed(new NodePathUnsafe(created).safe)
                catch
                    case e: js.JavaScriptException =>
                        Result.fail(FileIOException(
                            make(Chunk(prefix)),
                            new IOException(e.getMessage)
                        ))
            }
        }

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

    /** Generates a random identifier using the Node.js crypto module (avoids java.security.SecureRandom). */
    private def randomId(): String =
        js.Dynamic.global.require("node:crypto").randomBytes(16).applyDynamic("toString")("hex").asInstanceOf[String]

    private[kyo] def envOrEmpty(name: String): String =
        val v = js.Dynamic.global.process.env.selectDynamic(name)
        if js.isUndefined(v) || v == null then "" else v.asInstanceOf[String]

    private[kyo] def homePath: Path =
        make(Chunk(NodeOs.homedir()))

    private[kyo] def osPlatform: String =
        NodeOs.platform() match
            case "darwin" => "mac"
            case "win32"  => "win"
            case _        => "linux"

end PathPlatformSpecific
