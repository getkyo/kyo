package kyo.internal.crypto

import kyo.*
import kyo.crypto.Rsa.DerFailure

/** A tag-length-value reader for the key structures kyo reads. It reads lengths and never checks a declared length against the bytes
  * that follow, takes long-form lengths the short form would fit, and reads an INTEGER's magnitude whatever its high bit: BER as much as
  * DER, since a database server's key arrives in whichever encoding its tool wrote.
  */
final private[kyo] class DerReader(der: Array[Byte]):
    private var pos: Int = 0

    /** Bytes not yet consumed by this reader. */
    def remaining: Int = der.length - pos

    /** Whether the next byte is `tag`, without consuming it. */
    def hasTag(tag: Int): Boolean = pos < der.length && (der(pos) & 0xff) == tag

    def readTag(tag: Int): Result[DerFailure, Unit] =
        if pos >= der.length then Result.fail(DerFailure.EndOfData(tag))
        else
            val actual = der(pos) & 0xff
            if actual != tag then Result.fail(DerFailure.UnexpectedTag(tag, actual, pos))
            else
                pos += 1
                Result.unit
            end if
        end if
    end readTag

    def readLength(): Result[DerFailure, Int] =
        if pos >= der.length then Result.fail(DerFailure.LengthEndOfData)
        else
            val first = der(pos) & 0xff
            pos += 1
            if (first & 0x80) == 0 then Result.succeed(first)
            else
                val count = first & 0x7f
                if count == 0 || count > 4 || pos + count > der.length then Result.fail(DerFailure.LengthUnsupported(pos - 1))
                else
                    var length = 0L
                    var i      = 0
                    while i < count do
                        length = (length << 8) | (der(pos) & 0xff)
                        pos += 1
                        i += 1
                    end while
                    // Four bytes with the top bit set exceed Int.MaxValue; read as an Int they would be negative and move the reader
                    // backwards.
                    if length > Int.MaxValue then Result.fail(DerFailure.LengthUnsupported(pos - 1 - count))
                    else Result.succeed(length.toInt)
                end if
            end if
        end if
    end readLength

    def skip(count: Int): Result[DerFailure, Unit] =
        if pos.toLong + count > der.length then Result.fail(DerFailure.SkipPastEnd(count, pos))
        else
            pos += count
            Result.unit
    end skip

    def readInteger(): Result[DerFailure, BigInt] =
        readTag(0x02).flatMap { _ =>
            readLength().flatMap { length =>
                if pos.toLong + length > der.length then Result.fail(DerFailure.IntegerExceedsData(pos))
                else
                    val bytes = java.util.Arrays.copyOfRange(der, pos, pos + length)
                    pos += length
                    Result.succeed(BigInt(1, bytes))
                end if
            }
        }
    end readInteger

    /** The next `count` bytes, as a copy. Past the end, nothing is read and the failure is [[DerFailure.SkipPastEnd]], as for [[skip]]. */
    def readBytes(count: Int): Result[DerFailure, Array[Byte]] =
        if pos.toLong + count > der.length then Result.fail(DerFailure.SkipPastEnd(count, pos))
        else
            val bytes = java.util.Arrays.copyOfRange(der, pos, pos + count)
            pos += count
            Result.succeed(bytes)
    end readBytes

end DerReader
