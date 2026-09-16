package kyo.internal

import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

// --- Node.js facades, reached through NodeModules ---

@js.native
private[kyo] trait NodeFsApi extends js.Object:
    val constants: NodeFsConstants                                                             = js.native
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
    def linkSync(existingPath: String, newPath: String): Unit                                  = js.native
    def copyFileSync(src: String, dest: String, flags: Int): Unit                              = js.native
    def unlinkSync(path: String): Unit                                                         = js.native
    def rmSync(path: String, options: js.Dynamic): Unit                                        = js.native
    def rmdirSync(path: String): Unit                                                          = js.native
    def truncateSync(path: String, len: Double): Unit                                          = js.native
    def openSync(path: String, flags: String): Int                                             = js.native
    def openSync(path: String, flags: Int): Int                                                = js.native
    def readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int = js.native
    // Declared without a trailing position argument, which is the whole point: Node then writes at the
    // file description's own cursor, the only offset `O_APPEND` acts on. `NodeWriteHandle` must call this
    // overload, since a positioned write on a descriptor opened to append is silent data loss.
    def writeSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int): Int = js.native
    // The positioned overloads exist for `NodeRawChannel` alone, whose whole contract is that a call
    // never moves the descriptor's own cursor. No append path may reach them.
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
end NodeFsApi

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
    def birthtimeMs: Double       = js.native
    def dev: Double               = js.native
    def ino: Double               = js.native
end NodeStats

@js.native
private[kyo] trait NodePathApi extends js.Object:
    def join(paths: String*): String   = js.native
    def basename(path: String): String = js.native
    def dirname(path: String): String  = js.native
    def sep: String                    = js.native
end NodePathApi

@js.native
private[kyo] trait NodeOsApi extends js.Object:
    def tmpdir(): String   = js.native
    def homedir(): String  = js.native
    def hostname(): String = js.native
end NodeOsApi

// --- Exception translation helpers ---

