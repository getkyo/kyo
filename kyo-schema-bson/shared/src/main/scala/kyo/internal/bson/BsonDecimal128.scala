package kyo.internal.bson

import kyo.ParseException
import kyo.SchemaNotSerializableException

private[bson] object BsonDecimal128:
    private val ExponentBias = 6176
    private val MinExponent  = -6176
    private val MaxExponent  = 6111
    private val MaxDigits    = 34
    private val MaxCoeff     = BigInt("9999999999999999999999999999999999")
    private val CoeffBits    = 113
    private val CoeffMask    = (BigInt(1) << CoeffBits) - 1
    private val SpecialMask  = BigInt(3) << 125
    private val SpecialBits  = BigInt(3) << 125

    def encode(value: BigDecimal): Array[Byte] =
        val stripped =
            val bd = value.bigDecimal.stripTrailingZeros()
            if bd.signum == 0 then java.math.BigDecimal.ZERO else bd

        val negative = stripped.signum < 0
        var coeff    = BigInt(stripped.unscaledValue.abs)
        var exponent = -stripped.scale

        while exponent > MaxExponent && decimalDigits(coeff) < MaxDigits do
            coeff *= 10
            exponent -= 1
        end while

        if coeff > MaxCoeff || decimalDigits(coeff) > MaxDigits then
            invalid(s"BigDecimal $value cannot be represented exactly as BSON Decimal128")
        if exponent < MinExponent || exponent > MaxExponent then
            invalid(s"BigDecimal $value exponent is outside BSON Decimal128 range")

        val biased = exponent + ExponentBias
        var bits   = (BigInt(biased) << CoeffBits) | coeff
        if negative then bits |= BigInt(1) << 127
        toLittleEndian16(bits)
    end encode

    def decode(bytes: Array[Byte], format: kyo.Bson, inputPreview: String)(using frame: kyo.Frame): BigDecimal =
        val bits = fromLittleEndian(bytes)
        val sign = bits.testBit(127)

        var biased = 0
        var coeff0 = BigInt(0)
        if (bits & SpecialMask) == SpecialBits then
            biased = ((bits >> 111) & BigInt(0x3fff)).toInt
            coeff0 = (BigInt(1) << 113) | (bits & ((BigInt(1) << 111) - 1))
        else
            biased = ((bits >> CoeffBits) & BigInt(0x3fff)).toInt
            coeff0 = bits & CoeffMask
        end if

        val coeff =
            if coeff0 > MaxCoeff then BigInt(0)
            else coeff0
        val signedCoeff = if sign then -coeff else coeff
        val exponent    = biased - ExponentBias
        if exponent < MinExponent || exponent > MaxExponent then
            throw ParseException(format, inputPreview, "BSON Decimal128 exponent")(using frame)
        BigDecimal(signedCoeff, -exponent)
    end decode

    private def decimalDigits(value: BigInt): Int =
        if value == 0 then 1 else value.toString.length

    private def toLittleEndian16(value: BigInt): Array[Byte] =
        val bytes = new Array[Byte](16)
        var i     = 0
        while i < 16 do
            bytes(i) = ((value >> (i * 8)) & BigInt(0xff)).toByte
            i += 1
        end while
        bytes
    end toLittleEndian16

    private def fromLittleEndian(bytes: Array[Byte]): BigInt =
        var result = BigInt(0)
        var i      = 15
        while i >= 0 do
            result = (result << 8) | BigInt(bytes(i) & 0xff)
            i -= 1
        end while
        result
    end fromLittleEndian

    private def invalid(message: String): Nothing =
        throw SchemaNotSerializableException(message)(using kyo.Frame.internal)
    end invalid
end BsonDecimal128
