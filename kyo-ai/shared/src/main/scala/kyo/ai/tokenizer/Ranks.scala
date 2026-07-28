package kyo.ai.tokenizer

import kyo.*
import kyo.ai.Tokenizer.Encoding

/** An immutable byte-sequence -> rank lookup for one encoding, backed by the generated packed
  * rank table (O200kRanks / Cl100kRanks). Pure and process-shared (an embedded immutable value,
  * no process-global mutable state).
  */
final private[kyo] class Ranks(private val table: Map[Ranks.Key, Int]):
    /** The rank of the byte range `bytes[start until end)`, or Absent when that byte sequence is
      * not a token in this vocabulary (the BPE stops merging that pair).
      */
    def rankOf(bytes: Array[Byte], start: Int, end: Int): Maybe[Int] =
        Maybe.fromOption(table.get(Ranks.Key(bytes, start, end)))
end Ranks

private[kyo] object Ranks:
    /** A value key over a byte sub-range, equal by CONTENT so two equal byte sequences hash the
      * same regardless of backing array (the merge loop keys on live sub-ranges).
      */
    final class Key(private val bytes: Array[Byte], private val start: Int, private val end: Int) derives CanEqual:
        private val hash: Int =
            var h = 1
            var i = start
            while i < end do
                h = 31 * h + bytes(i); i += 1
            h
        end hash
        override def hashCode(): Int = hash
        override def equals(other: Any): Boolean =
            other match
                case that: Key => that.hash == hash && sameBytes(that.bytes, that.start, that.end)
                case _         => false

        /** True when this key's byte range equals `[s, e)` of `b`, byte-for-byte. Two keys are
          * equal by CONTENT, so the merge loop keys directly on live sub-ranges with no copy.
          */
        private def sameBytes(b: Array[Byte], s: Int, e: Int): Boolean =
            if (e - s) != (end - start) then false
            else
                var i  = start
                var j  = s
                var eq = true
                while eq && i < end do
                    if bytes(i) != b(j) then eq = false
                    i += 1
                    j += 1
                end while
                eq
    end Key

    private val o200k: Ranks  = Ranks(O200kRanks.load())
    private val cl100k: Ranks = Ranks(Cl100kRanks.load())

    def forEncoding(encoding: Encoding): Ranks =
        encoding match
            case Encoding.O200kBase  => o200k
            case Encoding.Cl100kBase => cl100k

    /** Decodes a generated rank table's base64 chunks into the content-keyed rank map. Pure and
      * cross-platform: a hand-rolled base64 decoder (java.util.Base64's javalib coverage across
      * Scala.js/Native/Wasm is unconfirmed in this tree, so it is not depended on) feeds a walk of
      * the packed byte stream, where each entry is one length byte, that many token bytes, then a
      * LEB128 varint rank. Called once per encoding by O200kRanks.load / Cl100kRanks.load; the
      * result is held in the immutable `o200k` / `cl100k` vals above.
      */
    private[kyo] def decode(chunks: Array[String]): Map[Key, Int] =
        val packed  = decodeBase64(chunks)
        val builder = Map.newBuilder[Key, Int]
        var i       = 0
        while i < packed.length do
            val len      = packed(i) & 0xff
            val keyStart = i + 1
            val keyEnd   = keyStart + len
            var rank     = 0
            var shift    = 0
            var j        = keyEnd
            var more     = true
            while more do
                val b = packed(j) & 0xff
                rank |= (b & 0x7f) << shift
                shift += 7
                j += 1
                more = (b & 0x80) != 0
            end while
            builder += (Key(packed, keyStart, keyEnd) -> rank)
            i = j
        end while
        builder.result()
    end decode

    /** Pure-Scala base64 decode of the concatenated chunks into the raw packed bytes. Standard
      * alphabet, '=' padding tolerated; no java.util.Base64, no java.util.zip, no resource file.
      */
    private def decodeBase64(chunks: Array[String]): Array[Byte] =
        val out  = Array.newBuilder[Byte]
        var acc  = 0
        var bits = 0
        var ci   = 0
        while ci < chunks.length do
            val s = chunks(ci)
            var k = 0
            while k < s.length do
                val c = s.charAt(k)
                if c != '=' then
                    acc = (acc << 6) | base64Value(c)
                    bits += 6
                    if bits >= 8 then
                        bits -= 8
                        out += ((acc >> bits) & 0xff).toByte
                end if
                k += 1
            end while
            ci += 1
        end while
        out.result()
    end decodeBase64

    private def base64Value(c: Char): Int =
        if c >= 'A' && c <= 'Z' then c - 'A'
        else if c >= 'a' && c <= 'z' then c - 'a' + 26
        else if c >= '0' && c <= '9' then c - '0' + 52
        else if c == '+' then 62
        else 63 // '/'
end Ranks
