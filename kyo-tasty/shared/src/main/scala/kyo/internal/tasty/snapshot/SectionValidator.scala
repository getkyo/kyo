package kyo.internal.tasty.snapshot

import kyo.TastyError

/** Wire-format invariant assertions for KRFL snapshot sections.
  *
  * Called by SnapshotReader at every section read to assert structural invariants before decoding proceeds. Throws
  * `SectionValidationException` (carrying a `TastyError.MalformedSection`) on violations rather than silently trusting the bytes.
  *
  * This eliminates the class of bugs (NAMES-vs-FQNIDX ordering, name IDs past pool length, record alignment) by making corruption loud
  * at read time. The caller (SnapshotReader.deserialize) catches `SectionValidationException` and converts it to `Abort.fail`.
  */
private[snapshot] object SectionValidator:

    /** Thrown when a section fails its structural invariant check.
      *
      * Carries the structured `TastyError.MalformedSection` so the caller can convert it to `Abort.fail` without losing type information.
      *
      * Internal sentinel: deliberately bypasses `KyoException` (no public-API crossing) and uses
      * `enableSuppression=false, writableStackTrace=false` so the throw path skips stack-trace materialisation
      * (NoStackTrace flags).
      */
    final class SectionValidationException(val error: TastyError.MalformedSection)
        extends RuntimeException(s"section validation failed: ${error.name} ${error.reason}", null, false, false)

    /** Describes the expected structural layout of a KRFL section. */
    enum SectionLayout derives CanEqual:
        /** Fixed-size records preceded by a 4-byte count header.
          *
          * The section layout is: [count: Int32LE] [record_0: recordSize bytes] ... [record_{count-1}: recordSize bytes]
          *
          * Validates that total byte length == 4 + count * recordSize.
          */
        case FixedRecordWithHeader(recordSize: Int)

        /** Section consists of a flat sequence of 32-bit integers. Length must be divisible by 4. */
        case Int32Array

        /** Section is a variable-length blob with no structural invariant. No assertion made. */
        case VariableLength
    end SectionLayout

    /** Assert that the slice `[offset, offset + length)` falls entirely within `[0, totalLen)`.
      *
      * Called before every `System.arraycopy` or `Arrays.copyOfRange` in `SnapshotReader.deserialize` and `SnapshotReader.deserializeMapped`
      * so that a corrupt section-index entry cannot cause an `ArrayIndexOutOfBoundsException`. When the check fails it throws
      * `SectionValidationException` carrying a `TastyError.MalformedSection`; the caller converts it to `Abort.fail`.
      *
      * @param section
      *   Human-readable section name used in the error message (e.g. "SYMBOLS").
      * @param offset
      *   Start byte offset within the snapshot byte array.
      * @param length
      *   Byte length of the slice.
      * @param totalLen
      *   Total length of the snapshot byte array.
      */
    def validateRange(section: String, offset: Int, length: Int, totalLen: Int): Unit =
        if offset < 0 || length < 0 || (offset.toLong + length.toLong) > totalLen.toLong then
            throw new SectionValidationException(
                TastyError.MalformedSection(
                    section,
                    s"section index entry out of bounds: offset=$offset, length=$length, totalLen=$totalLen",
                    0L
                )
            )

    /** Assert that `bytes` of section `section` satisfy the expected `layout`.
      *
      * Throws `SectionValidationException` if the assertion fails. Returns normally when the invariant holds.
      *
      * @param section
      *   Human-readable section name (e.g. "SYMBOLS", "NAMES").
      * @param bytes
      *   The raw byte array for this section, starting at offset 0.
      * @param layout
      *   The expected structural layout of this section.
      */
    def validate(section: String, bytes: Array[Byte], layout: SectionLayout): Unit =
        layout match
            case SectionLayout.FixedRecordWithHeader(recordSize) =>
                if bytes.length < 4 then
                    throw new SectionValidationException(
                        TastyError.MalformedSection(
                            section,
                            s"expected at least 4 bytes (count header), found length=${bytes.length}",
                            0L
                        )
                    )
                else if recordSize > 0 then
                    val count    = SnapshotFormat.readInt32LE(bytes, 0)
                    val expected = 4 + count * recordSize
                    if bytes.length != expected then
                        throw new SectionValidationException(
                            TastyError.MalformedSection(
                                section,
                                s"expected length=$expected (4 + $count * $recordSize), found length=${bytes.length}",
                                0L
                            )
                        )
                    end if
            case SectionLayout.Int32Array =>
                if bytes.length % 4 != 0 then
                    throw new SectionValidationException(
                        TastyError.MalformedSection(
                            section,
                            s"expected length divisible by 4 (Int32 array), found length=${bytes.length}",
                            0L
                        )
                    )
            case SectionLayout.VariableLength =>
                () // no assertion
    end validate

    /** Expected number of TastyError ADT variants in the closed enum (documentation invariant).
      *
      * Updated to 23 (added UnhandledSubtypingCase, UnresolvedReference, UnknownType,
      * MissingDeclaredType). Any future variant addition must bump the snapshot minor version AND
      * update this constant. The `TastyErrorRoundTripTest` verifies that every known variant
      * round-trips through the KRFL wire format; adding a case and running tests will catch the gap.
      *
      * Note: TastyError has non-singleton enum cases so `TastyError.values` is not available.
      * This constant serves as a documentation anchor (count the cases in TastyError.scala to verify).
      */
    val expectedTastyErrorVariantCount: Int = 23

end SectionValidator
