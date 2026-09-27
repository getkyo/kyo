package kyo.internal.crypto

import kyo.*
import scala.annotation.tailrec

/** Hexadecimal text for byte arrays: lowercase on encode, either case on decode.
  *
  * Platforms send digests, signatures and public keys as hex (a webhook signature header, an interaction's public key). The encoder emits
  * lowercase only, so equal bytes always render as equal text. Decoding returns `Absent` for an odd length or any character outside
  * `0-9 a-f A-F`, since a caller maps any malformed hex to its own failure and has no use for the reason.
  *
  * Note: neither direction is constant time. Use them on public values, such as a received signature, or on bytes that are then compared
  * with [[ConstantTime.isEqual]].
  */
private[kyo] object Hex:

    private val Digits: Array[Char] = "0123456789abcdef".toCharArray

    def encode(bytes: Array[Byte]): String =
        val out = new Array[Char](bytes.length * 2)

        @tailrec def loop(i: Int): Unit =
            if i < bytes.length then
                val v = bytes(i) & 0xff
                out(i * 2) = Digits(v >>> 4)
                out(i * 2 + 1) = Digits(v & 0x0f)
                loop(i + 1)
        loop(0)
        new String(out)
    end encode

    def decode(text: String): Maybe[Array[Byte]] =
        if text.length % 2 != 0 then Absent
        else
            val out = new Array[Byte](text.length / 2)

            @tailrec def loop(i: Int): Boolean =
                if i == out.length then true
                else
                    val high = digit(text.charAt(i * 2))
                    val low  = digit(text.charAt(i * 2 + 1))
                    if (high | low) < 0 then false
                    else
                        out(i) = ((high << 4) | low).toByte
                        loop(i + 1)
                    end if
            if loop(0) then Present(out) else Absent
    end decode

    private def digit(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else -1

end Hex
