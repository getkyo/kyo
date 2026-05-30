package kyo.internal.tasty.scala2

/** Pure-Scala RFC 1950 (ZLIB) inflate. No JVM dependencies. */
object PortableInflate:

    final class InflateException(msg: String, val byteOffset: Long) extends RuntimeException(msg)

    /** Bit-level reader over a byte array. LSB-first per DEFLATE spec. */
    final private[kyo] class BitStream(buf: Array[Byte], var bitOffset: Long):
        def byteOffset: Long = bitOffset >> 3

        def readBit(): Int =
            val byte = buf((bitOffset >> 3).toInt) & 0xff
            val bit  = (byte >> (bitOffset & 7).toInt) & 1
            bitOffset += 1
            bit
        end readBit

        def readBits(n: Int): Int =
            var result = 0
            var i      = 0
            while i < n do
                result |= (readBit() << i)
                i += 1
            result
        end readBits

        def alignToByte(): Int =
            val rem = bitOffset & 7
            if rem != 0 then bitOffset += (8 - rem)
            (bitOffset >> 3).toInt
        end alignToByte

        def readBytes(out: scala.collection.mutable.ArrayBuffer[Byte], len: Int): Unit =
            val start = alignToByte()
            var i     = 0
            while i < len do
                out += buf(start + i)
                i += 1
            bitOffset += (len * 8)
        end readBytes
    end BitStream

    /** Canonical Huffman tree built from code-length arrays per RFC 1951 §3.2.2. */
    final private[kyo] class HuffmanTree private (
        val maxBits: Int,
        val codeToSymbol: Array[Int],
        val bitLengthCounts: Array[Int]
    ):
        def decodeOne(stream: BitStream): Int =
            var code   = 0
            var first  = 0
            var index  = 0
            var len    = 1
            var result = -1
            while len <= maxBits && result < 0 do
                code = (code << 1) | stream.readBit()
                val count = bitLengthCounts(len)
                if code - count < first then
                    result = codeToSymbol(index + (code - first))
                else
                    index += count
                    first = (first + count) << 1
                    len += 1
                end if
            end while
            if result >= 0 then result
            else throw new InflateException(s"invalid Huffman code at bit ${stream.bitOffset}", stream.byteOffset)
        end decodeOne
    end HuffmanTree

    /** Companion for building a HuffmanTree from code-length arrays per RFC 1951 §3.2.2. */
    private[kyo] object HuffmanTree:
        def fromCodeLengths(lengths: Array[Int]): HuffmanTree =
            val maxBits         = lengths.max
            val bitLengthCounts = new Array[Int](maxBits + 1)
            var i               = 0
            while i < lengths.length do
                bitLengthCounts(lengths(i)) += 1
                i += 1
            bitLengthCounts(0) = 0
            val codeToSymbol = new Array[Int](lengths.length)
            val offsets      = new Array[Int](maxBits + 1)
            var sum          = 0
            var len          = 1
            while len <= maxBits do
                offsets(len) = sum
                sum += bitLengthCounts(len)
                len += 1
            end while
            var sym = 0
            while sym < lengths.length do
                val l = lengths(sym)
                if l != 0 then
                    codeToSymbol(offsets(l)) = sym
                    offsets(l) += 1
                sym += 1
            end while
            new HuffmanTree(maxBits, codeToSymbol, bitLengthCounts)
        end fromCodeLengths
    end HuffmanTree

    // RFC 1951 §3.2.5 length code table: indexed by (sym - 257), gives (base_length, extra_bits)
    private val lengthBase: Array[Int] = Array(
        3, 4, 5, 6, 7, 8, 9, 10,
        11, 13, 15, 17,
        19, 23, 27, 31,
        35, 43, 51, 59,
        67, 83, 99, 115,
        131, 163, 195, 227,
        258
    )
    private val lengthExtra: Array[Int] = Array(
        0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1,
        2, 2, 2, 2,
        3, 3, 3, 3,
        4, 4, 4, 4,
        5, 5, 5, 5,
        0
    )

    // RFC 1951 §3.2.5 distance code table: indexed by sym (0..29), gives (base_distance, extra_bits)
    private val distanceBase: Array[Int] = Array(
        1, 2, 3, 4,
        5, 7,
        9, 13,
        17, 25,
        33, 49,
        65, 97,
        129, 193,
        257, 385,
        513, 769,
        1025, 1537,
        2049, 3073,
        4097, 6145,
        8193, 12289,
        16385, 24577
    )
    private val distanceExtra: Array[Int] = Array(
        0, 0, 0, 0,
        1, 1,
        2, 2,
        3, 3,
        4, 4,
        5, 5,
        6, 6,
        7, 7,
        8, 8,
        9, 9,
        10, 10,
        11, 11,
        12, 12,
        13, 13
    )

    private def lengthCode(sym: Int): (Int, Int) =
        (lengthBase(sym - 257), lengthExtra(sym - 257))

    private def distanceCode(sym: Int): (Int, Int) =
        (distanceBase(sym), distanceExtra(sym))

    // RFC 1951 §3.2.4 stored (raw) block
    private[kyo] def decodeStoredBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
        val _        = stream.alignToByte()
        val lenLow   = stream.readBits(8)
        val lenHigh  = stream.readBits(8)
        val len      = lenLow | (lenHigh << 8)
        val nlenLow  = stream.readBits(8)
        val nlenHigh = stream.readBits(8)
        val nlen     = nlenLow | (nlenHigh << 8)
        if (len ^ nlen) != 0xffff then
            throw new InflateException(s"stored block LEN ^ NLEN != 0xffff (LEN=$len NLEN=$nlen)", stream.byteOffset)
        stream.readBytes(out, len)
    end decodeStoredBlock

    // RFC 1951 §3.2.6 fixed Huffman code lengths
    private val fixedLiteralLengths: Array[Int] =
        Array.tabulate(288) { i =>
            if i < 144 then 8 else if i < 256 then 9 else if i < 280 then 7 else 8
        }
    private val fixedDistanceLengths: Array[Int] = Array.fill(30)(5)

    private lazy val fixedLiteralTree: HuffmanTree  = HuffmanTree.fromCodeLengths(fixedLiteralLengths)
    private lazy val fixedDistanceTree: HuffmanTree = HuffmanTree.fromCodeLengths(fixedDistanceLengths)

    // RFC 1951 §3.2.6 fixed Huffman block
    private[kyo] def decodeFixedHuffmanBlock(stream: BitStream, out: scala.collection.mutable.ArrayBuffer[Byte]): Unit =
        decodeHuffmanBlock(stream, out, fixedLiteralTree, fixedDistanceTree)

    // Shared literal/length+distance decode loop used by both fixed and dynamic blocks
    private[kyo] def decodeHuffmanBlock(
        stream: BitStream,
        out: scala.collection.mutable.ArrayBuffer[Byte],
        litTree: HuffmanTree,
        distTree: HuffmanTree
    ): Unit =
        var done = false
        while !done do
            val sym = litTree.decodeOne(stream)
            if sym < 256 then
                out += sym.toByte
            else if sym == 256 then
                done = true
            else
                val (baseLen, lExtra)  = lengthCode(sym)
                val len                = baseLen + (if lExtra > 0 then stream.readBits(lExtra) else 0)
                val distSym            = distTree.decodeOne(stream)
                val (baseDist, dExtra) = distanceCode(distSym)
                val dist               = baseDist + (if dExtra > 0 then stream.readBits(dExtra) else 0)
                copyBack(out, dist, len)
            end if
        end while
    end decodeHuffmanBlock

    // LZ77 backreference copy: dist bytes back in out, copy len bytes
    private[kyo] def copyBack(out: scala.collection.mutable.ArrayBuffer[Byte], dist: Int, len: Int): Unit =
        val start = out.length
        var i     = 0
        while i < len do
            out += out(start - dist + i)
            i += 1
    end copyBack

    // RFC 1951 §3.2.7 dynamic Huffman block
    // code-length alphabet permutation per spec
    private val codeLengthOrder: Array[Int] =
        Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private[kyo] def decodeDynamicHuffmanBlock(
        stream: BitStream,
        out: scala.collection.mutable.ArrayBuffer[Byte]
    ): Unit =
        val hlit        = stream.readBits(5) + 257
        val hdist       = stream.readBits(5) + 1
        val hclen       = stream.readBits(4) + 4
        val codeLengths = new Array[Int](19)
        var i           = 0
        while i < hclen do
            codeLengths(codeLengthOrder(i)) = stream.readBits(3)
            i += 1
        val codeLengthTree = HuffmanTree.fromCodeLengths(codeLengths)
        val combined       = decodeCodeLengths(stream, codeLengthTree, hlit + hdist)
        val litLens        = combined.slice(0, hlit)
        val distLens       = combined.slice(hlit, hlit + hdist)
        val litTree        = HuffmanTree.fromCodeLengths(litLens)
        val distTree       = HuffmanTree.fromCodeLengths(distLens)
        decodeHuffmanBlock(stream, out, litTree, distTree)
    end decodeDynamicHuffmanBlock

    // Decode a run-length encoded code-length sequence per RFC 1951 §3.2.7
    private[kyo] def decodeCodeLengths(stream: BitStream, tree: HuffmanTree, total: Int): Array[Int] =
        val arr = new Array[Int](total)
        var i   = 0
        while i < total do
            val sym = tree.decodeOne(stream)
            if sym <= 15 then
                arr(i) = sym
                i += 1
            else if sym == 16 then
                val n = stream.readBits(2) + 3
                val v = arr(i - 1)
                var k = 0
                while k < n do
                    arr(i + k) = v
                    k += 1
                i += n
            else if sym == 17 then
                val n = stream.readBits(3) + 3
                i += n
            else
                val n = stream.readBits(7) + 11
                i += n
            end if
        end while
        arr
    end decodeCodeLengths

    /** RFC 1950 ZLIB inflate: validates CMF/FLG header, dispatches DEFLATE block decoders, and verifies the Adler-32 trailer.
      */
    def inflate(compressed: Array[Byte]): Array[Byte] =
        if compressed.length < 6 then
            throw new InflateException("ZLIB input too short (< 6 bytes)", 0L)
        val cmf = compressed(0) & 0xff
        val flg = compressed(1) & 0xff
        if (cmf & 0x0f) != 8 then
            throw new InflateException(s"unsupported compression method ${cmf & 0x0f}", 0L)
        if ((cmf << 8) | flg) % 31 != 0 then
            throw new InflateException("ZLIB header checksum failed", 0L)
        if (flg & 0x20) != 0 then
            throw new InflateException("ZLIB preset dictionary not supported", 1L)
        val stream    = new BitStream(compressed, 16L)
        val out       = new scala.collection.mutable.ArrayBuffer[Byte](compressed.length * 4)
        var lastBlock = false
        while !lastBlock do
            lastBlock = stream.readBit() == 1
            val blockType = stream.readBits(2)
            blockType match
                case 0 => decodeStoredBlock(stream, out)
                case 1 => decodeFixedHuffmanBlock(stream, out)
                case 2 => decodeDynamicHuffmanBlock(stream, out)
                case 3 => throw new InflateException("reserved DEFLATE block type 3", stream.byteOffset)
            end match
        end while
        val tail          = stream.alignToByte()
        val expectedAdler = readU32BE(compressed, tail)
        val actualAdler   = adler32(out.toArray)
        if expectedAdler != actualAdler then
            throw new InflateException(s"Adler-32 mismatch: expected $expectedAdler got $actualAdler", tail.toLong)
        out.toArray
    end inflate

    /** RFC 1950 §9 Adler-32. Uses Long accumulators to defer the modulo and avoid overflow. */
    private def adler32(data: Array[Byte]): Long =
        var a = 1L
        var b = 0L
        var i = 0
        while i < data.length do
            a = (a + (data(i) & 0xff)) % 65521
            b = (b + a)                % 65521
            i += 1
        end while
        (b << 16) | a
    end adler32

    /** Read a 4-byte big-endian unsigned value from buf at offset. The ZLIB Adler-32 trailer is big-endian. */
    private def readU32BE(buf: Array[Byte], offset: Int): Long =
        ((buf(offset) & 0xffL) << 24) |
            ((buf(offset + 1) & 0xffL) << 16) |
            ((buf(offset + 2) & 0xffL) << 8) |
            (buf(offset + 3) & 0xffL)
    end readU32BE

end PortableInflate