private[kyo] object NodeError:

    /** Extracts the Node.js error code from a js.JavaScriptException */
    private[kyo] def codeOf(e: js.JavaScriptException): String =
        val err = e.exception.asInstanceOf[js.Dynamic]
        val c   = err.code
        if js.isUndefined(c) then "UNKNOWN" else c.asInstanceOf[String]
    end codeOf

    /** What a Node-backed file-system call can fail with: the host's own error, or the host not having the module. */
    type NodeFailure = js.JavaScriptException | NodeModuleUnavailable

    /** The named failure for an operation on a host with no file system. */
    def unsupported(operation: FileSystemOperation, e: NodeModuleUnavailable)(using Frame): FileSystemUnsupportedOnHostException =
        FileSystemUnsupportedOnHostException(operation, e.host)

    def isMissing(e: js.JavaScriptException): Boolean =
        val code = codeOf(e)
        code == "ENOENT" || code == "ENOTDIR"

    def translateRead(path: Path, e: NodeFailure)(using Frame): FileReadException = e match
        case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Read, u)
        case e: js.JavaScriptException => codeOf(e) match
                case "ENOENT"           => FileNotFoundException(path)
                case "EACCES" | "EPERM" => FileAccessDeniedException(path)
                case "EISDIR"           => FileIsADirectoryException(path)
                case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Read)
                case _                  => FileIOException(path, FileSystemOperation.Read, e)

    def translateExists(path: Path, e: NodeFailure)(using
        Frame
    )
        : FileInvalidPathException | FileAccessDeniedException | FileIOException | FileSystemUnsupportedOnHostException = e match
        case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Exists, u)
        case e: js.JavaScriptException => codeOf(e) match
                case "EACCES" | "EPERM" => FileAccessDeniedException(path)
                case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Exists)
                case _                  => FileIOException(path, FileSystemOperation.Exists, e)

    def translateMove(source: Path, target: Path, atomicity: Path.Atomicity, e: NodeFailure)(using
        Frame
    ): FileStructureException =
        e match
            case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Move, u)
            case e: js.JavaScriptException =>
                if atomicity == Path.Atomicity.Required && codeOf(e) == "EXDEV" then FileAtomicMoveUnsupportedException(source, target)
                else translateFs(source, FileSystemOperation.Move, e)

    def translateWrite(path: Path, e: NodeFailure)(using Frame): FileWriteException = e match
        case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Write, u)
        case e: js.JavaScriptException => codeOf(e) match
                case "ENOENT"           => FileNotFoundException(path)
                case "EACCES" | "EPERM" => FileAccessDeniedException(path)
                case "EISDIR"           => FileIsADirectoryException(path)
                case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Write)
                case _                  => FileIOException(path, FileSystemOperation.Write, e)

    def translateSync(path: Path, e: NodeFailure)(using Frame): FileWriteException = e match
        case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Sync, u)
        case e: js.JavaScriptException => codeOf(e) match
                case "ENOENT"           => FileNotFoundException(path)
                case "EACCES" | "EPERM" => FileAccessDeniedException(path)
                case "EISDIR"           => FileIsADirectoryException(path)
                case "EINVAL"           => FileInvalidPathException(path.toString, FileSystemOperation.Sync)
                case _                  => FileIOException(path, FileSystemOperation.Sync, e)

    def translateFs(path: Path, operation: FileSystemOperation, e: NodeFailure)(using Frame): FileStructureException = e match
        case u: NodeModuleUnavailable => unsupported(operation, u)
        case e: js.JavaScriptException => codeOf(e) match
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
    def translateLock(path: Path, e: NodeFailure)(using Frame): FileLockException = e match
        case u: NodeModuleUnavailable => unsupported(FileSystemOperation.Lock, u)
        case e: js.JavaScriptException => codeOf(e) match
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
        val token = randomHex(16)
        Owner(
            NodeModules.os.hostname(),
            NodeProcess.require("Path.lock").pid.asInstanceOf[Int],
            token
        )
    end currentOwner

    private def exists(path: String): Boolean = NodeModules.fs.existsSync(path)

    private def ownerAt(path: String): Maybe[Owner] =
        try Owner.parse(NodeModules.fs.readFileSync(path, "utf8"))
        catch case _: js.JavaScriptException => Absent

    private def processIsDead(owner: Owner): Boolean =
        if owner.host != NodeModules.os.hostname() then false
        else
            try
                discard(NodeProcess.require("Checking whether a lock's owner is alive").applyDynamic("kill")(owner.pid, 0))
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
                    NodeModules.fs.renameSync(path, quarantine)
                    ownerAt(quarantine) match
                        case Present(actual) if actual == expected && processIsDead(actual) =>
                            NodeModules.fs.unlinkSync(quarantine)
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
        val parent = NodeModules.path.dirname(path)
        val prefix = NodeModules.path.basename(path) + ".publish."
        NodeModules.fs.readdirSync(parent).toSeq
            .filter(_.startsWith(prefix))
            .map(NodeModules.path.join(parent, _))
    end publications

    private def reclaimPublicationIfProvenDead(path: String): Boolean =
        publicationOwner(path) match
            case Present(owner) if processIsDead(owner) =>
                try
                    NodeModules.fs.unlinkSync(path)
                    true
                catch case _: js.JavaScriptException => false
            case _ => false

    private def publicationBlocked(path: String): Boolean =
        publications(path).foreach(reclaimPublicationIfProvenDead)
        publications(path).exists(exists)

    private def claimPublications(base: String): Seq[String] =
        val parent = NodeModules.path.dirname(base)
        val prefix = NodeModules.path.basename(base) + "."
        NodeModules.fs.readdirSync(parent).toSeq
            .filter(name => name.startsWith(prefix) && name.contains(".publish."))
            .map(NodeModules.path.join(parent, _))
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
                    NodeModules.fs.writeFileSync(temporary, owner.render, js.Dynamic.literal(flag = "wx"))
                    temporaryOwned = true
                    NodeModules.fs.linkSync(temporary, path)
                    Result.succeed(true)
            catch
                case e: js.JavaScriptException if NodeError.codeOf(e) == "EEXIST" => Result.succeed(false)
                case e: NodeError.NodeFailure                                     => Result.fail(NodeError.translateLock(target, e))
                case e: Throwable                                                 => Result.panic(e)
        val cleanup =
            if !temporaryOwned then Result.unit
            else
                try
                    beforeCleanup(temporary)
                    NodeModules.fs.unlinkSync(temporary)
                    Result.unit
                catch
                    case e: js.JavaScriptException if NodeError.codeOf(e) == "ENOENT" => Result.unit
                    case e: NodeError.NodeFailure                                     => Result.fail(NodeError.translateLock(target, e))
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
            val parent = NodeModules.path.dirname(gate)
            val name   = NodeModules.path.basename(gate)
            def gates = NodeModules.fs.readdirSync(parent).toSeq
                .filter(value => value == name || value.startsWith(name + ".reclaim."))
                .map(NodeModules.path.join(parent, _))
            gates.foreach(reclaimIfProvenDead(_))
            if gates.exists(exists) then Result.succeed(false) else create(target, gate, owner, beforeCleanup)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateLock(target, e))
            case e: Throwable             => Result.panic(e)
    end acquireGate

    private def releaseOwned(target: Path, path: String, owner: Owner)(using Frame): Result[FileLockException, Unit] =
        try
            Owner.parse(NodeModules.fs.readFileSync(path, "utf8")) match
                case Present(found) if found == owner =>
                    NodeModules.fs.unlinkSync(path)
                    Result.unit
                case _ => Result.fail(FileLockOwnershipLostException(target))
        catch
            case e: js.JavaScriptException if NodeError.codeOf(e) == "ENOENT" =>
                Result.fail(FileLockOwnershipLostException(target))
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateLock(target, e))
            case e: Throwable             => Result.panic(e)
        end try
    end releaseOwned

    private def conflictingClaims(base: String, mode: Path.LockMode): Seq[String] =
        val parent          = NodeModules.path.dirname(base)
        val name            = NodeModules.path.basename(base)
        val exclusiveName   = name + ".exclusive"
        val sharedNameStart = name + ".shared."
        val names           = NodeModules.fs.readdirSync(parent).toSeq
        val exclusiveClaims = names.filter(value =>
            value == exclusiveName ||
                value.startsWith(exclusiveName + ".reclaim.") ||
                value.startsWith(exclusiveName + ".publish.")
        )
            .map(NodeModules.path.join(parent, _))
        val shared =
            if mode == Path.LockMode.Shared then Seq.empty
            else
                names.filter(value => value.startsWith(sharedNameStart))
                    .map(NodeModules.path.join(parent, _))
        exclusiveClaims ++ shared
    end conflictingClaims

    def acquire(
        target: Path,
        pathStr: String,
        mode: Path.LockMode,
        sentinelSuffix: String = Path.defaultLockSuffix,
        beforeGateRelease: (String, String) => Unit = (_, _) => (),
        beforePublishCleanup: String => Unit = _ => ()
    )(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock] =
        // Every claim records this process's pid, so a host without `process` cannot take one. A failure rather than
        // a panic: `lock` declares FileLockException, and a host with no file system belongs on that channel.
        if !Platform.isNodeLike then Result.fail(FileSystemUnsupportedOnHostException(FileSystemOperation.Lock, Platform.host.toString))
        // Checked here because the owner record is built before the protocol's own failure handling begins.
        else if !NodeModules.isAvailable("node:fs") then
            Result.fail(FileSystemUnsupportedOnHostException(FileSystemOperation.Lock, Platform.host.toString))
        else acquireOnNode(target, pathStr, mode, sentinelSuffix, beforeGateRelease, beforePublishCleanup)

    private def acquireOnNode(
        target: Path,
        pathStr: String,
        mode: Path.LockMode,
        sentinelSuffix: String,
        beforeGateRelease: (String, String) => Unit,
        beforePublishCleanup: String => Unit
    )(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock] =
        val base                                 = pathStr + sentinelSuffix
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
                case e: NodeError.NodeFailure => Result.fail(NodeError.translateLock(target, e))
                case e: Throwable             => Result.panic(e)
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
    end acquireOnNode

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
        else if PathSyntaxJs.isAbsolute(pathStr) then
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
    def isAbsolute: Boolean = PathSyntaxJs.isAbsolute(pathStr)

    override def equals(other: Any): Boolean = other match
        case that: NodePathUnsafe => this.pathStr == that.pathStr
        case _                    => false

    override def hashCode(): Int = pathStr.hashCode

    // --- Inspection ---

    def exists()(using
        AllowUnsafe,
        Frame
    ): Result[FileInvalidPathException | FileAccessDeniedException | FileIOException | FileSystemUnsupportedOnHostException, Boolean] =
        exists(followLinks = true)

    def exists(followLinks: Boolean)(using
        AllowUnsafe,
        Frame
    )
        : Result[FileInvalidPathException | FileAccessDeniedException | FileIOException | FileSystemUnsupportedOnHostException, Boolean] =
        try
            if followLinks then
                discard(NodeModules.fs.statSync(pathStr))
            else
                discard(NodeModules.fs.lstatSync(pathStr))
            end if
            Result.succeed(true)
        catch
            case e: js.JavaScriptException if NodeError.isMissing(e) => Result.succeed(false)
            case e: NodeError.NodeFailure                            => Result.fail(NodeError.translateExists(safe, e))
            case e: Throwable                                        => Result.panic(e)

    def isDirectory()(using AllowUnsafe): Boolean =
        try NodeModules.fs.statSync(pathStr).isDirectory()
        catch case _: js.JavaScriptException => false

    def isRegularFile()(using AllowUnsafe): Boolean =
        try NodeModules.fs.statSync(pathStr).isFile()
        catch case _: js.JavaScriptException => false

    def isSymbolicLink()(using AllowUnsafe): Boolean =
        try NodeModules.fs.lstatSync(pathStr).isSymbolicLink()
        catch case _: js.JavaScriptException => false

    def realPath()(using
        AllowUnsafe,
        Frame
    )
        : Result[
            FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException |
                FileSystemUnsupportedOnHostException,
            Path
        ] =
        try Result.succeed(Path(NodeModules.fs.realpathSync(pathStr)))
        catch
            case e: NodeError.NodeFailure =>
                val failure: FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException |
                    FileSystemUnsupportedOnHostException =
                    NodeError.translateRead(safe, e) match
                        case value: FileSystemUnsupportedOnHostException => value
                        case value: FileNotFoundException                => value
                        case value: FileAccessDeniedException            => value
                        case value: FileInvalidPathException             => value
                        case value: FileIOException                      => value
                        case _                                           => FileIOException(safe, FileSystemOperation.RealPath, e)
                Result.fail(failure)
            case e: Throwable => Result.panic(e)

    // --- Read ---

    def read()(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead {
            NodeModules.fs.readFileSync(pathStr, "utf8")
        }

    def read(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, String] =
        catchRead {
            val bytes = NodeModules.fs.readFileSync(pathStr)
            new String(uint8ArrayToBytes(bytes), charset)
        }

    def readBytes()(using AllowUnsafe, Frame): Result[FileReadException, Span[Byte]] =
        catchRead {
            val arr = uint8ArrayToBytes(NodeModules.fs.readFileSync(pathStr))
            Span.from(arr)
        }

    def readLines()(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead {
            val content = NodeModules.fs.readFileSync(pathStr, "utf8")
            Chunk.from(splitLines(content))
        }

    def readLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]] =
        catchRead {
            val bytes   = NodeModules.fs.readFileSync(pathStr)
            val content = new String(uint8ArrayToBytes(bytes), charset)
            Chunk.from(splitLines(content))
        }

    // --- Streaming read handles ---

    def openRead()(using AllowUnsafe, Frame): Result[FileReadException, Path.ReadHandle] =
        catchRead {
            val fd = NodeModules.fs.openSync(pathStr, "r")
            new NodeReadHandle(fd, safe)
        }

    def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle] =
        catchRead {
            val bytes   = NodeModules.fs.readFileSync(pathStr)
            val content = new String(uint8ArrayToBytes(bytes), charset)
            val lines   = splitLines(content).toArray
            new NodeLineReadHandle(lines, 0)
        }

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        catchRead {
            NodeModules.fs.statSync(pathStr).size.toLong
        }

    def stat()(using AllowUnsafe, Frame): Result[FileReadException, kyo.Path.PathStat] =
        catchRead {
            val s = NodeModules.fs.statSync(pathStr)
            // Rounded, not truncated. Node converts a modification time to a double count of
            // seconds inside libuv before the syscall, so a value the caller set as 987654 ms is
            // stored as 987653999000 ns and read back as 987653.999. Truncating reports 987653, a
            // millisecond before both the instant the filesystem holds and the one that was asked
            // for. Rounding reports the nearest millisecond to what is stored, which recovers it.
            kyo.Path.PathStat(math.round(s.mtimeMs), s.size.toLong)
        }

    private[kyo] def stableIdentity()(using AllowUnsafe, Frame): Result[FileReadException, Maybe[String]] =
        catchRead {
            val s         = NodeModules.fs.statSync(pathStr)
            val birthtime = s.birthtimeMs
            // An inode can be reused as soon as an entry is deleted. Its birth time distinguishes
            // that replacement from a rename, which preserves both values.
            if birthtime.isNaN || birthtime <= 0 then Absent
            else Present(s"${s.dev.toString}:${s.ino.toString}:${birthtime.toString}")
        }

    // --- Write ---

    def write(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeModules.fs.writeFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def writeBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeModules.fs.writeFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def writeLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeModules.fs.writeFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def append(value: String, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeModules.fs.appendFileSync(pathStr, value, js.Dynamic.literal(encoding = "utf8"))
        }

    def appendBytes(value: Span[Byte], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            NodeModules.fs.appendFileSync(pathStr, bytesToUint8Array(value.toArray))
        }

    def appendLines(value: Chunk[String], options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            if options.createFolders then ensureParent()
            val content = value.mkString("\n") + "\n"
            NodeModules.fs.appendFileSync(pathStr, content, js.Dynamic.literal(encoding = "utf8"))
        }

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val currentSize = NodeModules.fs.lstatSync(pathStr).size.asInstanceOf[Double].toLong
            if size < currentSize then
                NodeModules.fs.truncateSync(pathStr, size.toDouble)
        }

    def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        catchWrite {
            val epochSec = epochMs / 1000.0
            NodeModules.fs.utimesSync(pathStr, epochSec, epochSec)
        }

    // --- Directory / structure ---

    def mkDir()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create) {
            NodeModules.fs.mkdirSync(pathStr, js.Dynamic.literal(recursive = true))
        }

    def mkFile()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Create) {
            ensureParent()
            if !NodeModules.fs.existsSync(pathStr) then
                NodeModules.fs.writeFileSync(pathStr, "")
        }

    def list()(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]] =
        catchFs(FileSystemOperation.List) {
            val entries = NodeModules.fs.readdirSync(pathStr)
            val sep     = NodeModules.path.sep
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
            if options.replace == Path.Replace.Never && NodeModules.fs.existsSync(toStr) then
                // Throw to trigger catchFs error translation
                throw js.JavaScriptException(
                    js.Dynamic.literal(code = "EEXIST", message = s"File already exists: $toStr")
                )
            end if
            NodeModules.fs.renameSync(pathStr, toStr)
            Result.succeed(())
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateMove(safe, to, options.atomicity, e))
            case e: Throwable             => Result.panic(e)

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
            val linkStat   = NodeModules.fs.lstatSync(pathStr)
            val sourceStat = if options.followLinks then NodeModules.fs.statSync(pathStr) else linkStat
            if linkStat.isSymbolicLink() && !options.followLinks then
                if targetExists then
                    val targetStat = NodeModules.fs.lstatSync(toStr)
                    if targetStat.isDirectory() then NodeModules.fs.rmdirSync(toStr)
                    else NodeModules.fs.unlinkSync(toStr)
                end if
                NodeModules.fs.symlinkSync(NodeModules.fs.readlinkSync(pathStr), toStr)
                if options.copyAttributes then
                    val epochSec = linkStat.mtimeMs / 1000.0
                    NodeModules.fs.lutimesSync(toStr, epochSec, epochSec)
            else if sourceStat.isDirectory() then
                if !targetExists then NodeModules.fs.mkdirSync(toStr, js.Dynamic.literal(recursive = false))
                else if !NodeModules.fs.lstatSync(toStr).isDirectory() then
                    NodeModules.fs.unlinkSync(toStr)
                    NodeModules.fs.mkdirSync(toStr, js.Dynamic.literal(recursive = false))
            else
                NodeModules.fs.copyFileSync(pathStr, toStr, 0)
                // Windows copies file times along with the bytes (copyFileSync goes through CopyFileExW), so the
                // target keeps the source's mtime even when the caller asked not to preserve attributes. POSIX gives
                // the new file the current time. Stamp `now` so `copyAttributes = false` means the same thing on
                // every host; the copyAttributes = true branch below overwrites this with the source's mtime.
                if !options.copyAttributes then
                    val nowSec = js.Date.now() / 1000.0
                    NodeModules.fs.utimesSync(toStr, nowSec, nowSec)
            end if
            if !(linkStat.isSymbolicLink() && !options.followLinks) then
                if options.copyAttributes then
                    val epochSec = sourceStat.mtimeMs / 1000.0
                    NodeModules.fs.utimesSync(toStr, epochSec, epochSec)
                else
                    // Node's copyFileSync inherits Win32 CopyFileEx timestamp preservation on Windows, so a
                    // copyAttributes=false copy keeps the source mtime there; POSIX copyFileSync already gives the
                    // destination a fresh mtime. Reset to the current time so the destination matches the
                    // JVM/Native contract (Files.copy without COPY_ATTRIBUTES) on every platform.
                    val nowSec = js.Date.now() / 1000.0
                    NodeModules.fs.utimesSync(toStr, nowSec, nowSec)
                end if
            end if
        }

    def remove()(using AllowUnsafe, Frame): Result[FileStructureException, Boolean] =
        try
            if !NodeModules.fs.existsSync(pathStr) then Result.succeed(false)
            else
                val stat = NodeModules.fs.lstatSync(pathStr)
                if stat.isDirectory() then
                    // Use rmdirSync for directories because it throws ENOTEMPTY for non-empty dirs.
                    // rmSync without recursive raises EISDIR on some platforms.
                    NodeModules.fs.rmdirSync(pathStr)
                else
                    NodeModules.fs.unlinkSync(pathStr)
                end if
                Result.succeed(true)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateFs(safe, FileSystemOperation.Remove, e))
            case e: Throwable             => Result.panic(e)

    def removeExisting()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove) {
            val stat = NodeModules.fs.lstatSync(pathStr)
            if stat.isDirectory() then
                // Use rmdirSync for directories because it throws ENOTEMPTY for non-empty dirs.
                NodeModules.fs.rmdirSync(pathStr)
            else
                NodeModules.fs.unlinkSync(pathStr)
            end if
        }

    def removeAll()(using AllowUnsafe, Frame): Result[FileStructureException, Unit] =
        catchFs(FileSystemOperation.Remove) {
            if NodeModules.fs.existsSync(pathStr) then
                val stat = NodeModules.fs.lstatSync(pathStr)
                if stat.isDirectory() then
                    NodeModules.fs.rmSync(pathStr, js.Dynamic.literal(recursive = true, force = true))
                else
                    NodeModules.fs.unlinkSync(pathStr)
                end if
        }

    // --- Walk handle ---

    def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileStructureException, Path.WalkHandle] =
        catchFs(FileSystemOperation.Walk) {
            // Validate that the root path exists before opening the walk handle.
            // lstatSync throws ENOENT if the path does not exist.
            discard(NodeModules.fs.lstatSync(pathStr))
            new NodeWalkHandle(pathStr, maxDepth, followLinks)
        }

    // --- Open write handle ---

    def openWrite(append: Boolean, options: Path.WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle] =
        catchWrite {
            if options.createFolders then ensureParent()
            val flags = if append then "a" else "w"
            val fd    = NodeModules.fs.openSync(pathStr, flags)
            new NodeWriteHandle(fd, safe)
        }

    // --- Positioned channel ---

    // Numeric non-append flags preserve explicit readSync/writeSync positions. Combining O_CREAT
    // with the requested access mode creates atomically without truncating existing content.
    private def openRawChannel(mode: Path.RawChannelAccess): Path.RawChannel =
        val constants = NodeModules.fs.constants
        mode match
            case Path.RawChannelAccess.Read =>
                new NodeRawChannel(NodeModules.fs.openSync(pathStr, "r"), safe)
            case Path.RawChannelAccess.Write(open) =>
                if open != FileSystem.WriteOpen.Existing then ensureParent()
                val flags = open match
                    case FileSystem.WriteOpen.Existing  => constants.O_WRONLY
                    case FileSystem.WriteOpen.Create    => constants.O_WRONLY | constants.O_CREAT
                    case FileSystem.WriteOpen.CreateNew => constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL
                new NodeRawChannel(NodeModules.fs.openSync(pathStr, flags), safe)
            case Path.RawChannelAccess.ReadWrite(open) =>
                if open != FileSystem.WriteOpen.Existing then ensureParent()
                val flags = open match
                    case FileSystem.WriteOpen.Existing  => constants.O_RDWR
                    case FileSystem.WriteOpen.Create    => constants.O_RDWR | constants.O_CREAT
                    case FileSystem.WriteOpen.CreateNew => constants.O_RDWR | constants.O_CREAT | constants.O_EXCL
                new NodeRawChannel(NodeModules.fs.openSync(pathStr, flags), safe)
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
    def lock(mode: Path.LockMode, sentinelSuffix: String)(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock] =
        NodePathLock.acquire(safe, pathStr, mode, sentinelSuffix)

    // --- Private helpers ---

    /** Splits content by newlines, dropping a single trailing empty element if the content ends with '\n'. This matches the behaviour of
      * java.nio.file.Files.readAllLines.
      */
    private def splitLines(content: String): Seq[String] =
        val parts = content.split("\n", -1).toSeq
        if parts.nonEmpty && parts.last.isEmpty then parts.init else parts
    end splitLines

    private def ensureParent(): Unit =
        val parent = NodeModules.path.dirname(pathStr)
        if parent.nonEmpty && parent != pathStr then
            NodeModules.fs.mkdirSync(parent, js.Dynamic.literal(recursive = true))
    end ensureParent

    private def ensureParentOf(target: String): Unit =
        val parent = NodeModules.path.dirname(target)
        if parent.nonEmpty && parent != target then
            NodeModules.fs.mkdirSync(parent, js.Dynamic.literal(recursive = true))
    end ensureParentOf

    private def existsNoFollow(target: String): Boolean =
        try
            discard(NodeModules.fs.lstatSync(target))
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
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateRead(safe, e))
            case e: Throwable             => Result.panic(e)

    private def catchWrite[A](expr: => A)(using Frame): Result[FileWriteException, A] =
        try Result.succeed(expr)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateWrite(safe, e))
            case e: Throwable             => Result.panic(e)

    private def catchChannelWrite[A](expr: => A)(using Frame): Result[FileWriteException | FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: js.JavaScriptException
                if !js.isUndefined(e.exception.asInstanceOf[js.Dynamic].code) &&
                    e.exception.asInstanceOf[js.Dynamic].code.asInstanceOf[String] == "EEXIST" =>
                Result.fail(FileAlreadyExistsException(safe))
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateWrite(safe, e))
            case e: Throwable             => Result.panic(e)

    private def catchFs[A](operation: FileSystemOperation)(expr: => A)(using Frame): Result[FileStructureException, A] =
        try Result.succeed(expr)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateFs(safe, operation, e))
            case e: Throwable             => Result.panic(e)

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
            val n = NodeModules.fs.readSync(fd, scanView, total, scan.length - total, offset.toDouble)
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
        val n     = NodeModules.fs.readSync(fd, uint8, 0, buffer.length, pos.toDouble)
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
        try Result.succeed(NodeModules.fs.fstatSync(fd).size.toLong)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable             => Result.panic(e)

    def close()(using AllowUnsafe): Unit =
        NodeModules.fs.closeSync(fd)

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
                if followLinks then NodeModules.fs.statSync else NodeModules.fs.lstatSync
            val isDir =
                try statFn(pathStr).isDirectory()
                catch case _: js.JavaScriptException => false
            if isDir && depth < maxDepth then
                val children =
                    try NodeModules.fs.readdirSync(pathStr).toSeq
                    catch case _: js.JavaScriptException => Seq.empty
                val sep = NodeModules.path.sep
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
                    loop(offset + NodeModules.fs.writeSync(fd, uint8, offset, arr.length - offset))
            loop(0)
            Result.unit
        catch
            case e: NodeError.NodeFailure =>
                Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable =>
                Result.panic(e)

    def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        writeBytes(Chunk.from(s.getBytes(charset)))

    def finish()(using AllowUnsafe): Unit =
        NodeModules.fs.fsyncSync(fd) // fsync: bytes are durable before the logical-completion flag
        finished = true

    def close()(using AllowUnsafe): Unit =
        NodeModules.fs.closeSync(fd)
        if !finished then
            // Unsafe: removes the partially-written file if finish() was never called
            if NodeModules.fs.existsSync(path.unsafe.show) then
                NodeModules.fs.unlinkSync(path.unsafe.show)
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
                val n = NodeModules.fs.readSync(fd, uint8, total, len - total, (pos + total).toDouble)
                if n == 0 then eof = true else total += n
            val out = new Array[Byte](total)
            var i   = 0
            while i < total do
                out(i) = uint8(i).toByte
                i += 1
            Result.succeed(out)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable             => Result.panic(e)

    def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            val uint8   = bytesToUint8Array(bytes)
            var written = 0
            while written < bytes.length do
                written += NodeModules.fs.writeSync(fd, uint8, written, bytes.length - written, (pos + written).toDouble)
            Result.unit
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable             => Result.panic(e)

    def sync(metadata: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            if metadata then NodeModules.fs.fsyncSync(fd) else NodeModules.fs.fdatasyncSync(fd)
            Result.unit
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateSync(path, e))
            case e: Throwable             => Result.panic(e)

    def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        try
            NodeModules.fs.ftruncateSync(fd, size.toDouble)
            Result.unit
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateWrite(path, e))
            case e: Throwable             => Result.panic(e)

    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] =
        try Result.succeed(NodeModules.fs.fstatSync(fd).size.toLong)
        catch
            case e: NodeError.NodeFailure => Result.fail(NodeError.translateRead(path, e))
            case e: Throwable             => Result.panic(e)

    def close()(using AllowUnsafe): Unit =
        if closed.compareAndSet(false, true) then NodeModules.fs.closeSync(fd)

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

