package kyo

import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.FileSystemCrc32
import kyo.internal.PathTrie
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
    case Copy(from: Chunk[String], to: Chunk[String], resolved: Path.Entry, copyAttributes: Boolean)
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

    /** A path that has been resolved through the lower.
      *
      * Constructible only by [[OverlayFileSystem.canonical]], so a value of this type is evidence
      * that resolution happened rather than a claim that it did. The upper, the journal, and the
      * read-set are all keyed by canonical paths; taking this type at every accessor is what makes
      * "keyed by an unresolved path" unrepresentable instead of merely documented.
      */
    opaque type CanonicalPath = Chunk[String]

    object CanonicalPath:
        private[kyo] def wrap(parts: Chunk[String]): CanonicalPath = parts

        extension (self: CanonicalPath)
            def parts: Chunk[String] = self

            /** Extends a canonical path by one segment.
              *
              * Sound without re-resolving: the parent is already resolved, and a child of a resolved
              * directory is reached without traversing a link at any level above it. Segment-wise
              * extension is how [[resolveThroughLower]] builds a resolution for a path the lower does
              * not hold, so a subtree staged beneath a resolved parent shares its parent's key space.
              */
            def append(segment: String): CanonicalPath = self.appended(segment)
        end extension

        given CanEqual[CanonicalPath, CanonicalPath] = CanEqual.derived
    end CanonicalPath

    /** The staged upper layer, keyed by canonical path.
      *
      * Opaque outside this object, so the only way to read or write an entry is with a
      * [[CanonicalPath]]. Keying the upper by a path that has not been resolved through the lower is
      * the defect this type exists to prevent: commit replays the journal against a link-following
      * lower, so a raw key stages bytes under one name and commits them under another. Opacity makes
      * that a compile error rather than something review has to catch.
      */
    opaque type UpperTrie = PathTrie[Upper]

    object UpperTrie:
        import CanonicalPath.parts

        val empty: UpperTrie = PathTrie.empty

        extension (self: UpperTrie)
            def get(cp: CanonicalPath): Maybe[Upper]                = self.get(cp.parts)
            def updated(cp: CanonicalPath, entry: Upper): UpperTrie = self.updated(cp.parts, entry)
            def removed(cp: CanonicalPath): UpperTrie               = self.removed(cp.parts)
            def nearestAncestorValue(cp: CanonicalPath): Maybe[Upper] =
                self.nearestAncestorValue(cp.parts)
            def childValues(cp: CanonicalPath): Chunk[(String, Upper)] = self.childValues(cp.parts)
            def descendantValues(cp: CanonicalPath): Chunk[(CanonicalPath, Upper)] =
                self.descendantValues(cp.parts).map((parts, v) => (CanonicalPath.wrap(parts), v))
        end extension

        given CanEqual[UpperTrie, UpperTrie] = CanEqual.derived
    end UpperTrie

    /** Lower observations recorded on first read, keyed by canonical path.
      *
      * Opaque for the same reason as [[UpperTrie]]: commit validates each recorded observation
      * against the lower path the commit actually mutates, so an entry keyed by an unresolved path
      * would validate a different file than the one being written.
      */
    opaque type ReadSet = Map[Chunk[String], Maybe[Path.Entry]]

    object ReadSet:
        import CanonicalPath.parts

        val empty: ReadSet = Map.empty

        extension (self: ReadSet)
            def contains(cp: CanonicalPath): Boolean = self.contains(cp.parts)
            def updated(cp: CanonicalPath, observed: Maybe[Path.Entry]): ReadSet =
                self.updated(cp.parts, observed)
            def removed(cp: CanonicalPath): ReadSet = self.removed(cp.parts)
            def entries: Chunk[(CanonicalPath, Maybe[Path.Entry])] =
                Chunk.from(self.toIndexedSeq.map((parts, v) => (CanonicalPath.wrap(parts), v)))
        end extension
    end ReadSet

    /** The overlay's mutable state: the upper trie of staged entries, the append-only journal of
      * staged write operations (consumed by commit), and the read-set of lower observations
      * (the entry recorded the first time a lower path is read through the overlay).
      *
      * The upper is a [[PathTrie]] rather than a flat map because every question asked of it beyond
      * a point lookup is hierarchical: shadowing by an ancestor, a directory's direct children, and
      * a subtree for move, copy, and removal. A flat map answers those by scanning.
      */
    final case class OverlayState(
        upper: UpperTrie,
        journal: Chunk[WriteOp],
        // The read-set records the full observed base entry, not a stat-only stamp, so
        // Conflict.ancestor (Maybe[Path.Entry]) is available at commit without re-reading the lower
        // path. The Absent-observation case is carried as an Absent value in the map.
        readSet: ReadSet,
        // Resolution of a raw path to its canonical form, recorded on first observation.
        //
        // A path's canonical form is an observation of the lower, exactly like a read-set entry, and
        // is recorded once for the same reason: resolving on every access would let a key drift as
        // the lower changes, so one logical file could acquire two keys and split its staged state.
        // Pinning it on first touch keeps keys stable; drift is a commit-time conflict, not a silent
        // re-key.
        resolved: PathTrie[Chunk[String]]
    )

    object OverlayState:
        val empty: OverlayState = OverlayState(UpperTrie.empty, Chunk.empty, ReadSet.empty, PathTrie.empty)

    def init[S, S2](lower: FileSystem.Write[S])(using
        frame: Frame,
        isolate: Isolate[S, Sync, S2]
    ): (
        FileSystem.StagedChanges[S & Sync & Abort[FileSystemException]] & FileSystem.Write[S & Sync] & FileSystem.Watch[S & Sync]
    ) < (Sync & Scope) =
        Scope.acquireRelease(
            AtomicRef.init(OverlayState.empty).map(ref => AtomicBoolean.init(true).map(active => (ref, active)))
        ) { case (ref, active) =>
            active.compareAndSet(true, false).map { wasOpen =>
                if wasOpen then ref.set(OverlayState.empty) else ()
            }
        }.flatMap { case (ref, active) =>
            // Unsafe: allocates per-instance commit counter at construction
            Sync.Unsafe.defer(new WatchableOverlayFileSystem(lower, ref, AtomicLong.Unsafe.init(0L).safe, active, isolate))
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
    private val Version: Byte                = 0x02
    private val MagicTerminator: Array[Byte] = Array('K'.toByte, 'Y'.toByte, 'C'.toByte, 'T'.toByte)

    // Table-driven CRC32 (IEEE 802.3 polynomial) moved to the shared kyo.internal.FileSystemCrc32
    // (promoted so kyo.internal.ZipArchive's per-entry checksum reuses the same implementation).

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
            case WriteOp.Copy(from, to, resolved, copyAttributes) =>
                buf += OpCopy
                encodeParts(buf, from)
                encodeParts(buf, to)
                encodeEntry(buf, resolved)
                buf += (if copyAttributes then 0x01.toByte else 0x00.toByte)
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
            wI32(buf, FileSystemCrc32.of(body))
            buf ++= body
        }
        val priorArr = buf.toArray
        buf ++= MagicTerminator
        wI32(buf, FileSystemCrc32.of(priorArr))
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
            return Result.fail(FileIOException(
                logPath,
                FileSystemOperation.Read,
                new java.io.IOException("bad magic bytes: expected KYIL")
            )(using Frame.internal))
        end if
        // Wrong version: format evolved. Fail loudly.
        if arr(4) != Version then
            return Result.fail(
                FileIOException(
                    logPath,
                    FileSystemOperation.Read,
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
        if storedTermCrc != FileSystemCrc32.of(arr, 0, termPos) then return Result.succeed(Absent) // terminator CRC mismatch
        var pos = 5
        val ops = new scala.collection.mutable.ArrayBuffer[WriteOp]()
        while pos < termPos do
            if pos + 8 > termPos then return Result.succeed(Absent)
            val bodyLen = rI32(arr, pos); pos += 4
            val bodyCrc = rI32(arr, pos); pos += 4
            if bodyLen < 0 || pos + bodyLen > termPos then return Result.succeed(Absent)
            val bodyStart = pos
            if bodyCrc != FileSystemCrc32.of(arr, pos, bodyLen) then return Result.succeed(Absent)
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
                                case Absent => Absent
                                case Present(resolved) =>
                                    if pos >= end then Absent
                                    else Present(WriteOp.Copy(from, to, resolved, arr(pos) != 0x00.toByte))
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
  * Scope-managed: the enclosing Scope bounds its lifetime; on scope exit open staged state is
  * discarded.
  */
private[kyo] class OverlayFileSystem[S](
    lower: FileSystem.Write[S],
    state: AtomicRef[OverlayFileSystem.OverlayState],
    uniqueSeq: AtomicLong,
    active: AtomicBoolean
) extends FileSystem.Write[S & Sync]
    with FileSystem.StagedChanges[S & Sync & Abort[FileSystemException]]:
    import OverlayFileSystem.*

    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < S = lower.defaultCaseSensitivity

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

    // CAS modify loop for operations that may fail with FileSystemException.
    private def modify[E <: FileSystemException, A](op: OverlayState => Result[E, (OverlayState, A)])(using Frame): A < (Sync & Abort[E]) =
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

    // Reads the current snapshot and presents it as `S & Abort[FileSystemException]` so callers can
    // sequence it with lower calls without leaking an extra Sync into their effect row.
    // Safe because S = Sync at the only instantiation site (OverlayFileSystem.init).
    private def stateGet(using Frame): OverlayState < S =
        state.get.asInstanceOf[OverlayState < S]

    // Snapshot access: reads state then runs f; the cast keeps the declared effect row clean.
    private def withState[E, A](f: OverlayState => A < (S & Abort[E]))(using Frame): A < (S & Abort[E]) =
        stateGet.map(f)

    // Pure-state modify: never fails. Declared as `< (S & Abort[FileSystemException])` so stamp
    // helpers and write methods share the same effect row as lower calls; the asInstanceOf is safe
    // because S = Sync at the only instantiation site.
    private def modifyPure(op: OverlayState => OverlayState)(using Frame): Unit < S =
        (Loop(()) { _ =>
            state.get.map { cur =>
                state.compareAndSet(cur, op(cur)).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }: Unit < Sync).asInstanceOf[Unit < S]

    // Record a lower observation in the read-set for a file: the full observed entry (bytes + stat),
    // not a stat-only stamp, so Conflict.ancestor is available at commit without re-reading the lower
    // path. Idempotent: an existing read-set entry is kept.
    private def stampFile(cp: CanonicalPath, stat: Path.PathStat, bytes: Span[Byte])(using Frame): Unit < S =
        modifyPure { s =>
            if s.readSet.contains(cp) then s
            else
                val entry = Path.Entry.File(bytes, stat)
                s.copy(readSet = s.readSet.updated(cp, Present(entry)))
        }

    private def stampDir(cp: CanonicalPath, stat: Path.PathStat)(using Frame): Unit < S =
        modifyPure { s =>
            if s.readSet.contains(cp) then s
            else
                val entry = Path.Entry.Directory(stat)
                s.copy(readSet = s.readSet.updated(cp, Present(entry)))
        }

    private def stampAbsent(cp: CanonicalPath)(using Frame): Unit < S =
        modifyPure { s =>
            if s.readSet.contains(cp) then s
            else s.copy(readSet = s.readSet.updated(cp, Absent))
        }

    // Stamp a lower observation using stat + isRegularFile to determine the kind. A regular file
    // requires a bytes read to build the recorded Path.Entry.File.
    private def stampLower(cp: CanonicalPath)(using Frame): Unit < (S & Abort[FileReadException]) =
        val path = lowerPath(cp)
        lower.exists(path).map { found =>
            if !found then stampAbsent(cp)
            else
                lower.stat(path).map { stat =>
                    lower.isRegularFile(path).map { isFile =>
                        if isFile then lower.readBytes(path).map(bytes => stampFile(cp, stat, bytes))
                        else stampDir(cp, stat)
                    }
                }
        }
    end stampLower

    // Reconstruct a Path from segment parts (parallel to Path.parts decomposition).
    private def pathFrom(parts: Chunk[String])(using Frame): Path =
        if parts.isEmpty then Path()
        else parts.tail.foldLeft(Path(parts.head))((acc, seg) => acc / seg)

    // The lower-facing Path for a canonical key. Every lower call the overlay makes on a staged path
    // goes through this, so the lower receives an already-resolved path and its own link following
    // is a no-op. That is what keeps stage time and commit time addressing the same file.
    // Named apart from pathFrom because CanonicalPath erases to Chunk[String], so the two would
    // collide as overloads.
    private def lowerPath(cp: CanonicalPath)(using Frame): Path = pathFrom(cp.parts)

    // Guard a caller-supplied Long size/offset before narrowing to Int for the array-backed
    // in-overlay representation. The overlay holds each file in a Span[Byte] (max Int.MaxValue
    // bytes), so a value beyond the Int range can never address real content; fail loudly rather
    // than silently wrapping to a negative index, which would empty or corrupt the file.
    private def boundInt(path: Path, label: String, value: Long)(using Frame): Int < (S & Abort[FileIOException]) =
        if value < 0L || value > Int.MaxValue.toLong then
            Abort.fail(FileIOException(
                path,
                FileSystemOperation.Write,
                new IOException(s"$label $value exceeds the addressable Int range for the in-overlay file representation")
            ))
        else value.toInt

    private def boundReadInt(path: Path, label: String, value: Long)(using Frame): Int < (S & Abort[FileIOException]) =
        if value < 0L || value > Int.MaxValue.toLong then
            Abort.fail(FileIOException(
                path,
                FileSystemOperation.Read,
                new IOException(s"$label $value exceeds the addressable Int range for the in-overlay file representation")
            ))
        else value.toInt

    // --- Canonical resolution -------------------------------------------------------------------

    /** Resolves `path` to its canonical form, recording the result on first observation.
      *
      * The recorded form is reused for the overlay's lifetime. Resolving afresh on every access
      * would let a key change as the lower changes, splitting one logical file across two keys; a
      * resolution that no longer matches the lower is a commit-time conflict, handled like any other
      * stale observation, rather than a silent re-key.
      */
    private[kyo] def canonical(path: Path)(using
        Frame
    ): CanonicalPath < (S & Abort[
        FileOutsideRootException | FileInvalidPathException | FileAccessDeniedException | FileIOException
    ]) =
        withState { s =>
            s.resolved.get(path.parts) match
                case Present(cached) => CanonicalPath.wrap(cached)
                case Absent =>
                    resolveThroughLower(path).map { resolved =>
                        modifyPure { st =>
                            // Another fiber may have recorded it first; first observation wins.
                            if st.resolved.get(path.parts).isDefined then st
                            else st.copy(resolved = st.resolved.updated(path.parts, resolved.parts))
                        }.andThen(
                            stateGet.map(st => CanonicalPath.wrap(st.resolved.get(path.parts).getOrElse(resolved.parts)))
                        )
                    }
        }

    /** Resolves `path` as far as the lower can, without failing when it does not exist yet.
      *
      * Staging a write is the common case for a path the lower does not hold, so absence cannot be
      * an error here. [[FileSystem.Read.realPathPrefix]] carries that contract, which also keeps the
      * resolution free of any need to recover a failure: the lower may be reached through an effect
      * suspension whose failures are raised past this class entirely.
      */
    private def resolveThroughLower(path: Path)(using
        Frame
    ): Path < (S & Abort[
        FileOutsideRootException | FileInvalidPathException | FileAccessDeniedException | FileIOException
    ]) =
        lower.realPathPrefix(path)

    // True when the nearest strict ancestor carrying an upper entry shadows `cp`, either by
    // whiteout or by an opaque directory. The trie answers this from the descent it already makes,
    // without deriving a key per ancestor level.
    private def ancestorWhiteout(s: OverlayState, cp: CanonicalPath): Boolean =
        s.upper.nearestAncestorValue(cp).exists {
            case Upper.Whiteout     => true
            case Upper.OpaqueDir(_) => true
            case _                  => false
        }

    // --- Inspection ---

    def exists(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout) => false
                    case Present(_)              => true
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else
                            lower.exists(lp).map { found =>
                                if !found then stampAbsent(cp).andThen(false)
                                else
                                    lower.stat(lp).map { stat =>
                                        lower.isRegularFile(lp).map { isFile =>
                                            (if isFile then lower.readBytes(lp).map(bytes => stampFile(cp, stat, bytes))
                                             else stampDir(cp, stat)).andThen(true)
                                        }
                                    }
                            }
            }
        }

    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (S & Abort[FileReadException]) = exists(path)

    def isDirectory(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout)                       => false
                    case Present(Upper.OpaqueDir(_))                   => true
                    case Present(Upper.Entry(Path.Entry.Directory(_))) => true
                    case Present(Upper.Entry(Path.Entry.File(_, _)))   => false
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else
                            lower.exists(lp).map { found =>
                                if !found then stampAbsent(cp).andThen(false)
                                else
                                    lower.isDirectory(lp).map { isDir =>
                                        lower.stat(lp).map { stat =>
                                            (if isDir then stampDir(cp, stat)
                                             else lower.readBytes(lp).map(bytes => stampFile(cp, stat, bytes))).andThen(isDir)
                                        }
                                    }
                            }
            }
        }

    def isRegularFile(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout)                       => false
                    case Present(Upper.OpaqueDir(_))                   => false
                    case Present(Upper.Entry(Path.Entry.Directory(_))) => false
                    case Present(Upper.Entry(Path.Entry.File(_, _)))   => true
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else
                            lower.exists(lp).map { found =>
                                if !found then stampAbsent(cp).andThen(false)
                                else
                                    lower.isRegularFile(lp).map { isFile =>
                                        lower.stat(lp).map { stat =>
                                            (if isFile then lower.readBytes(lp).map(bytes => stampFile(cp, stat, bytes))
                                             else stampDir(cp, stat)).andThen(isFile)
                                        }
                                    }
                            }
            }
        }

    /** Reports whether `path` is a symbolic link in the lower.
      *
      * A path staged in the upper is never a link: [[OverlayFileSystem.Upper]] models staged files,
      * whiteouts, and opaque directories, and has no link variant. A path the overlay hides
      * (whiteout, or a whiteout on an ancestor) does not exist in the overlay's view and is
      * reported as not a link. Every other path falls through to the lower, matching the
      * fallthrough the content reads already use.
      *
      * The lower is asked about `path` as written, not about its canonical form. Canonicalization
      * resolves links, so the canonical form of a link is the target it points at and is never
      * itself a link; asking the lower about it would answer false for every path. Only the upper
      * lookups are canonical here, because those ask whether the overlay has staged over this file.
      */
    def isSymbolicLink(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            withState { s =>
                s.upper.get(cp) match
                    case Present(_) => false
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else lower.isSymbolicLink(path)
            }
        }

    /** Resolves `path` to its canonical location, following the lower's symbolic links.
      *
      * A path the overlay hides (whiteout, or a whiteout on an ancestor) fails with
      * [[FileNotFoundException]]: it does not exist in the overlay's view regardless of what the
      * lower still holds. An unstaged path defers to the lower, which owns both the link topology
      * and the absent-path policy, so a host lower resolves links and fails on a missing path while
      * a volatile lower returns the path unchanged.
      *
      * Deferring to the lower is what keeps [[Path.confinedTo]] working while a staged-write
      * overlay is installed. Returning the argument unchanged for every path would reduce that
      * check to a path-prefix comparison and defeat its symlink-escape defense.
      *
      * A staged path resolves too: the upper is keyed by canonical path, so the resolved name is
      * one this overlay reads back to the same entry. An unstaged path is delegated to the lower in
      * its already-resolved form, which leaves the lower's absent-path policy in force rather than
      * answering for it.
      */
    def realPath(path: Path)(using
        Frame
    ): Path < (S & Abort[
        FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
    ]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout) => Abort.fail(FileNotFoundException(path))
                    case Present(_)              => lp
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else lower.realPath(lp)
            }
        }

    /** Resolves as far as the lower can without failing on an absent path.
      *
      * Delegated rather than defaulted. The overlay contributes no links of its own, so the lower's
      * answer is already the whole answer, and the default would route through this class's
      * [[realPath]], whose failures can originate past any handler this class installs.
      */
    override def realPathPrefix(path: Path)(using
        Frame
    ): Path < (S & Abort[
        FileOutsideRootException | FileInvalidPathException | FileAccessDeniedException | FileIOException
    ]) =
        lower.realPathPrefix(path)

    // --- Reads ---

    def read(path: Path)(using Frame): String < (S & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, StandardCharsets.UTF_8))

    def read(path: Path, charset: Charset)(using Frame): String < (S & Abort[FileReadException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))

    def readBytes(path: Path)(using Frame): Span[Byte] < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(bytes, _))) => bytes
                    case Present(_)                                      => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.readBytes(lp).map { bytes =>
                                lower.stat(lp).map { stat =>
                                    stampFile(cp, stat, bytes).andThen(bytes)
                                }
                            }
            }
        }

    def readLines(path: Path)(using Frame): Chunk[String] < (S & Abort[FileReadException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (S & Abort[FileReadException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def size(path: Path)(using Frame): Long < (S & Abort[FileReadException]) = readBytes(path).map(_.size.toLong)

    def stat(path: Path)(using Frame): Path.PathStat < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(_, ps)))   => ps
                    case Present(Upper.Entry(Path.Entry.Directory(ps))) => ps
                    case Present(Upper.OpaqueDir(ps))                   => ps
                    case Present(Upper.Whiteout)                        => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.stat(lp).map { ps =>
                                lower.isRegularFile(lp).map { isFile =>
                                    (if isFile then lower.readBytes(lp).map(bytes => stampFile(cp, ps, bytes))
                                     else stampDir(cp, ps)).andThen(ps)
                                }
                            }
            }
        }

    override private[kyo] def stableIdentity(path: Path)(using Frame): Maybe[String] < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            withState { s =>
                // The journal records canonical paths, so the walk starts from the canonical form and
                // every comparison below is between canonical segments.
                var current = cp.parts
                var index   = s.journal.size - 1
                var result  = Maybe.empty[String]
                var done    = false
                while index >= 0 && !done do
                    s.journal(index) match
                        case WriteOp.Move(from, to, _) if current == to =>
                            current = from
                        case WriteOp.Copy(_, to, _, _) if current == to =>
                            result = Present(s"overlay-copy:$index")
                            done = true
                        case WriteOp.WriteFile(written, _, _) if current == written =>
                            result = Present(s"overlay-write:$index")
                            done = true
                        case WriteOp.WriteDirectory(written, _) if current == written =>
                            result = Present(s"overlay-directory:$index")
                            done = true
                        case WriteOp.Remove(removed) if current.startsWith(removed) =>
                            done = true
                        case _ => ()
                    end match
                    index -= 1
                end while
                if done then result else lower.stableIdentity(pathFrom(current))
            }
        }

    // --- Read handles ---

    def openRead(path: Path)(using Frame): Path.ReadHandle < (S & Abort[FileReadException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))

    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (S & Abort[FileReadException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))

    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
        Frame
    ): Path.WalkHandle < (S & Abort[FileReadException | FileStructureException]) =
        walkCollect(path, maxDepth).map { paths =>
            new Path.WalkHandle:
                private val it                             = paths.iterator
                def next()(using AllowUnsafe): Maybe[Path] = if it.hasNext then Maybe(it.next()) else Maybe.empty
                def close()(using AllowUnsafe): Unit       = ()
        }

    // Preorder traversal through the overlay view for openWalk.
    private def walkCollect(path: Path, maxDepth: Int)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
        if maxDepth <= 0 then Chunk.empty
        else
            list(path).map { children =>
                children.foldLeft[Chunk[Path] < (S & Abort[FileReadException | FileStructureException])](Chunk.empty) { (accKyo, child) =>
                    accKyo.map { acc =>
                        isDirectory(child).map { isDir =>
                            if isDir then walkCollect(child, maxDepth - 1).map(sub => acc.appended(child).appendedAll(sub))
                            else acc.appended(child)
                        }
                    }
                }
            }

    // --- List ---

    /** Lists the overlay's view of `path`, merging staged children over the lower's.
      *
      * Children are named under `path` as the caller wrote it, never under its canonical form. The
      * canonical path is a key for the staged state; a caller that listed a directory reached
      * through a link expects its children under that link, and handing back resolved names would
      * put paths the caller never mentioned into its results.
      */
    def list(path: Path)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout)                     => Abort.fail(FileNotFoundException(path))
                    case Present(Upper.Entry(Path.Entry.File(_, _))) => Abort.fail(FileNotADirectoryException(path))
                    case maybeOpaque =>
                        val isOpaque = maybeOpaque.exists {
                            case Upper.OpaqueDir(_) => true
                            case _                  => false
                        }

                        // One node read yields this directory's staged children; a flat map would scan
                        // every staged entry twice to answer the same two questions.
                        val upperChildren = s.upper.childValues(cp)

                        // Segments already covered in upper for this directory.
                        val upperSegs: Set[String] = upperChildren.map(_._1).toSet

                        // Visible upper children (non-Whiteout).
                        val upperVisible: List[Path] = upperChildren.collect {
                            case (seg, v) if v != Upper.Whiteout => path / seg
                        }.toList

                        val lowerKyo: Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
                            if isOpaque then Chunk.empty
                            else
                                lower.exists(lp).map { exists =>
                                    if !exists then Chunk.empty[Path]
                                    else
                                        lower.stat(lp).map { stat =>
                                            stampDir(cp, stat).andThen {
                                                lower.list(lp).map { children =>
                                                    // Drop lower children that have any upper entry (Entry, Whiteout, or OpaqueDir),
                                                    // and re-express the survivors under the caller's path rather than the
                                                    // canonical one the lower was asked about.
                                                    children.collect {
                                                        case c if !upperSegs.contains(c.parts.last) => path / c.parts.last
                                                    }
                                                }
                                            }
                                        }
                                }

                        lowerKyo.map { lowerPaths =>
                            val combined = (lowerPaths.toSeq ++ upperVisible).distinctBy(_.parts).sortBy(_.parts.last)
                            Chunk.from(combined)
                        }
            }
        }

    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
        list(path).map(_.filter(p => glob.matches(Chunk(p.parts.last), caseSensitivity)))

    // --- Writes ---

    private def ensureWriteParent(path: Path, options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException]) =
        ensureWriteParent(path, options, path)

    private def ensureWriteParent(path: Path, options: Path.WriteOptions, errorPath: Path)(using
        Frame
    ): Unit < (S & Abort[FileWriteException]) =
        path.parent match
            case Absent => ()
            case Present(parent) =>
                Abort.run[FileReadException] {
                    exists(parent).map { found =>
                        if found then isDirectory(parent).map(isDir => (true, isDir))
                        else (false, false)
                    }
                }.map {
                    case Result.Success((true, true))                         => ()
                    case Result.Success((true, false))                        => Abort.fail(FileNotFoundException(errorPath))
                    case Result.Success((false, _)) if !options.createFolders => Abort.fail(FileNotFoundException(errorPath))
                    case Result.Success((false, _)) =>
                        ensureWriteParent(parent, options, errorPath).andThen {
                            canonical(parent).map { pcp =>
                                stampAbsent(pcp).andThen {
                                    val stat = Path.PathStat(0L, 0L)
                                    modifyPure { current =>
                                        val opaque =
                                            current.upper.get(pcp).contains(Upper.Whiteout) || ancestorWhiteout(current, pcp)
                                        val entry = if opaque then Upper.OpaqueDir(stat) else Upper.Entry(Path.Entry.Directory(stat))
                                        current.copy(
                                            upper = current.upper.updated(pcp, entry),
                                            journal = current.journal.appended(WriteOp.WriteDirectory(pcp.parts, opaque))
                                        )
                                    }
                                }
                            }
                        }
                    case Result.Failure(error: FileWriteException) => Abort.fail(error)
                    case Result.Panic(error)                       => Abort.panic(error)
                }
    end ensureWriteParent

    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException]) =
        writeBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), options)

    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException]) =
        val stat = Path.PathStat(0L, value.size.toLong)
        ensureWriteParent(path, options).andThen {
            canonical(path).map { cp =>
                modifyPure { s =>
                    s.copy(
                        upper = s.upper.updated(cp, Upper.Entry(Path.Entry.File(value, stat))),
                        journal = s.journal.appended(WriteOp.WriteFile(cp.parts, value, stat))
                    )
                }
            }
        }
    end writeBytes

    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException]) =
        write(path, value.mkString("", "\n", "\n"), options)

    def append(path: Path, value: String, options: Path.WriteOptions)(using
        Frame
    ): Unit < (S & Abort[FileReadException | FileWriteException]) =
        appendBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), options)

    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (S & Abort[FileReadException | FileWriteException]) =
        ensureWriteParent(path, options).andThen(canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(existing, _))) =>
                        // Already in upper: concatenate without consulting lower, no stamp needed.
                        val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                        val stat   = Path.PathStat(0L, merged.size.toLong)
                        modifyPure { cur =>
                            cur.copy(
                                upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(merged, stat))),
                                journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, merged, stat))
                            )
                        }
                    case _ =>
                        // Not in upper: read lower (stamp on first observation), then stage.
                        // Ancestor Whiteout hides lower content; treat as absent, start fresh.
                        lower.exists(lp).map { lowerFound =>
                            val found = lowerFound && !ancestorWhiteout(s, cp)
                            val readLower: Span[Byte] < (S & Abort[FileReadException]) =
                                if !found then stampAbsent(cp).andThen(Span.empty[Byte])
                                else
                                    lower.readBytes(lp).map { existing =>
                                        lower.stat(lp).map { stat => stampFile(cp, stat, existing).andThen(existing) }
                                    }
                            readLower.map { existing =>
                                val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                                val stat   = Path.PathStat(0L, merged.size.toLong)
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(merged, stat))),
                                        journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, merged, stat))
                                    )
                                }
                            }
                        }
            }
        })

    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (S & Abort[FileReadException | FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)

    def truncate(path: Path, size: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(bytes, _))) =>
                        boundInt(path, "truncate size", size).map { sz =>
                            val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(sz))
                            val stat = Path.PathStat(0L, kept.size.toLong)
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(kept, stat))),
                                    journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, kept, stat))
                                )
                            }
                        }
                    case Present(_) => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.readBytes(lp).map { bytes =>
                                lower.stat(lp).map { lStat =>
                                    stampFile(cp, lStat, bytes).andThen {
                                        boundInt(path, "truncate size", size).map { sz =>
                                            val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(sz))
                                            val stat = Path.PathStat(0L, kept.size.toLong)
                                            modifyPure { cur =>
                                                cur.copy(
                                                    upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(kept, stat))),
                                                    journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, kept, stat))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
            }
        }

    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(bytes, stat))) =>
                        val ns = stat.copy(lastModifiedMs = epochMs)
                        modifyPure { cur =>
                            cur.copy(
                                upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(bytes, ns))),
                                journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, bytes, ns))
                            )
                        }
                    case Present(Upper.Entry(Path.Entry.Directory(stat))) =>
                        val ns = stat.copy(lastModifiedMs = epochMs)
                        modifyPure { cur =>
                            cur.copy(
                                upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.Directory(ns))),
                                journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = false))
                            )
                        }
                    case Present(Upper.OpaqueDir(stat)) =>
                        val ns = stat.copy(lastModifiedMs = epochMs)
                        modifyPure { cur =>
                            cur.copy(
                                upper = cur.upper.updated(cp, Upper.OpaqueDir(ns)),
                                journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = true))
                            )
                        }
                    case Present(Upper.Whiteout) => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.stat(lp).map { stat =>
                                lower.isRegularFile(lp).map { isFile =>
                                    if isFile then
                                        // The bytes read precedes stampFile so the read-set entry and the staged
                                        // upper entry share the same observed bytes (no double read).
                                        lower.readBytes(lp).map { bytes =>
                                            stampFile(cp, stat, bytes).andThen {
                                                val ns = stat.copy(lastModifiedMs = epochMs)
                                                modifyPure { cur =>
                                                    cur.copy(
                                                        upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(bytes, ns))),
                                                        journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, bytes, ns))
                                                    )
                                                }
                                            }
                                        }
                                    else
                                        stampDir(cp, stat).andThen {
                                            val ns = stat.copy(lastModifiedMs = epochMs)
                                            modifyPure { cur =>
                                                cur.copy(
                                                    upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.Directory(ns))),
                                                    journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = false))
                                                )
                                            }
                                        }
                                }
                            }
            }
        }

    def mkDir(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.OpaqueDir(_))                   => () // already opaque dir
                    case Present(Upper.Entry(Path.Entry.Directory(_))) => () // already a dir in upper
                    case _                                             =>
                        // If lower has a directory at this path, create OpaqueDir (hides lower children).
                        // If lower has a file or absent, create a regular directory entry.
                        lower.exists(lp).map { exists =>
                            if !exists then
                                stampAbsent(cp).andThen {
                                    val st = Path.PathStat(0L, 0L)
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.Directory(st))),
                                            journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = false))
                                        )
                                    }
                                }
                            else
                                lower.stat(lp).map { stat =>
                                    lower.isDirectory(lp).map { isDir =>
                                        (if isDir then stampDir(cp, stat)
                                         else lower.readBytes(lp).map(bytes => stampFile(cp, stat, bytes))).andThen {
                                            // An existing lower dir (or file) gets OpaqueDir, hiding its children.
                                            val st = if isDir then stat else Path.PathStat(0L, 0L)
                                            modifyPure { cur =>
                                                cur.copy(
                                                    upper = cur.upper.updated(cp, Upper.OpaqueDir(st)),
                                                    journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = true))
                                                )
                                            }
                                        }
                                    }
                                }
                        }
            }
        }

    def mkFile(path: Path)(using Frame): Unit < (S & Abort[FileWriteException | FileStructureException]) =
        writeBytes(path, Span.empty[Byte], Path.WriteOptions())

    def move(
        from: Path,
        to: Path,
        options: Path.MoveOptions
    )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteParent(to, Path.WriteOptions(options.createFolders)).andThen(
            canonical(from).map { fromCp =>
                canonical(to).map { toCp =>
                    resolveEntry(from).map { resolved =>
                        checkMoveTarget(to, options.replace) {
                            resolved match
                                case file: Path.Entry.File =>
                                    // A file move keeps single-node semantics: whiteout the source, stage the
                                    // resolved file at the target, and journal one source-independent Move op.
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(fromCp, Upper.Whiteout).updated(toCp, Upper.Entry(file)),
                                            journal = cur.journal.appended(WriteOp.Move(fromCp.parts, toCp.parts, file))
                                        )
                                    }
                                case _: Path.Entry.Directory =>
                                    // A directory move relocates the entire subtree: every descendant visible in
                                    // the overlay view (upper-staged plus lower-only) is materialized under `to`,
                                    // and the whole source subtree is whiteouted so it is fully gone.
                                    collectSubtree(from).map { nodes =>
                                        modifyPure { cur =>
                                            val (upperT, journalT) = stageSubtree(cur.upper, cur.journal, fromCp, toCp, nodes)
                                            // Whiteout the source dir and every upper descendant of it (a direct
                                            // upper Entry outranks an ancestor whiteout, so each must be marked);
                                            // the Remove op recursively drops the source subtree from lower on commit.
                                            val srcKeys = cur.upper.descendantValues(fromCp).map(_._1).toList
                                            val upperW =
                                                srcKeys.foldLeft(upperT.updated(fromCp, Upper.Whiteout))((u, k) =>
                                                    u.updated(k, Upper.Whiteout)
                                                )
                                            cur.copy(upper = upperW, journal = journalT.appended(WriteOp.Remove(fromCp.parts)))
                                        }
                                    }
                        }
                    }
                }
            }
        )

    def copy(
        from: Path,
        to: Path,
        options: Path.CopyOptions
    )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteParent(to, Path.WriteOptions(options.createFolders)).andThen(
            canonical(from).map { fromCp =>
                canonical(to).map { toCp =>
                    resolveEntry(from).map { resolved =>
                        checkMoveTarget(to, options.replace) {
                            resolved match
                                case file: Path.Entry.File =>
                                    val copied =
                                        if options.copyAttributes then file
                                        else file.copy(stat = Path.PathStat(0L, file.stat.sizeBytes))
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(toCp, Upper.Entry(copied)),
                                            journal = cur.journal.appended(
                                                WriteOp.Copy(fromCp.parts, toCp.parts, copied, options.copyAttributes)
                                            )
                                        )
                                    }
                                case _: Path.Entry.Directory =>
                                    // A directory copy materializes the entire subtree under `to`, leaving the
                                    // source intact (no whiteout, no Remove op).
                                    collectSubtree(from).map { nodes =>
                                        modifyPure { cur =>
                                            val (upperT, journalT) = stageSubtree(
                                                cur.upper,
                                                cur.journal,
                                                fromCp,
                                                toCp,
                                                nodes,
                                                options.copyAttributes
                                            )
                                            cur.copy(upper = upperT, journal = journalT)
                                        }
                                    }
                        }
                    }
                }
            }
        )

    // Shared target-existence guard for move/copy. Existence is computed through the effective
    // overlay view so direct and ancestor whiteouts hide lower entries consistently with reads.
    private def checkMoveTarget[A](to: Path, replace: Path.Replace)(
        body: => A < (S & Abort[FileReadException | FileStructureException])
    )(using Frame): A < (S & Abort[FileReadException | FileStructureException]) =
        exists(to).map { targetExists =>
            if targetExists && replace == Path.Replace.Never then Abort.fail(FileAlreadyExistsException(to))
            else body
        }

    // Materialize a preorder subtree (root-first) captured by collectSubtree under `to`. Each node
    // becomes an upper entry plus a Copy op carrying its captured content and metadata, so replay is
    // source-independent and can honor copyAttributes after target materialization.
    //
    // Both endpoints arrive already resolved and each node's key is built by extending them with the
    // node's suffix, rather than resolving each descendant on its own. A descendant staged into this
    // overlay has no lower entry to resolve against, so resolving it independently could land it in a
    // different key space than the parent it was collected under.
    private def stageSubtree(
        upper: UpperTrie,
        journal: Chunk[WriteOp],
        from: CanonicalPath,
        to: CanonicalPath,
        nodes: Chunk[(Chunk[String], Path.Entry)],
        copyAttributes: Boolean = true
    ): (UpperTrie, Chunk[WriteOp]) =
        nodes.foldLeft((upper, journal)) { case ((u, j), (suffix, entry)) =>
            val sourceCp = suffix.foldLeft(from)((cp, seg) => cp.append(seg))
            val targetCp = suffix.foldLeft(to)((cp, seg) => cp.append(seg))
            entry match
                case Path.Entry.File(bytes, stat) =>
                    val targetStat = if copyAttributes then stat else Path.PathStat(0L, stat.sizeBytes)
                    (
                        u.updated(targetCp, Upper.Entry(Path.Entry.File(bytes, targetStat))),
                        j.appended(WriteOp.Copy(sourceCp.parts, targetCp.parts, Path.Entry.File(bytes, targetStat), copyAttributes))
                    )
                case Path.Entry.Directory(stat) =>
                    val targetStat = if copyAttributes then stat else Path.PathStat(0L, stat.sizeBytes)
                    (
                        u.updated(targetCp, Upper.OpaqueDir(targetStat)),
                        j.appended(WriteOp.Copy(sourceCp.parts, targetCp.parts, Path.Entry.Directory(targetStat), copyAttributes))
                    )
            end match
        }

    // Enumerate `root` and every descendant through the overlay view (upper staged entries unioned
    // with lower entries, minus whiteouts), preorder with parents before children so replay can
    // create each directory before its contents. Each element carries the descendant's segments
    // relative to `root` (empty for `root` itself) and its resolved Path.Entry.
    //
    // Relative rather than absolute so the caller decides which key space the subtree lands in:
    // stageSubtree extends the resolved source and target with the same suffix, and an absolute key
    // here would have to be canonicalized a second time to be usable.
    private def collectSubtree(root: Path)(using
        Frame
    ): Chunk[(Chunk[String], Path.Entry)] < (S & Abort[FileReadException | FileStructureException]) =
        resolveEntry(root).map {
            case file: Path.Entry.File =>
                Chunk((Chunk.empty[String], file: Path.Entry))
            case dir: Path.Entry.Directory =>
                list(root).map { children =>
                    children.foldLeft[Chunk[(Chunk[String], Path.Entry)] < (S & Abort[FileReadException | FileStructureException])](
                        Chunk((Chunk.empty[String], dir: Path.Entry))
                    ) { (accKyo, child) =>
                        accKyo.map { acc =>
                            val seg = child.parts.last
                            collectSubtree(child).map { sub =>
                                acc.appendedAll(sub.map((suffix, entry) => (Chunk(seg).concat(suffix), entry)))
                            }
                        }
                    }
                }
        }

    // Resolve a source path to a Path.Entry, checking upper first then lower.
    // Records a stamp when reading from lower. Fails if source is Whiteout or absent.
    private def resolveEntry(path: Path)(using Frame): Path.Entry < (S & Abort[FileReadException | FileStructureException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(e))        => e
                    case Present(Upper.OpaqueDir(stat)) => Path.Entry.Directory(stat): Path.Entry
                    case Present(Upper.Whiteout)        => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.exists(lp).map { found =>
                                if !found then Abort.fail(FileNotFoundException(path))
                                else
                                    lower.isRegularFile(lp).map { isFile =>
                                        if isFile then
                                            lower.readBytes(lp).map { bytes =>
                                                lower.stat(lp).map { stat =>
                                                    stampFile(cp, stat, bytes).andThen {
                                                        Path.Entry.File(bytes, stat): Path.Entry
                                                    }
                                                }
                                            }
                                        else
                                            lower.stat(lp).map { stat =>
                                                stampDir(cp, stat).andThen {
                                                    Path.Entry.Directory(stat): Path.Entry
                                                }
                                            }
                                    }
                            }
            }
        }

    def remove(path: Path)(using Frame): Boolean < (S & Abort[FileReadException | FileStructureException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout) => false
                    case Present(_) =>
                        modifyPure { cur =>
                            cur.copy(
                                upper = cur.upper.updated(cp, Upper.Whiteout),
                                journal = cur.journal.appended(WriteOp.Remove(cp.parts))
                            )
                        }.andThen(true)
                    case Absent =>
                        lower.exists(lp).map { found =>
                            if !found then stampAbsent(cp).andThen(false)
                            else
                                stampLower(cp).andThen {
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(cp, Upper.Whiteout),
                                            journal = cur.journal.appended(WriteOp.Remove(cp.parts))
                                        )
                                    }.andThen(true)
                                }
                        }
            }
        }

    def removeExisting(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))

    def removeAll(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException]) =
        remove(path).unit

    // --- Write handle ---

    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (S & Abort[FileReadException | FileWriteException]) =
        ensureWriteParent(path, options).andThen(canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                val upperSeed: Maybe[Span[Byte]] =
                    if append then
                        s.upper.get(cp) match
                            case Present(Upper.Entry(Path.Entry.File(bytes, _))) => Present(bytes)
                            case _                                               => Absent
                    else Present(Span.empty[Byte])

                upperSeed match
                    case Present(seed) => mkWriteHandle(cp, seed)
                    case Absent        =>
                        // append mode with no upper entry: seed from lower
                        lower.exists(lp).map { found =>
                            if !found then stampAbsent(cp).andThen(mkWriteHandle(cp, Span.empty[Byte]))
                            else
                                lower.readBytes(lp).map { bytes =>
                                    lower.stat(lp).map { stat =>
                                        stampFile(cp, stat, bytes).andThen(mkWriteHandle(cp, bytes))
                                    }
                                }
                        }
                end match
            }
        })

    // The key is captured here rather than resolved in finish(), which runs unsafely and cannot
    // suspend to consult the lower.
    private def mkWriteHandle(cp: CanonicalPath, seed: Span[Byte]): Path.WriteHandle =
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
                val _ = state.unsafe.updateAndGet { cur =>
                    cur.copy(
                        upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(bytes, stat))),
                        journal = cur.journal.appended(WriteOp.WriteFile(cp.parts, bytes, stat))
                    )
                }
                ()
            end finish
            def close()(using AllowUnsafe): Unit = () // bytes dropped if finish() not called

    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (S & Abort[FileWriteException]) =
        // Unsafe: pumps the write handle's internal buffer; no overlay state involved.
        // asInstanceOf: Sync.Unsafe.defer gives `< (Sync & Abort)` but S = Sync at the
        // only instantiation site so this is a no-op cast at runtime.
        Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeBytes(chunk))).asInstanceOf[Unit < (S & Abort[FileWriteException])]

    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (S & Abort[FileWriteException]) =
        // Unsafe: same as writeChunk.
        Sync.Unsafe.defer(Abort.get[FileWriteException](handle.writeString(
            value,
            charset
        ))).asInstanceOf[Unit < (S & Abort[FileWriteException])]

    // --- Temp dir ---

    // The canonical key for a path this overlay has already resolved, read from the pinned
    // resolutions rather than from the lower. For the unsafe handle paths, which cannot suspend.
    // A path with no recorded resolution has no staged state to address, so its own segments are
    // the key any staged state would have been filed under.
    private def canonicalPure(s: OverlayState, path: Path): CanonicalPath =
        s.resolved.get(path.parts) match
            case Present(resolved) => CanonicalPath.wrap(resolved)
            case Absent            => CanonicalPath.wrap(path.parts)

    override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
        new Path.TempFileHandle:
            def path: Path = temporary
            def remove()(using AllowUnsafe): Unit =
                val _ = state.unsafe.updateAndGet { cur =>
                    val cp    = canonicalPure(cur, temporary)
                    val parts = cp.parts
                    val journal = cur.journal.filter {
                        case WriteOp.WriteFile(path, _, _)   => path != parts
                        case WriteOp.WriteDirectory(path, _) => path != parts
                        case WriteOp.Remove(path)            => path != parts
                        case WriteOp.Move(_, to, _)          => to != parts
                        case WriteOp.Copy(_, to, _, _)       => to != parts
                    }
                    cur.copy(
                        upper = cur.upper.removed(cp),
                        journal = journal,
                        readSet = cur.readSet.removed(cp)
                    )
                }
                ()
            end remove

    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (S & Sync & Abort[FileStructureException]) =
        // Unsafe: monotone counter increment for a unique staged directory name.
        //
        // Not a hash of `prefix`: that hashes the argument, and Path.tempDir defaults the prefix to
        // a literal, so interned literals make repeated calls collide. Each handle's removal deletes
        // the shared upper entry, so a collision has the first finalizer destroy the second caller's
        // directory. The counter is per instance and gaps in it are harmless.
        Sync.Unsafe.defer(uniqueSeq.unsafe.getAndIncrement()).map { seq =>
            val dir  = Path(prefix + "-overlay-" + instanceSeed + "-" + seq.toHexString)
            val stat = Path.PathStat(0L, 0L)
            canonical(dir).map { cp =>
                modifyPure { cur =>
                    cur.copy(
                        upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.Directory(stat))),
                        journal = cur.journal.appended(WriteOp.WriteDirectory(cp.parts, opaque = false))
                    )
                }.andThen {
                    new Path.TempDirHandle:
                        def path: Path = dir
                        // Unsafe: removes the upper entry; never touches the host filesystem.
                        def remove()(using AllowUnsafe): Unit =
                            val _ = state.unsafe.updateAndGet(cur => cur.copy(upper = cur.upper.removed(cp)))
                            ()
                }
            }
        }
    end tempDir

    // --- Positioned channel (reads fall through to lower; writes stage in the upper
    // layer, replayed on commit like every other staged write) ---

    private def ensureReadChannelTarget(path: Path)(using Frame): Unit < (S & Abort[FileReadException]) =
        exists(path).map(found => if found then () else Abort.fail(FileNotFoundException(path)))

    private def ensureWriteChannelTarget(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Unit < (S & Sync & Abort[FileWriteException | FileStructureException]) =
        Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Channel, e)))(exists(path)).map {
            found =>
                open match
                    case FileSystem.WriteOpen.CreateNew if found => Abort.fail(FileAlreadyExistsException(path))
                    case FileSystem.WriteOpen.CreateNew =>
                        val stat = Path.PathStat(0L, 0L)
                        ensureWriteParent(path, Path.WriteOptions()).andThen {
                            canonical(path).map { cp =>
                                modify { current =>
                                    current.upper.get(cp) match
                                        case Present(Upper.Entry(_) | Upper.OpaqueDir(_)) => Result.fail(FileAlreadyExistsException(path))
                                        case _ =>
                                            Result.succeed((
                                                current.copy(
                                                    upper =
                                                        current.upper.updated(cp, Upper.Entry(Path.Entry.File(Span.empty[Byte], stat))),
                                                    journal = current.journal.appended(WriteOp.WriteFile(cp.parts, Span.empty[Byte], stat))
                                                ),
                                                ()
                                            ))
                                }
                            }
                        }
                    case FileSystem.WriteOpen.Existing if !found => Abort.fail(FileNotFoundException(path))
                    case FileSystem.WriteOpen.Create if !found   => writeBytes(path, Span.empty[Byte], Path.WriteOptions())
                    case _                                       => ()
        }

    final private class ChannelOps(path: Path):
        private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
        private def readOpen(using Frame): Unit < Abort[FileReadException] =
            if closed.get() then Abort.fail(FileIOException(path, FileSystemOperation.Read, new java.io.IOException("channel is closed")))
            else ()
        private def writeOpen(using Frame): Unit < Abort[FileWriteException] =
            if closed.get() then Abort.fail(FileIOException(path, FileSystemOperation.Write, new java.io.IOException("channel is closed")))
            else ()
        def close(): Unit =
            closed.compareAndSet(false, true)
            ()
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (S & Abort[FileReadException]) =
            readOpen.andThen {
                if length < 0 then
                    Abort.fail(FileIOException(path, FileSystemOperation.Read, new java.io.IOException("negative channel read length")))
                else boundReadInt(path, "channel read offset", position).map(p => readBytes(path).map(_.drop(p).take(length)))
            }
        def size(using Frame): Long < (S & Abort[FileReadException]) = readOpen.andThen(OverlayFileSystem.this.size(path))
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (S & Abort[FileWriteException]) =
            writeOpen.andThen {
                boundInt(path, "channel write offset", position).map { p =>
                    Abort.recover[FileReadException](e =>
                        Abort.fail(FileIOException(path, FileSystemOperation.Write, e))
                    )(readBytes(path)).map { existing =>
                        val padded  = if p <= existing.size then existing else existing ++ Span.fill[Byte](p - existing.size)(0.toByte)
                        val spliced = padded.take(p) ++ bytes ++ padded.drop(p + bytes.size)
                        writeBytes(path, spliced, Path.WriteOptions())
                    }
                }
            }
        def sync(metadata: Boolean)(using Frame): Unit < (S & Abort[FileWriteException]) = writeOpen
        def truncate(size: Long)(using Frame): Unit < (S & Abort[FileWriteException]) =
            writeOpen.andThen {
                if size < 0L then
                    Abort.fail(FileIOException(path, FileSystemOperation.Write, new java.io.IOException("negative channel truncate size")))
                else
                    Abort.recover[FileReadException](e =>
                        Abort.fail(FileIOException(path, FileSystemOperation.Write, e))
                    )(OverlayFileSystem.this.truncate(path, size))
            }
    end ChannelOps

    private def readChannel(ops: ChannelOps): Path.ReadChannel[S & Sync] = new Path.ReadChannel[S & Sync]:
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (S & Sync & Abort[FileReadException]) =
            ops.readAt(position, length)
        def size(using Frame): Long < (S & Sync & Abort[FileReadException]) = ops.size
    private def writeChannel(ops: ChannelOps): Path.WriteChannel[S & Sync] = new Path.WriteChannel[S & Sync]:
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (S & Sync & Abort[FileWriteException]) =
            ops.writeAt(position, bytes)
        def sync(metadata: Boolean)(using Frame): Unit < (S & Sync & Abort[FileWriteException]) = ops.sync(metadata)
        def truncate(size: Long)(using Frame): Unit < (S & Sync & Abort[FileWriteException])    = ops.truncate(size)
    private def readWriteChannel(ops: ChannelOps): Path.ReadWriteChannel[S & Sync] = new Path.ReadWriteChannel[S & Sync]:
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (S & Sync & Abort[FileReadException]) =
            ops.readAt(position, length)
        def size(using Frame): Long < (S & Sync & Abort[FileReadException]) = ops.size
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (S & Sync & Abort[FileWriteException]) =
            ops.writeAt(position, bytes)
        def sync(metadata: Boolean)(using Frame): Unit < (S & Sync & Abort[FileWriteException]) = ops.sync(metadata)
        def truncate(size: Long)(using Frame): Unit < (S & Sync & Abort[FileWriteException])    = ops.truncate(size)

    private def scopedChannel[A](ops: ChannelOps, channel: A)(using Frame): A < (Sync & Scope) =
        Scope.acquireRelease(ops)(_ => Sync.defer(ops.close())).map(_ => channel)
    private def unscopedChannel[A](ops: ChannelOps, channel: A)(using Frame): (A, () => Unit < (S & Sync)) =
        val release: () => Unit < (S & Sync) = () => Sync.defer(ops.close())
        (channel, release)

    private def unscopedWriteChannel(ops: ChannelOps, channel: Path.WriteChannel[S & Sync])(using
        Frame
    ): (Path.WriteChannel[S & Sync], () => Unit < (S & Sync), Path.ChannelCloseHandle) =
        val close = new Path.ChannelCloseHandle:
            def close()(using AllowUnsafe): Unit = ops.close()
        (channel, () => Sync.defer(ops.close()), close)
    end unscopedWriteChannel

    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[S & Sync] < (S & Sync & Scope & Abort[FileReadException]) =
        ensureReadChannelTarget(path).map { _ =>
            val ops = new ChannelOps(path)
            scopedChannel(ops, readChannel(ops))
        }
    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[S & Sync] < (S & Sync & Scope & Abort[FileWriteException | FileStructureException]) =
        ensureWriteChannelTarget(path, open).map { _ =>
            val ops = new ChannelOps(path)
            scopedChannel(ops, writeChannel(ops))
        }
    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[S & Sync] < (S & Sync & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteChannelTarget(path, open).map { _ =>
            val ops = new ChannelOps(path)
            scopedChannel(ops, readWriteChannel(ops))
        }
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[S & Sync], () => Unit < (S & Sync)) < (S & Sync & Abort[FileReadException]) =
        ensureReadChannelTarget(path).map { _ =>
            val ops = new ChannelOps(path); unscopedChannel(ops, readChannel(ops))
        }
    private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.WriteChannel[S & Sync], () => Unit < (S & Sync), Path.ChannelCloseHandle) <
        (S & Sync & Abort[FileWriteException | FileStructureException]) =
        ensureWriteChannelTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); unscopedWriteChannel(ops, writeChannel(ops))
        }
    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (
        Path.ReadWriteChannel[S & Sync],
        () => Unit < (S & Sync)
    ) < (S & Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ensureWriteChannelTarget(path, open).map { _ =>
            val ops = new ChannelOps(path); unscopedChannel(ops, readWriteChannel(ops))
        }

    /** The volatile staging layer has no persistence boundary. */
    def syncDirectory(path: Path)(using Frame): Unit < (S & Abort[FileWriteException]) = ()

    override def siblingTemporary(target: Path)(using
        Frame
    ): Path.TempFileHandle < (S & Sync & Abort[FileWriteException | FileStructureException]) =
        FileSystem.siblingTemporary[S](this, target)

    def durableReplace(target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (S & Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
        FileSystem.durableReplace[S](this, target, bytes)

    // --- Advisory lock (delegates to lower; the overlay layer has no separate exclusion
    // state, so contention is visible identically whether observed through the overlay or
    // directly on the lower) ---

    def tryLock(path: Path, mode: Path.LockMode)(using
        Frame
    ): Maybe[Path.Lock] < (S & Sync & Async & Scope & Abort[FileReadException | FileLockException]) =
        lower.tryLock(path, mode)

    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
        Frame
    ): Path.Lock < (S & Async & Scope & Abort[FileReadException | FileLockException]) =
        lower.lock(path, mode, wait)

    // --- Commit / discard ---

    private def terminate(action: String)(using Frame): Unit < (Sync & Abort[CommitConflict]) =
        active.compareAndSet(true, false).map { claimed =>
            if claimed then ()
            else Abort.fail(FileSystem.StagedChanges.AlreadyTerminated(action))
        }

    private[kyo] def discardOnScopeExit(using Frame): Unit < Sync =
        active.compareAndSet(true, false).map { claimed =>
            if claimed then state.set(OverlayState.empty) else ()
        }

    def discard(using Frame): Unit < (S & Sync & Abort[FileSystem.StagedChanges.TerminalState]) =
        active.compareAndSet(true, false).map { claimed =>
            if claimed then Abort.run[FileSystemException](modifyPure(_ => OverlayState.empty)).map(_ => ())
            else Abort.fail(FileSystem.StagedChanges.AlreadyTerminated("discard"))
        }

    // Validate the read-set: for each recorded ancestor entry, re-read lower and compare.
    //
    // Conflict.path is the canonical path, which is the file the commit would actually write, not
    // necessarily the name the caller used to stage it. Reporting the name as written would point a
    // caller at a path whose contents did not diverge.
    // Each conflict is paired with the key it was found under so commitWith can apply a resolution
    // to the staged state without reconstructing a key from the reported path.
    private def validate(s: OverlayState)(using Frame): Chunk[(CanonicalPath, Conflict)] < (S & Abort[FileSystemException]) =
        s.readSet.entries.foldLeft[Chunk[(CanonicalPath, Conflict)] < (S & Abort[FileSystemException])](Chunk.empty) {
            case (accKyo, (cp, ancestor)) =>
                accKyo.map { acc =>
                    val path = lowerPath(cp)
                    lower.exists(path).map { found =>
                        if !found then
                            if ancestor.isEmpty then acc
                            else
                                val conflict =
                                    Conflict(path, ancestor, s.upper.get(cp).fold[Maybe[Path.Entry]](Absent)(upperToEntry), Absent)
                                acc.appended((cp, conflict))
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
                                        val oursEntry = s.upper.get(cp).fold[Maybe[Path.Entry]](Absent)(upperToEntry)
                                        val liveEntryKyo: Maybe[Path.Entry] < (S & Abort[FileSystemException]) =
                                            if isFile then
                                                lower.readBytes(path).map { bytes =>
                                                    (Present(Path.Entry.File(bytes, liveStat)): Maybe[Path.Entry])
                                                }
                                            else Present[Path.Entry](Path.Entry.Directory(liveStat))
                                        liveEntryKyo.map { liveEntry =>
                                            val conflict = Conflict(path, ancestor, oursEntry, liveEntry)
                                            acc.appended((cp, conflict))
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
    private def runHook(hook: () => Unit)(using Frame): Unit < (S & Abort[FileSystemException]) =
        // Unsafe: hook may throw to halt the commit; exception propagates as a panic.
        Sync.Unsafe.defer(hook()).asInstanceOf[Unit < (S & Abort[FileSystemException])]

    private def runHookKN(hook: (Int, Int) => Unit, k: Int, n: Int)(using Frame): Unit < (S & Abort[FileSystemException]) =
        // Unsafe: same halt-on-throw contract as runHook.
        Sync.Unsafe.defer(hook(k, n)).asInstanceOf[Unit < (S & Abort[FileSystemException])]

    // Writes file content to `path` via the openWrite/writeChunk/finish/close path so the
    // bytes are durable (finish() calls fsync) before the caller proceeds. Used for staged
    // files in stageOps so that if the process crashes after staging but before the intent
    // log is written, the staged content is either fully present or absent -- never partial.
    private def stageDurableFile(path: Path, bytes: Span[Byte])(using Frame): Unit < (S & Abort[FileSystemException]) =
        lower.openWrite(path, false, Path.WriteOptions(createFolders = false)).map { handle =>
            lower.writeChunk(handle, Chunk.from(bytes.toArray)).andThen(
                // Unsafe: finish() fsyncs so staged bytes are durable before returning;
                // close() releases the channel. Without finish(), a crash mid-write leaves
                // no sealed file: recovery's existence check would see a partial artifact.
                Sync.Unsafe.defer { handle.finish(); handle.close() }
                    .asInstanceOf[Unit < (S & Abort[FileSystemException])]
            )
        }

    // Stage file content for every WriteOp that carries bytes. Writes each as "e<i>.dat"
    // inside stagingDir via the durable write path so staged bytes survive power loss.
    // Directory ops and removes have no staged file.
    private def stageOps(stagingDir: Path, journal: Chunk[WriteOp])(using Frame): Unit < (S & Abort[FileSystemException]) =
        journal.zipWithIndex.foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (acc, (op, i)) =>
            acc.andThen {
                op match
                    case WriteOp.WriteFile(_, bytes, _) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case WriteOp.Move(_, _, Path.Entry.File(bytes, _)) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case WriteOp.Copy(_, _, Path.Entry.File(bytes, _), _) =>
                        stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case _ => ()
            }
        }

    // Encodes the journal with WriteOpLog and writes it to "intent.kyo" in the staging dir
    // via the openWrite/finish/close path. finish() is the completion boundary: its absence
    // (crash during write) leaves the file absent or partial, so recovery skips. A plain
    // writeBytes call lacks the two-phase open/finish contract; a crash mid-write leaves no sealed log.
    private def writeIntentLog(stagingDir: Path, journal: Chunk[WriteOp])(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        val logBytes = WriteOpLog.encode(journal)
        lower.openWrite(stagingDir / "intent.kyo", false, Path.WriteOptions(createFolders = false)).map { handle =>
            val close: Unit < Sync = Sync.Unsafe.defer { handle.finish(); handle.close() }
            val persistDirectory: Unit < (Sync & S & Abort[FileSystemException]) =
                close.andThen(lower.syncDirectory(stagingDir))
            lower.writeChunk(handle, Chunk.from(logBytes.toArray)).andThen(
                // Unsafe: finish() seals the log as complete; close() releases the channel.
                // syncDirectory flushes the staging dir's dirent so the log file is reachable after
                // power loss. Without finish(), a crash mid-write leaves no sealed log: recovery skips.
                persistDirectory
            )
        }
    end writeIntentLog

    // Writes the "committed.marker" sentinel via the openWrite/finish/close path so that a
    // crash during the marker write leaves it absent (recovery re-applies), not partially
    // written in an ambiguous state.
    private def writeCommittedMarker(stagingDir: Path)(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        lower.openWrite(stagingDir / "committed.marker", false, Path.WriteOptions(createFolders = false)).map { handle =>
            val close: Unit < Sync = Sync.Unsafe.defer { handle.finish(); handle.close() }
            val persistDirectory: Unit < (Sync & S & Abort[FileSystemException]) =
                close.andThen(lower.syncDirectory(stagingDir))
            // No content; finish() commits the zero-byte sentinel, close() releases the channel.
            // syncDirectory flushes the staging dir's dirent so the marker is reachable after power loss.
            persistDirectory
        }

    // Apply one WriteOp during the commit apply phase. File entries move atomically from
    // their staged copy to the final lower path (POSIX rename on host; CAS on in-memory).
    // Each atomic move is followed by a required parent-directory sync so the renamed dirent
    // is durable after power loss. When `idempotent` is true, file arms check staged
    // existence before moving (recovery may have already applied the move).
    private def applyOneOp(stagingDir: Path, i: Int, op: WriteOp, idempotent: Boolean = false)(using
        Frame
    ): Unit < (S & Abort[FileSystemException]) =
        def applyLastModified(target: Path, stat: Maybe[Path.PathStat])(using Frame): Unit < (S & Abort[FileSystemException]) =
            stat.fold[Unit < (S & Abort[FileSystemException])]((): Unit)(s => lower.setLastModified(target, s.lastModifiedMs))

        def moveStagedFile(staged: Path, target: Path, stat: Maybe[Path.PathStat], syncParent: Boolean)(using
            Frame
        ): Unit < (S & Abort[FileSystemException]) =
            val materialize = if idempotent then
                lower.exists(staged).flatMap { has =>
                    if has then
                        lower.move(
                            staged,
                            target,
                            Path.MoveOptions(replace = Path.Replace.Existing, atomicity = Path.Atomicity.Required)
                        )
                    else ()
                }
            else
                lower.move(
                    staged,
                    target,
                    Path.MoveOptions(replace = Path.Replace.Existing, atomicity = Path.Atomicity.Required)
                )
            materialize.andThen(applyLastModified(target, stat)).andThen(if syncParent then syncParentOf(target) else ())
        end moveStagedFile

        op match
            case WriteOp.WriteFile(parts, _, _) =>
                moveStagedFile(stagingDir / s"e$i.dat", pathFrom(parts), Absent, syncParent = !idempotent)
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
                        case Path.Entry.File(_, stat) =>
                            moveStagedFile(stagingDir / s"e$i.dat", pathFrom(toP), Present(stat), syncParent = !idempotent)
                        case Path.Entry.Directory(stat) =>
                            lower.mkDir(pathFrom(toP)).andThen(applyLastModified(pathFrom(toP), Present(stat)))
                }
            case WriteOp.Copy(_, toP, resolved, copyAttributes) =>
                resolved match
                    case Path.Entry.File(_, copiedStat) =>
                        val stat = if copyAttributes then Present(copiedStat) else Absent
                        moveStagedFile(stagingDir / s"e$i.dat", pathFrom(toP), stat, syncParent = !idempotent)
                    case Path.Entry.Directory(copiedStat) =>
                        val stat = if copyAttributes then Present(copiedStat) else Absent
                        lower.mkDir(pathFrom(toP)).andThen(applyLastModified(pathFrom(toP), stat))
                end match
        end match
    end applyOneOp

    private def applyOneOpIdempotent(stagingDir: Path, i: Int, op: WriteOp)(using Frame): Unit < (S & Abort[FileSystemException]) =
        applyOneOp(stagingDir, i, op, idempotent = true)

    // Child materialization changes host directory timestamps. Reapply metadata for each final
    // effective copied or moved directory after every journal entry has been materialized. Exact
    // later writes, removes, moves, or copies replace the earlier metadata decision for that path.
    private def finalizeDirectoryMetadata(journal: Chunk[WriteOp])(using Frame): Unit < (S & Abort[FileSystemException]) =
        val finalMetadata = journal.foldLeft(Map.empty[Chunk[String], Maybe[Path.PathStat]]) { (acc, op) =>
            op match
                case WriteOp.WriteFile(parts, _, _)   => acc.updated(parts, Absent)
                case WriteOp.WriteDirectory(parts, _) => acc.updated(parts, Absent)
                case WriteOp.Remove(parts)            => acc.updated(parts, Absent)
                case WriteOp.Move(from, to, resolved) =>
                    val withoutSource = acc.updated(from, Absent)
                    resolved match
                        case Path.Entry.Directory(stat) => withoutSource.updated(to, Present(stat))
                        case _: Path.Entry.File         => withoutSource.updated(to, Absent)
                case WriteOp.Copy(_, to, resolved, copyAttributes) =>
                    resolved match
                        case Path.Entry.Directory(stat) if copyAttributes => acc.updated(to, Present(stat))
                        case _                                            => acc.updated(to, Absent)
        }
        finalMetadata.foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (effect, (parts, stat)) =>
            effect.andThen(stat.fold[Unit < (S & Abort[FileSystemException])]((): Unit)(s =>
                lower.setLastModified(pathFrom(parts), s.lastModifiedMs)
            ))
        }
    end finalizeDirectoryMetadata

    // 5-step durable commit protocol driven by a pre-created staging directory.
    // (1) Stage file content; (2) write intent log + terminator; (3) atomic-move each entry
    // with per-entry hook; (4) write committed.marker. Each crash hook is a test-injection
    // point: recovery tests set the hook to throw, then call recover() to verify resumption.
    private def applyResolved(stagingDir: Path, journal: Chunk[WriteOp])(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        val n = journal.size
        stageOps(stagingDir, journal).andThen {
            runHook(afterStageHook).andThen { // crash point 1
                writeIntentLog(stagingDir, journal).andThen {
                    runHook(afterIntentLogHook).andThen { // crash point 2
                        journal.zipWithIndex
                            .foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (acc, (op, i)) =>
                                acc.andThen {
                                    applyOneOp(stagingDir, i, op).andThen {
                                        runHookKN(afterEntryApplyHook, i + 1, n) // crash point 3
                                    }
                                }
                            }
                            .andThen {
                                finalizeDirectoryMetadata(journal).andThen {
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
        }
    end applyResolved

    // Creates a staging dir, runs the durable commit protocol, and cleans up on success.
    // On failure (crash hook throws or lower I/O error) the staging dir and stagingDirHandle
    // remain set so recover() can find and re-apply the partial commit.
    private def withCommit(journal: Chunk[WriteOp])(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        // Unsafe: monotone counter increment for unique staging dir names
        Sync.Unsafe.defer(uniqueSeq.unsafe.getAndIncrement())
            .asInstanceOf[Long < (S & Abort[FileSystemException])]
            .flatMap { seq =>
                // Zero-padded so lexicographic order is numeric order: recovery sorts these names to
                // replay orphaned commits in the order they were made, and an unpadded hex counter
                // would sort "a" after "10".
                val commitId = f"$instanceSeed-$seq%016x"
                lower.tempDir(s"kyo-commit-$commitId").map { handle =>
                    // Write the ownership sentinel as the first entry in the staging dir. recoverFromDisk
                    // skips any kyo-commit-* dir that lacks it to prevent misclassifying user directories
                    // as orphaned staging dirs. A crash between staging dir creation and sentinel write
                    // leaks an empty dir; disk-scan skips it (no sentinel). Accepted trade.
                    lower.writeBytes(
                        handle.path / ".kyo-staging",
                        Span.from(Array.empty[Byte]),
                        Path.WriteOptions(createFolders = false)
                    ).andThen {
                        // Unsafe: stores handle after sentinel write so recover() can find the staging dir
                        // if applyResolved is interrupted; also syncs the parent dir so the
                        // staging dir's own dirent is durable.
                        val remember: Unit < Sync = Sync.Unsafe.defer { stagingDirHandle = Present(handle) }
                        val persistParent: Unit < (Sync & S & Abort[FileSystemException]) =
                            remember.andThen(lower.syncDirectory(pathFrom(handle.path.parts.dropRight(1))))
                        persistParent
                            .andThen {
                                applyResolved(handle.path, journal).andThen {
                                    runHook(afterMarkerHook).andThen { // crash point 6
                                        // Committed marker written; safe to remove staging dir.
                                        // Unsafe: clears handle reference and removes the staging directory.
                                        Sync.Unsafe.defer {
                                            stagingDirHandle = Absent
                                            handle.remove()
                                        }.asInstanceOf[Unit < (S & Abort[FileSystemException])]
                                    }
                                }
                            }
                    }
                }
            }
    end withCommit

    // Syncs the parent directory of `path` so that a newly-moved file's directory entry is
    // durable after power loss. Unsupported host platforms fail precisely.
    private def syncParentOf(path: Path)(using Frame): Unit < (S & Abort[FileSystemException]) =
        val pp = path.parts.dropRight(1)
        lower.syncDirectory(pathFrom(pp))
    end syncParentOf

    def commit(using Frame): Unit < (S & Sync & Abort[FileSystemException] & Abort[CommitConflict]) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                if conflicts.isEmpty then
                    terminate("commit").andThen(withCommit(s.journal)).andThen(modifyPure(_ => OverlayState.empty))
                else
                    Abort.fail(CommitConflict(conflicts.map(_._2)))
            }
        }

    def commitWith(resolve: FileSystem.Conflict => FileSystem.Resolution)(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException] & Abort[CommitConflict]) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                // Collect one Resolution per conflicted path, then rebuild upper and journal
                // so the replay reflects every resolution (not just the original staged ops).
                conflicts.foldLeft[Chunk[(CanonicalPath, Resolution)] < (S & Abort[FileSystemException])](Chunk.empty) {
                    case (accKyo, (cp, conflict)) =>
                        accKyo.map(resolutions => resolutions.appended((cp, resolve(conflict))))
                }.map { resolutions =>
                    // Pure fold: compute replacement upper and journal from the resolutions.
                    val (newUpper, replacedJournal) =
                        resolutions.foldLeft((s.upper, s.journal)) { case ((upper, journal), (cp, resolution)) =>
                            val parts = cp.parts
                            resolution match
                                case Resolution.KeepOurs =>
                                    (upper, journal)
                                case Resolution.KeepTheirs =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _, _)    => to != parts
                                    }
                                    (upper.removed(cp), stripped)
                                case Resolution.Write(entry) =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _, _)    => to != parts
                                    }
                                    val newOp = entry match
                                        case Path.Entry.File(bytes, stat) => WriteOp.WriteFile(parts, bytes, stat)
                                        case Path.Entry.Directory(_)      => WriteOp.WriteDirectory(parts, opaque = false)
                                    (upper.updated(cp, Upper.Entry(entry)), stripped.appended(newOp))
                                case Resolution.Remove =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _, _)    => to != parts
                                    }
                                    (upper.updated(cp, Upper.Whiteout), stripped.appended(WriteOp.Remove(parts)))
                            end match
                        }
                    terminate("commitWith").andThen(modifyPure(_.copy(upper = newUpper, journal = replacedJournal))).andThen {
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
    private def recoverStagingDir(stagingDir: Path, checkSentinel: Boolean = false)(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException]) =
        if !checkSentinel then recoverStagingDirImpl(stagingDir)
        else
            lower.exists(stagingDir / ".kyo-staging").map { hasSentinel =>
                if !hasSentinel then () // not kyo-owned; skip to protect user directories
                else recoverStagingDirImpl(stagingDir)
            }

    private def recoverStagingDirImpl(stagingDir: Path)(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
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
                                        .foldLeft[Unit < (S & Sync & Abort[FileSystemException])](()) {
                                            case (acc, (op, i)) =>
                                                acc.andThen(applyOneOpIdempotent(stagingDir, i, op))
                                        }
                                        .andThen {
                                            finalizeDirectoryMetadata(journal).andThen {
                                                writeCommittedMarker(stagingDir).andThen {
                                                    lower.removeAll(stagingDir)
                                                }
                                            }
                                        }
                                case Result.Failure(e) =>
                                    // Bad magic or unsupported version: not a crash artifact.
                                    // Fail loudly so the caller can observe the unexpected state.
                                    Abort.fail[FileSystemException](e)
                        }
                }
        }
    end recoverStagingDirImpl

    // Recovery driver: re-applies a partially-applied commit found via stagingDirHandle.
    // Called after a simulated mid-commit crash: the overlay object remains alive, so
    // stagingDirHandle still points to the staging dir created before the failure.
    // private[kyo] so recovery tests (same package, outside this class) can call it.
    private[kyo] def recover()(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        stagingDirHandle match
            case Absent => ()
            case Present(handle) =>
                recoverStagingDir(handle.path).andThen {
                    // Clear the in-memory staging reference after recovery; subsequent calls to
                    // recover() see Absent and exit as no-ops (idempotency guarantee).
                    // Unsafe: mutation of stagingDirHandle outside the Kyo effect system.
                    Sync.Unsafe.defer { stagingDirHandle = Absent }
                }

    // Scans the lower service's root for orphaned staging directories (kyo-commit-* prefix)
    // left by a prior process crash and recovers each via recoverStagingDir. Does NOT wire
    // at OverlayFileSystem.init: wiring at init would require adding root: Path and
    // Abort[FileSystemException] to Service.overlay's public signature, which would change the
    // established API. Call recoverFromDisk(root) explicitly immediately after
    // Service.overlay(lower) to enable automatic crash recovery. private[kyo] so disk-scan
    // recovery tests can call it directly.
    private[kyo] def recoverFromDisk(root: Path)(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
        lower.list(root).map { entries =>
            // Sorted before replay. The host's list has no ordering guarantee, unlike every other
            // listing surface in this module, so two orphaned commits touching one target would
            // otherwise recover to a state that depends on the platform and filesystem. The names
            // carry a zero-padded commit counter, so sorting them is replaying in commit order.
            val staging = entries.filter(_.name.exists(_.startsWith("kyo-commit-"))).sortBy(_.name.getOrElse(""))
            staging.foldLeft[Unit < (S & Sync & Abort[FileSystemException])](()) { (acc, entry) =>
                acc.andThen(recoverStagingDir(entry, checkSentinel = true))
            }
        }

end OverlayFileSystem

final private[kyo] class WatchableOverlayFileSystem[S, S2](
    lower: FileSystem.Write[S],
    state: AtomicRef[OverlayFileSystem.OverlayState],
    uniqueSeq: AtomicLong,
    active: AtomicBoolean,
    isolate: Isolate[S, Sync, S2]
) extends OverlayFileSystem[S](lower, state, uniqueSeq, active), FileSystem.Watch[S & Sync]:

    def openWatcher(path: Path, options: WatchOptions)(using
        Frame
    ): Path.Watcher < (S & Sync & Async & Scope & Abort[FileWatchException]) =
        isolate.use(PathWatch.polling[S, S2](this, path, options))
end WatchableOverlayFileSystem

// Forwards every path op back under PathRead or PathWrite so an overlay wrapping this
// service is transparent to whatever PathWrite handler is installed in the outer scope.
final private[kyo] class ForwardingLowerFileSystem extends FileSystem.Write[PathWrite]:

    private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
        throw new IllegalStateException("temporary cleanup handles are forwarded by siblingTemporary and durableReplace")
    def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < PathWrite =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.DefaultCaseSensitivity())
    def exists(path: Path)(using Frame): Boolean < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Exists(path))
    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ExistsFollow(path, followLinks))
    def isDirectory(path: Path)(using Frame): Boolean < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsDirectory(path))
    def isRegularFile(path: Path)(using Frame): Boolean < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsRegularFile(path))
    def isSymbolicLink(path: Path)(using Frame): Boolean < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.IsSymbolicLink(path))
    def realPath(path: Path)(using
        Frame
    ): Path < (PathWrite & Abort[
        FileOutsideRootException | FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
    ]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPath(path))

    // Overridden rather than defaulted: the default resolves by recovering FileNotFoundException from
    // realPath, and a failure from this forwarding suspension is raised at the capability handler,
    // past any handler installed here. Suspending the operation itself pushes the recovery to the
    // service that can perform it locally.
    override def realPathPrefix(path: Path)(using
        Frame
    ): Path < (PathWrite & Abort[
        FileOutsideRootException | FileInvalidPathException | FileAccessDeniedException | FileIOException
    ]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPathPrefix(path))
    def read(path: Path)(using Frame): String < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Read(path))
    def read(path: Path, charset: Charset)(using Frame): String < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadCharset(path, charset))
    def readBytes(path: Path)(using Frame): Span[Byte] < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadBytes(path))
    def readLines(path: Path)(using Frame): Chunk[String] < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLines(path))
    def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLinesCharset(path, charset))
    def size(path: Path)(using Frame): Long < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(path))
    def stat(path: Path)(using Frame): Path.PathStat < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.Stat(path))
    def list(path: Path)(using Frame): Chunk[Path] < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ListDir(path))
    def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
        Frame
    ): Chunk[Path] < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.ListGlob(path, glob, Maybe(caseSensitivity)))
    def openRead(path: Path)(using Frame): Path.ReadHandle < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenRead(path))
    def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (PathWrite & Abort[FileReadException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenReadLines(path, charset))
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
        Frame
    ): Path.WalkHandle < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenWalk(path, maxDepth, followLinks))
    def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Write(path, value, options))
    def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteBytes(path, value, options))
    def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteLines(path, value, options))
    def append(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Append(path, value, options))
    def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
        Frame
    ): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendBytes(path, value, options))
    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendLines(path, value, options))
    def truncate(path: Path, size: Long)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Truncate(path, size))
    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.SetLastModified(path, epochMs))
    def mkDir(path: Path)(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkDir(path))
    def mkFile(path: Path)(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkFile(path))
    def move(
        from: Path,
        to: Path,
        options: Path.MoveOptions
    )(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Move(from, to, options))
    def copy(
        from: Path,
        to: Path,
        options: Path.CopyOptions
    )(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Copy(from, to, options))
    def remove(path: Path)(using Frame): Boolean < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.Remove(path))
    def removeExisting(path: Path)(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveExisting(path))
    def removeAll(path: Path)(using Frame): Unit < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveAll(path))
    def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
        Frame
    ): Path.WriteHandle < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append, options))
    def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (PathWrite & Abort[FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.TempDir(prefix))
    override def siblingTemporary(target: Path)(using
        Frame
    ): Path.TempFileHandle < (PathWrite & Abort[FileWriteException | FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.SiblingTemporary(target))
    def durableReplace(target: Path, bytes: Span[Byte])(using
        Frame
    ): Unit < (PathWrite & Abort[FileReadException | FileWriteException | FileStructureException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.DurableReplace(target, bytes))
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteChunk(handle, chunk))
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using
        Frame
    ): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, value, charset))
    // Positioned channels have no Path.Op case to forward through: this ephemeral
    // forwarding service backs Path staged-write scopes only, and OverlayFileSystem's
    // own positioned channels never call lower channel acquisition, routing every channel read and
    // write through the already-Op-forwardable readBytes/writeBytes/truncate/size instead.
    // Both members are therefore unreachable in practice; each fails loud rather than
    // silently misbehaving if some future refactor reaches them.
    private def unavailable(path: Path, acquisition: String)(using Frame): FileIOException =
        FileIOException(
            path,
            FileSystemOperation.Channel,
            new IOException(
                s"$acquisition is unavailable through Path staged-write scopes; hold a FileSystem value directly"
            )
        )
    def openReadChannel(path: Path)(using Frame): Path.ReadChannel[PathWrite] < (PathWrite & Scope & Abort[FileReadException]) =
        Abort.fail(unavailable(path, "openReadChannel"))
    def openWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.WriteChannel[PathWrite] < (PathWrite & Scope & Abort[FileWriteException | FileStructureException]) =
        Abort.fail(unavailable(path, "openWriteChannel"))
    def openReadWriteChannel(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): Path.ReadWriteChannel[PathWrite] < (PathWrite & Scope & Abort[FileReadException | FileWriteException | FileStructureException]) =
        Abort.fail(unavailable(path, "openReadWriteChannel"))
    private[kyo] def openReadChannelUnscoped(path: Path)(using
        Frame
    ): (Path.ReadChannel[PathWrite], () => Unit < PathWrite) < (PathWrite & Abort[FileReadException]) =
        Abort.fail(unavailable(path, "openReadChannelUnscoped"))
    private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (Path.WriteChannel[PathWrite], () => Unit < PathWrite, Path.ChannelCloseHandle) <
        (PathWrite & Abort[FileWriteException | FileStructureException]) =
        Abort.fail(unavailable(path, "openWriteChannelUnscoped"))
    private[kyo] def openReadWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
        Frame
    ): (
        Path.ReadWriteChannel[PathWrite],
        () => Unit < PathWrite
    ) < (PathWrite & Abort[FileReadException | FileWriteException | FileStructureException]) =
        Abort.fail(unavailable(path, "openReadWriteChannelUnscoped"))
    def syncDirectory(path: Path)(using Frame): Unit < (PathWrite & Abort[FileWriteException]) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.SyncDirectory(path))
    def tryLock(path: Path, mode: Path.LockMode)(using
        Frame
    ): Maybe[Path.Lock] < (PathWrite & Sync & Async & Scope & Abort[FileLockException]) =
        Path.suspendTryLock(path, mode)

    def lock(path: Path, mode: Path.LockMode, wait: Path.LockWait)(using
        Frame
    ): Path.Lock < (PathWrite & Async & Scope & Abort[FileLockException]) =
        Path.suspendLock(path, mode, wait)
end ForwardingLowerFileSystem
