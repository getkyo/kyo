package kyo

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.kernel.ArrowEffect

// The overlay's staged-journal operation record. Unrelated to the conceptual Path.WriteOp
// write-group partition of Path.Op (a private[kyo] read/write op family); despite sharing a
// base name these are different types defined in different contexts.
private[kyo] enum WriteOp:
    case WriteFile(path: Chunk[String], bytes: Span[Byte], stat: Path.PathStat)
    case WriteDirectory(path: Chunk[String], opaque: Boolean)
    case Remove(path: Chunk[String])
    // Move and Copy carry the source entry captured at stage time (`resolved`), so replay is
    // source-independent: Move replays as remove(from) then write(resolved) at to; Copy writes
    // resolved at to. No diff or partial-entry format.
    case Move(from: Chunk[String], to: Chunk[String], resolved: Path.Entry)
    case Copy(from: Chunk[String], to: Chunk[String], resolved: Path.Entry)
end WriteOp

private[kyo] object OverlayFileSystem:

    /** Upper-layer entry variants. `Entry` holds a staged file or directory; `Whiteout` marks a
      * deleted path; `OpaqueDir` marks a directory that hides all lower children.
      */
    enum Upper derives CanEqual:
        case Entry(body: Path.Entry)
        case Whiteout
        case OpaqueDir(stat: Path.PathStat)
    end Upper

    /** The overlay's mutable state: the upper map of staged entries, the append-only journal of
      * staged write operations (consumed by commit), and the read-set of lower observations
      * (the entry recorded the first time a lower path is read through the overlay).
      */
    final case class OverlayState(
        upper: Map[Chunk[String], Upper],
        journal: Chunk[WriteOp],
        // The read-set records the full observed base entry, not a stat-only stamp, so
        // Conflict.ancestor (Maybe[Path.Entry]) is available at commit without re-reading the lower
        // path. The Absent-observation case is carried as an Absent value in the map.
        readSet: Map[Chunk[String], Maybe[Path.Entry]]
    )

    object OverlayState:
        val empty: OverlayState = OverlayState(Map.empty, Chunk.empty, Map.empty)

    def init[S](lower: FileSystem[S])(using Frame): FileSystem.CommitHandle[S] < (Sync & Scope) =
        Scope.acquireRelease(AtomicRef.init(OverlayState.empty)) { ref =>
            // Unsafe: resets staged state on scope exit (auto-rollback); no Sync dispatch needed.
            Sync.Unsafe.defer { discard(ref.unsafe.updateAndGet(_ => OverlayState.empty)) }
        }.flatMap { ref =>
            // Unsafe: allocates per-instance commit counter at construction
            Sync.Unsafe.defer(new OverlayFileSystem(lower, ref, AtomicLong.Unsafe.init(0L).safe))
        }

end OverlayFileSystem

