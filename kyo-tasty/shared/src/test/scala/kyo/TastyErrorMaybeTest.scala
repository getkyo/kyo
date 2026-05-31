package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.symbol.SymbolId
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Tests pinning INV-007: TastyError.NotImplemented is reserved for genuinely-deferred features only.
  *
  * INV-007 (produced by Phase 03, consumed by Phases 04, 06, 10): TastyError.NotImplemented is returned only when a TASTy feature is not
  * yet implemented in this version of kyo-tasty. It is never returned for "this attribute does not apply to this symbol kind" (use
  * Maybe.Absent) and never returned for an unrecognized TASTy tag during tree decode (use Tree.Unknown for graceful degradation).
  */
class TastyErrorMaybeTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Snapshot building helper ────────────────────────────────────────────

    /** Build a minimal valid KRFL snapshot byte array containing the given ERRORS section payload.
      *
      * The snapshot has only a NAMES section (0 names), a SYMBOLS section (0 symbols), and an ERRORS section with the provided payload
      * bytes. This is the minimum required to make SnapshotReader.read succeed.
      */
    private def buildSnapshotWithErrors(errorsPayload: Array[Byte]): Array[Byte] =
        val namesPayload = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(namesPayload, 0, 0) // 0 names
        val symbolsPayload = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(symbolsPayload, 0, 0) // 0 symbols

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesPayload),
            (SnapshotFormat.sectionSYMBOLS, symbolsPayload),
            (SnapshotFormat.sectionERRORS, errorsPayload)
        )

        val sectionCount     = sections.length
        val sectionIndexSize = 4 + sectionCount * SnapshotFormat.sectionIndexEntrySize
        val headerSize       = SnapshotFormat.headerSize + sectionIndexSize

        var offset = headerSize.toLong
        val sectionMeta = sections.map: (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry

        val totalSize = offset.toInt
        val buf       = new Array[Byte](totalSize)

        // Magic "KRFL"
        buf(0) = 'K'
        buf(1) = 'R'
        buf(2) = 'F'
        buf(3) = 'L'
        // Version: current major and minor
        buf(4) = SnapshotFormat.majorVersion.toByte
        buf(5) = SnapshotFormat.minorVersion.toByte
        // flags (LE = 0)
        SnapshotFormat.writeInt64LE(buf, 8, 0L)
        // inputDigest (zeros)
        SnapshotFormat.writeInt64LE(buf, 16, 0L)
        // reserved (zeros)
        SnapshotFormat.writeInt64LE(buf, 24, 0L)
        // section count
        SnapshotFormat.writeInt32LE(buf, 32, sectionCount)
        // section index entries
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buf, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionLen)
            idxPos += 8
        end for
        // Copy payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buf, sectionOffset.toInt, bytes.length)
        buf
    end buildSnapshotWithErrors

    /** Encode an ERRORS section with the given list of error strings. */
    private def makeErrorsPayload(msgs: Seq[String]): Array[Byte] =
        val encoded = msgs.map(SnapshotFormat.encodeString)
        val size    = 4 + encoded.map(b => 4 + b.length).sum
        val buf     = new Array[Byte](size)
        SnapshotFormat.writeInt32LE(buf, 0, msgs.length)
        var pos = 4
        for bytes <- encoded do
            SnapshotFormat.writeInt32LE(buf, pos, bytes.length)
            pos += 4
            java.lang.System.arraycopy(bytes, 0, buf, pos, bytes.length)
            pos += bytes.length
        end for
        buf
    end makeErrorsPayload

    // ── In-memory FileSource (minimal) ──────────────────────────────────────

    final class MemoryFileSource extends kyo.internal.tasty.query.FileSource:
        private val files                               = mutable.HashMap.empty[String, Array[Byte]]
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.empty)
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.SnapshotIoError(s"rename not supported in test"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def exists(path: String)(using Frame): Boolean < Sync                    = Sync.defer(files.contains(path))
        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // ── Leaf 1: NotImplemented usage audit ─────────────────────────────────
    //
    // INV-007 states that NotImplemented is only used in forward-compat deserialisation.
    // This test pins the contract by verifying that:
    // (a) A recognized error string prefix ("FileNotFound(...)") round-trips to the
    //     correct TastyError variant and NOT to NotImplemented.
    // (b) An unrecognized error string prefix ("FutureError(...)") produces NotImplemented.
    // This distinguishes the two call sites in SnapshotReader.parseErrorString from
    // any hypothetical misuse.

    "INV-007 leaf-1: recognized error prefix round-trips without NotImplemented; unrecognized yields NotImplemented" in run {
        val recognized   = "FileNotFound(/some/path.tasty)"
        val unrecognized = "FutureError(some unknown future error kind)"

        val errorsPayload = makeErrorsPayload(Seq(recognized, unrecognized))
        val snapshotBytes = buildSnapshotWithErrors(errorsPayload)

        val src = MemoryFileSource()
        src.add("cache/test.krfl", snapshotBytes)

        Abort.run[TastyError]:
            InternalClasspath.allocate.flatMap: rawCp =>
                SnapshotReader.read("cache/test.krfl", src, rawCp).map: _ =>
                    val errors = rawCp.accumulatedErrors
                    // Recognized string must map to the concrete variant, not NotImplemented.
                    val fileNotFoundEntries = errors.filter:
                        case TastyError.FileNotFound(_) => true
                        case _                          => false
                    assert(
                        fileNotFoundEntries.length == 1,
                        s"Expected exactly one FileNotFound entry; got: $errors"
                    )
                    // Unrecognized string must map to NotImplemented.
                    val notImplEntries = errors.filter:
                        case TastyError.NotImplemented(_) => true
                        case _                            => false
                    assert(
                        notImplEntries.length == 1,
                        s"Expected exactly one NotImplemented entry; got: $errors"
                    )
                    // Verify the NotImplemented feature string contains "deserialized: " prefix.
                    notImplEntries.head match
                        case TastyError.NotImplemented(feature) =>
                            assert(
                                feature.startsWith("deserialized: "),
                                s"NotImplemented feature should start with 'deserialized: ' but got: $feature"
                            )
                        case _ => ()
                    end match
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 2: TreeUnpickler unrecognized-tag returns Tree.Unknown (not NotImplemented) ─
    //
    // The plan anticipated that TreeUnpickler would return NotImplemented for unrecognized tags.
    // Audit shows TreeUnpickler does NOT use NotImplemented at all: unknown category-2-4 tags
    // produce Tree.Unknown for graceful degradation. This test pins the ACTUAL contract.
    //
    // Setup: build a SymbolBody whose slice contains tag 77 (a category-2 gap in the
    // [60,89] range; not assigned in TastyFormat). decodeSync dispatches through
    // decodeSymBody -> readTree -> decodeTreeTag -> "Unknown category 2-4" arm -> Tree.Unknown(77, 0).
    // The Unresolved kind is used so decodeSymBody falls into the generic readTree path.

    "INV-007 leaf-2: TreeUnpickler unknown category-2 tag produces Tree.Unknown not TastyError.NotImplemented" in run {
        // Tag 77 is in the category-2 range (60-89) and is not assigned in TastyFormat.
        // Category-2 tag body is: [tag][Nat]. Nat 0x80 encodes value 0 in TASTy Nat encoding.
        val unknownCat2Tag: Byte      = 77.toByte
        val natZero: Byte             = 0x80.toByte // TASTy single-byte Nat: high bit set, value = low 7 bits = 0
        val sectionBytes: Array[Byte] = Array(unknownCat2Tag, natZero)

        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name("testSym")
        )
        val body = Tasty.SymbolBody(
            bodyStart = 0,
            bodyEnd = sectionBytes.length,
            sectionBytes = sectionBytes,
            names = Array.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId]
        )

        Sync.defer:
            val result =
                try
                    val tree = TreeUnpickler.decodeSync(body, sym, _ => sym)
                    Right(tree)
                catch
                    case ex: TreeUnpickler.DecodeException => Left(TastyError.MalformedSection("ASTs", ex.getMessage, ex.byteOffset))
                    case ex: Exception                     => Left(TastyError.MalformedSection("ASTs", ex.getMessage, 0L))
            result match
                case Right(Tasty.Tree.Unknown(tag, _)) =>
                    assert(
                        tag == 77,
                        s"Expected Tree.Unknown with tag 77 but got tag $tag"
                    )
                    succeed
                case Right(other) =>
                    fail(s"Expected Tree.Unknown(77, _) but got: $other")
                case Left(e) =>
                    fail(s"Expected Tree.Unknown but got error: $e -- TreeUnpickler must NOT use NotImplemented for unknown tags")
            end match
    }

    // ── Leaf 3: Snapshot ERRORS section with fully-unrecognized prefix returns NotImplemented ─
    //
    // Forward-compatibility scenario: a snapshot written by a future kyo-tasty version may contain
    // TastyError variants not known to this reader. The SnapshotReader.parseErrorString fallback
    // returns NotImplemented so that callers can inspect accumulated errors without losing them.

    "INV-007 leaf-3: ERRORS section with unrecognized error string produces TastyError.NotImplemented" in run {
        val futureErrorMsg = "QuantumEntanglementError(qubit-17 decoherence during classpath scan)"
        val errorsPayload  = makeErrorsPayload(Seq(futureErrorMsg))
        val snapshotBytes  = buildSnapshotWithErrors(errorsPayload)

        val src = MemoryFileSource()
        src.add("cache/forward.krfl", snapshotBytes)

        Abort.run[TastyError]:
            InternalClasspath.allocate.flatMap: rawCp =>
                SnapshotReader.read("cache/forward.krfl", src, rawCp).map: _ =>
                    val errors = rawCp.accumulatedErrors
                    assert(errors.length == 1, s"Expected 1 accumulated error but got: $errors")
                    errors.head match
                        case TastyError.NotImplemented(feature) =>
                            assert(
                                feature.contains("QuantumEntanglementError"),
                                s"Expected feature string to contain the original message but got: $feature"
                            )
                            succeed
                        case other =>
                            fail(s"Expected TastyError.NotImplemented but got: $other")
                    end match
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure loading snapshot: $e")
            case Result.Panic(t)   => throw t
    }

end TastyErrorMaybeTest
