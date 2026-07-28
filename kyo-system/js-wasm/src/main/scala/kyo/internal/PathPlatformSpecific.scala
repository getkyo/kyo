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
    val constants: NodeFsConstants                                                              = js.native
    def existsSync(path: String): Boolean                                                       = js.native
    def realpathSync(path: String): String                                                      = js.native
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
    def linkSync(existingPath: String, newPath: String): Unit                                   = js.native
    def copyFileSync(src: String, dest: String, flags: Int): Unit                               = js.native
    def unlinkSync(path: String): Unit                                                          = js.native
    def rmSync(path: String, options: js.Dynamic): Unit                                         = js.native
    def rmdirSync(path: String): Unit                                                           = js.native
    def truncateSync(path: String, len: Double): Unit                                           = js.native
    def openSync(path: String, flags: String): Int                                              = js.native
    def openSync(path: String, flags: Int): Int                                                 = js.native
    def readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int  = js.native
    def writeSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int = js.native
    def writeSync(fd: Int, data: String, position: Double, encoding: String): Int               = js.native
    def closeSync(fd: Int): Unit                                                                = js.native
    def fsyncSync(fd: Int): Unit                                                                = js.native
    def fdatasyncSync(fd: Int): Unit                                                            = js.native
    def fstatSync(fd: Int): NodeStats                                                           = js.native
    def ftruncateSync(fd: Int, len: Double): Unit                                               = js.native
    def symlinkSync(target: String, path: String): Unit                                         = js.native
    def chmodSync(path: String, mode: Int): Unit                                                = js.native
    def readlinkSync(path: String): String                                                      = js.native
    def mkdtempSync(prefix: String): String                                                     = js.native
    def writeFileSync(path: String, data: String): Unit                                         = js.native
    def utimesSync(path: String, atime: Double, mtime: Double): Unit                            = js.native
    def lutimesSync(path: String, atime: Double, mtime: Double): Unit                           = js.native
end NodeFs

@js.native
private[kyo] trait NodeFsConstants extends js.Object:
    val O_WRONLY: Int = js.native
    val O_RDWR: Int   = js.native
    val O_CREAT: Int  = js.native
    val O_EXCL: Int   = js.native
end NodeFsConstants

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
    def basename(path: String): String    = js.native
    def dirname(path: String): String     = js.native
    def sep: String                       = js.native
    def delimiter: String                 = js.native
end NodePath

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object NodeOs extends js.Object:
    def tmpdir(): String   = js.native
    def homedir(): String  = js.native
    def hostname(): String = js.native
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

    def translateSync(path: Path, e: js.JavaScriptException)(using Frame): FileWriteException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "EISDIR"           => FileIsADirectoryException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Sync)
            case _                  => FileIOException(path, FileSystemOperation.Sync, e)

    def translateFs(path: Path, operation: FileSystemOperation, e: js.JavaScriptException)(using Frame): FileStructureException =
        codeOf(e) match
            case "ENOENT"           => FileNotFoundException(path)
            case "EACCES" | "EPERM" => FileAccessDeniedException(path)
            case "ENOTDIR"          => FileNotADirectoryException(path)
            case "EEXIST"           => FileAlreadyExistsException(path)
            case "ENOTEMPTY"        => FileDirectoryNotEmptyException(path)
            case "EINVAL"           => FileInvalidPathException(path.toString, operation)
            case _                  => FileIOException(path, operation, e)

    /** Distinguishes the O_EXCL lockfile contention code (`EEXIST`) from every other filesystem
      * error `Path.Unsafe.lock` can raise.
      */
    def translateLock(path: Path, e: js.JavaScriptException)(using Frame): FileLockException =
        codeOf(e) match
            case "EEXIST" => FileLockUnavailableException(path)
            case "EINVAL" => FileInvalidPathException(path.toString, FileSystemOperation.Lock)
            case _        => FileIOException(path, FileSystemOperation.Lock, e)

end NodeError

// --- Node advisory lock protocol ---