// Self-contained binary intent-log format for the overlay durable commit state machine.
// Written to "intent.kyo" in the staging directory before any lower mutations.
// The commit terminator (KYCT) seals the log; its absence means an incomplete write that
// recovery skips. No dependency on kyo-eventlog (circular); pure kyo-system.
private[kyo] object WriteOpLog:

    private val OpWriteFile: Byte = 0x01
    private val OpWriteDir: Byte  = 0x02
    private val OpRemove: Byte    = 0x03
    private val OpMove: Byte      = 0x04
    private val OpCopy: Byte      = 0x05

    private val TagFile: Byte = 0x01
    private val TagDir: Byte  = 0x02

    private val MagicHeader: Array[Byte]     = Array('K'.toByte, 'Y'.toByte, 'I'.toByte, 'L'.toByte)
    private val Version: Byte                = 0x01
    private val MagicTerminator: Array[Byte] = Array('K'.toByte, 'Y'.toByte, 'C'.toByte, 'T'.toByte)

    // Pure-Scala CRC32 (IEEE 802.3 polynomial). No java.util.zip; works on JVM, JS, Native.
    private val crc32Table: Array[Int] = Array.tabulate(256) { n =>
        var c = n
        var k = 0
        while k < 8 do
            c = if (c & 1) != 0 then 0xedb88320 ^ (c >>> 1) else c >>> 1
            k += 1
        c
    }

    private def crc32(bytes: Array[Byte], offset: Int, len: Int): Int =
        var crc = 0xffffffff
        var i   = offset
        val end = offset + len
        while i < end do
            crc = (crc >>> 8) ^ crc32Table((crc ^ bytes(i).toInt) & 0xff)
            i += 1
        crc ^ 0xffffffff
    end crc32

    private def wI16(buf: scala.collection.mutable.ArrayBuffer[Byte], v: Int): Unit =
        buf += ((v >>> 8) & 0xff).toByte
        buf += (v & 0xff).toByte

    private def wI32(buf: scala.collection.mutable.ArrayBuffer[Byte], v: Int): Unit =
        buf += ((v >>> 24) & 0xff).toByte
        buf += ((v >>> 16) & 0xff).toByte
        buf += ((v >>> 8) & 0xff).toByte
        buf += (v & 0xff).toByte
    end wI32

    private def wI64(buf: scala.collection.mutable.ArrayBuffer[Byte], v: Long): Unit =
        buf += ((v >>> 56) & 0xff).toByte
        buf += ((v >>> 48) & 0xff).toByte
        buf += ((v >>> 40) & 0xff).toByte
        buf += ((v >>> 32) & 0xff).toByte
        buf += ((v >>> 24) & 0xff).toByte
        buf += ((v >>> 16) & 0xff).toByte
        buf += ((v >>> 8) & 0xff).toByte
        buf += (v & 0xff).toByte
    end wI64

    private def rI16(arr: Array[Byte], pos: Int): Int =
        ((arr(pos) & 0xff) << 8) | (arr(pos + 1) & 0xff)

    private def rI32(arr: Array[Byte], pos: Int): Int =
        ((arr(pos) & 0xff) << 24) | ((arr(pos + 1) & 0xff) << 16) |
            ((arr(pos + 2) & 0xff) << 8) | (arr(pos + 3) & 0xff)

    private def rI64(arr: Array[Byte], pos: Int): Long =
        ((arr(pos) & 0xffL) << 56) | ((arr(pos + 1) & 0xffL) << 48) |
            ((arr(pos + 2) & 0xffL) << 40) | ((arr(pos + 3) & 0xffL) << 32) |
            ((arr(pos + 4) & 0xffL) << 24) | ((arr(pos + 5) & 0xffL) << 16) |
            ((arr(pos + 6) & 0xffL) << 8) | (arr(pos + 7) & 0xffL)

    private def encodeParts(buf: scala.collection.mutable.ArrayBuffer[Byte], parts: Chunk[String]): Unit =
        wI32(buf, parts.size)
        parts.foreach { part =>
            val encoded = part.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            wI16(buf, encoded.length)
            buf ++= encoded
        }
    end encodeParts

    private def encodeEntry(buf: scala.collection.mutable.ArrayBuffer[Byte], entry: Path.Entry): Unit =
        entry match
            case Path.Entry.File(bytes, stat) =>
                buf += TagFile
                val arr = bytes.toArrayUnsafe
                wI32(buf, arr.length)
                buf ++= arr
                wI64(buf, stat.lastModifiedMs)
                wI64(buf, stat.sizeBytes)
            case Path.Entry.Directory(stat) =>
                buf += TagDir
                wI64(buf, stat.lastModifiedMs)
                wI64(buf, stat.sizeBytes)

    private def encodeOp(op: WriteOp): Array[Byte] =
        val buf = new scala.collection.mutable.ArrayBuffer[Byte](32)
        op match
            case WriteOp.WriteFile(parts, bytes, stat) =>
                buf += OpWriteFile
                encodeParts(buf, parts)
                val arr = bytes.toArrayUnsafe
                wI32(buf, arr.length)
                buf ++= arr
                wI64(buf, stat.lastModifiedMs)
                wI64(buf, stat.sizeBytes)
            case WriteOp.WriteDirectory(parts, opaque) =>
                buf += OpWriteDir
                encodeParts(buf, parts)
                buf += (if opaque then 0x01.toByte else 0x00.toByte)
            case WriteOp.Remove(parts) =>
                buf += OpRemove
                encodeParts(buf, parts)
            case WriteOp.Move(from, to, resolved) =>
                buf += OpMove
                encodeParts(buf, from)
                encodeParts(buf, to)
                encodeEntry(buf, resolved)
            case WriteOp.Copy(from, to, resolved) =>
                buf += OpCopy
                encodeParts(buf, from)
                encodeParts(buf, to)
                encodeEntry(buf, resolved)
        end match
        buf.toArray
    end encodeOp

    // Encodes the journal as: header | records | terminator.
    // Record framing: len4 | crc4(body) | body.
    // Terminator: "KYCT" | crc4(all prior bytes). Presence of the terminator = complete log.
    def encode(journal: Chunk[WriteOp]): Span[Byte] =
        val buf = new scala.collection.mutable.ArrayBuffer[Byte](64)
        buf ++= MagicHeader
        buf += Version
        journal.foreach { op =>
            val body = encodeOp(op)
            wI32(buf, body.length)
            wI32(buf, crc32(body, 0, body.length))
            buf ++= body
        }
        val priorArr = buf.toArray
        buf ++= MagicTerminator
        wI32(buf, crc32(priorArr, 0, priorArr.length))
        Span.fromUnsafe(buf.toArray)
    end encode

    // Returns Success(Present(journal)) on a valid sealed log.
    // Returns Success(Absent) on truncation or any CRC failure: these are crash artifacts
    // from an incomplete write (finish() was never called); the commit never became durable,
    // so recovery can safely discard the staging dir.
    // Returns Failure(FileIOException) on bad magic bytes or unsupported version: not a crash
    // artifact; something else wrote the file or the format evolved. Fail loudly.
    def decode(logPath: Path, bytes: Span[Byte]): Result[FileIOException, Maybe[Chunk[WriteOp]]] =
        val arr = bytes.toArrayUnsafe
        val len = arr.length
        // Too short to even inspect magic: treat as truncated crash artifact.
        if len < 5 then return Result.succeed(Absent)
        // Bad magic: not our file at all. Fail loudly so the caller can observe it.
        if arr(0) != 'K' || arr(1) != 'Y' || arr(2) != 'I' || arr(3) != 'L' then
            return Result.fail(FileIOException(logPath, new java.io.IOException("bad magic bytes: expected KYIL"))(using Frame.internal))
        // Wrong version: format evolved. Fail loudly.
        if arr(4) != Version then
            return Result.fail(
                FileIOException(
                    logPath,
                    new java.io.IOException(s"unsupported version ${arr(4) & 0xff}, expected ${Version & 0xff}")
                )(using Frame.internal)
            )
        end if
        // Valid magic + version but too short for terminator: truncated crash artifact.
        if len < 13 then return Result.succeed(Absent)
        val termPos = len - 8
        if arr(termPos) != 'K' || arr(termPos + 1) != 'Y' ||
            arr(termPos + 2) != 'C' || arr(termPos + 3) != 'T'
        then return Result.succeed(Absent) // terminator absent: crash artifact
        val storedTermCrc = rI32(arr, termPos + 4)
        if storedTermCrc != crc32(arr, 0, termPos) then return Result.succeed(Absent) // terminator CRC mismatch
        var pos = 5
        val ops = new scala.collection.mutable.ArrayBuffer[WriteOp]()
        while pos < termPos do
            if pos + 8 > termPos then return Result.succeed(Absent)
            val bodyLen = rI32(arr, pos); pos += 4
            val bodyCrc = rI32(arr, pos); pos += 4
            if bodyLen < 0 || pos + bodyLen > termPos then return Result.succeed(Absent)
            val bodyStart = pos
            if bodyCrc != crc32(arr, pos, bodyLen) then return Result.succeed(Absent)
            pos += bodyLen
            decodeRecord(arr, bodyStart, bodyLen) match
                case Absent      => return Result.succeed(Absent)
                case Present(op) => ops += op
        end while
        Result.succeed(Present(Chunk.from(ops.toIndexedSeq)))
    end decode

    private def decodeRecord(arr: Array[Byte], offset: Int, len: Int): Maybe[WriteOp] =
        if len < 1 then return Absent
        val opcode = arr(offset)
        var pos    = offset + 1
        val end    = offset + len

        def readParts(): Maybe[Chunk[String]] =
            if pos + 4 > end then return Absent
            val count = rI32(arr, pos); pos += 4
            if count < 0 || count > 65536 then return Absent
            val parts = new scala.collection.mutable.ArrayBuffer[String](math.min(count, 64))
            var i     = 0
            var ok    = true
            while i < count && ok do
                if pos + 2 > end then ok = false
                else
                    val sLen = rI16(arr, pos); pos += 2
                    if sLen < 0 || pos + sLen > end then ok = false
                    else
                        parts += new String(arr, pos, sLen, java.nio.charset.StandardCharsets.UTF_8)
                        pos += sLen
                        i += 1
                    end if
            end while
            if ok then Present(Chunk.from(parts.toIndexedSeq)) else Absent
        end readParts

        def readEntry(): Maybe[Path.Entry] =
            if pos >= end then return Absent
            val tag = arr(pos); pos += 1
            if tag == TagFile then
                if pos + 4 > end then return Absent
                val bLen = rI32(arr, pos); pos += 4
                if bLen < 0 || pos + bLen > end then return Absent
                val bytes = Span.fromUnsafe(arr.slice(pos, pos + bLen)); pos += bLen
                if pos + 16 > end then return Absent
                val lm = rI64(arr, pos); pos += 8
                val sz = rI64(arr, pos); pos += 8
                Present(Path.Entry.File(bytes, Path.PathStat(lm, sz)))
            else if tag == TagDir then
                if pos + 16 > end then return Absent
                val lm = rI64(arr, pos); pos += 8
                val sz = rI64(arr, pos); pos += 8
                Present(Path.Entry.Directory(Path.PathStat(lm, sz)))
            else Absent
            end if
        end readEntry

        if opcode == OpWriteFile then
            readParts() match
                case Absent => Absent
                case Present(parts) =>
                    if pos + 4 > end then Absent
                    else
                        val bLen = rI32(arr, pos); pos += 4
                        if bLen < 0 || pos + bLen > end then Absent
                        else
                            val bytes = Span.fromUnsafe(arr.slice(pos, pos + bLen)); pos += bLen
                            if pos + 16 > end then Absent
                            else
                                val lm = rI64(arr, pos); pos += 8
                                val sz = rI64(arr, pos); pos += 8
                                Present(WriteOp.WriteFile(parts, bytes, Path.PathStat(lm, sz)))
                            end if
                        end if
        else if opcode == OpWriteDir then
            readParts() match
                case Absent => Absent
                case Present(parts) =>
                    if pos >= end then Absent
                    else
                        val opaque = arr(pos) != 0x00.toByte; pos += 1
                        Present(WriteOp.WriteDirectory(parts, opaque))
        else if opcode == OpRemove then
            readParts() match
                case Absent         => Absent
                case Present(parts) => Present(WriteOp.Remove(parts))
        else if opcode == OpMove then
            readParts() match
                case Absent => Absent
                case Present(from) =>
                    readParts() match
                        case Absent => Absent
                        case Present(to) =>
                            readEntry() match
                                case Absent            => Absent
                                case Present(resolved) => Present(WriteOp.Move(from, to, resolved))
        else if opcode == OpCopy then
            readParts() match
                case Absent => Absent
                case Present(from) =>
                    readParts() match
                        case Absent => Absent
                        case Present(to) =>
                            readEntry() match
                                case Absent            => Absent
                                case Present(resolved) => Present(WriteOp.Copy(from, to, resolved))
        else Absent
        end if
    end decodeRecord

end WriteOpLog

