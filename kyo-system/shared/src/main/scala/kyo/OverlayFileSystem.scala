package kyo

import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.FileSystemCrc32
import kyo.internal.PathTrie
import kyo.kernel.ArrowEffect

/** One step of a commit, derived from the upper trie rather than recorded alongside it.
  *
  * The commit plan is a function of the staged state, so the bytes a caller reads back through the
  * overlay and the bytes the commit writes cannot disagree. A parallel operation log could, and
  * did: it was less expressive than the trie it shadowed, so a directory's staged modification time
  * had nowhere to go, and a resolution applied to one had to be translated into the other by hand.
  *
  * `explicitMtime` is `Present` only where a caller set a modification time. A staged write carries
  * `PathStat(0, size)` as a placeholder for a timestamp the commit will assign, and replaying that
  * as a timestamp would date every committed file to 1970.
  */
private[kyo] enum ReplayEntry derives CanEqual:
    case File(path: Chunk[String], bytes: Span[Byte], stat: Path.PathStat, explicitMtime: Maybe[Long])
    case Directory(path: Chunk[String], opaque: Boolean, explicitMtime: Maybe[Long])
    case Whiteout(path: Chunk[String])
end ReplayEntry

private[kyo] object OverlayFileSystem:

    /** Upper-layer entry variants. `Entry` holds a staged file or directory; `Whiteout` marks a
      * deleted path; `OpaqueDir` marks a directory that hides all lower children.
      *
      * `explicitMtime` is what a caller asked the modification time to be, and is `Absent` wherever
      * no caller asked. The `stat` inside a staged entry cannot answer that on its own: a plain
      * write fills it with `PathStat(0, size)`, so replaying its timestamp would date the committed
      * file to the epoch. Every construction site states its answer, and there is deliberately no
      * default: an implicit `Absent` is how the staged entry and the operation that shadowed it
      * drifted apart in the first place.
      */
    enum Upper derives CanEqual:
        case Entry(body: Path.Entry, explicitMtime: Maybe[Long])
        case Whiteout
        case OpaqueDir(stat: Path.PathStat, explicitMtime: Maybe[Long])
    end Upper

    /** A path that has been resolved through the lower.
      *
      * Constructible only by [[OverlayFileSystem.canonical]], so a value of this type is evidence
      * that resolution happened rather than a claim that it did. The upper and the read-set are
      * both keyed by canonical paths; taking this type at every accessor is what makes
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
      * the defect this type exists to prevent: commit replays this layer against a link-following
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

            /** Every staged entry, a node before its children and siblings in segment order.
              *
              * That order is what replay needs: a directory is created before anything is written
              * into it, and a whiteout clears a path before a staged subtree is materialized
              * beneath it.
              */
            def entries: Chunk[(Chunk[String], Upper)] = self.entries
        end extension

        given CanEqual[UpperTrie, UpperTrie] = CanEqual.derived
    end UpperTrie

    /** What the overlay saw the first time it looked at a lower path.
      *
      * The read-set exists so commit can tell whether the lower still matches what the staged work
      * was built on. That question is only as strong as the observation behind it: a caller that
      * asked whether a path exists is entitled to fail on a path that stopped existing, not on a
      * path whose contents changed underneath an answer that never depended on them.
      *
      * Recording the observation rather than a uniform full entry is also what keeps a metadata
      * question from reading the file. `exists` on a large file used to read all of it to build a
      * record whose contents nothing would consult.
      *
      * The two directory cases are separate because the questions differ. Writing into a directory
      * asks whether there is one to write into, and that answer does not change when a sibling
      * appears. Listing asks what it holds, and that answer does. One directory case validated by
      * modification time gets both wrong at once: it makes any sibling activity a conflict for the
      * first, and for the second it leans on timestamp granularity to notice a change it could
      * observe directly.
      */
    private[kyo] enum Observed derives CanEqual:
        /** The path was not there. */
        case Missing

        /** The path was there and was of this kind. Nothing was observed about its stat or its
          * contents, which is all `exists`, `isDirectory` and `isRegularFile` ever look at.
          */
        case Exists(isDirectory: Boolean)

        /** `stat` returned this. A caller that asked for a stat may depend on any of it, the
          * modification time included.
          */
        case Metadata(stat: Path.PathStat, isDirectory: Boolean)

        /** `list` returned these child names. */
        case DirectoryListing(children: Set[String])

        /** The contents were read. */
        case FileContent(bytes: Span[Byte], stat: Path.PathStat)
    end Observed

    object Observed:
        /** How much an observation claims. A later, stronger observation of the same path replaces a
          * weaker one, because the stronger one is what the staged work goes on to depend on.
          *
          * `Metadata` and `DirectoryListing` share a rank because they cannot describe the same
          * path: one is a stat, the other a directory's contents.
          */
        def strength(o: Observed): Int = o match
            case Observed.Missing             => 0
            case Observed.Exists(_)           => 1
            case Observed.Metadata(_, _)      => 2
            case Observed.DirectoryListing(_) => 2
            case Observed.FileContent(_, _)   => 3
    end Observed

    /** Lower observations recorded on first read, keyed by canonical path.
      *
      * Opaque for the same reason as [[UpperTrie]]: commit validates each recorded observation
      * against the lower path the commit actually mutates, so an entry keyed by an unresolved path
      * would validate a different file than the one being written.
      */
    opaque type ReadSet = Map[Chunk[String], Observed]

    object ReadSet:
        import CanonicalPath.parts

        val empty: ReadSet = Map.empty

        extension (self: ReadSet)
            def get(cp: CanonicalPath): Maybe[Observed] = Maybe.fromOption(self.get(cp.parts))
            def contains(cp: CanonicalPath): Boolean    = self.contains(cp.parts)
            def updated(cp: CanonicalPath, observed: Observed): ReadSet =
                self.updated(cp.parts, observed)
            def removed(cp: CanonicalPath): ReadSet = self.removed(cp.parts)
            def entries: Chunk[(CanonicalPath, Observed)] =
                Chunk.from(self.toIndexedSeq.map((parts, v) => (CanonicalPath.wrap(parts), v)))
        end extension
    end ReadSet

    /** How a path in the overlay's view came to hold what it holds.
      *
      * A watcher asks whether two observations name the same file, so that a rename correlates into
      * one change rather than a removal and an unrelated creation. Staged origin and move provenance
      * are the whole of what answers that, which is why recording them directly replaces scanning a
      * log of operations for the same information.
      */
    private[kyo] enum Provenance derives CanEqual:
        /** Content that originated in this overlay. The token is unique per staging event, so a
          * staged write never reports the identity of whatever it replaced.
          */
        case Staged(token: String)

        /** This path took another path's identity, so a rename is not observed as a new file. */
        case MovedFrom(origin: Chunk[String])
    end Provenance

    /** Where the overlay is in its one-shot lifecycle.
      *
      * `Open` accepts writes. `Committing` rejects them for the duration of a commit, so the staged
      * state a commit took cannot gain entries after it was taken. `Terminated` is final.
      *
      * A commit that finds conflicts returns to `Open` rather than terminating: the caller's next
      * move is `commitWith`, which needs the staged state intact and the overlay still accepting the
      * writes a resolution implies.
      *
      * Carried inside [[OverlayState]] rather than in an atomic of its own so that a write's
      * admission check and the write itself are one compare-and-set. A separate flag lets a commit
      * flip the lifecycle between a write's check and its mutation, which is how a write lands in
      * state that nothing will ever read.
      */
    enum Phase derives CanEqual:
        case Open, Committing, Terminated
    end Phase

    /** The overlay's mutable state: the upper trie of staged entries, the read-set of lower
      * observations (what the overlay saw the first time it read each lower path), and the
      * lifecycle phase that decides whether a further write is admitted at all.
      *
      * There is deliberately no second record of the staged writes. The commit plan is derived from
      * the upper trie by [[replayPlan]], so what a caller reads back and what a commit writes are
      * the same value seen twice rather than two records kept in step by hand.
      *
      * The upper is a [[PathTrie]] rather than a flat map because every question asked of it beyond
      * a point lookup is hierarchical: shadowing by an ancestor, a directory's direct children, and
      * a subtree for move, copy, and removal. A flat map answers those by scanning.
      */
    final case class OverlayState(
        upper: UpperTrie,
        // What the overlay saw the first time it looked at each lower path. Commit validates each
        // record against the live lower, and each is only as strong as the read that produced it.
        readSet: ReadSet,
        // Resolution of a raw path to its canonical form, recorded on first observation.
        //
        // A path's canonical form is an observation of the lower, exactly like a read-set entry, and
        // is recorded once for the same reason: resolving on every access would let a key drift as
        // the lower changes, so one logical file could acquire two keys and split its staged state.
        // Pinning it on first touch keeps keys stable; drift is a commit-time conflict, not a silent
        // re-key.
        resolved: PathTrie[Chunk[String]],
        // How each staged path came to hold what it holds, for stableIdentity. A trie rather than a
        // map because a path with no record of its own is still gone when the overlay removed
        // something above it, which is an ancestor question.
        provenance: PathTrie[Provenance],
        phase: Phase
    ):
        /** The commit plan for this state: every staged entry, parents before children.
          *
          * A function of the staged state rather than a record kept beside it, so the plan cannot
          * describe anything other than what the overlay's own reads return.
          */
        def replayPlan: Chunk[ReplayEntry] =
            upper.entries.map {
                case (parts, Upper.Whiteout) =>
                    ReplayEntry.Whiteout(parts)
                case (parts, Upper.OpaqueDir(_, mtime)) =>
                    ReplayEntry.Directory(parts, opaque = true, mtime)
                case (parts, Upper.Entry(Path.Entry.Directory(_), mtime)) =>
                    ReplayEntry.Directory(parts, opaque = false, mtime)
                case (parts, Upper.Entry(Path.Entry.File(bytes, stat), mtime)) =>
                    ReplayEntry.File(parts, bytes, stat, mtime)
            }
    end OverlayState

    object OverlayState:
        val empty: OverlayState =
            OverlayState(UpperTrie.empty, ReadSet.empty, PathTrie.empty, PathTrie.empty, Phase.Open)

        /** The state an overlay is left in once its lifetime is over: nothing staged, nothing
          * admitted. Reached by discard, by a completed commit, and by scope exit.
          */
        val terminated: OverlayState = empty.copy(phase = Phase.Terminated)
    end OverlayState

    // Allocates the instance both constructors return. Declared as the concrete class so
    // initRecovering can call recoverFromDisk on a value that is statically known to have it,
    // rather than widening here and casting back.
    private def build[S, S2](lower: FileSystem.Write[S])(using
        frame: Frame,
        isolate: Isolate[S, Sync, S2]
    ): WatchableOverlayFileSystem[S, S2] < (Sync & Scope) =
        Scope.acquireRelease(AtomicRef.init(OverlayState.empty)) { ref =>
            ref.set(OverlayState.terminated)
        }.flatMap { ref =>
            // Unsafe: allocates per-instance commit counter at construction
            Sync.Unsafe.defer(new WatchableOverlayFileSystem(lower, ref, AtomicLong.Unsafe.init(0L).safe, isolate))
        }

    def init[S, S2](lower: FileSystem.Write[S])(using
        frame: Frame,
        isolate: Isolate[S, Sync, S2]
    ): (
        FileSystem.StagedChanges[S & Sync & Abort[FileSystemException]] & FileSystem.Write[S & Sync] & FileSystem.Watch[S & Sync]
    ) < (Sync & Scope) =
        build(lower)

    /** Builds an overlay and replays any staging directory a previous process left behind.
      *
      * Separate from [[init]] rather than folded into it because recovery needs a root and a scan
      * of it, and neither is available on every path that builds an overlay: the staged-write
      * scopes in [[Path]] build one over a forwarding service that re-suspends every operation as
      * an effect and has no root at all. Making the root a parameter of one constructor keeps the
      * other honest rather than handing it a root it cannot use.
      *
      * The scan runs before the overlay is returned, so a caller never stages work on top of a
      * lower that still holds a half-applied commit.
      *
      * The effect row carries `S` where [[init]]'s does not, because this one runs the scan rather
      * than only allocating, and the reads the scan makes are the lower's.
      */
    def initRecovering[S, S2](lower: FileSystem.Write[S], root: Path)(using
        frame: Frame,
        isolate: Isolate[S, Sync, S2]
    ): (
        FileSystem.StagedChanges[S & Sync & Abort[FileSystemException]] & FileSystem.Write[S & Sync] & FileSystem.Watch[S & Sync]
    ) < (S & Sync & Scope & Abort[FileSystemException]) =
        build(lower).map(overlay => overlay.recoverFromDisk(root).andThen(overlay))