private[kyo] object NodePathLock:

    final private[kyo] case class Owner(host: String, pid: Int, token: String) derives CanEqual:
        def render: String = s"$host\n$pid\n$token"

    private object Owner:
        def parse(value: String): Maybe[Owner] =
            value.split("\n", -1).toSeq match
                case Seq(host, pid, token) if host.nonEmpty && token.nonEmpty =>
                    pid.toIntOption.fold[Maybe[Owner]](Absent)(value => Present(Owner(host, value, token)))
                case _ => Absent
    end Owner

    private def currentOwner(): Owner =
        val bytes = NodeCrypto.randomBytes(16)
        val token = bytes.applyDynamic("toString")("hex").asInstanceOf[String]
        Owner(
            NodeOs.hostname(),
            js.Dynamic.global.process.selectDynamic("pid").asInstanceOf[Int],
            token
        )
    end currentOwner

    private def exists(path: String): Boolean = NodeFs.existsSync(path)

    private def ownerAt(path: String): Maybe[Owner] =
        try Owner.parse(NodeFs.readFileSync(path, "utf8"))
        catch case _: js.JavaScriptException => Absent

    private def processIsDead(owner: Owner): Boolean =
        if owner.host != NodeOs.hostname() then false
        else
            try
                discard(js.Dynamic.global.process.applyDynamic("kill")(owner.pid, 0))
                false
            catch
                case e: js.JavaScriptException => NodeError.codeOf(e) == "ESRCH"

    private def encodeHost(host: String): String =
        host.toCharArray.iterator.map { char =>
            val hex = Integer.toHexString(char.toInt)
            "0" * (4 - hex.length) + hex
        }.mkString

    private def decodeHost(encoded: String): Maybe[String] =
        if encoded.isEmpty || encoded.length % 4 != 0 then Absent
        else
            try Present(encoded.grouped(4).map(value => Integer.parseInt(value, 16).toChar).mkString)
            catch case _: NumberFormatException => Absent

    private[kyo] def publicationPath(path: String, host: String, pid: Int, token: String): String =
        path + ".publish." + encodeHost(host) + "." + pid + "." + token

    private def publicationPath(path: String, owner: Owner): String =
        publicationPath(path, owner.host, owner.pid, owner.token)

    private def publicationOwner(path: String): Maybe[Owner] =
        val marker = ".publish."
        val index  = path.lastIndexOf(marker)
        if index < 0 then Absent
        else
            path.substring(index + marker.length).split("\\.", -1).toSeq match
                case Seq(host, pid, token) if token.nonEmpty =>
                    decodeHost(host).flatMap(decoded =>
                        pid.toIntOption.filter(_ > 0).fold[Maybe[Owner]](Absent)(value => Present(Owner(decoded, value, token)))
                    )
                case _ => Absent
        end if
    end publicationOwner

    private[kyo] def reclaimIfProvenDead(path: String, beforeMove: () => Unit = () => ()): Boolean =
        ownerAt(path) match
            case Present(expected) if processIsDead(expected) =>
                val quarantine = path + ".reclaim." + currentOwner().token
                try
                    beforeMove()
                    NodeFs.renameSync(path, quarantine)
                    ownerAt(quarantine) match
                        case Present(actual) if actual == expected && processIsDead(actual) =>
                            NodeFs.unlinkSync(quarantine)
                            true
                        case _ => false
                    end match
                catch case _: js.JavaScriptException => false
                end try
            case _ => false

    private def withCleanup[A](
        target: Path,
        primary: Result[FileLockException, A],
        cleanup: Result[FileLockException, Unit]
    )(using Frame): Result[FileLockException, A] =
        cleanup match
            case Result.Success(_) => primary
            case Result.Failure(cleanup) =>
                primary match
                    case Result.Success(_)       => Result.fail(cleanup)
                    case Result.Failure(primary) => Result.fail(FileLockCleanupException(target, primary, cleanup))
                    case Result.Panic(primary) =>
                        primary.addSuppressed(cleanup)
                        Result.panic(primary)
            case Result.Panic(cleanup) =>
                primary match
                    case Result.Success(_) => Result.panic(cleanup)
                    case Result.Failure(primary) =>
                        cleanup.addSuppressed(primary)
                        Result.panic(cleanup)
                    case Result.Panic(primary) =>
                        primary.addSuppressed(cleanup)
                        Result.panic(primary)
    end withCleanup

    private def publications(path: String): Seq[String] =
        val parent = NodePath.dirname(path)
        val prefix = NodePath.basename(path) + ".publish."
        NodeFs.readdirSync(parent).toSeq
            .filter(_.startsWith(prefix))
            .map(NodePath.join(parent, _))
    end publications

    private def reclaimPublicationIfProvenDead(path: String): Boolean =
        publicationOwner(path) match
            case Present(owner) if processIsDead(owner) =>
                try
                    NodeFs.unlinkSync(path)
                    true
                catch case _: js.JavaScriptException => false
            case _ => false

    private def publicationBlocked(path: String): Boolean =
        publications(path).foreach(reclaimPublicationIfProvenDead)
        publications(path).exists(exists)

    private def claimPublications(base: String): Seq[String] =
        val parent = NodePath.dirname(base)
        val prefix = NodePath.basename(base) + "."
        NodeFs.readdirSync(parent).toSeq
            .filter(name => name.startsWith(prefix) && name.contains(".publish."))
            .map(NodePath.join(parent, _))
    end claimPublications

    private def create(
        target: Path,
        path: String,
        owner: Owner,
        beforeCleanup: String => Unit
    )(using Frame): Result[FileLockException, Boolean] =
        val temporary      = publicationPath(path, owner)
        var temporaryOwned = false
        val published: Result[FileLockException, Boolean] =
            try
                if publicationBlocked(path) then Result.succeed(false)
                else
                    NodeFs.writeFileSync(temporary, owner.render, js.Dynamic.literal(flag = "wx"))
                    temporaryOwned = true
                    NodeFs.linkSync(temporary, path)
                    Result.succeed(true)
            catch
                case e: js.JavaScriptException if NodeError.codeOf(e) == "EEXIST" => Result.succeed(false)
                case e: js.JavaScriptException                                    => Result.fail(NodeError.translateLock(target, e))
                case e: Throwable                                                 => Result.panic(e)
        val cleanup =
            if !temporaryOwned then Result.unit
            else
                try
                    beforeCleanup(temporary)
                    NodeFs.unlinkSync(temporary)
                    Result.unit
                catch
                    case e: js.JavaScriptException if NodeError.codeOf(e) == "ENOENT" => Result.unit
                    case e: js.JavaScriptException                                    => Result.fail(NodeError.translateLock(target, e))
                    case e: Throwable                                                 => Result.panic(e)
        cleanup match
            case Result.Success(_) => published
            case cleanupFailure =>
                published match
                    case Result.Success(true) =>
                        withCleanup(target, cleanupFailure, releaseOwned(target, path, owner)) match
                            case Result.Success(_)     => Result.fail(FileLockOwnershipLostException(target))
                            case Result.Failure(error) => Result.fail(error)
                            case Result.Panic(error)   => Result.panic(error)
                    case _ => withCleanup(target, published, cleanup)
        end match
    end create

    private def acquireGate(target: Path, gate: String, owner: Owner, beforeCleanup: String => Unit)(using
        Frame
    ): Result[FileLockException, Boolean] =
        try
            val parent = NodePath.dirname(gate)
            val name   = NodePath.basename(gate)
            def gates = NodeFs.readdirSync(parent).toSeq
                .filter(value => value == name || value.startsWith(name + ".reclaim."))
                .map(NodePath.join(parent, _))
            gates.foreach(reclaimIfProvenDead(_))
            if gates.exists(exists) then Result.succeed(false) else create(target, gate, owner, beforeCleanup)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateLock(target, e))
            case e: Throwable              => Result.panic(e)
    end acquireGate

    private def releaseOwned(target: Path, path: String, owner: Owner)(using Frame): Result[FileLockException, Unit] =
        try
            Owner.parse(NodeFs.readFileSync(path, "utf8")) match
                case Present(found) if found == owner =>
                    NodeFs.unlinkSync(path)
                    Result.unit
                case _ => Result.fail(FileLockOwnershipLostException(target))
        catch
            case e: js.JavaScriptException if NodeError.codeOf(e) == "ENOENT" =>
                Result.fail(FileLockOwnershipLostException(target))
            case e: js.JavaScriptException => Result.fail(NodeError.translateLock(target, e))
            case e: Throwable              => Result.panic(e)
        end try
    end releaseOwned

    private def conflictingClaims(base: String, mode: Path.LockMode): Seq[String] =
        val parent          = NodePath.dirname(base)
        val name            = NodePath.basename(base)
        val exclusiveName   = name + ".exclusive"
        val sharedNameStart = name + ".shared."
        val names           = NodeFs.readdirSync(parent).toSeq
        val exclusiveClaims = names.filter(value =>
            value == exclusiveName ||
                value.startsWith(exclusiveName + ".reclaim.") ||
                value.startsWith(exclusiveName + ".publish.")
        )
            .map(NodePath.join(parent, _))
        val shared =
            if mode == Path.LockMode.Shared then Seq.empty
            else
                names.filter(value => value.startsWith(sharedNameStart))
                    .map(NodePath.join(parent, _))
        exclusiveClaims ++ shared
    end conflictingClaims

    def acquire(
        target: Path,
        pathStr: String,
        mode: Path.LockMode,
        beforeGateRelease: (String, String) => Unit = (_, _) => (),
        beforePublishCleanup: String => Unit = _ => ()
    )(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock] =
        val base                                 = pathStr + ".kyo-lock"
        val gate                                 = base + ".gate"
        val gateOwner                            = currentOwner()
        var gateAcquired                         = false
        var createdClaim: Maybe[(String, Owner)] = Absent
        val acquired: Result[FileLockException, Path.RawLock] =
            try
                acquireGate(target, gate, gateOwner, beforePublishCleanup) match
                    case Result.Failure(error) => Result.fail(error)
                    case Result.Panic(error)   => Result.panic(error)
                    case Result.Success(false) => Result.fail(FileLockUnavailableException(target))
                    case Result.Success(true) =>
                        gateAcquired = true
                        claimPublications(base).foreach(reclaimPublicationIfProvenDead)
                        val result =
                            if claimPublications(base).exists(exists) then
                                Result.fail(FileLockUnavailableException(target))
                            else
                                val conflicts = conflictingClaims(base, mode).filter(exists)
                                conflicts.foreach(reclaimIfProvenDead(_))
                                if conflictingClaims(base, mode).exists(exists) then
                                    Result.fail(FileLockUnavailableException(target))
                                else
                                    val owner = currentOwner()
                                    val claim = mode match
                                        case Path.LockMode.Exclusive => base + ".exclusive"
                                        case Path.LockMode.Shared    => base + ".shared." + owner.token
                                    create(target, claim, owner, beforePublishCleanup) match
                                        case Result.Success(true) =>
                                            createdClaim = Present((claim, owner))
                                            Result.succeed(new NodeRawLock(target, claim, owner, mode))
                                        case Result.Success(false) => Result.fail(FileLockUnavailableException(target))
                                        case Result.Failure(error) => Result.fail(error)
                                        case Result.Panic(error)   => Result.panic(error)
                                    end match
                                end if
                        end result
                        beforeGateRelease(gate, createdClaim.fold("")(_._1))
                        result
                end match
            catch
                case e: js.JavaScriptException => Result.fail(NodeError.translateLock(target, e))
                case e: Throwable              => Result.panic(e)
            end try
        end acquired
        if !gateAcquired then acquired
        else
            releaseOwned(target, gate, gateOwner) match
                case Result.Success(_) => acquired
                case Result.Failure(error) =>
                    val gateFailure: Result[FileLockException, Path.RawLock] = Result.fail(error)
                    createdClaim match
                        case Present((claim, owner)) => withCleanup(target, gateFailure, releaseOwned(target, claim, owner))
                        case Absent                  => gateFailure
                case Result.Panic(error) =>
                    val gateFailure: Result[FileLockException, Path.RawLock] = Result.panic(error)
                    createdClaim match
                        case Present((claim, owner)) => withCleanup(target, gateFailure, releaseOwned(target, claim, owner))
                        case Absent                  => gateFailure
            end match
        end if
    end acquire

    private[kyo] def owns(claim: String, owner: Owner): Boolean = ownerAt(claim).exists(_ == owner)

    private[kyo] def release(target: Path, claim: String, owner: Owner)(using Frame): Result[FileLockException, Unit] =
        releaseOwned(target, claim, owner)

