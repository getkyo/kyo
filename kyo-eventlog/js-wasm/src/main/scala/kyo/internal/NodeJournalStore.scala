package kyo.internal

import kyo.*
import scala.annotation.tailrec
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

// Minimal node:fs synchronous bindings for the journal backend: positional I/O,
// durability primitives, fd management, and the file-level atomics the lock protocol
// requires. These are added here (not in kyo-system) because fsyncSync, fdatasyncSync,
// and ftruncateSync are journal-specific and keeping them inside kyo-eventlog avoids
// widening kyo-system's public surface.
@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object NodeFsSync extends js.Object:
    def openSync(path: String, flags: String): Int                                              = js.native
    def closeSync(fd: Int): Unit                                                                = js.native
    def readSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int  = js.native
    def writeSync(fd: Int, buffer: Uint8Array, offset: Int, length: Int, position: Double): Int = js.native
    def writeSync(fd: Int, data: String, position: Double, encoding: String): Int               = js.native
    def fstatSync(fd: Int): NodeJournalStat                                                     = js.native
    def fsyncSync(fd: Int): Unit                                                                = js.native
    def fdatasyncSync(fd: Int): Unit                                                            = js.native
    def ftruncateSync(fd: Int, len: Double): Unit                                               = js.native
    def unlinkSync(path: String): Unit                                                          = js.native
    def renameSync(oldPath: String, newPath: String): Unit                                      = js.native
end NodeFsSync

// Minimal stat result exposing the file size and the directory predicate the lock protocol needs.
@js.native
private[kyo] trait NodeJournalStat extends js.Object:
    def size: Double           = js.native
    def isDirectory(): Boolean = js.native
end NodeJournalStat

// node:os hostname binding: used to record the lock holder's host so a LOCK from another
// machine is never probed with the local pid namespace.
@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object NodeOsHost extends js.Object:
    def hostname(): String = js.native
end NodeOsHost

// NodeSegmentStore: SegmentStore implementation backed by Node's synchronous fs API.
// Constructed only when isNodeRuntime is true (the gate is in FileJournalBackend.scala).
// All bridge operations carry `// Unsafe:` comments as required by the unsafe-boundary
// discipline (CONTRIBUTING.md).
final private[kyo] class NodeSegmentStore extends SegmentStore:

    def open(path: Path)(using AllowUnsafe): SegmentStore.Handle =
        // Unsafe: raw fd acquired via openSync, held for the segment handle's lifetime.
        // "r+" opens an existing file for positioned read+write without truncation (recovery
        // path). "w+" creates and opens a new empty file (segment creation path); on Node.js
        // a newly created file opened with "w+" has length 0, and createSegment writes the
        // 5-byte header as the first writeAt call.
        val pathStr = path.unsafe.show
        val fd =
            if path.unsafe.exists() then NodeFsSync.openSync(pathStr, "r+")
            else NodeFsSync.openSync(pathStr, "w+")
        new NodeHandle(fd)
    end open

    // Unsafe: opens the directory fd and fsyncs it so newly created children (stream
    // directories, segment files) are durably linked before an acknowledged append.
    // On platforms where opening a directory fd is unsupported (Windows) the openSync
    // throws; the exception is silently swallowed, matching the jvm-native syncDir
    // tolerance (FileJournalBackend.scala).
    def syncDir(dir: Path)(using AllowUnsafe): Unit =
        val dirStr = dir.unsafe.show
        try
            val fd = NodeFsSync.openSync(dirStr, "r")
            try NodeFsSync.fsyncSync(fd)
            finally NodeFsSync.closeSync(fd)
        catch case _: js.JavaScriptException => ()
        end try
    end syncDir

    def acquireLock(root: Path)(using AllowUnsafe, Frame): Result[JournalStorageError, SegmentStore.Lock] =
        NodeFileLock.acquire(root)

end NodeSegmentStore