/** `length` bytes from `SecureRandom.live` as lowercase hex, for temporary file names and lock owner tokens. */
private[kyo] def randomHex(length: Int): String =
    import AllowUnsafe.embrace.danger
    val bytes   = SecureRandom.live.unsafe.nextBytes(length).toArrayUnsafe
    val builder = new java.lang.StringBuilder(length * 2)
    var i       = 0
    while i < bytes.length do
        val value = bytes(i) & 0xff
        discard(builder.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 0xf, 16)))
        i += 1
    end while
    builder.toString
end randomHex

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

    private[kyo] val platformPathSeparator: String = Platform.pathSeparator
    private[kyo] val platformFileSeparator: String = Platform.fileSeparator

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
                // PathSyntaxJs.normalize resolves .., ., redundant separators;
                // constructor normalizes \ to /
                new NodePathUnsafe(PathSyntaxJs.normalize(raw)).safe
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
                    val tmpDir  = NodeModules.os.tmpdir()
                    val name    = prefix + randomId() + suffix
                    val tmpPath = tmpDir + NodeModules.path.sep + name
                    NodeModules.fs.writeFileSync(tmpPath, "")
                    Result.succeed(new NodePathUnsafe(tmpPath).safe)
                catch
                    case e: NodeError.NodeFailure =>
                        Result.fail(NodeError.translateFs(make(Chunk(prefix + suffix)), FileSystemOperation.Create, e))
            }
        }

    def tempDirUnscoped(
        prefix: String = "kyo"
    )(using Frame): Path < (Sync & Abort[FileStructureException]) =
        // Unsafe: bridges Node temp-directory creation into the Sync tier.
        Sync.Unsafe.defer {
            Abort.get {
                try
                    val tmpDir  = NodeModules.os.tmpdir()
                    val created = NodeModules.fs.mkdtempSync(tmpDir + NodeModules.path.sep + prefix)
                    Result.succeed(new NodePathUnsafe(created).safe)
                catch
                    case e: NodeError.NodeFailure =>
                        Result.fail(NodeError.translateFs(make(Chunk(prefix)), FileSystemOperation.Create, e))
            }
        }

    private def randomId(): String = randomHex(16)

    private[kyo] def envOrEmpty(name: String): String =
        val v = NodeProcess.env(name)
        if v == null then "" else v

    private[kyo] def homePath: Path =
        make(Chunk(NodeModules.os.homedir()))

    private[kyo] def cwdPath: Path =
        make(Chunk(NodeProcess.require("Path.cwd").cwd().asInstanceOf[String]))

end PathPlatformSpecific
