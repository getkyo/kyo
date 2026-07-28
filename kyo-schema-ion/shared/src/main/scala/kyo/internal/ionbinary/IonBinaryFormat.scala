package kyo.internal.ionbinary

import java.io.ByteArrayOutputStream
import kyo.Span
import scala.annotation.tailrec

private[kyo] object IonBinaryFormat:
    val CodecName: String = "IonBinary"

    val VersionMarker: Span[Byte] =
        Span(0xe0.toByte, 0x01.toByte, 0x00.toByte, 0xea.toByte)

    val TidNull: Int       = 0x0
    val TidBool: Int       = 0x1
    val TidPosInt: Int     = 0x2
    val TidNegInt: Int     = 0x3
    val TidFloat: Int      = 0x4
    val TidDecimal: Int    = 0x5
    val TidTimestamp: Int  = 0x6
    val TidSymbol: Int     = 0x7
    val TidString: Int     = 0x8
    val TidClob: Int       = 0x9
    val TidBlob: Int       = 0xa
    val TidList: Int       = 0xb
    val TidSexp: Int       = 0xc
    val TidStruct: Int     = 0xd
    val TidAnnotation: Int = 0xe

    val NullNibble: Int   = 0xf
    val VarLenNibble: Int = 0xe

    val SystemSymbols: Vector[String] = Vector(
        "$ion",
        "$ion_1_0",
        "$ion_symbol_table",
        "name",
        "version",
        "imports",
        "symbols",
        "max_id",
        "$ion_shared_symbol_table"
    )

    val IonSymbolTableSid: Int = 3
    val ImportsSid: Int        = 6
    val SymbolsSid: Int        = 7

    def preview(input: Span[Byte]): String =
        val bytes = input.toArray.take(16)
        bytes.map(b => f"${b & 0xff}%02x").mkString(" ")
    end preview

    def writeDescriptor(out: ByteArrayOutputStream, tid: Int, len: Int): Unit =
        if len < 0 then throw new IllegalArgumentException(s"negative length $len")
        else if len <= 13 then out.write((tid << 4) | len)
        else
            out.write((tid << 4) | VarLenNibble)
            writeVarUInt(out, BigInt(len))
        end if
    end writeDescriptor

    def writeTypedNull(out: ByteArrayOutputStream, tid: Int): Unit =
        out.write((tid << 4) | NullNibble)

    def writeVarUInt(out: ByteArrayOutputStream, value: BigInt): Unit =
        if value < 0 then throw new IllegalArgumentException(s"negative VarUInt $value")
        val groups = sevenBitGroups(value)
        groups.zipWithIndex.foreach { case (group, index) =>
            val last = index == groups.size - 1
            out.write(group | (if last then 0x80 else 0))
        }
    end writeVarUInt

    def writeVarInt(out: ByteArrayOutputStream, value: BigInt): Unit =
        val negative = value < 0
        val groups   = signedGroups(value.abs)
        groups.zipWithIndex.foreach { case (group, index) =>
            val last = index == groups.size - 1
            val sign = if index == 0 && negative then 0x40 else 0
            out.write(group | sign | (if last then 0x80 else 0))
        }
    end writeVarInt

    def unsignedMagnitude(value: BigInt): Array[Byte] =
        if value == 0 then Array.emptyByteArray
        else
            val raw = value.toByteArray
            if raw.nonEmpty && raw(0) == 0.toByte then raw.drop(1) else raw
    end unsignedMagnitude

    def signedMagnitude(value: BigInt): Array[Byte] =
        if value == 0 then Array.emptyByteArray
        else
            val magnitude = unsignedMagnitude(value.abs)
            if value < 0 then
                val out = magnitude.clone()
                out(0) = (out(0) | 0x80).toByte
                out
            else if (magnitude(0) & 0x80) != 0 then 0.toByte +: magnitude
            else magnitude
            end if
        end if
    end signedMagnitude

    def writeBE32(out: ByteArrayOutputStream, value: Int): Unit =
        out.write((value >>> 24) & 0xff)
        out.write((value >>> 16) & 0xff)
        out.write((value >>> 8) & 0xff)
        out.write(value & 0xff)
    end writeBE32

    def writeBE64(out: ByteArrayOutputStream, value: Long): Unit =
        @tailrec def loop(shift: Int): Unit =
            if shift >= 0 then
                out.write(((value >>> shift) & 0xff).toInt)
                loop(shift - 8)
        loop(56)
    end writeBE64

    private def sevenBitGroups(value: BigInt): Vector[Int] =
        @tailrec def loop(v: BigInt, acc: Vector[Int]): Vector[Int] =
            if v == 0 then if acc.isEmpty then Vector(0) else acc
            else loop(v >> 7, ((v & 0x7f).toInt) +: acc)
        loop(value, Vector.empty)
    end sevenBitGroups

    private def signedGroups(value: BigInt): Vector[Int] =
        if value < 0x40 then Vector(value.toInt)
        else
            val tail = sevenBitGroups(value >> 6)
            ((value & 0x3f).toInt) +: tail
        end if
    end signedGroups
end IonBinaryFormat