// Positioned I/O handle for one open segment file. All methods are Unsafe: they bridge
// from Kyo's typed effect system into raw Node.js fd operations with no managed resource
// tracking of their own (the handle map in FileJournalCore owns the lifecycle).
final private[kyo] class NodeHandle(fd: Int) extends SegmentStore.Handle:

    // Unsafe: positional read; returns exactly the bytes read (short read on EOF is fine,
    // the caller checks the returned array length).
    def readAt(pos: Long, len: Int)(using AllowUnsafe): Array[Byte] =
        val buf = new Uint8Array(len)
        val n   = NodeFsSync.readSync(fd, buf, 0, len, pos.toDouble)
        val out = new Array[Byte](n)
        var i   = 0
        while i < n do
            out(i) = buf(i).toByte
            i += 1
        out
    end readAt

    // Unsafe: positional write; loops in case writeSync returns fewer bytes than requested
    // (unreachable on regular files in practice, but required for correctness parity with
    // the jvm-native FileChannelStore.writeAt loop).
    def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe): Unit =
        val uint8 = bytesToUint8Array(bytes)
        var off   = 0
        while off < bytes.length do
            val n = NodeFsSync.writeSync(fd, uint8, off, bytes.length - off, (pos + off).toDouble)
            off += n
        end while
    end writeAt

    // Unsafe: data-only durability flush, mirroring JVM FileChannel.force(false).
    // fdatasyncSync flushes data pages without necessarily updating metadata timestamps,
    // which is semantically equivalent to force(false). fdatasyncSync is available in
    // all supported Node.js versions (present since v0.1.96).
    def sync()(using AllowUnsafe): Unit =
        NodeFsSync.fdatasyncSync(fd)

    // Unsafe: truncates the segment to exactly `size` bytes (torn-tail recovery path).
    def truncate(size: Long)(using AllowUnsafe): Unit =
        NodeFsSync.ftruncateSync(fd, size.toDouble)

    // Unsafe: fstat the fd to get the current file size without a separate stat call.
    def size()(using AllowUnsafe): Long =
        NodeFsSync.fstatSync(fd).size.toLong

    // Unsafe: close the fd; errors are suppressed since a double-close is benign and the
    // caller (FileJournalCore.release) already wraps each close in Result.catching.
    def close()(using AllowUnsafe): Unit =
        discard(Result.catching[Throwable](NodeFsSync.closeSync(fd)))

end NodeHandle

