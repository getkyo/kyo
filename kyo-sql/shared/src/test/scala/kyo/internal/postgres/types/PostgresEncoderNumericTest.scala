package kyo.internal.postgres.types

import kyo.*
import kyo.internal.postgres.PostgresBufferWriter

/** Exact-bytes and round-trip tests for the PostgreSQL base-10000 binary NUMERIC encoder and decoder.
  *
  * Test vectors are derived from the PG wire spec (`numeric.c` `numericvar_to_binary`) and verified by hand. Each test encodes a
  * [[BigDecimal]] and checks the resulting bytes against the known PG wire representation, or decodes a known wire byte sequence and checks
  * the resulting [[BigDecimal]].
  *
  * Wire layout reminder:
  * {{{
  *   Int16  ndigits   -- count of base-10000 digits
  *   Int16  weight    -- weight of first digit (base-10000 powers; signed)
  *   UInt16 sign      -- 0x0000 positive, 0x4000 negative
  *   UInt16 dscale    -- decimal digits after point
  *   Int16  digits[]  -- each in [0..9999], most-significant first
  * }}}
  */
class PostgresEncoderNumericTest extends kyo.Test:

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private def encode(value: BigDecimal): Span[Byte] =
        val buf = new PostgresBufferWriter
        PostgresEncoder.numericBinary.write(value, buf)
        buf.toSpan
    end encode

    private def readU16(bytes: Span[Byte], offset: Int): Int =
        ((bytes(offset) & 0xff) << 8) | (bytes(offset + 1) & 0xff)

    private def readI16(bytes: Span[Byte], offset: Int): Int =
        val raw = readU16(bytes, offset)
        if raw >= 0x8000 then raw - 0x10000 else raw

    /** Parses the NUMERIC binary header + digits from an encoded [[Span[Byte]]].
      *
      * Returns `(ndigits, weight, sign, dscale, digits)`.
      */
    private def parseHeader(bytes: Span[Byte]): (Int, Int, Int, Int, List[Int]) =
        val ndigits = readU16(bytes, 0)
        val weight  = readI16(bytes, 2) // signed
        val sign    = readU16(bytes, 4) // unsigned
        val dscale  = readU16(bytes, 6) // unsigned
        val digits  = (0 until ndigits).map(i => readU16(bytes, 8 + i * 2)).toList
        (ndigits, weight, sign, dscale, digits)
    end parseHeader

    // ── Decoder helper ────────────────────────────────────────────────────────

    private def decode(bytes: Span[Byte]): BigDecimal =
        PostgresDecoder.numeric.read(Format.Binary, bytes)

    // ── Test vectors from PHASE-2-PREP.md Section 4 ───────────────────────────

    "numericBinary encodes 0 as ndigits=0, weight=0, sign=0x0000, dscale=0" in {
        val bytes = encode(BigDecimal(0))
        assert(bytes.size == 8, s"Expected 8 bytes for zero, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 0, s"ndigits expected 0, got $ndigits")
        assert(weight == 0, s"weight expected 0, got $weight")
        assert(sign == 0x0000, s"sign expected 0x0000, got 0x${sign.toHexString}")
        assert(dscale == 0, s"dscale expected 0, got $dscale")
        assert(digits.isEmpty, s"digits expected [], got $digits")
    }

    "numericBinary encodes 1 (positive integer)" in {
        val bytes = encode(BigDecimal(1))
        assert(bytes.size == 10, s"Expected 10 bytes for 1, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1, s"ndigits expected 1, got $ndigits")
        assert(weight == 0, s"weight expected 0, got $weight")
        assert(sign == 0x0000, s"sign expected 0x0000, got 0x${sign.toHexString}")
        assert(dscale == 0, s"dscale expected 0, got $dscale")
        assert(digits == List(1), s"digits expected [1], got $digits")
        // Exact bytes: 00 01  00 00  00 00  00 00  00 01
        assert(bytes(0) == 0.toByte && bytes(1) == 1.toByte, "ndigits bytes")
        assert(bytes(2) == 0.toByte && bytes(3) == 0.toByte, "weight bytes")
        assert(bytes(4) == 0.toByte && bytes(5) == 0.toByte, "sign bytes")
        assert(bytes(6) == 0.toByte && bytes(7) == 0.toByte, "dscale bytes")
        assert(bytes(8) == 0.toByte && bytes(9) == 1.toByte, "digit[0] bytes")
    }

    "numericBinary encodes -1 (negative integer)" in {
        val bytes = encode(BigDecimal(-1))
        assert(bytes.size == 10, s"Expected 10 bytes for -1, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1)
        assert(weight == 0)
        assert(sign == 0x4000, s"sign expected 0x4000 for negative, got 0x${sign.toHexString}")
        assert(dscale == 0)
        assert(digits == List(1))
        // Exact bytes for sign field: 40 00
        assert(bytes(4) == 0x40.toByte && bytes(5) == 0x00.toByte, "sign field 0x4000")
    }

    "numericBinary encodes 12345 (multiple base-10000 digits)" in {
        // 12345 = 1 × 10000 + 2345 → ndigits=2, weight=1, digits=[1, 2345]
        val bytes = encode(BigDecimal(12345))
        assert(bytes.size == 12, s"Expected 12 bytes for 12345, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 2, s"ndigits expected 2, got $ndigits")
        assert(weight == 1, s"weight expected 1, got $weight")
        assert(sign == 0x0000)
        assert(dscale == 0)
        assert(digits == List(1, 2345), s"digits expected [1, 2345], got $digits")
        // digits[1] = 2345 = 0x0929
        assert(bytes(10) == 0x09.toByte && bytes(11) == 0x29.toByte, "digits[1]=2345 (0x0929)")
    }

    "numericBinary encodes 0.000001 (only fractional, weight < 0)" in {
        // dscale=6, fracBase10000Digits=2, scaledInt=100, padded=[0,100]
        // strip 1 leading zero → [100], leadingZeros=1, weight=(2-2-1)-1=-2
        val bytes = encode(BigDecimal("0.000001"))
        assert(bytes.size == 10, s"Expected 10 bytes for 0.000001, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1, s"ndigits expected 1, got $ndigits")
        assert(weight == -2, s"weight expected -2, got $weight")
        assert(sign == 0x0000)
        assert(dscale == 6, s"dscale expected 6, got $dscale")
        assert(digits == List(100), s"digits expected [100], got $digits")
        // weight = -2 → 0xFFFE in two's-complement
        assert(bytes(2) == 0xff.toByte && bytes(3) == 0xfe.toByte, "weight=-2 (0xFFFE)")
        // digits[0] = 100 = 0x0064
        assert(bytes(8) == 0x00.toByte && bytes(9) == 0x64.toByte, "digit[0]=100 (0x0064)")
    }

    "numericBinary encodes 123456789.987654321 (split across decimal point)" in {
        // dscale=9, fracBase10000Digits=3, scaledInt=123456789987654321000
        // toBase10000 → [1, 2345, 6789, 9876, 5432, 1000], no leading/trailing zeros
        // weight = (6-3-1) - 0 = 2
        val bytes                                   = encode(BigDecimal("123456789.987654321"))
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 6, s"ndigits expected 6, got $ndigits")
        assert(weight == 2, s"weight expected 2, got $weight")
        assert(sign == 0x0000)
        assert(dscale == 9, s"dscale expected 9, got $dscale")
        assert(digits == List(1, 2345, 6789, 9876, 5432, 1000), s"digits mismatch: $digits")
    }

    "numericBinary encodes BigDecimal(\"1E+100\") (large weight)" in {
        // scale=-100, dscale=0, fracBase10000Digits=0, scaledInt=10^100
        // toBase10000 → 26 digits [1, 0, 0, ..., 0]; strip 25 trailing zeros → [1]
        // weight = (26-0-1) - 0 = 25
        val bytes = encode(BigDecimal("1E+100"))
        assert(bytes.size == 10, s"Expected 10 bytes for 1E+100, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1, s"ndigits expected 1, got $ndigits")
        assert(weight == 25, s"weight expected 25, got $weight")
        assert(sign == 0x0000)
        assert(dscale == 0, s"dscale expected 0, got $dscale")
        assert(digits == List(1), s"digits expected [1], got $digits")
    }

    "numericBinary encodes BigDecimal(\"1E-100\") (negative weight)" in {
        // scale=100, dscale=100, fracBase10000Digits=25, scaledInt=1
        // pad to 25: [0,0,...,0,1]; strip 24 leading zeros → [1], leadingZeros=24
        // weight = (25-25-1) - 24 = -25
        val bytes = encode(BigDecimal("1E-100"))
        assert(bytes.size == 10, s"Expected 10 bytes for 1E-100, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1, s"ndigits expected 1, got $ndigits")
        assert(weight == -25, s"weight expected -25, got $weight")
        assert(sign == 0x0000)
        assert(dscale == 100, s"dscale expected 100, got $dscale")
        assert(digits == List(1), s"digits expected [1], got $digits")
    }

    "numericBinary encodes negative fractional (-0.5) with sign=0x4000" in {
        // abs=0.5, scale=1, dscale=1, fracBase10000Digits=1
        // scaledInt = 0.5 * 10^4 = 5000, toBase10000 = [5000]
        // no padding, no leading/trailing zeros, intCount=0, weight=0-1-0=-1
        val bytes = encode(BigDecimal("-0.5"))
        assert(bytes.size == 10, s"Expected 10 bytes for -0.5, got ${bytes.size}")
        val (ndigits, weight, sign, dscale, digits) = parseHeader(bytes)
        assert(ndigits == 1, s"ndigits expected 1, got $ndigits")
        assert(weight == -1, s"weight expected -1, got $weight")
        assert(sign == 0x4000, s"sign expected 0x4000, got 0x${sign.toHexString}")
        assert(dscale == 1, s"dscale expected 1, got $dscale")
        assert(digits == List(5000), s"digits expected [5000], got $digits")
        // Exact bytes for sign: 40 00; digits[0]=5000=0x1388
        assert(bytes(4) == 0x40.toByte && bytes(5) == 0x00.toByte, "sign=0x4000")
        assert(bytes(8) == 0x13.toByte && bytes(9) == 0x88.toByte, "digits[0]=5000 (0x1388)")
        // weight=-1 → 0xFFFF
        assert(bytes(2) == 0xff.toByte && bytes(3) == 0xff.toByte, "weight=-1 (0xFFFF)")
    }

    // ── Decoder: NaN and Infinity ─────────────────────────────────────────────
    // Wire bytes for special NUMERIC sentinels (PG 14+):
    //   ndigits=0 (00 00), weight=0 (00 00), sign=0xC000/0xD000/0xF000, dscale=0 (00 00)
    // Total: 8 bytes.

    "NUMERIC decode of NaN sentinel raises SqlException.Decode" in {
        // sign=0xC000 → NaN
        val nanBytes = Span.from(Array[Byte](
            0x00,
            0x00, // ndigits=0
            0x00,
            0x00, // weight=0
            0xc0.toByte,
            0x00, // sign=0xC000 (NaN)
            0x00,
            0x00 // dscale=0
        ))
        try
            PostgresDecoder.numeric.read(Format.Binary, nanBytes)
            fail("Expected SqlException.Decode for NaN but read succeeded")
        catch
            case e: kyo.SqlException.Decode =>
                assert(
                    e.getMessage.contains("NaN"),
                    s"SqlException.Decode message should contain 'NaN', got: ${e.getMessage}"
                )
        end try
    }

    "NUMERIC decode of +Infinity sentinel raises SqlException.Decode" in {
        // sign=0xD000 → +Infinity
        val infPosBytes = Span.from(Array[Byte](
            0x00,
            0x00, // ndigits=0
            0x00,
            0x00, // weight=0
            0xd0.toByte,
            0x00, // sign=0xD000 (+Infinity)
            0x00,
            0x00 // dscale=0
        ))
        try
            PostgresDecoder.numeric.read(Format.Binary, infPosBytes)
            fail("Expected SqlException.Decode for +Infinity but read succeeded")
        catch
            case e: kyo.SqlException.Decode =>
                assert(
                    e.getMessage.contains("Infinity"),
                    s"SqlException.Decode message should contain 'Infinity', got: ${e.getMessage}"
                )
        end try
    }

    "NUMERIC decode of -Infinity sentinel raises SqlException.Decode" in {
        // sign=0xF000 → -Infinity
        val infNegBytes = Span.from(Array[Byte](
            0x00,
            0x00, // ndigits=0
            0x00,
            0x00, // weight=0
            0xf0.toByte,
            0x00, // sign=0xF000 (-Infinity)
            0x00,
            0x00 // dscale=0
        ))
        try
            PostgresDecoder.numeric.read(Format.Binary, infNegBytes)
            fail("Expected SqlException.Decode for -Infinity but read succeeded")
        catch
            case e: kyo.SqlException.Decode =>
                assert(
                    e.getMessage.contains("Infinity"),
                    s"SqlException.Decode message should contain 'Infinity', got: ${e.getMessage}"
                )
        end try
    }

    "numeric (decoder) parses NaN as SqlException.Decode when decoded through SqlSchema" in {
        // NaN wire bytes: ndigits=0, weight=0, sign=0xC000, dscale=0 (8 bytes total).
        // PostgresDecoder.numeric.read throws SqlException.Decode directly for NaN.
        // SqlSchema.readPostgres re-raises SqlException.Decode via Abort.
        import kyo.SqlException
        import kyo.SqlRow
        import kyo.internal.postgres.FieldDescription

        val nanBytes = Span.from(Array[Byte](
            0x00,
            0x00, // ndigits=0
            0x00,
            0x00, // weight=0
            0xc0.toByte,
            0x00, // sign=0xC000 (NaN)
            0x00,
            0x00 // dscale=0
        ))

        // Build a fake binary-format SqlRow containing the NaN bytes as column 0.
        val dummyField =
            FieldDescription("n", 0, 0.toShort, 1700 /* OID_NUMERIC */, -1.toShort, -1, 1.toShort /* binary format code */ )
        val fakeRow = SqlRow(Chunk(Maybe.Present(nanBytes)), Chunk(dummyField), Format.Binary)

        // Decode through SqlSchema[BigDecimal], which wraps all serializeRead exceptions into SqlException.Decode.
        Abort.run[SqlException.Decode](summon[SqlSchema[BigDecimal]].readPostgres(fakeRow)).map {
            case Result.Failure(e) =>
                assert(
                    e.getMessage.contains("NaN") || e.getMessage.contains("decode") || e.getMessage.contains("column"),
                    s"SqlException.Decode message did not mention NaN/decode/column: ${e.getMessage}"
                )
            case Result.Success(v) =>
                fail(s"Expected SqlException.Decode for NaN input but got value: $v")
            case Result.Panic(t) =>
                fail(s"Expected SqlException.Decode for NaN input but got panic: ${t.getClass.getName}: ${t.getMessage}")
        }
    }

    "numeric (decoder) round-trips every encoded test vector" in {
        val testCases = List(
            BigDecimal(0),
            BigDecimal(1),
            BigDecimal(-1),
            BigDecimal(12345),
            BigDecimal("0.000001"),
            BigDecimal("123456789.987654321"),
            BigDecimal("1E+100"),
            BigDecimal("1E-100"),
            BigDecimal("-0.5")
        )
        for v <- testCases do
            val encoded = encode(v)
            val decoded = decode(encoded)
            assert(
                decoded.compare(v) == 0,
                s"Round-trip failed for $v: encoded $encoded → decoded $decoded"
            )
        end for
        succeed
    }

    "numericText and numericBinary decode to the same BigDecimal for the same value" in {
        val testCases = List(
            BigDecimal("3.14159265358979"),
            BigDecimal("0"),
            BigDecimal("-999999999.000000001"),
            BigDecimal("1")
        )
        for v <- testCases do
            val textBytes =
                val b = new PostgresBufferWriter; PostgresEncoder.numericText.write(v, b); b.toSpan
            val binaryBytes = encode(v)
            val fromText    = PostgresDecoder.numeric.read(Format.Text, textBytes)
            val fromBinary  = decode(binaryBytes)
            assert(
                fromText.compare(fromBinary) == 0,
                s"String and binary decoders disagree for $v: text=$fromText, binary=$fromBinary"
            )
        end for
        succeed
    }

end PostgresEncoderNumericTest
