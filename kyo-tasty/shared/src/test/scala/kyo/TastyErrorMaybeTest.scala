package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.symbol.SymbolBody
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.immutable.IntMap

/** Tests pinning: TastyError.NotImplemented is reserved for genuinely-deferred features only.
  *
  * TastyError.NotImplemented is returned only when a TASTy feature is not
  * yet implemented in this version of kyo-tasty. It is never returned for "this attribute does not apply to this symbol kind" (use
  * Maybe.Absent) and never returned for an unrecognized TASTy tag during tree decode (use Tree.Unknown for graceful degradation).
  */
class TastyErrorMaybeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Build a minimal valid KRFL snapshot byte array containing the given ERRORS section payload.
      *
      * The snapshot has only a NAMES section (0 names), a SYMBOLS section (0 symbols), and an ERRORS section with the provided payload
      * bytes. This is the minimum required to make SnapshotReader.readFromBytes succeed.
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
        val sectionMeta = sections.map { (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry
        }

        val totalSize = offset.toInt
        val buffer    = new Array[Byte](totalSize)

        // Magic "KRFL"
        buffer(0) = 'K'
        buffer(1) = 'R'
        buffer(2) = 'F'
        buffer(3) = 'L'
        // Version: current major and minor
        buffer(4) = SnapshotFormat.majorVersion.toByte
        buffer(5) = SnapshotFormat.minorVersion.toByte
        // flags (LE = 0)
        SnapshotFormat.writeInt64LE(buffer, 8, 0L)
        // inputDigest (zeros)
        SnapshotFormat.writeInt64LE(buffer, 16, 0L)
        // reserved (zeros)
        SnapshotFormat.writeInt64LE(buffer, 24, 0L)
        // section count
        SnapshotFormat.writeInt32LE(buffer, 32, sectionCount)
        // section index entries
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buffer, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionLen)
            idxPos += 8
        end for
        // Copy payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buffer, sectionOffset.toInt, bytes.length)
        buffer
    end buildSnapshotWithErrors

    /** Encode an ERRORS section using the minor=10 string-tag format for the given TastyError list.
      *
      * Each entry: [LEB128 varint: tag byte count] [UTF-8 tag bytes == productPrefix] [variant-specific fields].
      * String fields: [4-byte len LE][UTF-8 bytes].
      */
    private def makeErrorsPayload(errors: Seq[TastyError]): Array[Byte] =
        val baos = new java.io.ByteArrayOutputStream()
        val tmp4 = new Array[Byte](4)
        val tmp8 = new Array[Byte](8)
        SnapshotFormat.writeInt32LE(tmp4, 0, errors.size)
        baos.write(tmp4)

        def writeStr(s: String): Unit =
            val bytes = SnapshotFormat.encodeString(s)
            SnapshotFormat.writeInt32LE(tmp4, 0, bytes.length)
            baos.write(tmp4)
            baos.write(bytes)
        end writeStr

        def writeLong(v: Long): Unit =
            SnapshotFormat.writeInt64LE(tmp8, 0, v)
            baos.write(tmp8)

        def writeInt(v: Int): Unit =
            SnapshotFormat.writeInt32LE(tmp4, 0, v)
            baos.write(tmp4)

        def writeVersion(v: Tasty.Version): Unit =
            writeInt(v.major)
            writeInt(v.minor)

        def writeUUID(u: Tasty.Uuid): Unit =
            writeLong(Tasty.Uuid.msb(u))
            writeLong(Tasty.Uuid.lsb(u))

        // Standard LEB128 varint encoder (ERRORS wire format).
        def writeVarint(value: Int): Unit =
            var v = value
            while
                val b = v & 0x7f
                v = v >>> 7
                if v != 0 then
                    baos.write(b | 0x80)
                    true
                else
                    baos.write(b)
                    false
                end if
            do ()
            end while
        end writeVarint

        for err <- errors do
            val tagBytes = err.productPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            writeVarint(tagBytes.length)
            baos.write(tagBytes)
            err match
                case TastyError.FileNotFound(path)              => writeStr(path)
                case TastyError.CorruptedFile(path, at, reason) => writeStr(path); writeLong(at); writeStr(reason)
                case TastyError.UnsupportedVersion(f, s)        => writeVersion(f); writeVersion(s)
                case TastyError.InconsistentClasspath(file, e, fd) =>
                    writeStr(file); writeUUID(e); writeUUID(fd)
                case TastyError.FullNameCollisionError(fullName)  => writeStr(fullName)
                case TastyError.MalformedSection(n, r, at)        => writeStr(n); writeStr(r); writeLong(at)
                case TastyError.SymbolNotFound(fullName)          => writeStr(fullName)
                case TastyError.NotFound(fullName)                => writeStr(fullName)
                case TastyError.ClassfileFormatError(p, r, at)    => writeStr(p); writeStr(r); writeLong(at)
                case TastyError.ClasspathClosed(ctx)              => writeStr(ctx)
                case TastyError.ClasspathBuilding(ctx)            => writeStr(ctx)
                case TastyError.SnapshotFormatError(p, r, at)     => writeStr(p); writeStr(r); writeLong(at)
                case TastyError.SnapshotVersionMismatch(f, s)     => writeVersion(f); writeVersion(s)
                case TastyError.SnapshotIoError(cause)            => writeStr(cause)
                case TastyError.NotImplemented(feature)           => writeStr(feature)
                case TastyError.UnsupportedPlatform(feature)      => writeStr(feature)
                case TastyError.UnknownTagInPosition(t, p)        => writeInt(t); writeStr(p)
                case TastyError.InvalidFullName(fullName, reason) => writeStr(fullName); writeStr(reason)
                case TastyError.InvalidUuid(input)                => writeStr(input)
                case TastyError.DigestMismatch(exp, act)          => writeStr(exp); writeStr(act)
                // UnhandledSubtypingCase is not yet serializable; write no fields.
                case TastyError.UnhandledSubtypingCase(_, _, _, _) => ()
                // UnresolvedReference is a loading-phase error; not serializable ; write no fields.
                case TastyError.UnresolvedReference(_, _) => ()
                // UnknownType and MissingDeclaredType are serialized; write no fields.
                case TastyError.UnknownType(_, _, _)      => ()
                case TastyError.MissingDeclaredType(_, _) => ()
            end match
        end for
        baos.toByteArray
    end makeErrorsPayload

    /** Encode a raw ERRORS section payload containing a single entry with an unknown tag string and no fields.
      *
      * Used to test forward-compat: a snapshot written by a future kyo-tasty version may contain error tags not known to this reader. The
      * reader maps unknown string tags to TastyError.NotImplemented (ERRORS wire format).
      */
    private def makeUnknownTagErrorsPayload(unknownTag: String): Array[Byte] =
        val tagBytes = unknownTag.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val baos     = new java.io.ByteArrayOutputStream()
        val tmp4     = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp4, 0, 1) // 1 entry
        baos.write(tmp4)
        // LEB128 varint for tag length (single-byte if < 128).
        baos.write(tagBytes.length)
        baos.write(tagBytes)
        // No fields for the unknown tag.
        baos.toByteArray
    end makeUnknownTagErrorsPayload

    "recognized error prefix round-trips without NotImplemented; unrecognized yields NotImplemented" in {
        val recognized = TastyError.FileNotFound("/some/path.tasty")
        // Forward-compat (string-tag format): an unknown tag string maps to NotImplemented.
        val unknownTag = "FutureError"

        // Build ERRORS payload: first entry is the recognized FileNotFound (string-tag format),
        // second entry uses an unknown tag string (forward-compat scenario).
        val recognizedPayload = makeErrorsPayload(Seq(recognized))
        val unknownPayload    = makeUnknownTagErrorsPayload(unknownTag)

        // Merge payloads: combined count=2, recognized entry bytes, unknown entry bytes.
        val combinedBaos = new java.io.ByteArrayOutputStream()
        val tmp4         = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp4, 0, 2) // combined count = 2
        combinedBaos.write(tmp4)
        // Skip the count bytes from each individual payload (offset 4 onwards is entry data).
        combinedBaos.write(recognizedPayload, 4, recognizedPayload.length - 4)
        combinedBaos.write(unknownPayload, 4, unknownPayload.length - 4)
        val errorsPayload = combinedBaos.toByteArray
        val snapshotBytes = buildSnapshotWithErrors(errorsPayload)

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(snapshotBytes, "cache/test.krfl").map { loadedCp =>
                val errors = loadedCp.errors
                // Recognized string tag must map to the concrete variant, not NotImplemented.
                val fileNotFoundEntries = errors.filter {
                    case TastyError.FileNotFound(_) => true
                    case _                          => false
                }
                assert(
                    fileNotFoundEntries.length == 1,
                    s"Expected exactly one FileNotFound entry; got: $errors"
                )
                // Unknown string tag must map to NotImplemented.
                val notImplEntries = errors.filter {
                    case TastyError.NotImplemented(_) => true
                    case _                            => false
                }
                assert(
                    notImplEntries.length == 1,
                    s"Expected exactly one NotImplemented entry; got: $errors"
                )
                succeed
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Tag 77 is in the category-2 range [60, 89] and is not assigned in TastyFormat.
    // TreeUnpickler produces Tree.Unknown for graceful degradation; it never uses NotImplemented.
    "TreeUnpickler unknown category-2 tag produces Tree.Unknown not TastyError.NotImplemented" in {
        // Tag 77 is in the category-2 range (60-89) and is not assigned in TastyFormat.
        // Category-2 tag body is: [tag][Nat]. Nat 0x80 encodes value 0 in TASTy Nat encoding.
        val unknownCat2Tag: Byte      = 77.toByte
        val natZero: Byte             = 0x80.toByte // TASTy single-byte Nat: high bit set, value = low 7 bits = 0
        val sectionBytes: Array[Byte] = Array(unknownCat2Tag, natZero)

        // TypeParam kind falls into the generic `case _` arm in decodeSymBody, invoking readTree directly.
        // Package has special dispatch to PackageDef; TypeParam is correct here.
        val symbol = Tasty.Symbol.TypeParam(
            Tasty.SymbolId(-1),
            Tasty.Name("testSym"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )
        val body = SymbolBody(
            bodyStart = 0,
            bodyEnd = sectionBytes.length,
            sectionBytes = Span.fromUnsafe(sectionBytes),
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty[SymbolId],
            pickleId = 0
        )

        Sync.defer {
            val result =
                try
                    val tree = TreeUnpickler.decodeSync(body, symbol, _ => symbol)
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
    }

    "ERRORS section with unrecognized error string produces TastyError.NotImplemented" in {
        // Forward-compat scenario (string-tag format): a future kyo-tasty version adds a new
        // TastyError variant with an unknown tag string. The current reader maps it to NotImplemented.
        val futureTag     = "QuantumEntanglementError"
        val errorsPayload = makeUnknownTagErrorsPayload(futureTag)
        val snapshotBytes = buildSnapshotWithErrors(errorsPayload)

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(snapshotBytes, "cache/forward.krfl").map { loadedCp =>
                val errors = loadedCp.errors
                assert(errors.length == 1, s"Expected 1 accumulated error but got: $errors")
                errors.head match
                    case TastyError.NotImplemented(feature) =>
                        assert(
                            feature.contains("QuantumEntanglementError"),
                            s"Expected feature string to contain 'QuantumEntanglementError' but got: $feature"
                        )
                        succeed
                    case other =>
                        fail(s"Expected TastyError.NotImplemented but got: $other")
                end match
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure loading snapshot: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "TastyError.UnresolvedReference is emitted by finalizeMerge for unresolvable fully-qualified name entries" in {
        Abort.run[TastyError](
            kyo.internal.tasty.query.ClasspathOrchestrator.triggerUnresolvedReferenceForTest()
        ).map { result =>
            result match
                case Result.Success(classpath) =>
                    val refs = classpath.errors.collect {
                        case e: TastyError.UnresolvedReference => e
                    }
                    assert(
                        refs.length == 1,
                        s"Expected exactly one UnresolvedReference but got: ${classpath.errors}"
                    )
                    val ref = refs.head
                    assert(
                        ref.name.contains("GhostFullName"),
                        s"Expected name to contain 'GhostFullName' but got: ${ref.name}"
                    )
                    val isTastyError: TastyError = ref
                    assert(isTastyError.isInstanceOf[TastyError], "UnresolvedReference must be a TastyError subtype")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Expected Success(classpath) with UnresolvedReference in classpath.errors but got Failure: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
        }
    }

    "TastyError.UnknownType and MissingDeclaredType are reachable closed-enum variants" in {
        // Verify UnknownType is constructable and matches as TastyError
        val ut: TastyError = TastyError.UnknownType("f.tasty", 0L, "reason")
        val utResult = ut match
            case TastyError.UnknownType(f, bo, r) =>
                assert(f == "f.tasty")
                assert(bo == 0L)
                assert(r == "reason")
                true
            case _ => false
        assert(utResult, "UnknownType pattern match arm must fire")
        // Verify MissingDeclaredType is constructable and matches as TastyError
        val mdt: TastyError = TastyError.MissingDeclaredType(Tasty.SymbolId(3), "g.tasty")
        val mdtResult = mdt match
            case TastyError.MissingDeclaredType(sid, f) =>
                assert(sid == Tasty.SymbolId(3))
                assert(f == "g.tasty")
                true
            case _ => false
        assert(mdtResult, "MissingDeclaredType pattern match arm must fire")
        // Verify both are subtype-equal (CanEqual derived) and distinguishable from other variants
        assert(ut != mdt, "UnknownType and MissingDeclaredType must be distinct")
        assert(ut == TastyError.UnknownType("f.tasty", 0L, "reason"), "CanEqual structural equality must hold for UnknownType")
        assert(
            mdt == TastyError.MissingDeclaredType(Tasty.SymbolId(3), "g.tasty"),
            "CanEqual structural equality must hold for MissingDeclaredType"
        )
        // Verify neither is constructable from the other's shape (compile-time probe)
        val errCount = compiletime.testing.typeCheckErrors(
            "kyo.TastyError.UnknownType(kyo.Tasty.SymbolId(3), \"f\")"
        ).length
        assert(errCount > 0, "UnknownType(SymbolId, String) must not compile -- wrong arity")
        succeed
    }

end TastyErrorMaybeTest