// O_EXCL lockfile cross-process single-owner exclusion protocol.
//
// Node.js has no flock / FileChannel.tryLock analog. Cross-process exclusion is built on
// an O_EXCL lockfile carrying the holder's identity (pid + hostname + startedAt), a
// process.kill(pid, 0) liveness probe, and a rename-aside atomic reclaim of a dead
// holder's file. The in-process registry (FileJournalCore.heldRoots) provides same-
// process exclusion on all platforms and is checked before acquireLock is called
// (FileJournalCore.acquire); this object provides only the cross-process layer.
//
// Failure matrix:
//   Case 1: no LOCK           -> O_EXCL create succeeds -> OWN
//   Case 2: LOCK, alive pid   -> refuse with "locked by another owner (pid N on H)"
//   Case 3: LOCK, dead pid    -> rename-aside, retry O_EXCL -> OWN
//   Case 4: LOCK, unparseable -> fail closed "unreadable holder content; remove LOCK manually"
//   Case 5: LOCK, other host  -> refuse "held on another host" (pid namespace meaningless)
//   Case 6/7: reclaim race    -> O_EXCL arbitrates; loser sees live winner, refuses cleanly
//   Case 8: recycled pid      -> probe says alive (false-alive), refuse (safe direction)
private[kyo] object NodeFileLock:

    private val MaxRetries = 3

    // Counter for unique rename-aside nonce per acquire invocation.
    // Node.js is single-threaded, so this var is safe without synchronization.
    private var nonceSeq = 0

    // LOCK file content: one JSON object identifying the holder.
    private def holderJson(pid: Int, host: String): String =
        s"""{"pid":$pid,"startedAt":${java.lang.System.currentTimeMillis()},"host":"$host"}"""

    private[kyo] def currentPid: Int =
        js.Dynamic.global.process.pid.asInstanceOf[Double].toInt

    private[kyo] def currentHost: String = NodeOsHost.hostname()

    // process.kill(pid, 0) sends no signal; it only probes process reachability.
    //   No throw           -> process exists and is reachable: ALIVE
    //   Throws ESRCH       -> no such process: DEAD (reclaim eligible)
    //   Throws EPERM       -> process exists, insufficient permissions: ALIVE
    //   Any other throw    -> unknown state: conservatively ALIVE (fail-safe, never reclaim
    //                         a possibly-live lock)
    private[kyo] def isAlive(pid: Int): Boolean =
        try
            discard(js.Dynamic.global.process.applyDynamic("kill")(pid.asInstanceOf[js.Any], 0.asInstanceOf[js.Any]))
            true
        catch
            case e: js.JavaScriptException =>
                val c = e.exception.asInstanceOf[js.Dynamic].code
                if js.isUndefined(c) then true
                else
                    c.asInstanceOf[String] match
                        case "ESRCH" => false
                        case "EPERM" => true
                        case _       => true
                end if
            case _: Throwable => true
    end isAlive

    // Parses {"pid":N,"startedAt":M,"host":"H"} from LOCK content.
    // Returns None on any JSON parse error or missing field (triggers fail-closed path).
    private[kyo] def parseLock(content: String): Option[(Int, String)] =
        try
            val obj  = js.JSON.parse(content)
            val pid  = obj.pid
            val host = obj.host
            if js.isUndefined(pid) || js.isUndefined(host) then None
            else Some((pid.asInstanceOf[Double].toInt, host.asInstanceOf[String]))
        catch case _: Throwable => None
    end parseLock

    // Reads lock file content as UTF-8; returns None on any I/O error (e.g., LOCK
    // removed by a concurrent reclaimer between our EEXIST and our read).
    private def readLockContent(lockStr: String): Option[String] =
        try
            // Unsafe: raw fd read to get holder identity without going through the effect system.
            val fd = NodeFsSync.openSync(lockStr, "r")
            try
                val size = NodeFsSync.fstatSync(fd).size.toInt
                if size <= 0 then Some("")
                else
                    val buf = new Uint8Array(size)
                    val n   = NodeFsSync.readSync(fd, buf, 0, size, 0.0)
                    val out = new Array[Byte](n)
                    var i   = 0
                    while i < n do
                        out(i) = buf(i).toByte
                        i += 1
                    Some(new String(out, "UTF-8"))
                end if
            finally NodeFsSync.closeSync(fd)
            end try
        catch case _: Throwable => None
    end readLockContent

    private def errorCode(e: js.JavaScriptException): String =
        val c = e.exception.asInstanceOf[js.Dynamic].code
        if js.isUndefined(c) then "UNKNOWN" else c.asInstanceOf[String]

    // True if `path` is a directory, false for regular files and any open error.
    // Used to distinguish the "LOCK is a directory" error (surface IOException) from
    // "LOCK is a regular file held by another process" (continue with lock analysis).
    // Unsafe: opens a raw fd and fstats it; used only inside the acquire loop to detect
    // the LOCK-is-a-directory arm before any lock-content analysis.
    private def pathIsDirectory(path: String): Boolean =
        try
            val fd  = NodeFsSync.openSync(path, "r")
            val dir = NodeFsSync.fstatSync(fd).isDirectory()
            NodeFsSync.closeSync(fd)
            dir
        catch case _: Throwable => false

    def acquire(root: Path)(using AllowUnsafe, Frame): Result[JournalStorageError, SegmentStore.Lock] =
        val lockStr  = root.unsafe.show + "/LOCK"
        val pid      = currentPid
        val host     = currentHost
        val identity = holderJson(pid, host)

        @tailrec
        def loop(remaining: Int): Result[JournalStorageError, SegmentStore.Lock] =
            if remaining <= 0 then
                Result.fail(JournalStorageError(
                    s"Journal root '${root.unsafe.show}' LOCK contention could not be resolved after $MaxRetries attempts",
                    Absent
                ))
            else
                // Unsafe: O_EXCL ("wx") = O_CREAT | O_EXCL | O_WRONLY. The OS makes this
                // atomic: exactly one opener succeeds when multiple processes race.
                try
                    val fd = NodeFsSync.openSync(lockStr, "wx")
                    try
                        discard(NodeFsSync.writeSync(fd, identity, 0.0, "utf8"))
                        NodeFsSync.fsyncSync(fd)
                    finally NodeFsSync.closeSync(fd)
                    end try
                    Result.succeed(new NodeFileLock(lockStr))
                catch
                    case e: js.JavaScriptException if errorCode(e) == "EEXIST" =>
                        // openSync("wx") returns EEXIST for any pre-existing path, including
                        // directories. Detect that case first and fail with a present IOException
                        // (the same contract the JVM FileChannel.open throws naturally).
                        if pathIsDirectory(lockStr) then
                            Result.fail(JournalStorageError(
                                s"Journal root '${root.unsafe.show}' LOCK path is a directory, not a file",
                                Present(new java.io.IOException(
                                    s"Cannot acquire LOCK at '$lockStr': path is a directory"
                                ))
                            ))
                        else
                            // LOCK is a regular file: classify the holder and decide whether to reclaim.
                            readLockContent(lockStr) match
                                case None =>
                                    // Read failed: LOCK may have just been removed by a concurrent
                                    // reclaimer. Retry the O_EXCL create directly.
                                    loop(remaining - 1)
                                case Some(raw) =>
                                    parseLock(raw) match
                                        case None =>
                                            // Unparseable content: never reclaim a lock whose
                                            // holder we cannot identify; require manual removal.
                                            Result.fail(JournalStorageError(
                                                s"Journal root '${root.unsafe.show}' LOCK has unreadable holder content; remove LOCK manually",
                                                Absent
                                            ))
                                        case Some((holderPid, holderHost)) =>
                                            if holderHost != host then
                                                // Pid is in a foreign host's namespace: refuse.
                                                Result.fail(JournalStorageError(
                                                    s"Journal root '${root.unsafe.show}' LOCK is held on another host ($holderHost); cross-host use is not supported",
                                                    Absent
                                                ))
                                            else if isAlive(holderPid) then
                                                // process.kill probe did not throw ESRCH: holder alive, refuse.
                                                Result.fail(JournalStorageError(
                                                    s"Journal root '${root.unsafe.show}' is locked by another owner (pid $holderPid on $holderHost)",
                                                    Absent
                                                ))
                                            else
                                                // Holder dead (probe threw ESRCH). Atomic reclaim:
                                                // move the stale LOCK aside under a unique name so two
                                                // concurrent reclaimers do not collide on the aside path.
                                                // The sole ownership arbiter is the subsequent O_EXCL create.
                                                nonceSeq += 1
                                                val aside = s"$lockStr.stale.${pid}_$nonceSeq"
                                                try
                                                    NodeFsSync.renameSync(lockStr, aside)
                                                    // Best-effort cleanup of the aside; losing this unlink is fine.
                                                    discard(Result.catching[Throwable](NodeFsSync.unlinkSync(aside)))
                                                catch
                                                    case ex: js.JavaScriptException =>
                                                        // ENOENT: another reclaimer already moved this stale LOCK.
                                                        // Any other code: proceed to retry anyway.
                                                        ()
                                                    case _: Throwable => ()
                                                end try
                                                loop(remaining - 1)
                    case e: js.JavaScriptException =>
                        // Wrap the JS exception as java.io.IOException so the shared FileJournalTest
                        // cause-presence assertion holds on all platforms (JVM throws IOException
                        // natively; Node wraps the semantically equivalent JS error here).
                        val cause = new java.io.IOException(
                            s"openSync('$lockStr', \"wx\") failed: ${errorCode(e)}"
                        )
                        Result.fail(JournalStorageError(
                            s"Journal root '${root.unsafe.show}' LOCK create failed with code ${errorCode(e)}",
                            Present(cause)
                        ))
        end loop
        loop(MaxRetries)
    end acquire

end NodeFileLock

// Lock token returned on successful acquire. Release unlinks the LOCK file (best-effort,
// matching jvm-native FileLock.release() tolerance). The root key is unregistered from
// FileJournalCore.heldRoots by FileJournalCore.release(), which calls this after lock.release().
final private[kyo] class NodeFileLock(lockStr: String) extends SegmentStore.Lock:
    // Unsafe: removes the LOCK file so the next process can acquire it. Errors are
    // suppressed because a failed unlink (e.g., already removed) is benign at close time.
    def release()(using AllowUnsafe): Unit =
        discard(Result.catching[Throwable](NodeFsSync.unlinkSync(lockStr)))
end NodeFileLock
