package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.Sha1
import kyo.internal.Sha256

/** A universally unique identifier as specified by RFC 9562 (which obsoletes RFC 4122).
  *
  * A UUID is a 128-bit value, stored here as a pair of `Long`s (the most and least significant 64 bits, matching the layout of
  * `java.util.UUID`). The canonical textual form is the lowercase, hyphenated `8-4-4-4-12` grouping of hex digits, for example
  * `550e8400-e29b-41d4-a716-446655440000`.
  *
  * This type carries only pure value operations: parsing, formatting, byte conversion, and the deterministic name-based constructors
  * [[UUID.v5]] and [[UUID.v8Sha256]]. Random and time-based generation (v4, v7) is an effectful capability that lives in `kyo-core`.
  */
opaque type UUID = (Long, Long)

object UUID:

    inline given CanEqual[UUID, UUID] = CanEqual.derived

    given Ordering[UUID] with
        def compare(x: UUID, y: UUID): Int = x.compare(y)

    /** The nil UUID, `00000000-0000-0000-0000-000000000000`, with all 128 bits set to zero. */
    val nil: UUID = (0L, 0L)

    /** The max UUID, `ffffffff-ffff-ffff-ffff-ffffffffffff`, with all 128 bits set to one. */
    val max: UUID = (-1L, -1L)

    private[kyo] def fromLongs(mostSignificantBits: Long, leastSignificantBits: Long): UUID =
        (mostSignificantBits, leastSignificantBits)

    /** The RFC 9562 variant encoded in a UUID's variant bits.
      *
      * The variant occupies the most significant bits of octet 8. `RFC` is the variant used by every UUID version this module
      * constructs (v5, v8Sha256); the others are recognized for inspection of foreign UUIDs but never produced here.
      */
    enum Variant derives CanEqual:
        /** Reserved for backward compatibility with the obsolete NCS format (top bit `0`). */
        case NCS

        /** The RFC 9562 variant (top bits `10`), used by all UUIDs this module constructs. */
        case RFC

        /** Reserved for backward compatibility with Microsoft's GUID format (top bits `110`). */
        case Microsoft

        /** Reserved for future definition (top bits `111`). */
        case Future
    end Variant

    /** The reason a UUID failed to parse or construct. */
    enum InvalidProblem derives CanEqual:
        /** The input text did not have the canonical 36-character length. */
        case TextLength(actual: Int)

        /** A `-` separator was missing at the expected index. */
        case Separator(index: Int)

        /** A non-hexadecimal character was found at the given index. */
        case HexDigit(index: Int, value: Char)

        /** The input did not begin with the `urn:uuid:` prefix required by [[UUID.parseUrn]]. */
        case UrnPrefix

        /** The input did not have exactly 16 bytes. */
        case ByteLength(actual: Int)

        /** Renders this problem as a human-readable message. */
        def show: String =
            this match
                case TextLength(actual)  => s"expected 36 characters, got $actual"
                case Separator(index)    => s"expected '-' at index $index"
                case HexDigit(index, ch) => s"expected a hex digit at index $index, got '$ch'"
                case UrnPrefix           => "expected a 'urn:uuid:' prefix"
                case ByteLength(actual)  => s"expected 16 bytes, got $actual"
    end InvalidProblem

    /** Raised when a UUID could not be parsed or constructed from bytes.
      *
      * @param problem
      *   the specific reason the input was rejected
      */
    final class InvalidUUID(val problem: InvalidProblem)(using Frame) extends KyoException(problem.show)

    private val hexDigits: Array[Char] = "0123456789abcdef".toCharArray

    private val urnPrefix = "urn:uuid:"

    private val v8Sha256Domain: Array[Byte] = "kyo.uuid.v8.sha256.v1".getBytes(StandardCharsets.UTF_8)

    private def hexValue(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else -1

    private def isSeparatorIndex(i: Int): Boolean =
        i == 8 || i == 13 || i == 18 || i == 23

    /** Parses the 36 validated canonical hex characters of `value` into a UUID. Callers must have already checked the length, separators,
      * and hex digits.
      */
    private def readCanonical(value: String): UUID =
        var msb      = 0L
        var lsb      = 0L
        var hexIndex = 0
        var i        = 0
        while i < 36 do
            if !isSeparatorIndex(i) then
                val v = hexValue(value.charAt(i)).toLong
                if hexIndex < 16 then msb = (msb << 4) | v
                else lsb = (lsb << 4) | v
                hexIndex += 1
            end if
            i += 1
        end while
        (msb, lsb)
    end readCanonical

    private def validateCanonical(value: String): Maybe[InvalidProblem] =
        if value.length != 36 then Maybe(InvalidProblem.TextLength(value.length))
        else
            var problem: Maybe[InvalidProblem] = Maybe.empty
            var i                              = 0
            while i < 36 && problem.isEmpty do
                val c = value.charAt(i)
                if isSeparatorIndex(i) then
                    if c != '-' then problem = Maybe(InvalidProblem.Separator(i))
                else if hexValue(c) < 0 then
                    problem = Maybe(InvalidProblem.HexDigit(i, c))
                end if
                i += 1
            end while
            problem
        end if
    end validateCanonical

    /** Parses the canonical `8-4-4-4-12` textual representation of a UUID.
      *
      * Accepts the canonical form case-insensitively. Rejects braces, URN text, missing or misplaced separators, non-hex characters, and
      * any other deviation from the exact 36-character layout.
      *
      * @param value
      *   the text to parse
      * @return
      *   the parsed UUID, or an [[InvalidUUID]] describing why parsing failed
      */
    def parse(value: String)(using Frame): Result[InvalidUUID, UUID] =
        validateCanonical(value) match
            case Present(problem) => Result.fail(InvalidUUID(problem))
            case Absent           => Result.succeed(readCanonical(value))

    /** Parses a UUID from its URN form, `urn:uuid:<canonical>`.
      *
      * The `urn:uuid:` prefix is matched case-insensitively; the remainder is parsed as canonical text via [[parse]].
      *
      * @param value
      *   the URN text to parse
      * @return
      *   the parsed UUID, or an [[InvalidUUID]] describing why parsing failed
      */
    def parseUrn(value: String)(using Frame): Result[InvalidUUID, UUID] =
        if !value.regionMatches(true, 0, urnPrefix, 0, urnPrefix.length) then
            Result.fail(InvalidUUID(InvalidProblem.UrnPrefix))
        else
            parse(value.substring(urnPrefix.length))

    /** Constructs a UUID from its raw 128-bit big-endian byte representation.
      *
      * @param bytes
      *   exactly 16 bytes, most significant byte first
      * @return
      *   the constructed UUID, or an [[InvalidUUID]] if `bytes` is not exactly 16 bytes long
      */
    def fromBytes(bytes: Span[Byte])(using Frame): Result[InvalidUUID, UUID] =
        if bytes.size != 16 then Result.fail(InvalidUUID(InvalidProblem.ByteLength(bytes.size)))
        else Result.succeed(fromByteArray(bytes.toArray))

    private def fromByteArray(bytes: Array[Byte]): UUID =
        var msb = 0L
        var lsb = 0L
        var i   = 0
        while i < 8 do
            msb = (msb << 8) | (bytes(i) & 0xffL)
            i += 1
        while i < 16 do
            lsb = (lsb << 8) | (bytes(i) & 0xffL)
            i += 1
        (msb, lsb)
    end fromByteArray

    /** Builds a UUID from the first 16 bytes of a name-based hash, stamping the version nibble and the RFC 9562 variant bits. */
    private def fromHash(hash: Array[Byte], version: Int): UUID =
        val bytes = new Array[Byte](16)
        java.lang.System.arraycopy(hash, 0, bytes, 0, 16)
        bytes(6) = ((bytes(6) & 0x0f) | (version << 4)).toByte
        bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
        fromByteArray(bytes)
    end fromHash

    private[kyo] def unsignedIntBytes(value: Int): Array[Byte] =
        Array(
            (value >>> 24).toByte,
            (value >>> 16).toByte,
            (value >>> 8).toByte,
            value.toByte
        )
    end unsignedIntBytes

    /** Deterministically derives a version 5 UUID from a namespace and a name, per RFC 9562's name-based algorithm.
      *
      * The result is `SHA-1(namespace.bytes ++ name)`, truncated to 128 bits with the version nibble set to `5` and the variant bits set
      * to the RFC 9562 variant. Equal `(namespace, name)` pairs always produce the same UUID.
      *
      * @param namespace
      *   the namespace UUID (for example one of the well-known RFC namespaces)
      * @param name
      *   the name to derive the UUID from, encoded as bytes
      * @return
      *   the deterministic version 5 UUID
      */
    def v5(namespace: UUID, name: Span[Byte]): UUID =
        val input = Seq(namespace.bytes.toArrayUnsafe, name.toArrayUnsafe)
        fromHash(Sha1.hashChunks(input), version = 5)

    /** Deterministically derives a version 8 UUID from a namespace and a name using the Kyo `v8Sha256` profile.
      *
      * The result hashes `u32be(21) ++ UTF8("kyo.uuid.v8.sha256.v1") ++ namespace.bytes ++ u32be(name.length) ++ name`, truncates the
      * SHA-256 digest to 128 bits, and sets the version nibble to `8` and the variant bits to the RFC 9562 variant. The unsigned 32-bit
      * lengths use big-endian encoding. A `Span` length is bounded by `Int`, so every accepted name length has an exact representation.
      * Equal `(namespace, name)` pairs always produce the same UUID.
      *
      * @param namespace
      *   the namespace UUID
      * @param name
      *   the name to derive the UUID from, encoded as bytes
      * @return
      *   the deterministic version 8 UUID
      */
    def v8Sha256(namespace: UUID, name: Span[Byte]): UUID =
        val input = Seq(
            unsignedIntBytes(v8Sha256Domain.length),
            v8Sha256Domain,
            namespace.bytes.toArrayUnsafe,
            unsignedIntBytes(name.size),
            name.toArrayUnsafe
        )
        fromHash(Sha256.hashChunks(input), version = 8)
    end v8Sha256

    extension (self: UUID)

        /** Renders this UUID in canonical lowercase `8-4-4-4-12` hex form. */
        def show: String =
            val (msb, lsb) = self
            def nibbleAt(n: Int): Int =
                if n < 16 then ((msb >>> ((15 - n) * 4)) & 0x0fL).toInt
                else ((lsb >>> ((31 - n) * 4)) & 0x0fL).toInt
            val chars = new Array[Char](36)
            var ci    = 0
            var ni    = 0
            while ci < 36 do
                if isSeparatorIndex(ci) then
                    chars(ci) = '-'
                else
                    chars(ci) = hexDigits(nibbleAt(ni))
                    ni += 1
                end if
                ci += 1
            end while
            new String(chars)
        end show

        /** Returns the raw 128-bit big-endian byte representation of this UUID, as a fresh 16-byte `Span`. */
        def bytes: Span[Byte] =
            val (msb, lsb) = self
            val arr        = new Array[Byte](16)
            var i          = 0
            while i < 8 do
                arr(i) = ((msb >>> ((7 - i) * 8)) & 0xffL).toByte
                i += 1
            while i < 16 do
                arr(i) = ((lsb >>> ((15 - i) * 8)) & 0xffL).toByte
                i += 1
            Span.fromUnsafe(arr)
        end bytes

        /** Returns the version nibble (bits 12-15 of the time_hi_and_version field), e.g. `5` for a [[UUID.v5]] value. */
        def version: Int =
            val (msb, _) = self
            ((msb >>> 12) & 0x0fL).toInt

        /** Returns the RFC 9562 variant encoded in this UUID's variant bits. */
        def variant: Variant =
            val (_, lsb) = self
            val topByte  = ((lsb >>> 56) & 0xffL).toInt
            if (topByte & 0x80) == 0 then Variant.NCS
            else if (topByte & 0x40) == 0 then Variant.RFC
            else if (topByte & 0x20) == 0 then Variant.Microsoft
            else Variant.Future
            end if
        end variant

        /** Returns the Unix timestamp in milliseconds embedded in a version 7 UUID, or `Absent` for any other version. */
        def unixTimestampMillis: Maybe[Long] =
            if self.version == 7 then
                val (msb, _) = self
                Maybe((msb >>> 16) & 0xffffffffffffL)
            else Absent

        /** Compares this UUID to `that` using unsigned bytewise ordering of the 128-bit value, matching RFC 9562's ordering. */
        def compare(that: UUID): Int =
            val (msb1, lsb1) = self
            val (msb2, lsb2) = that
            val msbOrder     = java.lang.Long.compareUnsigned(msb1, msb2)
            if msbOrder != 0 then msbOrder
            else java.lang.Long.compareUnsigned(lsb1, lsb2)
        end compare
    end extension
end UUID