/** Copy-on-write overlay service. Reads check the upper layer first; writes stage in the upper
  * layer and append to the journal without touching lower. The journal is replayed onto lower on
  * commit. The read-set records the full observed Path.Entry for each lower path on its first
  * observation; commit validates these entries against the live lower before replaying.
  *
  * The four structural components are: lower (the constructor field), upper (Map in OverlayState),
  * journal (Chunk[WriteOp] in OverlayState), and readSet (Map[Chunk[String], Maybe[Path.Entry]] in
  * OverlayState). All state changes go through the CAS modify loop so concurrent access is safe.
  *
  * Scope-managed: the enclosing Scope bounds its lifetime; on scope exit the staged state is
  * reset (auto-rollback). commitStrategy is Manual.
  */
final private[kyo] class OverlayFileSystem[S](
    lower: FileSystem[S],
    state: AtomicRef[OverlayFileSystem.OverlayState],
    commitSeq: AtomicLong
)(using Frame)
    extends FileSystem.CommitHandle[S]:
    import OverlayFileSystem.*

    val commitStrategy: FileSystem.CommitStrategy = FileSystem.CommitStrategy.Manual

    // Startup seed: mixes nanosecond time with identity hash to be distinct across restarts
    // and across concurrently-alive instances. Not cryptographic; collision probability is
    // negligible for the expected number of commits per root.
    private val instanceSeed: String =
        (java.lang.System.nanoTime() ^ java.lang.System.identityHashCode(this).toLong).toHexString

    // Crash-injection hooks for recovery tests; each marks a point where a test can inject a
    // failure to verify the commit is replayable from that position. Default no-ops; recovery
    // tests replace them with functions that throw. Single-writer semantics: only one test
    // sets and clears a hook at a time. private[kyo] so tests (same package) can reach them.
    // Performance note: these hooks dispatch as no-op lambdas (default) on every commit.
    // The per-commit overhead (N+3 Sync.Unsafe.defer dispatches) is negligible relative to the
    // file I/O in the commit hot path.
    private[kyo] var afterStageHook: () => Unit              = () => ()
    private[kyo] var afterIntentLogHook: () => Unit          = () => ()
    private[kyo] var afterEntryApplyHook: (Int, Int) => Unit = (_, _) => ()
    private[kyo] var beforeMarkerHook: () => Unit            = () => ()
    private[kyo] var afterMarkerHook: () => Unit             = () => ()

    // Tracks the staging directory handle of the current or most recent commit attempt.
    // Set in withCommit before applyResolved, cleared after successful cleanup. Left set
    // when applyResolved throws (crash simulation) so recover() can find the staging dir.
    private[kyo] var stagingDirHandle: Maybe[Path.TempDirHandle] = Absent

    // CAS modify loop for operations that may fail with FileException.
    private def modify[A](op: OverlayState => Result[FileException, (OverlayState, A)]): A < (Sync & Abort[FileException]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                Abort.get(op(cur)).map { (next, v) =>
                    state.compareAndSet(cur, next).map {
                        case true  => Loop.done(v)
                        case false => Loop.continue(())
                    }
                }
            }
        }

    // Reads the current snapshot and presents it as `S & Abort[FileException]` so callers can
    // sequence it with lower calls without leaking an extra Sync into their effect row.
    // Safe because S = Sync at the only instantiation site (OverlayFileSystem.init).
    private def stateGet: OverlayState < (S & Abort[FileException]) =
        state.get.asInstanceOf[OverlayState < (S & Abort[FileException])]

    // Snapshot access: reads state then runs f; the cast keeps the declared effect row clean.
    private def withState[A](f: OverlayState => A < (S & Abort[FileException])): A < (S & Abort[FileException]) =
        stateGet.map(f)

    // Pure-state modify: never fails. Declared as `< (S & Abort[FileException])` so stamp
    // helpers and write methods share the same effect row as lower calls; the asInstanceOf is safe
    // because S = Sync at the only instantiation site.
    private def modifyPure(op: OverlayState => OverlayState): Unit < (S & Abort[FileException]) =
        (Loop(()) { _ =>
            state.get.map { cur =>
                state.compareAndSet(cur, op(cur)).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }: Unit < Sync).asInstanceOf[Unit < (S & Abort[FileException])]

    // Record a lower observation in the read-set for a file: the full observed entry (bytes + stat),
    // not a stat-only stamp, so Conflict.ancestor is available at commit without re-reading the lower
    // path. Idempotent: an existing read-set entry is kept.
    private def stampFile(parts: Chunk[String], stat: Path.PathStat, bytes: Span[Byte]): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else
                val entry = Path.Entry.File(bytes, stat)
                s.copy(readSet = s.readSet.updated(parts, Present(entry)))
        }

    private def stampDir(parts: Chunk[String], stat: Path.PathStat): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else
                val entry = Path.Entry.Directory(stat)
                s.copy(readSet = s.readSet.updated(parts, Present(entry)))
        }

    private def stampAbsent(parts: Chunk[String]): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else s.copy(readSet = s.readSet.updated(parts, Absent))
        }

    // Stamp a lower observation using stat + isRegularFile to determine the kind. A regular file
    // requires a bytes read to build the recorded Path.Entry.File.
    private def stampLower(parts: Chunk[String]): Unit < (S & Abort[FileException]) =
        val path = pathFrom(parts)
        lower.exists(path).map { found =>
            if !found then stampAbsent(parts)
            else
                lower.stat(path).map { stat =>
                    lower.isRegularFile(path).map { isFile =>
                        if isFile then lower.readBytes(path).map(bytes => stampFile(parts, stat, bytes))
                        else stampDir(parts, stat)
                    }
                }
        }
    end stampLower

    // Reconstruct a Path from its parts (parallel to Path.parts decomposition).
    private def pathFrom(parts: Chunk[String]): Path =
        if parts.isEmpty then Path("")
        else parts.tail.foldLeft(Path(parts.head))((acc, seg) => acc / seg)

    // Walk ancestor prefixes from nearest to farthest. A Whiteout or OpaqueDir at the nearest
    // found ancestor hides this path when it has no direct upper entry of its own. A positive
    // Entry at an ancestor stops the walk: re-creation of that prefix makes its children visible.
    // Only called in the case-None branch (a direct upper entry already takes precedence).
    private def ancestorWhiteout(s: OverlayState, parts: Chunk[String]): Boolean =
        (1 until parts.size).reverseIterator
            .map(n => s.upper.get(parts.take(n)))
            .collectFirst { case Some(v) => v }
            .exists {
                case Upper.Whiteout     => true
                case Upper.OpaqueDir(_) => true
                case _                  => false
            }

    // --- Inspection ---

    def exists(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout) => false
                case Some(_)              => true
                case None =>
                    if ancestorWhiteout(s, path.parts) then false
                    else
                        lower.exists(path).map { found =>
                            if !found then stampAbsent(path.parts).andThen(false)
                            else
                                lower.stat(path).map { stat =>
                                    lower.isRegularFile(path).map { isFile =>
                                        (if isFile then lower.readBytes(path).map(bytes => stampFile(path.parts, stat, bytes))
                                         else stampDir(path.parts, stat)).andThen(true)
                                    }
                                }
                        }
        }

    def exists(path: Path, followLinks: Boolean): Boolean < (S & Abort[FileException]) = exists(path)

    def isDirectory(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                       => false
                case Some(Upper.OpaqueDir(_))                   => true
                case Some(Upper.Entry(Path.Entry.Directory(_))) => true
                case Some(Upper.Entry(Path.Entry.File(_, _)))   => false
                case None =>
                    if ancestorWhiteout(s, path.parts) then false
                    else
                        lower.isDirectory(path).map { isDir =>
                            lower.stat(path).map { stat =>
                                (if isDir then stampDir(path.parts, stat)
                                 else lower.readBytes(path).map(bytes => stampFile(path.parts, stat, bytes))).andThen(isDir)
                            }
                        }
        }

    def isRegularFile(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                       => false
                case Some(Upper.OpaqueDir(_))                   => false
                case Some(Upper.Entry(Path.Entry.Directory(_))) => false
                case Some(Upper.Entry(Path.Entry.File(_, _)))   => true
                case None =>
                    if ancestorWhiteout(s, path.parts) then false
                    else
                        lower.isRegularFile(path).map { isFile =>
                            lower.stat(path).map { stat =>
                                (if isFile then lower.readBytes(path).map(bytes => stampFile(path.parts, stat, bytes))
                                 else stampDir(path.parts, stat)).andThen(isFile)
                            }
                        }
        }

    def isSymbolicLink(path: Path): Boolean < (S & Abort[FileException]) = false

    def realPath(path: Path): Path < (S & Abort[FileException]) = path

    // --- Reads ---

    def read(path: Path): String < (S & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, StandardCharsets.UTF_8))

    def read(path: Path, charset: Charset): String < (S & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))

    def readBytes(path: Path): Span[Byte] < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, _))) => bytes
                case Some(_)                                      => Abort.fail(FileNotFoundException(path))
                case None =>
                    if ancestorWhiteout(s, path.parts) then Abort.fail(FileNotFoundException(path))
                    else
                        lower.readBytes(path).map { bytes =>
                            lower.stat(path).map { stat =>
                                stampFile(path.parts, stat, bytes).andThen(bytes)
                            }
                        }
        }

    def readLines(path: Path): Chunk[String] < (S & Abort[FileException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def readLines(path: Path, charset: Charset): Chunk[String] < (S & Abort[FileException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def size(path: Path): Long < (S & Abort[FileException]) = readBytes(path).map(_.size.toLong)

    def stat(path: Path): Path.PathStat < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(_, ps)))   => ps
                case Some(Upper.Entry(Path.Entry.Directory(ps))) => ps
                case Some(Upper.OpaqueDir(ps))                   => ps
                case Some(Upper.Whiteout)                        => Abort.fail(FileNotFoundException(path))
                case None =>
                    if ancestorWhiteout(s, path.parts) then Abort.fail(FileNotFoundException(path))
                    else
                        lower.stat(path).map { ps =>
                            lower.isRegularFile(path).map { isFile =>
                                (if isFile then lower.readBytes(path).map(bytes => stampFile(path.parts, ps, bytes))
                                 else stampDir(path.parts, ps)).andThen(ps)
                            }
                        }
        }

    // --- Read handles ---

    def openRead(path: Path): Path.ReadHandle < (S & Abort[FileException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))

    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (S & Abort[FileException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))

    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (S & Abort[FileException]) =
        walkCollect(path, maxDepth).map { paths =>
            new Path.WalkHandle:
                private val it                             = paths.iterator
                def next()(using AllowUnsafe): Maybe[Path] = if it.hasNext then Maybe(it.next()) else Maybe.empty
                def close()(using AllowUnsafe): Unit       = ()
        }

    // Preorder traversal through the overlay view for openWalk.
    private def walkCollect(path: Path, maxDepth: Int): Chunk[Path] < (S & Abort[FileException]) =
        if maxDepth <= 0 then Chunk.empty
        else
            list(path).map { children =>
                children.foldLeft[Chunk[Path] < (S & Abort[FileException])](Chunk.empty) { (accKyo, child) =>
                    accKyo.map { acc =>
                        isDirectory(child).map { isDir =>
                            if isDir then walkCollect(child, maxDepth - 1).map(sub => acc.appended(child).appendedAll(sub))
                            else acc.appended(child)
                        }
                    }
                }
            }

    // --- List ---

    def list(path: Path): Chunk[Path] < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                     => Abort.fail(FileNotFoundException(path))
                case Some(Upper.Entry(Path.Entry.File(_, _))) => Abort.fail(FileNotADirectoryException(path))
                case maybeOpaque =>
                    val isOpaque = maybeOpaque.exists {
                        case Upper.OpaqueDir(_) => true
                        case _                  => false
                    }

                    // Collect the set of segments already covered in upper for this directory.
                    val upperSegs: Set[String] = s.upper.keysIterator.collect {
                        case parts if parts.size == path.parts.size + 1 && parts.startsWith(path.parts) =>
                            parts.last
                    }.toSet

                    // Collect visible upper children (non-Whiteout).
                    val upperVisible: List[Path] = s.upper.collect {
                        case (parts, v) if parts.size == path.parts.size + 1 && parts.startsWith(path.parts) && v != Upper.Whiteout =>
                            pathFrom(parts)
                    }.toList

                    val lowerKyo: Chunk[Path] < (S & Abort[FileException]) =
                        if isOpaque then Chunk.empty
                        else
                            lower.exists(path).map { exists =>
                                if !exists then Chunk.empty[Path]
                                else
                                    lower.stat(path).map { stat =>
                                        stampDir(path.parts, stat).andThen {
                                            lower.list(path).map { lp =>
                                                // Drop lower children that have any upper entry (Entry, Whiteout, or OpaqueDir).
                                                lp.filter(p => !upperSegs.contains(p.parts.last))
                                            }
                                        }
                                    }
                            }

                    lowerKyo.map { lowerPaths =>
                        val combined = (lowerPaths.toSeq ++ upperVisible).distinctBy(_.parts).sortBy(_.parts.last)
                        Chunk.from(combined)
                    }
        }

    def list(path: Path, glob: String): Chunk[Path] < (S & Abort[FileException]) =
        list(path).map(_.filter(p => p.name.exists(InMemoryHandles.matchesGlob(_, glob))))

    // --- Writes ---

    def write(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException]) =
        writeBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), createFolders)

    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        val stat = Path.PathStat(0L, value.size.toLong)
        modifyPure { s =>
            s.copy(
                upper = s.upper.updated(path.parts, Upper.Entry(Path.Entry.File(value, stat))),
                journal = s.journal.appended(WriteOp.WriteFile(path.parts, value, stat))
            )
        }
    end writeBytes

    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        write(path, value.mkString("", "\n", "\n"), createFolders)

    def append(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException]) =
        appendBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), createFolders)

    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(existing, _))) =>
                    // Already in upper: concatenate without consulting lower, no stamp needed.
                    val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                    val stat   = Path.PathStat(0L, merged.size.toLong)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(merged, stat))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, merged, stat))
                        )
                    }
                case _ =>
                    // Not in upper: read lower (stamp on first observation), then stage.
                    // Ancestor Whiteout hides lower content; treat as absent, start fresh.
                    lower.exists(path).map { lowerFound =>
                        val found = lowerFound && !ancestorWhiteout(s, path.parts)
                        val readLower: Span[Byte] < (S & Abort[FileException]) =
                            if !found then stampAbsent(path.parts).andThen(Span.empty[Byte])
                            else
                                lower.readBytes(path).map { existing =>
                                    lower.stat(path).map { stat => stampFile(path.parts, stat, existing).andThen(existing) }
                                }
                        readLower.map { existing =>
                            val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                            val stat   = Path.PathStat(0L, merged.size.toLong)
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(merged, stat))),
                                    journal = cur.journal.appended(WriteOp.WriteFile(path.parts, merged, stat))
                                )
                            }
                        }
                    }
        }

    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        append(path, value.mkString("", "\n", "\n"), createFolders)

    def truncate(path: Path, size: Long): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, _))) =>
                    val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(size.toInt))
                    val stat = Path.PathStat(0L, kept.size.toLong)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(kept, stat))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, kept, stat))
                        )
                    }
                case Some(_) => Abort.fail(FileNotFoundException(path))
                case None =>
                    if ancestorWhiteout(s, path.parts) then Abort.fail(FileNotFoundException(path))
                    else
                        lower.readBytes(path).map { bytes =>
                            lower.stat(path).map { lStat =>
                                stampFile(path.parts, lStat, bytes).andThen {
                                    val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(size.toInt))
                                    val stat = Path.PathStat(0L, kept.size.toLong)
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(kept, stat))),
                                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, kept, stat))
                                        )
                                    }
                                }
                            }
                        }
        }

    def setLastModified(path: Path, epochMs: Long): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, stat))) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, ns))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, ns))
                        )
                    }
                case Some(Upper.Entry(Path.Entry.Directory(stat))) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(ns))),
                            journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                        )
                    }
                case Some(Upper.OpaqueDir(stat)) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.OpaqueDir(ns)),
                            journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = true))
                        )
                    }
                case Some(Upper.Whiteout) => Abort.fail(FileNotFoundException(path))
                case None =>
                    if ancestorWhiteout(s, path.parts) then Abort.fail(FileNotFoundException(path))
                    else
                        lower.stat(path).map { stat =>
                            lower.isRegularFile(path).map { isFile =>
                                if isFile then
                                    // The bytes read precedes stampFile so the read-set entry and the staged
                                    // upper entry share the same observed bytes (no double read).
                                    lower.readBytes(path).map { bytes =>
                                        stampFile(path.parts, stat, bytes).andThen {
                                            val ns = stat.copy(lastModifiedMs = epochMs)
                                            modifyPure { cur =>
                                                cur.copy(
                                                    upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, ns))),
                                                    journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, ns))
                                                )
                                            }
                                        }
                                    }
                                else
                                    stampDir(path.parts, stat).andThen {
                                        val ns = stat.copy(lastModifiedMs = epochMs)
                                        modifyPure { cur =>
                                            cur.copy(
                                                upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(ns))),
                                                journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                                            )
                                        }
                                    }
                            }
                        }
        }

    def mkDir(path: Path): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.OpaqueDir(_))                   => () // already opaque dir
                case Some(Upper.Entry(Path.Entry.Directory(_))) => () // already a dir in upper
                case _                                          =>
                    // If lower has a directory at this path, create OpaqueDir (hides lower children).
                    // If lower has a file or absent, create a regular directory entry.
                    lower.exists(path).map { exists =>
                        if !exists then
                            stampAbsent(path.parts).andThen {
                                val st = Path.PathStat(0L, 0L)
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(st))),
                                        journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                                    )
                                }
                            }
                        else
                            lower.stat(path).map { stat =>
                                lower.isDirectory(path).map { isDir =>
                                    (if isDir then stampDir(path.parts, stat)
                                     else lower.readBytes(path).map(bytes => stampFile(path.parts, stat, bytes))).andThen {
                                        // An existing lower dir (or file) gets OpaqueDir, hiding its children.
                                        val st = if isDir then stat else Path.PathStat(0L, 0L)
                                        modifyPure { cur =>
                                            cur.copy(
                                                upper = cur.upper.updated(path.parts, Upper.OpaqueDir(st)),
                                                journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = true))
                                            )
                                        }
                                    }
                                }
                            }
                    }
        }

    def mkFile(path: Path): Unit < (S & Abort[FileException]) = writeBytes(path, Span.empty[Byte], createFolders = true)

    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException]) =
        resolveEntry(from).map { resolved =>
            withState { s =>
                val targetExists: Boolean =
                    s.upper.get(to.parts) match
                        case Some(Upper.Whiteout) => false
                        case Some(_)              => true
                        case None                 => false // lower checked separately below
                if targetExists && !replaceExisting then
                    Abort.fail(FileAlreadyExistsException(to))
                else
                    lower.exists(to).map { lowerTargetExists =>
                        if lowerTargetExists && !replaceExisting then
                            Abort.fail(FileAlreadyExistsException(to))
                        else
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(from.parts, Upper.Whiteout).updated(to.parts, Upper.Entry(resolved)),
                                    journal = cur.journal.appended(WriteOp.Move(from.parts, to.parts, resolved))
                                )
                            }
                    }
                end if
            }
        }

    def copy(
        from: Path,
        to: Path,
        followLinks: Boolean,
        replaceExisting: Boolean,
        copyAttributes: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException]) =
        resolveEntry(from).map { resolved =>
            withState { s =>
                val targetExists: Boolean =
                    s.upper.get(to.parts) match
                        case Some(Upper.Whiteout) => false
                        case Some(_)              => true
                        case None                 => false
                if targetExists && !replaceExisting then
                    Abort.fail(FileAlreadyExistsException(to))
                else
                    lower.exists(to).map { lowerTargetExists =>
                        if lowerTargetExists && !replaceExisting then
                            Abort.fail(FileAlreadyExistsException(to))
                        else
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(to.parts, Upper.Entry(resolved)),
                                    journal = cur.journal.appended(WriteOp.Copy(from.parts, to.parts, resolved))
                                )
                            }
                    }
                end if
            }
        }

    // Resolve a source path to a Path.Entry, checking upper first then lower.
    // Records a stamp when reading from lower. Fails if source is Whiteout or absent.
    private def resolveEntry(path: Path): Path.Entry < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(e))        => e
                case Some(Upper.OpaqueDir(stat)) => Path.Entry.Directory(stat): Path.Entry
                case Some(Upper.Whiteout)        => Abort.fail(FileNotFoundException(path))
                case None =>
                    if ancestorWhiteout(s, path.parts) then Abort.fail(FileNotFoundException(path))
                    else
                        lower.exists(path).map { found =>
                            if !found then Abort.fail(FileNotFoundException(path))
                            else
                                lower.isRegularFile(path).map { isFile =>
                                    if isFile then
                                        lower.readBytes(path).map { bytes =>
                                            lower.stat(path).map { stat =>
                                                stampFile(path.parts, stat, bytes).andThen {
                                                    Path.Entry.File(bytes, stat): Path.Entry
                                                }
                                            }
                                        }
                                    else
                                        lower.stat(path).map { stat =>
                                            stampDir(path.parts, stat).andThen {
                                                Path.Entry.Directory(stat): Path.Entry
                                            }
                                        }
                                }
                        }
        }

    def remove(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout) => false
                case Some(_) =>
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Whiteout),
                            journal = cur.journal.appended(WriteOp.Remove(path.parts))
                        )
                    }.andThen(true)
                case None =>
                    lower.exists(path).map { found =>
                        if !found then stampAbsent(path.parts).andThen(false)
                        else
                            stampLower(path.parts).andThen {
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(path.parts, Upper.Whiteout),
                                        journal = cur.journal.appended(WriteOp.Remove(path.parts))
                                    )
                                }.andThen(true)
                            }
                    }
        }

    def removeExisting(path: Path): Unit < (S & Abort[FileException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))

    def removeAll(path: Path): Unit < (S & Abort[FileException]) =
        remove(path).unit

    // --- Write handle ---

    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (S & Abort[FileException]) =
        withState { s =>
            val upperSeed: Maybe[Span[Byte]] =
                if append then
                    s.upper.get(path.parts) match
                        case Some(Upper.Entry(Path.Entry.File(bytes, _))) => Present(bytes)
                        case _                                            => Absent
                else Present(Span.empty[Byte])

            upperSeed match
                case Present(seed) => mkWriteHandle(path, seed)
                case Absent        =>
                    // append mode with no upper entry: seed from lower
                    lower.exists(path).map { found =>
                        if !found then stampAbsent(path.parts).andThen(mkWriteHandle(path, Span.empty[Byte]))
                        else
                            lower.readBytes(path).map { bytes =>
                                lower.stat(path).map { stat =>
                                    stampFile(path.parts, stat, bytes).andThen(mkWriteHandle(path, bytes))
                                }
                            }
                    }
            end match
        }

    private def mkWriteHandle(path: Path, seed: Span[Byte]): Path.WriteHandle =
        new Path.WriteHandle:
            private var acc = seed.toArrayUnsafe
            def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ chunk.toArray
                Result.succeed(())
            def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ s.getBytes(charset)
                Result.succeed(())
            def finish()(using AllowUnsafe): Unit =
                val bytes = Span.fromUnsafe(acc)
                val stat  = Path.PathStat(0L, bytes.size.toLong)
                // Unsafe: commits buffered bytes into the overlay upper layer at finish()
                discard(state.unsafe.updateAndGet { cur =>
                    cur.copy(
                        upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, stat))),
                        journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, stat))
                    )
                })
            end finish
            def close()(using AllowUnsafe): Unit = () // bytes dropped if finish() not called

    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (S & Abort[FileException]) =
        // Unsafe: pumps the write handle's internal buffer; no overlay state involved.
        // asInstanceOf: Sync.Unsafe.defer gives `< (Sync & Abort)` but S = Sync at the
        // only instantiation site so this is a no-op cast at runtime.
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeBytes(chunk))).asInstanceOf[Unit < (S & Abort[FileException])]

    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (S & Abort[FileException]) =
        // Unsafe: same as writeChunk.
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeString(value, charset))).asInstanceOf[Unit < (S & Abort[FileException])]

    // --- Temp dir ---

    def tempDir(prefix: String): Path.TempDirHandle < (S & Abort[FileException]) =
        val id   = java.lang.System.identityHashCode(prefix).toHexString
        val dir  = Path(prefix + "-overlay-" + id)
        val stat = Path.PathStat(0L, 0L)
        modifyPure { cur =>
            cur.copy(
                upper = cur.upper.updated(dir.parts, Upper.Entry(Path.Entry.Directory(stat))),
                journal = cur.journal.appended(WriteOp.WriteDirectory(dir.parts, opaque = false))
            )
        }.andThen {
            new Path.TempDirHandle:
                def path: Path = dir
                // Unsafe: removes the upper entry; never touches the host filesystem.
                def remove()(using AllowUnsafe): Unit =
                    discard(state.unsafe.updateAndGet(cur => cur.copy(upper = cur.upper.removed(dir.parts))))
        }
    end tempDir

    // --- Commit / rollback ---

    def rollback(using Frame): Unit < S =
        // modifyPure's CAS loop never triggers Abort[FileException]; Abort.run discharges the
        // phantom Abort row, leaving Unit < S as declared.
        Abort.run[FileException](modifyPure(_ => OverlayState.empty)).map(_ => ())

    // Validate the read-set: for each recorded ancestor entry, re-read lower and compare.
    private def validate(s: OverlayState): Chunk[Conflict] < (S & Abort[FileException]) =
        s.readSet.toIndexedSeq.foldLeft[Chunk[Conflict] < (S & Abort[FileException])](Chunk.empty) {
            case (accKyo, (parts, ancestor)) =>
                accKyo.map { acc =>
                    val path = pathFrom(parts)
                    lower.exists(path).map { found =>
                        if !found then
                            if ancestor.isEmpty then acc
                            else
                                val conflict =
                                    Conflict(path, ancestor, s.upper.get(parts).fold[Maybe[Path.Entry]](Absent)(upperToEntry), Absent)
                                acc.appended(conflict)
                        else
                            lower.stat(path).map { liveStat =>
                                lower.isRegularFile(path).map { isFile =>
                                    val matches = ancestor match
                                        case Present(Path.Entry.File(_, aStat)) =>
                                            isFile && aStat.lastModifiedMs == liveStat.lastModifiedMs && aStat.sizeBytes == liveStat.sizeBytes
                                        case Present(Path.Entry.Directory(aStat)) =>
                                            !isFile && aStat.lastModifiedMs == liveStat.lastModifiedMs
                                        case Absent => false
                                    if matches then acc
                                    else
                                        // theirs: fresh bytes for file divergence; stat only for directory divergence.
                                        val oursEntry = s.upper.get(parts).fold[Maybe[Path.Entry]](Absent)(upperToEntry)
                                        val liveEntryKyo: Maybe[Path.Entry] < (S & Abort[FileException]) =
                                            if isFile then
                                                lower.readBytes(path).map { bytes =>
                                                    (Present(Path.Entry.File(bytes, liveStat)): Maybe[Path.Entry])
                                                }
                                            else Present[Path.Entry](Path.Entry.Directory(liveStat))
                                        liveEntryKyo.map { liveEntry =>
                                            val conflict = Conflict(path, ancestor, oursEntry, liveEntry)
                                            acc.appended(conflict)
                                        }
                                    end if
                                }
                            }
                    }
                }
        }

    private def upperToEntry(u: Upper): Maybe[Path.Entry] =
        u match
            case Upper.Entry(e)      => Present(e)
            case Upper.OpaqueDir(st) => Present(Path.Entry.Directory(st))
            case Upper.Whiteout      => Absent

    // Invoke a () => Unit hook synchronously. The hook may throw to halt the commit at the
    // marked step; the thrown exception propagates as a Kyo panic through the effect stack.
    private def runHook(hook: () => Unit): Unit < (S & Abort[FileException]) =
        // Unsafe: hook may throw to halt the commit; exception propagates as a panic.
        Sync.Unsafe.defer(hook()).asInstanceOf[Unit < (S & Abort[FileException])]

    private def runHookKN(hook: (Int, Int) => Unit, k: Int, n: Int): Unit < (S & Abort[FileException]) =
        // Unsafe: same halt-on-throw contract as runHook.
        Sync.Unsafe.defer(hook(k, n)).asInstanceOf[Unit < (S & Abort[FileException])]

    // Writes file content to `path` via the openWrite/writeChunk/finish/close path so the
    // bytes are durable (finish() calls fsync) before the caller proceeds. Used for staged
    // files in stageOps so that if the process crashes after staging but before the intent
    // log is written, the staged content is either fully present or absent -- never partial.
    private def stageDurableFile(path: Path, bytes: Span[Byte]): Unit < (S & Abort[FileException]) =
        lower.openWrite(path, false, false).map { handle =>
            lower.writeChunk(handle, Chunk.from(bytes.toArray)).andThen(
                // Unsafe: finish() fsyncs so staged bytes are durable before returning;
                // close() releases the channel. Without finish(), a crash mid-write leaves
                // no sealed file: recovery's existence check would see a partial artifact.
                Sync.Unsafe.defer { handle.finish(); handle.close() }
                    .asInstanceOf[Unit < (S & Abort[FileException])]
            )
        }

    // Stage file content for every WriteOp that carries bytes. Writes each as "e<i>.dat"
    // inside stagingDir via the durable write path so staged bytes survive power loss.
    // Directory ops and removes have no staged file.
    private def stageOps(stagingDir: Path, journal: Chunk[WriteOp]): Unit < (S & Abort[FileException]) =
        journal.zipWithIndex.foldLeft[Unit < (S & Abort[FileException])](()) { case (acc, (op, i)) =>
            acc.andThen {
                op match
                    case WriteOp.WriteFile(_, bytes, _) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case WriteOp.Move(_, _, Path.Entry.File(bytes, _)) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case WriteOp.Copy(_, _, Path.Entry.File(bytes, _)) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case _ => ()
            }
        }

    // Encodes the journal with WriteOpLog and writes it to "intent.kyo" in the staging dir
    // via the openWrite/finish/close path. finish() is the completion boundary: its absence
    // (crash during write) leaves the file absent or partial, so recovery skips. A plain
    // writeBytes call lacks the two-phase open/finish contract; a crash mid-write leaves no sealed log.
    private def writeIntentLog(stagingDir: Path, journal: Chunk[WriteOp]): Unit < (S & Abort[FileException]) =
        val logBytes = WriteOpLog.encode(journal)
        lower.openWrite(stagingDir / "intent.kyo", false, false).map { handle =>
            lower.writeChunk(handle, Chunk.from(logBytes.toArray)).andThen(
                // Unsafe: finish() seals the log as complete; close() releases the channel.
                // syncDir flushes the staging dir's dirent so the log file is reachable after
                // power loss. Without finish(), a crash mid-write leaves no sealed log: recovery skips.
                Sync.Unsafe.defer { handle.finish(); handle.close(); stagingDir.unsafe.syncDir() }
                    .asInstanceOf[Unit < (S & Abort[FileException])]
            )
        }
    end writeIntentLog

    // Writes the "committed.marker" sentinel via the openWrite/finish/close path so that a
    // crash during the marker write leaves it absent (recovery re-applies), not partially
    // written in an ambiguous state.
    private def writeCommittedMarker(stagingDir: Path): Unit < (S & Abort[FileException]) =
        lower.openWrite(stagingDir / "committed.marker", false, false).map { handle =>
            // No content; finish() commits the zero-byte sentinel, close() releases the channel.
            // syncDir flushes the staging dir's dirent so the marker is reachable after power loss.
            Sync.Unsafe.defer { handle.finish(); handle.close(); stagingDir.unsafe.syncDir() }
                .asInstanceOf[Unit < (S & Abort[FileException])]
        }

    // Apply one WriteOp during the commit apply phase. File entries move atomically from
    // their staged copy to the final lower path (POSIX rename on host; CAS on in-memory).
    // Each atomic move is followed by a best-effort parent-dir sync so the renamed dirent
    // is durable after power loss. When `idempotent` is true, file arms check staged
    // existence before moving (recovery may have already applied the move).
    private def applyOneOp(stagingDir: Path, i: Int, op: WriteOp, idempotent: Boolean = false): Unit < (S & Abort[FileException]) =
        def moveStagedFile(staged: Path, target: Path, syncParent: Boolean): Unit < (S & Abort[FileException]) =
            if idempotent then
                lower.exists(staged).flatMap { has =>
                    if has then
                        lower.move(staged, target, replaceExisting = true, atomicMove = true, createFolders = true)
                            .andThen(if syncParent then syncParentOf(target) else ())
                    else ()
                }
            else
                lower.move(staged, target, replaceExisting = true, atomicMove = true, createFolders = true)
                    .andThen(if syncParent then syncParentOf(target) else ())
        end moveStagedFile

        op match
            case WriteOp.WriteFile(parts, _, _) =>
                moveStagedFile(stagingDir / s"e$i.dat", pathFrom(parts), syncParent = !idempotent)
            case WriteOp.WriteDirectory(parts, _) =>
                lower.mkDir(pathFrom(parts))
            case WriteOp.Remove(parts) =>
                lower.removeAll(pathFrom(parts))
            case WriteOp.Move(fromP, toP, resolved) =>
                val removeFrom =
                    if idempotent then
                        lower.exists(pathFrom(fromP)).flatMap { exists =>
                            if exists then lower.removeAll(pathFrom(fromP)) else ()
                        }
                    else lower.removeAll(pathFrom(fromP))
                removeFrom.andThen {
                    resolved match
                        case _: Path.Entry.File =>
                            moveStagedFile(stagingDir / s"e$i.dat", pathFrom(toP), syncParent = !idempotent)
                        case _: Path.Entry.Directory =>
                            lower.mkDir(pathFrom(toP))
                }
            case WriteOp.Copy(_, toP, resolved) =>
                resolved match
                    case _: Path.Entry.File =>
                        moveStagedFile(stagingDir / s"e$i.dat", pathFrom(toP), syncParent = !idempotent)
                    case _: Path.Entry.Directory =>
                        lower.mkDir(pathFrom(toP))
        end match
    end applyOneOp

    private def applyOneOpIdempotent(stagingDir: Path, i: Int, op: WriteOp): Unit < (S & Abort[FileException]) =
        applyOneOp(stagingDir, i, op, idempotent = true)

    // 5-step durable commit protocol driven by a pre-created staging directory.
    // (1) Stage file content; (2) write intent log + terminator; (3) atomic-move each entry
    // with per-entry hook; (4) write committed.marker. Each crash hook is a test-injection
    // point: recovery tests set the hook to throw, then call recover() to verify resumption.
    private def applyResolved(stagingDir: Path, journal: Chunk[WriteOp]): Unit < (S & Abort[FileException]) =
        val n = journal.size
        stageOps(stagingDir, journal).andThen {
            runHook(afterStageHook).andThen { // crash point 1
                writeIntentLog(stagingDir, journal).andThen {
                    runHook(afterIntentLogHook).andThen { // crash point 2
                        journal.zipWithIndex
                            .foldLeft[Unit < (S & Abort[FileException])](()) { case (acc, (op, i)) =>
                                acc.andThen {
                                    applyOneOp(stagingDir, i, op).andThen {
                                        runHookKN(afterEntryApplyHook, i + 1, n) // crash point 3
                                    }
                                }
                            }
                            .andThen {
                                runHook(beforeMarkerHook).andThen { // crash point 4
                                    // Crash during the marker write is not an independently injectable point:
                                    // the sentinel either exists or does not; no partial state is possible.
                                    // The afterMarkerHook fires in withCommit after applyResolved returns.
                                    writeCommittedMarker(stagingDir)
                                }
                            }
                    }
                }
            }
        }
    end applyResolved

    // Creates a staging dir, runs the durable commit protocol, and cleans up on success.
    // On failure (crash hook throws or lower I/O error) the staging dir and stagingDirHandle
    // remain set so recover() can find and re-apply the partial commit.
    private def withCommit(journal: Chunk[WriteOp]): Unit < (S & Abort[FileException]) =
        // Unsafe: monotone counter increment for unique staging dir names
        Sync.Unsafe.defer(commitSeq.unsafe.getAndIncrement())
            .asInstanceOf[Long < (S & Abort[FileException])]
            .flatMap { seq =>
                val commitId = s"$instanceSeed-${seq.toHexString}"
                lower.tempDir(s"kyo-commit-$commitId").map { handle =>
                    // Write the ownership sentinel as the first entry in the staging dir. recoverFromDisk
                    // skips any kyo-commit-* dir that lacks it to prevent misclassifying user directories
                    // as orphaned staging dirs. A crash between staging dir creation and sentinel write
                    // leaks an empty dir; disk-scan skips it (no sentinel). Accepted trade.
                    lower.writeBytes(handle.path / ".kyo-staging", Span.from(Array.empty[Byte]), createFolders = false).andThen {
                        // Unsafe: stores handle after sentinel write so recover() can find the staging dir
                        // if applyResolved is interrupted; also best-effort syncs the parent dir so the
                        // staging dir's own dirent is durable.
                        Sync.Unsafe.defer {
                            stagingDirHandle = Present(handle)
                            pathFrom(handle.path.parts.dropRight(1)).unsafe.syncDir()
                        }.asInstanceOf[Unit < (S & Abort[FileException])]
                            .andThen {
                                applyResolved(handle.path, journal).andThen {
                                    runHook(afterMarkerHook).andThen { // crash point 6
                                        // Committed marker written; safe to remove staging dir.
                                        // Unsafe: clears handle reference and removes the staging directory.
                                        Sync.Unsafe.defer {
                                            stagingDirHandle = Absent
                                            handle.remove()
                                        }.asInstanceOf[Unit < (S & Abort[FileException])]
                                    }
                                }
                            }
                    }
                }
            }
    end withCommit

    // Best-effort: syncs the parent directory of `path` so that a newly-moved file's
    // directory entry is durable after power loss. Swallowed silently on platforms that
    // do not support directory fsync (Windows, some in-memory lowerers).
    private def syncParentOf(path: Path): Unit < (S & Abort[FileException]) =
        val pp = path.parts.dropRight(1)
        Sync.Unsafe.defer { if pp.nonEmpty then pathFrom(pp).unsafe.syncDir() }
            .asInstanceOf[Unit < (S & Abort[FileException])]
    end syncParentOf

    def commit(using Frame): Unit < (S & Abort[FileException] & Abort[CommitConflict]) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                if conflicts.isEmpty then
                    withCommit(s.journal).andThen(modifyPure(_ => OverlayState.empty))
                else
                    Abort.fail(CommitConflict(conflicts))
            }
        }

    def commitOverwrite(using Frame): Unit < (S & Abort[FileException]) =
        stateGet.map { s =>
            withCommit(s.journal).andThen(modifyPure(_ => OverlayState.empty))
        }

    def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (S & Abort[FileException] & S2) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                // Collect one Resolution per conflicted path, then rebuild upper and journal
                // so the replay reflects every resolution (not just the original staged ops).
                conflicts.foldLeft[Map[Chunk[String], Resolution] < (S & Abort[FileException] & S2)](Map.empty) { (accKyo, conflict) =>
                    accKyo.map { resolutions =>
                        resolve(conflict).map { resolution =>
                            resolutions.updated(conflict.path.parts, resolution)
                        }
                    }
                }.map { resolutions =>
                    // Pure fold: compute replacement upper and journal from the resolution map.
                    val (newUpper, replacedJournal) =
                        resolutions.foldLeft((s.upper, s.journal)) { case ((upper, journal), (parts, resolution)) =>
                            resolution match
                                case Resolution.KeepOurs =>
                                    (upper, journal)
                                case Resolution.KeepTheirs =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    (upper.removed(parts), stripped)
                                case Resolution.Write(entry) =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    val newOp = entry match
                                        case Path.Entry.File(bytes, stat) => WriteOp.WriteFile(parts, bytes, stat)
                                        case Path.Entry.Directory(_)      => WriteOp.WriteDirectory(parts, opaque = false)
                                    (upper.updated(parts, Upper.Entry(entry)), stripped.appended(newOp))
                                case Resolution.Remove =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    (upper.updated(parts, Upper.Whiteout), stripped.appended(WriteOp.Remove(parts)))
                        }
                    modifyPure(_.copy(upper = newUpper, journal = replacedJournal)).andThen {
                        withCommit(replacedJournal).andThen(modifyPure(_ => OverlayState.empty))
                    }
                }
            }
        }

    // Recovers a single staging directory: reads the intent log, re-applies ops idempotently
    // (skipping ops already applied to the lower), writes the committed marker if absent, then
    // removes the staging directory via the lower service. Used by both recover() (live commit
    // reference still in memory) and recoverFromDisk() (disk-scan after a process restart).
    //
    // `checkSentinel`: when true (disk-scan path), the dir must contain the ".kyo-staging"
    // ownership sentinel before recovery proceeds. This prevents misclassifying a user directory
    // whose name happens to start with "kyo-commit-" as an orphaned staging dir.
    // When false (in-process recover() path), the staging handle is authoritative and the
    // sentinel check is skipped.
    private def recoverStagingDir(stagingDir: Path, checkSentinel: Boolean = false): Unit < (S & Abort[FileException]) =
        if !checkSentinel then recoverStagingDirImpl(stagingDir)
        else
            lower.exists(stagingDir / ".kyo-staging").map { hasSentinel =>
                if !hasSentinel then () // not kyo-owned; skip to protect user directories
                else recoverStagingDirImpl(stagingDir)
            }

    private def recoverStagingDirImpl(stagingDir: Path): Unit < (S & Abort[FileException]) =
        val logPath = stagingDir / "intent.kyo"
        lower.exists(logPath).map { hasLog =>
            if !hasLog then
                // Staging dir exists but no intent log was written (crash before log write).
                // The commit was never durable; remove the orphaned staging dir and treat as clean.
                lower.removeAll(stagingDir)
            else
                lower.exists(stagingDir / "committed.marker").map { hasMarker =>
                    if hasMarker then
                        // Committed marker present; the commit was fully applied. Cleanup only.
                        lower.removeAll(stagingDir)
                    else
                        lower.readBytes(logPath).map { logBytes =>
                            WriteOpLog.decode(logPath, logBytes) match
                                case Result.Success(Absent) =>
                                    // Torn or CRC-failed log: crash artifact from an incomplete
                                    // intent-log write (finish() never called). The commit was
                                    // never durable; discard the staging dir and treat as clean.
                                    lower.removeAll(stagingDir)
                                case Result.Success(Present(journal)) =>
                                    journal.zipWithIndex
                                        .foldLeft[Unit < (S & Abort[FileException])](()) {
                                            case (acc, (op, i)) =>
                                                acc.andThen(applyOneOpIdempotent(stagingDir, i, op))
                                        }
                                        .andThen {
                                            writeCommittedMarker(stagingDir).andThen {
                                                lower.removeAll(stagingDir)
                                            }
                                        }
                                case Result.Failure(e) =>
                                    // Bad magic or unsupported version: not a crash artifact.
                                    // Fail loudly so the caller can observe the unexpected state.
                                    Abort.fail[FileException](e)
                        }
                }
        }
    end recoverStagingDirImpl

    // Recovery driver: re-applies a partially-applied commit found via stagingDirHandle.
    // Called after a simulated mid-commit crash: the overlay object remains alive, so
    // stagingDirHandle still points to the staging dir created before the failure.
    // private[kyo] so recovery tests (same package, outside this class) can call it.
    private[kyo] def recover(): Unit < (S & Abort[FileException]) =
        stagingDirHandle match
            case Absent => ()
            case Present(handle) =>
                recoverStagingDir(handle.path).andThen {
                    // Clear the in-memory staging reference after recovery; subsequent calls to
                    // recover() see Absent and exit as no-ops (idempotency guarantee).
                    // Unsafe: mutation of stagingDirHandle outside the Kyo effect system.
                    Sync.Unsafe.defer { stagingDirHandle = Absent }
                        .asInstanceOf[Unit < (S & Abort[FileException])]
                }

    // Scans the lower service's root for orphaned staging directories (kyo-commit-* prefix)
    // left by a prior process crash and recovers each via recoverStagingDir. Does NOT wire
    // at OverlayFileSystem.init: wiring at init would require adding root: Path and
    // Abort[FileException] to Service.overlay's public signature, which would change the
    // established API. Call recoverFromDisk(root) explicitly immediately after
    // Service.overlay(lower) to enable automatic crash recovery. private[kyo] so disk-scan
    // recovery tests can call it directly.
    private[kyo] def recoverFromDisk(root: Path): Unit < (S & Abort[FileException]) =
        lower.list(root).map { entries =>
            entries.foldLeft[Unit < (S & Abort[FileException])](()) { (acc, entry) =>
                acc.andThen {
                    entry.name match
                        case Present(n) if n.startsWith("kyo-commit-") => recoverStagingDir(entry, checkSentinel = true)
                        case _                                         => ()
                }
            }
        }