end OverlayFileSystem

// Self-contained binary intent-log format for the overlay durable commit state machine.
// Written to "intent.kyo" in the staging directory before any lower mutations.
// The commit terminator (KYCT) seals the log; its absence means an incomplete write that
// recovery skips. No dependency on kyo-eventlog (circular); pure kyo-system.
//
// The log holds one record per staged path: a file, a directory, or a whiteout, in the order the
// commit plan replays them. It used to hold one record per staged operation, with move and copy
// carrying a whole embedded Path.Entry, which is a record of how the state was reached rather than
// of what it is. Two operations on one path wrote two records, and recovery replayed both.
//
// Version 3 is a deliberate break: a version 3 log is unreadable by a version 2 decoder and the
// reverse, and decode fails loudly on an unexpected version rather than mis-recovering. kyo-system
// has never been released, so no version 2 log exists anywhere to migrate.
private[kyo] object WriteOpLog:

    private val OpFile: Byte      = 0x01
    private val OpDirectory: Byte = 0x02
    private val OpWhiteout: Byte  = 0x03

    private val MagicHeader: Array[Byte]     = Array('K'.toByte, 'Y'.toByte, 'I'.toByte, 'L'.toByte)
    private val Version: Byte                = 0x03
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

    // An optional timestamp: a presence byte, and the eight bytes only when it is there. Absent is
    // not the same as zero, so it cannot be encoded as a sentinel value: zero is a modification
    // time a caller can legitimately set.
    private def wOptI64(buf: scala.collection.mutable.ArrayBuffer[Byte], value: Maybe[Long]): Unit =
        value match
            case Absent => buf += 0x00.toByte
            case Present(v) =>
                buf += 0x01.toByte
                wI64(buf, v)

    private def encodeRecord(entry: ReplayEntry): Array[Byte] =
        val buf = new scala.collection.mutable.ArrayBuffer[Byte](32)
        entry match
            case ReplayEntry.File(parts, bytes, stat, explicitMtime) =>
                buf += OpFile
                encodeParts(buf, parts)
                val arr = bytes.toArrayUnsafe
                wI32(buf, arr.length)
                buf ++= arr
                wI64(buf, stat.lastModifiedMs)
                wI64(buf, stat.sizeBytes)
                wOptI64(buf, explicitMtime)
            case ReplayEntry.Directory(parts, opaque, explicitMtime) =>
                buf += OpDirectory
                encodeParts(buf, parts)
                buf += (if opaque then 0x01.toByte else 0x00.toByte)
                wOptI64(buf, explicitMtime)
            case ReplayEntry.Whiteout(parts) =>
                buf += OpWhiteout
                encodeParts(buf, parts)
        end match
        buf.toArray
    end encodeRecord

    // Encodes the commit plan as: header | records | terminator.
    // Record framing: len4 | crc4(body) | body.
    // Terminator: "KYCT" | crc4(all prior bytes). Presence of the terminator = complete log.
    def encode(plan: Chunk[ReplayEntry]): Span[Byte] =
        val buf = new scala.collection.mutable.ArrayBuffer[Byte](64)
        buf ++= MagicHeader
        buf += Version
        plan.foreach { entry =>
            val body = encodeRecord(entry)
            wI32(buf, body.length)
            wI32(buf, FileSystemCrc32.of(body))
            buf ++= body
        }
        val priorArr = buf.toArray
        buf ++= MagicTerminator
        wI32(buf, FileSystemCrc32.of(priorArr))
        Span.fromUnsafe(buf.toArray)
    end encode

    // Returns Success(Present(plan)) on a valid sealed log.
    // Returns Success(Absent) on truncation or any CRC failure: these are crash artifacts
    // from an incomplete write (finish() was never called); the commit never became durable,
    // so recovery can safely discard the staging dir.
    // Returns Failure(FileIOException) on bad magic bytes or unsupported version: not a crash
    // artifact; something else wrote the file or the format evolved. Fail loudly.
    def decode(logPath: Path, bytes: Span[Byte]): Result[FileIOException, Maybe[Chunk[ReplayEntry]]] =
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
        var pos     = 5
        val entries = new scala.collection.mutable.ArrayBuffer[ReplayEntry]()
        while pos < termPos do
            if pos + 8 > termPos then return Result.succeed(Absent)
            val bodyLen = rI32(arr, pos); pos += 4
            val bodyCrc = rI32(arr, pos); pos += 4
            if bodyLen < 0 || pos + bodyLen > termPos then return Result.succeed(Absent)
            val bodyStart = pos
            if bodyCrc != FileSystemCrc32.of(arr, pos, bodyLen) then return Result.succeed(Absent)
            pos += bodyLen
            decodeRecord(arr, bodyStart, bodyLen) match
                case Absent         => return Result.succeed(Absent)
                case Present(entry) => entries += entry
        end while
        Result.succeed(Present(Chunk.from(entries.toIndexedSeq)))
    end decode

    private def decodeRecord(arr: Array[Byte], offset: Int, len: Int): Maybe[ReplayEntry] =
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

        // Mirrors wOptI64: a presence byte, then eight bytes only when present. The outer Maybe
        // reports a truncated record, the inner one reports the encoded absence, so a record that
        // ran out of bytes is not read as a timestamp that was never set.
        def readOptI64(): Maybe[Maybe[Long]] =
            if pos >= end then Absent
            else
                val tag = arr(pos); pos += 1
                if tag == 0x00.toByte then Present(Absent)
                else if pos + 8 > end then Absent
                else
                    val v = rI64(arr, pos); pos += 8
                    Present(Present(v))
                end if

        if opcode == OpFile then
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
                                readOptI64() match
                                    case Absent => Absent
                                    case Present(explicitMtime) =>
                                        Present(ReplayEntry.File(parts, bytes, Path.PathStat(lm, sz), explicitMtime))
                                end match
                            end if
                        end if
        else if opcode == OpDirectory then
            readParts() match
                case Absent => Absent
                case Present(parts) =>
                    if pos >= end then Absent
                    else
                        val opaque = arr(pos) != 0x00.toByte; pos += 1
                        readOptI64() match
                            case Absent                 => Absent
                            case Present(explicitMtime) => Present(ReplayEntry.Directory(parts, opaque, explicitMtime))
        else if opcode == OpWhiteout then
            readParts() match
                case Absent         => Absent
                case Present(parts) => Present(ReplayEntry.Whiteout(parts))
        else Absent
        end if
    end decodeRecord