end NodePathLock

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

    override private[kyo] def syncDirectory()(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val directory = if pathStr.isEmpty then "." else pathStr
            val fd        = NodeFs.openSync(directory, "r")
            try NodeFs.fsyncSync(fd)
            finally NodeFs.closeSync(fd)
            Result.unit
        catch
            case e: js.JavaScriptException =>
                Result.fail(FileIOException(safe, FileSystemOperation.SyncDirectory, new RuntimeException(e.getMessage())))
            case e: Throwable => Result.panic(e)

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

    def stat()(using AllowUnsafe, Frame): Result[FileReadException, kyo.Path.PathStat] =
        catchRead {
            val s = NodeFs.statSync(pathStr)
            kyo.Path.PathStat(s.mtimeMs.toLong, s.size.toLong)
        }

    private[kyo] def stableIdentity()(using AllowUnsafe, Frame): Result[FileReadException, Maybe[String]] =
        catchRead {
            val s = NodeFs.statSync(pathStr)
            Present(s"${s.dev.toString}:${s.ino.toString}")
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

    // --- Positioned channel ---

    // Numeric non-append flags preserve explicit readSync/writeSync positions. Combining O_CREAT
    // with the requested access mode creates atomically without truncating existing content.
    private def openRawChannel(mode: Path.RawChannelAccess): Path.RawChannel =
        val constants = NodeFs.constants
        mode match
            case Path.RawChannelAccess.Read =>
                new NodeRawChannel(NodeFs.openSync(pathStr, "r"), safe)
            case Path.RawChannelAccess.Write(open) =>
                if open != FileSystem.WriteOpen.Existing then ensureParent()
                val flags = open match
                    case FileSystem.WriteOpen.Existing  => constants.O_WRONLY
                    case FileSystem.WriteOpen.Create    => constants.O_WRONLY | constants.O_CREAT
                    case FileSystem.WriteOpen.CreateNew => constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL
                new NodeRawChannel(NodeFs.openSync(pathStr, flags), safe)
            case Path.RawChannelAccess.ReadWrite(open) =>
                if open != FileSystem.WriteOpen.Existing then ensureParent()
                val flags = open match
                    case FileSystem.WriteOpen.Existing  => constants.O_RDWR
                    case FileSystem.WriteOpen.Create    => constants.O_RDWR | constants.O_CREAT
                    case FileSystem.WriteOpen.CreateNew => constants.O_RDWR | constants.O_CREAT | constants.O_EXCL
                new NodeRawChannel(NodeFs.openSync(pathStr, flags), safe)
        end match
    end openRawChannel

    def openReadChannelRaw()(using AllowUnsafe, Frame): Result[FileReadException, Path.RawChannel] =
        catchRead(openRawChannel(Path.RawChannelAccess.Read))
    def openWriteChannelRaw(open: FileSystem.WriteOpen)(using
        AllowUnsafe,
        Frame
    ): Result[FileWriteException | FileStructureException, Path.RawChannel] =
        catchChannelWrite(openRawChannel(Path.RawChannelAccess.Write(open)))
    def openReadWriteChannelRaw(open: FileSystem.WriteOpen)(using
        AllowUnsafe,
        Frame
    ): Result[FileReadException | FileWriteException | FileStructureException, Path.RawChannel] =
        catchChannelWrite(openRawChannel(Path.RawChannelAccess.ReadWrite(open)))

    // --- Advisory lock ---

    // Node has no OS advisory lock primitive. NodePathLock uses owner-tagged O_EXCL control files
    // for portable shared and exclusive claims, and only reclaims claims proven to belong to a dead
    // process on the local host.
    def lock(mode: Path.LockMode)(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock] =
        NodePathLock.acquire(safe, pathStr, mode)

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

    private def catchChannelWrite[A](expr: => A)(using Frame): Result[FileWriteException | FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException
                if !js.isUndefined(e.exception.asInstanceOf[js.Dynamic].code) &&
                    e.exception.asInstanceOf[js.Dynamic].code.asInstanceOf[String] == "EEXIST" =>
                Result.fail(FileAlreadyExistsException(safe))
            case e: js.JavaScriptException => Result.fail(NodeError.translateWrite(safe, e))
            case e: Throwable              => Result.panic(e)

    private def catchFs[A](operation: FileSystemOperation)(expr: => A)(using Frame): Result[FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateFs(safe, operation, e))
            case e: Throwable              => Result.panic(e)

end NodePathUnsafe

// --- NodeReadHandle ---

final private[kyo] class NodeReadHandle(fd: Int) extends Path.ReadHandle:

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
    private var finished  = false

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

// --- NodeRawChannel ---

/** Concrete positioned raw channel backed by a Node.js file descriptor, using `readSync`/
  * `writeSync`'s explicit `position` argument so no call moves the fd's own read/write cursor.
  */
final private[kyo] class NodeRawChannel(fd: Int, path: Path) extends Path.RawChannel:

    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

    def readAt(pos: Long, len: Int)(using AllowUnsafe, Frame): Result[FileReadException, Array[Byte]] =
        try
            val uint8 = new Uint8Array(len)
            var total = 0
            var eof   = false
            while total < len && !eof do
                val n = NodeFs.readSync(fd, uint8, total, len - total, (pos + total).toDouble)
                if n == 0 then eof = true else total += n
            val out = new Array[Byte](total)
            var i   = 0
            while i < total do
                out(i) = uint8(i).toByte
                i += 1
            Result.succeed(out)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable              => Result.panic(e)

    def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val uint8   = bytesToUint8Array(bytes)
            var written = 0
            while written < bytes.length do
                written += NodeFs.writeSync(fd, uint8, written, bytes.length - written, (pos + written).toDouble)
            Result.unit
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable              => Result.panic(e)

    def sync(metadata: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            if metadata then NodeFs.fsyncSync(fd) else NodeFs.fdatasyncSync(fd)
            Result.unit
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateSync(path, e))
            case e: Throwable              => Result.panic(e)

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            NodeFs.ftruncateSync(fd, size.toDouble)
            Result.unit
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable              => Result.panic(e)

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        try Result.succeed(NodeFs.fstatSync(fd).size.toLong)
        catch
            case e: js.JavaScriptException => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable              => Result.panic(e)

    def close()(using AllowUnsafe): Unit =
        if closed.compareAndSet(false, true) then NodeFs.closeSync(fd)

end NodeRawChannel

// --- NodeRawLock ---

/** Concrete advisory lock backed by an owner-tagged O_EXCL control file. Shared handles use
  * independent claims, while exclusive handles use the path's single exclusive claim.
  */
final private[kyo] class NodeRawLock(
    path: Path,
    claim: String,
    owner: NodePathLock.Owner,
    mode: Path.LockMode
) extends Path.RawLock:
    def isExclusive: Boolean = mode == Path.LockMode.Exclusive

    def check()(using AllowUnsafe, Frame): Result[FileLockException, Unit] =
        if NodePathLock.owns(claim, owner) then Result.unit
        else Result.fail(FileLockOwnershipLostException(path))

    // Unsafe: removes only this handle's owner-tagged claim. Missing and mismatched claims report
    // ownership loss; filesystem failures retain their typed lock error.
    def release()(using AllowUnsafe, Frame): Result[FileLockException, Unit] =
        NodePathLock.release(path, claim, owner)
end NodeRawLock

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
    override def tempScoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Scope & Abort[FileStructureException]) =
        super.tempScoped(prefix, suffix)

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