end OverlayFileSystem

// Forwards every path op back under PathRead or PathWrite so an overlay wrapping this
// service is transparent to whatever PathWrite handler is installed in the outer scope.
final private[kyo] class ForwardingLowerFileSystem(using Frame) extends FileSystem[PathWrite]:
    val commitStrategy: FileSystem.CommitStrategy = FileSystem.CommitStrategy.Auto
    def exists(path: Path): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Exists(path))
    def exists(path: Path, followLinks: Boolean): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ExistsFollow(path, followLinks))
    def isDirectory(path: Path): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsDirectory(path))
    def isRegularFile(path: Path): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsRegularFile(path))
    def isSymbolicLink(path: Path): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsSymbolicLink(path))
    def realPath(path: Path): Path < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPath(path))
    def read(path: Path): String < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Read(path))
    def read(path: Path, charset: Charset): String < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadCharset(path, charset))
    def readBytes(path: Path): Span[Byte] < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadBytes(path))
    def readLines(path: Path): Chunk[String] < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLines(path))
    def readLines(path: Path, charset: Charset): Chunk[String] < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLinesCharset(path, charset))
    def size(path: Path): Long < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(path))
    def stat(path: Path): Path.PathStat < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Stat(path))
    def list(path: Path): Chunk[Path] < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ListDir(path))
    def list(path: Path, glob: String): Chunk[Path] < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ListGlob(path, glob))
    def openRead(path: Path): Path.ReadHandle < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenRead(path))
    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenReadLines(path, charset))
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenWalk(path, maxDepth, followLinks))
    def write(path: Path, value: String, createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Write(path, value, createFolders))
    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteBytes(path, value, createFolders))
    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteLines(path, value, createFolders))
    def append(path: Path, value: String, createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Append(path, value, createFolders))
    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendBytes(path, value, createFolders))
    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendLines(path, value, createFolders))
    def truncate(path: Path, size: Long): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Truncate(path, size))
    def setLastModified(path: Path, epochMs: Long): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.SetLastModified(path, epochMs))
    def mkDir(path: Path): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkDir(path))
    def mkFile(path: Path): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkFile(path))
    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Move(from, to, replaceExisting, atomicMove, createFolders))
    def copy(
        from: Path,
        to: Path,
        followLinks: Boolean,
        replaceExisting: Boolean,
        copyAttributes: Boolean,
        createFolders: Boolean
    ): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Copy(from, to, followLinks, replaceExisting, copyAttributes, createFolders))
    def remove(path: Path): Boolean < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Remove(path))
    def removeExisting(path: Path): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveExisting(path))
    def removeAll(path: Path): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveAll(path))
    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append, createFolders))
    def tempDir(prefix: String): Path.TempDirHandle < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.TempDir(prefix))
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteChunk(handle, chunk))
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (PathWrite & Abort[FileException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, value, charset))
end ForwardingLowerFileSystem