end WriteOpLog

/** Copy-on-write overlay service. Reads check the upper layer first; writes stage in the upper
  * layer without touching lower. Commit derives a plan from the upper layer and replays it onto
  * lower. The read-set records what the overlay observed of each lower path on its first read;
  * commit validates those observations against the live lower before replaying.
  *
  * The structure is one lower service (the constructor field) and one [[OverlayState]] behind an
  * atomic reference. That state holds the staged upper layer, the read-set, the pinned canonical
  * resolutions, the per-path provenance a watcher's identity question is answered from, and the
  * lifecycle phase. Nothing in it records the staged writes a second time:
  * [[OverlayFileSystem.OverlayState.replayPlan]] derives the commit plan from the upper layer.
  *
  * That derivation is the point rather than a convenience. A parallel log of write operations
  * alongside the upper layer is two descriptions of one intent, paired by hand at every mutation
  * site, and the weaker of the two decides what a commit does: a staged directory's modification
  * time had nowhere to live in an operation record, so a read reported it and a commit dropped it.
  * Deriving the plan makes what a caller reads back and what a commit writes the same value seen
  * twice.
  *
  * A commit is durable across a crash: each staged file is written into a staging directory, the
  * plan is recorded in an intent log, the entries are applied, directory timestamps are settled,
  * and only then is a marker written declaring the commit complete. A process that dies partway
  * leaves that staging directory behind, and [[FileSystem.overlayRecovering]] is how the next
  * process finds and replays it.
  *
  * Every operation that reads state and writes a function of it is one transition: [[transact]]
  * decides against a snapshot and applies the result only if that snapshot still stands, retrying
  * from a fresh one otherwise. Reading the lower inside a decision is therefore safe under
  * concurrency, because a decision built on a read that has been overtaken is discarded rather than
  * applied. A compare-and-set on each individual update is not enough on its own: it leaves the span
  * from the snapshot, through a lower read, to the update unprotected, which is where a concurrent
  * append loses one of the two writes.
  *
  * Two paths stay outside that guarantee, and neither is a read-modify-write. `ensureWriteParent`
  * creates the directories missing above a target, which converges on the same result whoever runs
  * it. A write handle from `openWrite` buffers into its own array and publishes at `finish`, so two
  * handles open on one path replace each other wholesale, which is what a file handle means.
  *
  * Scope-managed: the enclosing Scope bounds its lifetime; on scope exit open staged state is
  * discarded.
  */
private[kyo] class OverlayFileSystem[S](
    lower: FileSystem.Write[S],
    state: AtomicRef[OverlayFileSystem.OverlayState],
    uniqueSeq: AtomicLong
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

    // The failure a write raises once the overlay is no longer admitting them.
    //
    // FileIOException is the one FileSystemException satisfying the read, write and structure bounds
    // at once, so every write method can raise it without widening its declared row.
    // FileSystem.StagedChanges.AlreadyTerminated cannot be used here: it extends CommitConflict,
    // which is not a FileSystemException and appears in no write method's row.
    private def terminated(path: Path, operation: FileSystemOperation)(using Frame): FileIOException =
        FileIOException(path, operation, new IOException("staged changes are no longer accepting writes"))

    // CAS modify loop for operations that may fail with FileSystemException. Admits the mutation
    // only while the overlay is open, so a write cannot land in state a commit has already taken or
    // a discard has already thrown away.
    private def modify[E <: FileSystemException, A](path: Path, operation: FileSystemOperation)(
        op: OverlayState => Result[E, (OverlayState, A)]
    )(using Frame): A < (Sync & Abort[E | FileIOException]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                if cur.phase != Phase.Open then Abort.fail(terminated(path, operation))
                else
                    Abort.get(op(cur)).map { (next, v) =>
                        state.compareAndSet(cur, next).map {
                            case true  => Loop.done(v)
                            case false => Loop.continue(())
                        }
                    }
            }
        }

    // Stages a change, admitted only while the overlay is open.
    //
    // Separate from modifyPure, which stays ungated: modifyPure also records read-set observations
    // and pinned canonical resolutions, and those accompany reads. A read through a discarded
    // overlay is not an error, it just sees the lower, so gating every mutation would turn reading
    // after discard into a failure. What must be refused is a write, because a write after the
    // staged state is gone has nowhere to land.
    private def stage(path: Path, operation: FileSystemOperation)(op: OverlayState => OverlayState)(using
        Frame
    ): Unit < (S & Abort[FileIOException]) =
        (Loop(()) { _ =>
            state.get.map { cur =>
                if cur.phase != Phase.Open then Abort.fail(terminated(path, operation))
                else
                    state.compareAndSet(cur, op(cur)).map {
                        case true  => Loop.done(())
                        case false => Loop.continue(())
                    }
            }
        }: Unit < (Sync & Abort[FileIOException])).asInstanceOf[Unit < (S & Abort[FileIOException])]

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

    /** Runs one logical operation as a single state transition.
      *
      * `decide` receives a snapshot and returns the state that should replace it. It may suspend to
      * read the lower, and it is retried from a fresh snapshot whenever the state moved underneath
      * it, so a decision is never applied to a state other than the one it was made against. That is
      * the property the previous snapshot-then-modify pair lacked: it read one snapshot, suspended,
      * and wrote into a later one, which silently discards a concurrent read-modify-write.
      *
      * Everything the operation stages has to be folded into the returned state. A nested [[stage]]
      * inside `decide` would apply on every pass, and since staging is not idempotent a retry would
      * apply it twice.
      *
      * Nested *observations* are fine. `canonical` and the read-set stamps publish through their own
      * compare-and-set, so the first pass loses the outer one and retries; on the second pass each
      * of them finds its record already present and returns the state unchanged, so the outer
      * compare-and-set succeeds. They are idempotent by construction, which is what makes the retry
      * terminate rather than spin.
      *
      * The lower reads inside `decide` are idempotent too, so replaying them on retry is safe.
      */
    private def transact[E, A](path: Path, operation: FileSystemOperation)(
        decide: OverlayState => (OverlayState, A) < (S & Abort[E])
    )(using Frame): A < (S & Abort[E | FileIOException]) =
        (Loop(()) { _ =>
            stateGet.map { cur =>
                if cur.phase != Phase.Open then Abort.fail(terminated(path, operation))
                else
                    decide(cur).map { (next, value) =>
                        state.compareAndSet(cur, next).map {
                            case true  => Loop.done(value)
                            case false => Loop.continue(())
                        }
                    }
            }
        }: A < (S & Sync & Abort[E | FileIOException])).asInstanceOf[A < (S & Abort[E | FileIOException])]

    // Records an observation, as a state function rather than as its own transition, so an
    // operation's observation and the entry it stages land together.
    //
    // Keeps the strongest observation recorded for a path. Commit validates the staged work against
    // what that work was built on, so if a caller checked that a path exists and later read it, the
    // read is what the staged work depends on. Ranking says that outright instead of leaving it to
    // whichever observation happened to arrive first.
    //
    // Returns the same state value when there is nothing to add, which is what lets this run inside a
    // retried transaction without spinning.
    private def withObservation(s: OverlayState, cp: CanonicalPath, observed: Observed): OverlayState =
        s.readSet.get(cp) match
            case Present(existing) if Observed.strength(existing) >= Observed.strength(observed) => s
            case _ => s.copy(readSet = s.readSet.updated(cp, observed))

    private def observe(cp: CanonicalPath, observed: Observed)(using Frame): Unit < S =
        modifyPure(withObservation(_, cp, observed))

    /** Records what a metadata question saw of a lower path, and reports its kind: `Absent` when the
      * path is missing, `Present(true)` for a regular file, `Present(false)` for a directory.
      *
      * No content read. `exists`, `stat`, `isDirectory` and `isRegularFile` all used to record the
      * full entry, which meant reading a whole file to answer a question that never consulted it and
      * then failing the commit if anything about those contents later changed.
      */
    private def observeKind(cp: CanonicalPath)(using Frame): Maybe[Boolean] < (S & Abort[FileReadException]) =
        val lp = lowerPath(cp)
        lower.exists(lp).map { found =>
            if !found then observe(cp, Observed.Missing).andThen(Maybe.empty[Boolean])
            else
                lower.isRegularFile(lp).map { isFile =>
                    observe(cp, Observed.Exists(isDirectory = !isFile)).andThen(Present(isFile))
                }
        }
    end observeKind

    // Records how a path came to hold what it holds. Unconditional, unlike an observation: the most
    // recent staging event is what a caller asking about identity now should be told about.
    private def withProvenance(s: OverlayState, cp: CanonicalPath, p: Provenance): OverlayState =
        s.copy(provenance = s.provenance.updated(cp.parts, p))

    // A token distinct from every other staging event on this overlay. The kind prefix carries no
    // meaning to a caller comparing two identities; it is there so a token read in a log says what
    // produced it.
    private def stagedToken(kind: String)(using Frame): String =
        // Unsafe: monotone counter increment for a per-event identity token. Read outside any
        // transaction, because a retried decision must not mint a fresh token on each pass.
        import AllowUnsafe.embrace.danger
        s"overlay-$kind:${uniqueSeq.unsafe.getAndIncrement().toHexString}"
    end stagedToken

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
            case Upper.Whiteout        => true
            case Upper.OpaqueDir(_, _) => true
            case _                     => false
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
                        else observeKind(cp).map(_.isDefined)
            }
        }

    def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (S & Abort[FileReadException]) = exists(path)

    def isDirectory(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout)                          => false
                    case Present(Upper.OpaqueDir(_, _))                   => true
                    case Present(Upper.Entry(Path.Entry.Directory(_), _)) => true
                    case Present(Upper.Entry(Path.Entry.File(_, _), _))   => false
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else observeKind(cp).map(_.fold(false)(isFile => !isFile))
            }
        }

    def isRegularFile(path: Path)(using Frame): Boolean < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            val lp = lowerPath(cp)
            withState { s =>
                s.upper.get(cp) match
                    case Present(Upper.Whiteout)                          => false
                    case Present(Upper.OpaqueDir(_, _))                   => false
                    case Present(Upper.Entry(Path.Entry.Directory(_), _)) => false
                    case Present(Upper.Entry(Path.Entry.File(_, _), _))   => true
                    case Absent =>
                        if ancestorWhiteout(s, cp) then false
                        else observeKind(cp).map(_.fold(false)(identity))
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
                    case Present(Upper.Entry(Path.Entry.File(bytes, _), _)) => bytes
                    case Present(_)                                         => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.readBytes(lp).map { bytes =>
                                lower.stat(lp).map { stat =>
                                    observe(cp, Observed.FileContent(bytes, stat)).andThen(bytes)
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
                    case Present(Upper.Entry(Path.Entry.File(_, ps), _))   => ps
                    case Present(Upper.Entry(Path.Entry.Directory(ps), _)) => ps
                    case Present(Upper.OpaqueDir(ps, _))                   => ps
                    case Present(Upper.Whiteout)                           => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.stat(lp).map { ps =>
                                lower.isRegularFile(lp).map { isFile =>
                                    observe(cp, Observed.Metadata(ps, isDirectory = !isFile)).andThen(ps)
                                }
                            }
            }
        }

    /** Reports a value that names the same file across two observations, or `Absent` when the path
      * has no identity to report.
      *
      * A watcher correlates a rename by comparing these, so a path this overlay staged has to answer
      * differently from whatever it replaced, and a moved file has to answer the same as its source.
      * Both come straight from the recorded provenance; a path the overlay never touched is the
      * lower's to answer.
      */
    override private[kyo] def stableIdentity(path: Path)(using Frame): Maybe[String] < (S & Abort[FileReadException]) =
        canonical(path).map { cp =>
            withState { s =>
                // Provenance is keyed by canonical path, so the walk starts from the canonical form
                // and every step below stays in canonical segments.
                //
                // A move chain is followed to its origin. The visited set bounds the walk: nothing in
                // the staging API builds a cycle today, and an unbounded chase would hang rather than
                // answer, which is harder to diagnose than a wrong answer.
                @annotation.tailrec
                def chase(parts: Chunk[String], visited: Set[Chunk[String]]): Maybe[Chunk[String]] =
                    if visited.contains(parts) then Absent
                    else
                        s.provenance.get(parts) match
                            case Present(Provenance.MovedFrom(origin)) => chase(origin, visited + parts)
                            case _                                     => Present(parts)

                // A path the overlay hides has no identity to report, whether it was removed outright
                // or is covered by a whiteout above it. That is asked of the path the caller named,
                // not of the origin a move chain ends at: a move whiteouts its own source, and the
                // content that left it is exactly what the target now answers for.
                val hidden = s.upper.get(cp).contains(Upper.Whiteout) || ancestorWhiteout(s, cp)
                if hidden then Absent
                else
                    chase(cp.parts, Set.empty) match
                        case Absent => Absent
                        case Present(origin) =>
                            s.provenance.get(origin) match
                                case Present(Provenance.Staged(token)) => Present(token)
                                // No record of its own, so the lower owns the answer.
                                case _ => lower.stableIdentity(pathFrom(origin))
                    end match
                end if
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
                    case Present(Upper.Whiteout)                        => Abort.fail(FileNotFoundException(path))
                    case Present(Upper.Entry(Path.Entry.File(_, _), _)) => Abort.fail(FileNotADirectoryException(path))
                    case maybeOpaque =>
                        val isOpaque = maybeOpaque.exists {
                            case Upper.OpaqueDir(_, _) => true
                            case _                     => false
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
                                        lower.list(lp).map { children =>
                                            // A listing depends on what the directory holds, so that is what is
                                            // recorded: the names as the lower returned them, before any upper
                                            // entry is subtracted. Commit compares the same set, which detects a
                                            // child appearing or vanishing exactly rather than inferring it from
                                            // the directory's modification time.
                                            observe(cp, Observed.DirectoryListing(children.flatMap(_.name).toSet)).andThen {
                                                // Drop lower children that have any upper entry (Entry, Whiteout, or OpaqueDir),
                                                // and re-express the survivors under the caller's path rather than the
                                                // canonical one the lower was asked about.
                                                children.collect {
                                                    case c if !upperSegs.contains(c.parts.last) => path / c.parts.last
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
                                observe(pcp, Observed.Missing).andThen {
                                    val stat  = Path.PathStat(0L, 0L)
                                    val token = stagedToken("directory")
                                    stage(parent, FileSystemOperation.Create) { current =>
                                        val opaque =
                                            current.upper.get(pcp).contains(Upper.Whiteout) || ancestorWhiteout(current, pcp)
                                        // No caller asked for a timestamp on a parent created to hold a write.
                                        val entry =
                                            if opaque then Upper.OpaqueDir(stat, Absent)
                                            else Upper.Entry(Path.Entry.Directory(stat), Absent)
                                        withProvenance(
                                            current.copy(upper = current.upper.updated(pcp, entry)),
                                            pcp,
                                            Provenance.Staged(token)
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
                val token = stagedToken("write")
                stage(path, FileSystemOperation.Write) { s =>
                    // A plain write asks for no particular modification time: the commit assigns one.
                    withProvenance(
                        s.copy(upper = s.upper.updated(cp, Upper.Entry(Path.Entry.File(value, stat), Absent))),
                        cp,
                        Provenance.Staged(token)
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
            val lp    = lowerPath(cp)
            val token = stagedToken("write")
            transact[FileReadException | FileWriteException, Unit](path, FileSystemOperation.Write) { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(existing, _), _)) =>
                        // Already in upper: concatenate without consulting lower, no observation to
                        // record. Both the base and the result come from the same snapshot, so the
                        // transaction rejects the merge if anything else appended in between.
                        (stagedAppend(s, cp, existing, value, token), ())
                    case _ =>
                        // Not in upper: read lower, record the observation, and stage the result all
                        // in the one state this transaction will apply.
                        // Ancestor Whiteout hides lower content; treat as absent, start fresh.
                        lower.exists(lp).map { lowerFound =>
                            if !lowerFound || ancestorWhiteout(s, cp) then
                                (stagedAppend(withObservation(s, cp, Observed.Missing), cp, Span.empty[Byte], value, token), ())
                            else
                                lower.readBytes(lp).map { existing =>
                                    lower.stat(lp).map { stat =>
                                        (
                                            stagedAppend(
                                                withObservation(s, cp, Observed.FileContent(existing, stat)),
                                                cp,
                                                existing,
                                                value,
                                                token
                                            ),
                                            ()
                                        )
                                    }
                                }
                        }
            }
        })

    // Concatenation and staging, shared by both arms of appendBytes so the two cannot drift.
    private def stagedAppend(
        s: OverlayState,
        cp: CanonicalPath,
        existing: Span[Byte],
        value: Span[Byte],
        token: String
    ): OverlayState =
        val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
        val stat   = Path.PathStat(0L, merged.size.toLong)
        withProvenance(
            s.copy(upper = s.upper.updated(cp, Upper.Entry(Path.Entry.File(merged, stat), Absent))),
            cp,
            Provenance.Staged(token)
        )
    end stagedAppend

    def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
        Frame
    ): Unit < (S & Abort[FileReadException | FileWriteException]) =
        append(path, value.mkString("", "\n", "\n"), options)

    def truncate(path: Path, size: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException]) =
        canonical(path).map { cp =>
            val lp    = lowerPath(cp)
            val token = stagedToken("write")
            transact[FileReadException | FileWriteException, Unit](path, FileSystemOperation.Write) { s =>
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(bytes, _), _)) =>
                        boundInt(path, "truncate size", size).map(sz => (stagedTruncate(s, cp, bytes, sz, token), ()))
                    case Present(_) => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.readBytes(lp).map { bytes =>
                                lower.stat(lp).map { lStat =>
                                    boundInt(path, "truncate size", size).map { sz =>
                                        (
                                            stagedTruncate(
                                                withObservation(s, cp, Observed.FileContent(bytes, lStat)),
                                                cp,
                                                bytes,
                                                sz,
                                                token
                                            ),
                                            ()
                                        )
                                    }
                                }
                            }
            }
        }

    // Truncation and staging, shared by both arms of truncate so the two cannot drift.
    private def stagedTruncate(s: OverlayState, cp: CanonicalPath, bytes: Span[Byte], size: Int, token: String): OverlayState =
        val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(size))
        val stat = Path.PathStat(0L, kept.size.toLong)
        withProvenance(
            s.copy(upper = s.upper.updated(cp, Upper.Entry(Path.Entry.File(kept, stat), Absent))),
            cp,
            Provenance.Staged(token)
        )
    end stagedTruncate

    def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException]) =
        canonical(path).map { cp =>
            val lp    = lowerPath(cp)
            val token = stagedToken("write")
            transact[FileReadException | FileWriteException, Unit](path, FileSystemOperation.Write) { s =>
                // The one caller-supplied timestamp in the module: this is the request the commit has
                // to honor, so it is recorded as an explicit modification time rather than left to
                // be inferred from the staged stat.
                def stagedFile(base: OverlayState, bytes: Span[Byte], stat: Path.PathStat): OverlayState =
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    withProvenance(
                        base.copy(upper = base.upper.updated(cp, Upper.Entry(Path.Entry.File(bytes, ns), Present(epochMs)))),
                        cp,
                        Provenance.Staged(token)
                    )
                end stagedFile
                def stagedDir(base: OverlayState, stat: Path.PathStat, opaque: Boolean): OverlayState =
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    val entry =
                        if opaque then Upper.OpaqueDir(ns, Present(epochMs))
                        else Upper.Entry(Path.Entry.Directory(ns), Present(epochMs))
                    withProvenance(
                        base.copy(upper = base.upper.updated(cp, entry)),
                        cp,
                        Provenance.Staged(token)
                    )
                end stagedDir
                s.upper.get(cp) match
                    case Present(Upper.Entry(Path.Entry.File(bytes, stat), _)) => (stagedFile(s, bytes, stat), ())
                    case Present(Upper.Entry(Path.Entry.Directory(stat), _))   => (stagedDir(s, stat, opaque = false), ())
                    case Present(Upper.OpaqueDir(stat, _))                     => (stagedDir(s, stat, opaque = true), ())
                    case Present(Upper.Whiteout)                               => Abort.fail(FileNotFoundException(path))
                    case Absent =>
                        if ancestorWhiteout(s, cp) then Abort.fail(FileNotFoundException(path))
                        else
                            lower.stat(lp).map { stat =>
                                lower.isRegularFile(lp).map { isFile =>
                                    if isFile then
                                        // The bytes read precedes the stamp so the read-set entry and the staged
                                        // upper entry share the same observed bytes (no double read).
                                        lower.readBytes(lp).map { bytes =>
                                            (stagedFile(withObservation(s, cp, Observed.FileContent(bytes, stat)), bytes, stat), ())
                                        }
                                    else (stagedDir(withObservation(s, cp, Observed.Exists(isDirectory = true)), stat, opaque = false), ())
                                }
                            }
                end match
            }
        }

    def mkDir(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException]) =
        canonical(path).map { cp =>
            val lp    = lowerPath(cp)
            val token = stagedToken("directory")
            transact[FileReadException | FileStructureException, Unit](path, FileSystemOperation.Create) { s =>
                def staged(base: OverlayState, entry: Upper): OverlayState =
                    withProvenance(
                        base.copy(upper = base.upper.updated(cp, entry)),
                        cp,
                        Provenance.Staged(token)
                    )
                s.upper.get(cp) match
                    case Present(Upper.OpaqueDir(_, _))                   => (s, ()) // already opaque dir
                    case Present(Upper.Entry(Path.Entry.Directory(_), _)) => (s, ()) // already a dir in upper
                    case _                                                =>
                        // If lower has a directory at this path, create OpaqueDir (hides lower children).
                        // If lower has a file or absent, create a regular directory entry.
                        // mkDir asks for a directory, not for a directory dated a particular way, so
                        // no arm here records an explicit modification time.
                        lower.exists(lp).map { exists =>
                            if !exists then
                                val st = Path.PathStat(0L, 0L)
                                (
                                    staged(withObservation(s, cp, Observed.Missing), Upper.Entry(Path.Entry.Directory(st), Absent)),
                                    ()
                                )
                            else
                                lower.stat(lp).map { stat =>
                                    lower.isDirectory(lp).map { isDir =>
                                        // An existing lower dir (or file) gets OpaqueDir, hiding its children.
                                        val st = if isDir then stat else Path.PathStat(0L, 0L)
                                        if isDir then
                                            (
                                                staged(
                                                    withObservation(s, cp, Observed.Exists(isDirectory = true)),
                                                    Upper.OpaqueDir(st, Absent)
                                                ),
                                                ()
                                            )
                                        else
                                            lower.readBytes(lp).map { bytes =>
                                                (
                                                    staged(
                                                        withObservation(s, cp, Observed.FileContent(bytes, stat)),
                                                        Upper.OpaqueDir(st, Absent)
                                                    ),
                                                    ()
                                                )
                                            }
                                        end if
                                    }
                                }
                        }
                end match
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
                    // The source read, the target guard and the staging are one transition: resolving
                    // the source and then staging against a later state is what lets a target created
                    // in between slip past Replace.Never.
                    val moveToken = stagedToken("copy")
                    transact[FileReadException | FileWriteException | FileStructureException, Unit](
                        to,
                        FileSystemOperation.Move
                    ) { s =>
                        resolveEntry(from).map { resolved =>
                            checkMoveTarget(to, options.replace) {
                                resolved match
                                    case file: Path.Entry.File =>
                                        // A file move keeps single-node semantics: whiteout the source and stage the
                                        // resolved file at the target. Staging the resolved content rather than a
                                        // reference to the source is what makes the commit source-independent.
                                        // The target takes the source's identity so a rename reads as one file
                                        // moving rather than one vanishing and another appearing.
                                        //
                                        // A move preserves the source's modification time, so the target records it
                                        // as explicit: a plain write's placeholder would date the moved file to the
                                        // commit instead of to when its content was last changed.
                                        val moved = s.copy(
                                            upper = s.upper
                                                .updated(fromCp, Upper.Whiteout)
                                                .updated(toCp, Upper.Entry(file, Present(file.stat.lastModifiedMs)))
                                        )
                                        (withProvenance(moved, toCp, Provenance.MovedFrom(fromCp.parts)), ())
                                    case _: Path.Entry.Directory =>
                                        // A directory move relocates the entire subtree: every descendant visible in
                                        // the overlay view (upper-staged plus lower-only) is materialized under `to`,
                                        // and the whole source subtree is whiteouted so it is fully gone.
                                        collectSubtree(from).map { nodes =>
                                            val upperT = stageSubtree(s.upper, toCp, nodes)
                                            // Whiteout the source dir and every upper descendant of it (a direct
                                            // upper Entry outranks an ancestor whiteout, so each must be marked);
                                            // the whiteout at the source replays as a recursive removal, which is
                                            // what drops the subtree from the lower on commit.
                                            val srcKeys = s.upper.descendantValues(fromCp).map(_._1).toList
                                            val upperW =
                                                srcKeys.foldLeft(upperT.updated(fromCp, Upper.Whiteout))((u, k) =>
                                                    u.updated(k, Upper.Whiteout)
                                                )
                                            // A directory move materializes its subtree as fresh staged content and
                                            // drops the source, so the target holds new content rather than the
                                            // source's identity.
                                            (withProvenance(s.copy(upper = upperW), toCp, Provenance.Staged(moveToken)), ())
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
                    // One transition, for the same reason as move: the target guard and the staging
                    // have to see the same state, or a target created in between defeats
                    // Replace.Never.
                    val copyToken = stagedToken("copy")
                    transact[FileReadException | FileWriteException | FileStructureException, Unit](
                        to,
                        FileSystemOperation.Copy
                    ) { s =>
                        resolveEntry(from).map { resolved =>
                            checkMoveTarget(to, options.replace) {
                                resolved match
                                    case file: Path.Entry.File =>
                                        val copied =
                                            if options.copyAttributes then file
                                            else file.copy(stat = Path.PathStat(0L, file.stat.sizeBytes))
                                        // copyAttributes is exactly the caller asking for the source's timestamp to
                                        // carry over. Without it the copy is new content and the commit dates it.
                                        val mtime       = if options.copyAttributes then Present(file.stat.lastModifiedMs) else Absent
                                        val copiedState = s.copy(upper = s.upper.updated(toCp, Upper.Entry(copied, mtime)))
                                        // A copy is new content at the target, so it gets an identity of its own
                                        // rather than the source's.
                                        (withProvenance(copiedState, toCp, Provenance.Staged(copyToken)), ())
                                    case _: Path.Entry.Directory =>
                                        // A directory copy materializes the entire subtree under `to`, leaving the
                                        // source intact (no whiteout, no Remove op).
                                        collectSubtree(from).map { nodes =>
                                            val upperT = stageSubtree(s.upper, toCp, nodes, options.copyAttributes)
                                            (
                                                withProvenance(s.copy(upper = upperT), toCp, Provenance.Staged(copyToken)),
                                                ()
                                            )
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
    // becomes an upper entry carrying its captured content and metadata, so the commit is
    // source-independent: it writes what was captured rather than re-reading a source that may be
    // gone by then.
    //
    // Each node's key is built by extending `to` with the node's suffix, rather than resolving each
    // descendant on its own. A descendant staged into this overlay has no lower entry to resolve
    // against, so resolving it independently could land it in a different key space than the parent
    // it was collected under.
    private def stageSubtree(
        upper: UpperTrie,
        to: CanonicalPath,
        nodes: Chunk[(Chunk[String], Path.Entry)],
        copyAttributes: Boolean = true
    ): UpperTrie =
        nodes.foldLeft(upper) { case (u, (suffix, entry)) =>
            val targetCp = suffix.foldLeft(to)((cp, seg) => cp.append(seg))
            // Per node, the same split the single-file arms make: copyAttributes is the caller
            // asking for the source's timestamp, and without it the commit dates the new content.
            entry match
                case Path.Entry.File(bytes, stat) =>
                    val targetStat = if copyAttributes then stat else Path.PathStat(0L, stat.sizeBytes)
                    val mtime      = if copyAttributes then Present(stat.lastModifiedMs) else Absent
                    u.updated(targetCp, Upper.Entry(Path.Entry.File(bytes, targetStat), mtime))
                case Path.Entry.Directory(stat) =>
                    val targetStat = if copyAttributes then stat else Path.PathStat(0L, stat.sizeBytes)
                    val mtime      = if copyAttributes then Present(stat.lastModifiedMs) else Absent
                    u.updated(targetCp, Upper.OpaqueDir(targetStat, mtime))
            end match
        }
    end stageSubtree

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
                    case Present(Upper.Entry(e, _))        => e
                    case Present(Upper.OpaqueDir(stat, _)) => Path.Entry.Directory(stat): Path.Entry
                    case Present(Upper.Whiteout)           => Abort.fail(FileNotFoundException(path))
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
                                                    observe(cp, Observed.FileContent(bytes, stat)).andThen {
                                                        Path.Entry.File(bytes, stat): Path.Entry
                                                    }
                                                }
                                            }
                                        else
                                            lower.stat(lp).map { stat =>
                                                observe(cp, Observed.Exists(isDirectory = true)).andThen {
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
            transact[FileReadException | FileStructureException, Boolean](path, FileSystemOperation.Remove) { s =>
                def staged(base: OverlayState): OverlayState =
                    base.copy(upper = base.upper.updated(cp, Upper.Whiteout))
                s.upper.get(cp) match
                    case Present(Upper.Whiteout) => (s, false)
                    case Present(_)              => (staged(s), true)
                    case Absent =>
                        lower.exists(lp).map { found =>
                            if !found then (withObservation(s, cp, Observed.Missing), false)
                            else
                                // The removal's base is recorded before the whiteout hides it, so commit
                                // still has something to validate the removal against.
                                observedLower(s, cp).map(observed => (staged(observed), true))
                        }
                end match
            }
        }

    // Records the base a removal is taken against, for use inside a transaction.
    //
    // Deliberately stronger than what `remove` literally looks at, which is only whether the path is
    // there. A commit replays a removal as a recursive delete, so the cost of recording too little is
    // deleting a file that changed after the caller decided to remove it. Reading the contents here
    // makes that a conflict the caller can resolve instead. A directory is recorded by presence: its
    // contents are not read, and the removal takes the whole subtree either way.
    private def observedLower(s: OverlayState, cp: CanonicalPath)(using
        Frame
    ): OverlayState < (S & Abort[FileReadException]) =
        val path = lowerPath(cp)
        lower.exists(path).map { found =>
            if !found then withObservation(s, cp, Observed.Missing)
            else
                lower.stat(path).map { stat =>
                    lower.isRegularFile(path).map { isFile =>
                        if isFile then lower.readBytes(path).map(bytes => withObservation(s, cp, Observed.FileContent(bytes, stat)))
                        else withObservation(s, cp, Observed.Exists(isDirectory = true))
                    }
                }
        }
    end observedLower

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
                            case Present(Upper.Entry(Path.Entry.File(bytes, _), _)) => Present(bytes)
                            case _                                                  => Absent
                    else Present(Span.empty[Byte])

                upperSeed match
                    case Present(seed) => mkWriteHandle(cp, seed)
                    case Absent        =>
                        // append mode with no upper entry: seed from lower
                        lower.exists(lp).map { found =>
                            if !found then observe(cp, Observed.Missing).andThen(mkWriteHandle(cp, Span.empty[Byte]))
                            else
                                lower.readBytes(lp).map { bytes =>
                                    lower.stat(lp).map { stat =>
                                        observe(cp, Observed.FileContent(bytes, stat)).andThen(mkWriteHandle(cp, bytes))
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
                    // A handle write asks for no particular modification time, like every plain write.
                    cur.copy(upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.File(bytes, stat), Absent)))
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
                    val cp = canonicalPure(cur, temporary)
                    // Dropping the staged entry is the whole of the removal now. It used to also
                    // have to filter a parallel log by path, and the two predicates could disagree
                    // about which record belonged to this path.
                    cur.copy(
                        upper = cur.upper.removed(cp),
                        readSet = cur.readSet.removed(cp),
                        provenance = cur.provenance.removed(cp.parts)
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
                val token = stagedToken("directory")
                stage(dir, FileSystemOperation.Create) { cur =>
                    withProvenance(
                        cur.copy(upper = cur.upper.updated(cp, Upper.Entry(Path.Entry.Directory(stat), Absent))),
                        cp,
                        Provenance.Staged(token)
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
                                modify(path, FileSystemOperation.Channel) { current =>
                                    current.upper.get(cp) match
                                        case Present(Upper.Entry(_, _) | Upper.OpaqueDir(_, _)) =>
                                            Result.fail(FileAlreadyExistsException(path))
                                        case _ =>
                                            // Creating a channel target asks for no particular timestamp.
                                            Result.succeed((
                                                current.copy(
                                                    upper = current.upper.updated(
                                                        cp,
                                                        Upper.Entry(Path.Entry.File(Span.empty[Byte], stat), Absent)
                                                    )
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

    // Claims the commit by closing the overlay to writes for its duration.
    //
    // The close has to happen before the staged state is read, not after it is validated: a write
    // admitted in between would be added to a plan already taken and then erased by the reset that
    // ends the commit, so the caller's write would succeed and land nowhere.
    private def beginCommit(action: String)(using Frame): Unit < (Sync & Abort[CommitConflict]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                cur.phase match
                    case Phase.Open =>
                        state.compareAndSet(cur, cur.copy(phase = Phase.Committing)).map {
                            case true  => Loop.done(())
                            case false => Loop.continue(())
                        }
                    case Phase.Committing | Phase.Terminated =>
                        Abort.fail(FileSystem.StagedChanges.AlreadyTerminated(action))
            }
        }

    // Reopens an overlay whose commit could not proceed. A conflicting commit is not terminal: the
    // caller's next move is commitWith, which needs the staged state intact and further writes
    // admitted.
    private def abandonCommit(using Frame): Unit < Sync =
        Loop(()) { _ =>
            state.get.map { cur =>
                state.compareAndSet(cur, cur.copy(phase = Phase.Open)).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }

    // Ends the overlay's life, discarding whatever is still staged.
    private def terminate(using Frame): Unit < Sync =
        Loop(()) { _ =>
            state.get.map { cur =>
                state.compareAndSet(cur, OverlayState.terminated).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }

    private[kyo] def discardOnScopeExit(using Frame): Unit < Sync = terminate

    def discard(using Frame): Unit < (S & Sync & Abort[FileSystem.StagedChanges.TerminalState]) =
        state.get.map { cur =>
            if cur.phase != Phase.Open then Abort.fail(FileSystem.StagedChanges.AlreadyTerminated("discard"))
            else terminate
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
            case (accKyo, (cp, observed)) =>
                accKyo.map { acc =>
                    val path = lowerPath(cp)
                    liveShape(path).map { live =>
                        stillMatches(path, observed, live).map { matches =>
                            if matches then acc
                            else
                                val ours = s.upper.get(cp).fold[Maybe[Path.Entry]](Absent)(upperToEntry)
                                liveEntry(path, live).map { theirs =>
                                    acc.appended((cp, Conflict(path, ancestorOf(observed), ours, theirs)))
                                }
                        }
                    }
                }
        }

    // The live lower's shape at `path`: Absent when missing, otherwise its stat and whether it is a
    // regular file. Read once per validated path and shared by the comparison and the report.
    private def liveShape(path: Path)(using Frame): Maybe[(Path.PathStat, Boolean)] < (S & Abort[FileSystemException]) =
        lower.exists(path).map { found =>
            if !found then Maybe.empty[(Path.PathStat, Boolean)]
            else lower.stat(path).map(stat => lower.isRegularFile(path).map(isFile => Present((stat, isFile))))
        }

    // True when the live lower still matches what was observed.
    //
    // Each observation is checked against what it actually claimed. Checking more than was observed
    // reports conflicts the staged work does not depend on; checking less lets a real divergence
    // through. That is the whole contract, and it is why the observation kinds exist.
    private def stillMatches(path: Path, observed: Observed, live: Maybe[(Path.PathStat, Boolean)])(using
        Frame
    ): Boolean < (S & Abort[FileSystemException]) =
        live match
            case Absent =>
                // Every observation but one claimed the path was there.
                observed == Observed.Missing
            case Present((liveStat, isFile)) =>
                observed match
                    case Observed.Missing             => false
                    case Observed.Exists(isDirectory) =>
                        // Existence and kind only. Comparing a stat here would make any edit next to
                        // the observation a conflict, and a caller that only asked whether the path
                        // was there does not depend on its size, its timestamp or its contents.
                        isDirectory != isFile
                    case Observed.DirectoryListing(children) =>
                        // Compared by child names rather than by modification time: it is what `list`
                        // returned, so it detects an added or removed child exactly, without
                        // depending on the host's timestamp granularity.
                        if isFile then false
                        else lower.list(path).map(entries => entries.flatMap(_.name).toSet == children)
                    case Observed.Metadata(stat, isDirectory) =>
                        // A caller that asked for a stat may depend on any of it.
                        isDirectory != isFile && stat.sizeBytes == liveStat.sizeBytes &&
                        stat.lastModifiedMs == liveStat.lastModifiedMs
                    case Observed.FileContent(bytes, stat) =>
                        // Size first, because it rejects most divergences without reading the file.
                        // Content second, because a same-size edit inside one timestamp tick is
                        // invisible to stat and the observed bytes are already in hand to detect it.
                        if !isFile || stat.sizeBytes != liveStat.sizeBytes then false
                        else lower.readBytes(path).map(liveBytes => bytes.toArrayUnsafe.sameElements(liveBytes.toArrayUnsafe))
        end match
    end stillMatches

    // The live entry reported as `theirs` on a divergence. Only reached once a path has diverged, so
    // the content read here is not on the path of a commit that succeeds.
    private def liveEntry(path: Path, live: Maybe[(Path.PathStat, Boolean)])(using
        Frame
    ): Maybe[Path.Entry] < (S & Abort[FileSystemException]) =
        live match
            case Absent => Absent
            case Present((stat, isFile)) =>
                if isFile then lower.readBytes(path).map(bytes => Present(Path.Entry.File(bytes, stat)))
                else Present[Path.Entry](Path.Entry.Directory(stat))

    // The observation a conflict is measured against, as the caller sees it.
    private def ancestorOf(observed: Observed): Conflict.Ancestor =
        observed match
            case Observed.Missing                  => Conflict.Ancestor.Missing
            case Observed.Exists(isDirectory)      => Conflict.Ancestor.Presence(isDirectory)
            case Observed.DirectoryListing(names)  => Conflict.Ancestor.DirectoryListing(names)
            case Observed.Metadata(stat, isDir)    => Conflict.Ancestor.Metadata(stat, isDir)
            case Observed.FileContent(bytes, stat) => Conflict.Ancestor.Content(Path.Entry.File(bytes, stat))

    private def upperToEntry(u: Upper): Maybe[Path.Entry] =
        u match
            case Upper.Entry(e, _)      => Present(e)
            case Upper.OpaqueDir(st, _) => Present(Path.Entry.Directory(st))
            case Upper.Whiteout         => Absent

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
    // files in stagePlan so that if the process crashes after staging but before the intent
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

    // Stage file content for every plan entry that carries bytes. Writes each as "e<i>.dat"
    // inside stagingDir via the durable write path so staged bytes survive power loss.
    // Directory entries and whiteouts have no staged file.
    //
    // The index is the entry's position in the plan, and it is the index applyReplayEntry reads
    // back. Both walk the plan the intent log records, so a recovered commit finds each staged
    // file under the name the commit that wrote it used.
    private def stagePlan(stagingDir: Path, plan: Chunk[ReplayEntry])(using Frame): Unit < (S & Abort[FileSystemException]) =
        plan.zipWithIndex.foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (acc, (entry, i)) =>
            acc.andThen {
                entry match
                    case ReplayEntry.File(_, bytes, _, _) => stageDurableFile(stagingDir / s"e$i.dat", bytes)
                    case _                                => ()
            }
        }

    // Encodes the commit plan with WriteOpLog and writes it to "intent.kyo" in the staging dir
    // via the openWrite/finish/close path. finish() is the completion boundary: its absence
    // (crash during write) leaves the file absent or partial, so recovery skips. A plain
    // writeBytes call lacks the two-phase open/finish contract; a crash mid-write leaves no sealed log.
    private def writeIntentLog(stagingDir: Path, plan: Chunk[ReplayEntry])(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException]) =
        val logBytes = WriteOpLog.encode(plan)
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

    // Apply one plan entry during the commit apply phase. File entries move atomically from their
    // staged copy to the final lower path (POSIX rename on host; CAS on in-memory). Each atomic
    // move is followed by a required parent-directory sync so the renamed dirent is durable after
    // power loss.
    //
    // `idempotent` marks the recovery path: a staged file may already have been moved into place,
    // so its absence means the step is done rather than that the commit is broken.
    private def applyReplayEntry(stagingDir: Path, i: Int, entry: ReplayEntry, idempotent: Boolean)(using
        Frame
    ): Unit < (S & Abort[FileSystemException]) =
        entry match
            case ReplayEntry.Whiteout(parts) =>
                lower.removeAll(pathFrom(parts))
            case ReplayEntry.Directory(parts, opaque, _) =>
                val target = pathFrom(parts)
                // An opaque directory is the overlay's whole answer for that path: reads through the
                // overlay show only the staged children, so the committed directory must hold only
                // those. Clearing first is what makes the two agree. The staged children are written
                // after this entry, because the plan yields a node before its children.
                //
                // No timestamp is applied here. Writing those children moves the directory's
                // modification time again, so a stamp at this point would not survive to the end of
                // the commit. restampDirectories settles it once every file beneath it is final.
                val clear: Unit < (S & Abort[FileSystemException]) =
                    if opaque then lower.removeAll(target) else ()
                clear.andThen(lower.mkDir(target))
            case ReplayEntry.File(parts, _, _, mtime) =>
                val target = pathFrom(parts)
                val staged = stagingDir / s"e$i.dat"
                val materialize =
                    if idempotent then
                        lower.exists(staged).flatMap(has => if has then moveInto(staged, target) else ())
                    else moveInto(staged, target)
                val stamp: Unit < (S & Abort[FileSystemException]) =
                    mtime.fold[Unit < (S & Abort[FileSystemException])]((): Unit)(ms => lower.setLastModified(target, ms))
                materialize.andThen(stamp).andThen(syncParentOf(target))
        end match
    end applyReplayEntry

    private def moveInto(staged: Path, target: Path)(using Frame): Unit < (S & Abort[FileSystemException]) =
        lower.move(staged, target, Path.MoveOptions(replace = Path.Replace.Existing, atomicity = Path.Atomicity.Required))

    // Writing a file into a directory changes that directory's modification time on a real
    // filesystem, so a directory stamped before its children are placed does not keep its stamp.
    // Restamping afterwards, deepest first, settles each directory only once every file beneath it
    // is final. Derived from the same plan the first pass used, so the two cannot disagree.
    private def restampDirectories(plan: Chunk[ReplayEntry])(using Frame): Unit < (S & Abort[FileSystemException]) =
        val dirs = plan.collect {
            case ReplayEntry.Directory(parts, _, Present(mtime)) => (parts, mtime)
        }
        dirs.reverse.foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (acc, (parts, mtime)) =>
            acc.andThen(lower.setLastModified(pathFrom(parts), mtime))
        }
    end restampDirectories

    // 5-step durable commit protocol driven by a pre-created staging directory.
    // (1) Stage file content; (2) write intent log + terminator; (3) apply each plan entry with a
    // per-entry hook; (4) restamp staged directory timestamps; (5) write committed.marker. Each
    // crash hook is a test-injection point: recovery tests set the hook to throw, then call
    // recover() to verify resumption.
    private def applyResolved(stagingDir: Path, plan: Chunk[ReplayEntry])(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException]) =
        val n = plan.size
        stagePlan(stagingDir, plan).andThen {
            runHook(afterStageHook).andThen { // crash point 1
                writeIntentLog(stagingDir, plan).andThen {
                    runHook(afterIntentLogHook).andThen { // crash point 2
                        plan.zipWithIndex
                            .foldLeft[Unit < (S & Abort[FileSystemException])](()) { case (acc, (entry, i)) =>
                                acc.andThen {
                                    applyReplayEntry(stagingDir, i, entry, idempotent = false).andThen {
                                        runHookKN(afterEntryApplyHook, i + 1, n) // crash point 3
                                    }
                                }
                            }
                            .andThen {
                                restampDirectories(plan).andThen {
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
    private def withCommit(plan: Chunk[ReplayEntry])(using Frame): Unit < (S & Sync & Abort[FileSystemException]) =
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
                                applyResolved(handle.path, plan).andThen {
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
        beginCommit("commit").andThen {
            stateGet.map { s =>
                validate(s).map { conflicts =>
                    if conflicts.isEmpty then withCommit(s.replayPlan).andThen(terminate)
                    else abandonCommit.andThen(Abort.fail(CommitConflict(conflicts.map(_._2))))
                }
            }
        }

    def commitWith(resolve: FileSystem.Conflict => FileSystem.Resolution)(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException] & Abort[CommitConflict]) =
        beginCommit("commitWith").andThen {
            stateGet.map { s =>
                validate(s).map { conflicts =>
                    // Collect one Resolution per conflicted path, then rebuild the upper layer so
                    // the replay reflects every resolution.
                    conflicts.foldLeft[Chunk[(CanonicalPath, Resolution)] < (S & Abort[FileSystemException])](Chunk.empty) {
                        case (accKyo, (cp, conflict)) =>
                            accKyo.map(resolutions => resolutions.appended((cp, resolve(conflict))))
                    }.map { resolutions =>
                        // Four point updates on the trie. Each resolution says what the path should
                        // hold, and the plan follows from that.
                        //
                        // This used to also strip a parallel log by path predicate, and the two
                        // could not be kept in step: a staged write later moved elsewhere produced
                        // two records, and dropping the one at the resolved path dropped the move
                        // with it, so the target the staged view showed was never created.
                        val newUpper = resolutions.foldLeft(s.upper) { case (upper, (cp, resolution)) =>
                            resolution match
                                case Resolution.KeepOurs     => upper
                                case Resolution.KeepTheirs   => upper.removed(cp)
                                case Resolution.Write(entry) =>
                                    // The caller supplied the entry whole, timestamp included, so the
                                    // commit honors that timestamp rather than dating the write itself.
                                    val mtime = entry match
                                        case Path.Entry.File(_, stat)   => stat.lastModifiedMs
                                        case Path.Entry.Directory(stat) => stat.lastModifiedMs
                                    upper.updated(cp, Upper.Entry(entry, Present(mtime)))
                                case Resolution.Remove => upper.updated(cp, Upper.Whiteout)
                        }
                        val resolved = s.copy(upper = newUpper)
                        modifyPure(_.copy(upper = newUpper)).andThen {
                            withCommit(resolved.replayPlan).andThen(terminate)
                        }
                    }
                }
            }
        }

    // Recovers a single staging directory: reads the intent log, re-applies its plan idempotently
    // (skipping entries already applied to the lower), writes the committed marker if absent, then
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
                                case Result.Success(Present(plan)) =>
                                    plan.zipWithIndex
                                        .foldLeft[Unit < (S & Sync & Abort[FileSystemException])](()) {
                                            case (acc, (entry, i)) =>
                                                acc.andThen(applyReplayEntry(stagingDir, i, entry, idempotent = true))
                                        }
                                        .andThen {
                                            restampDirectories(plan).andThen {
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

    // Scans the lower service's root for orphaned staging directories (kyo-commit-* prefix) left by
    // a prior process crash and recovers each via recoverStagingDir. This is the scan behind
    // FileSystem.overlayRecovering, which runs it before handing the overlay back.
    //
    // Stays private[kyo] because a caller reaches it through that constructor rather than on its
    // own: running it against a lower an overlay is already staging onto would replay a commit
    // underneath work built on the pre-replay view.
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
    isolate: Isolate[S, Sync, S2]
) extends OverlayFileSystem[S](lower, state, uniqueSeq), FileSystem.Watch[S & Sync]:

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
