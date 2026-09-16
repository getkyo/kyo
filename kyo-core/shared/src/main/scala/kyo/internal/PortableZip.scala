package kyo.internal

/** DEFLATE (RFC 1951), ZLIB (RFC 1950) and the two checksums, in Scala.
  *
  * `java.util.zip` is native code on the JVM and absent everywhere else, so a host without it needs this. The shapes here mirror the small
  * part of `java.util.zip` that `StreamCompression` drives, so one set of stream drivers serves every platform: a `Deflater` takes input,
  * is asked for output until it says it has none, and is finished once; an `Inflater` is the same in reverse and reports what it did not
  * consume.
  *
  * The compressor emits one block per 32 KB of input, with matches found inside that block only. Dropping the window that spans blocks
  * costs a little ratio at each boundary and removes the bookkeeping that carries a match across one. Blocks use the fixed Huffman codes
  * of RFC 1951 3.2.6, where zlib builds a table per block, so the output is larger: by little on prose, where a table of its own saves
  * zlib little, and by more on input with long repeats, where that table makes every length code cheap and this one pays full price for
  * each. `PortableZipTest` pins both. Whatever the size, it reads back on anything that reads DEFLATE, and the decompressor here reads
  * every block type, dynamic tables included, since it has to read what other compressors produce.
  *
  * A block the fixed codes would make larger than its input, which is what input that repays no match comes to, is stored as it is
  * instead, so incompressible input grows by the stored framing only, as it does under zlib.
  *
  * The decompressor is resumable. Every symbol is decoded from a mark, and input that runs out mid-symbol rewinds to that mark rather than
  * leaving half a symbol behind, so a stream arriving one byte at a time decodes exactly as one arriving whole. It decodes a symbol with one
  * table lookup and keeps its bit state in `Int`s: a `Long` is emulated on Scala.js, and a decoder that walked a code a bit at a time
  * through one ran at a few megabytes a second there.
  *
  * This is mutable, unsynchronized state with a bit-level hot loop, the case where mutability is the implementation: it is confined to one
  * instance, and every instance is owned by the `Scope` that made it.
  */
private[kyo] object PortableZip:

    /** Raised on input that is not the stream it claims to be: a bad ZLIB header, an unknown block type, a Huffman code with no symbol, a
      * back-reference past the start of the output, or a checksum that does not match.
      */
    final class DataFormatException(message: String) extends RuntimeException(message, null, false, false)

    /** CRC-32 as RFC 1952 defines it, table driven over one byte at a time. */
    final class Crc32:
        private var current: Int = -1

        def update(bytes: Array[Byte], off: Int, len: Int): Unit =
            var i   = off
            val end = off + len
            var c   = current
            while i < end do
                c = Crc32.Table((c ^ bytes(i)) & 0xff) ^ (c >>> 8)
                i += 1
            current = c
        end update

        def update(bytes: Array[Byte]): Unit = update(bytes, 0, bytes.length)

        def getValue: Long = (~current).toLong & 0xffffffffL

        def reset(): Unit = current = -1
    end Crc32

    object Crc32:
        private[PortableZip] val Table: Array[Int] =
            val table = new Array[Int](256)
            var n     = 0
            while n < 256 do
                var c = n
                var k = 0
                while k < 8 do
                    c = if (c & 1) != 0 then 0xedb88320 ^ (c >>> 1) else c >>> 1
                    k += 1
                table(n) = c
                n += 1
            end while
            table
        end Table
    end Crc32

    /** The longest run of bytes that can be added to both Adler-32 sums before either could pass `Int.MaxValue`.
      *
      * zlib reduces every 5552, the bound for unsigned 32-bit arithmetic. These sums are `Int`, which is signed, so the bound is the
      * largest n with `65520 + 65520n + 255n(n+1)/2 <= Int.MaxValue`. At 5552 the second sum goes negative on bytes with high values, and
      * the remainder of a negative number is negative, which is how a checksum over long input silently stops matching.
      */
    private val AdlerRun = 3854

    /** Adler-32 as RFC 1950 defines it. */
    final class Adler32:
        private var a: Int = 1
        private var b: Int = 0

        def update(bytes: Array[Byte], off: Int, len: Int): Unit =
            var i         = off
            var remaining = len
            while remaining > 0 do
                val run = if remaining < AdlerRun then remaining else AdlerRun
                var k   = 0
                while k < run do
                    a += bytes(i) & 0xff
                    b += a
                    i += 1
                    k += 1
                end while
                a %= 65521
                b %= 65521
                remaining -= run
            end while
        end update

        def update(bytes: Array[Byte]): Unit = update(bytes, 0, bytes.length)

        def getValue: Long = ((b.toLong << 16) | a.toLong) & 0xffffffffL

        def reset(): Unit =
            a = 1
            b = 0
    end Adler32

    // The length and distance alphabets of RFC 1951 3.2.5.
    private val LengthBase =
        Array(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LengthExtra =
        Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DistanceBase =
        Array(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193,
            12289, 16385, 24577)
    private val DistanceExtra =
        Array(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    /** The order the code-length alphabet's own lengths arrive in, RFC 1951 3.2.7. */
    private val CodeLengthOrder = Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val WindowSize  = 32768
    private val MaxMatch    = 258
    private val MinMatch    = 3
    private val EndOfBlock  = 256
    private val LitLenCodes = 288
    private val MaxCodeBits = 15
    private val BlockLimit  = 32768
    private val SyncFlush   = 2
    private val FullFlush   = 3
    private val HuffmanOnly = 2

    private val FixedLitLengths: Array[Int] =
        val lengths = new Array[Int](LitLenCodes)
        var i       = 0
        while i < LitLenCodes do
            lengths(i) =
                if i < 144 then 8
                else if i < 256 then 9
                else if i < 280 then 7
                else 8
            i += 1
        end while
        lengths
    end FixedLitLengths

    private val FixedDistLengths: Array[Int] = Array.fill(32)(5)

    /** A canonical Huffman code as a lookup table over its longest code's length in bits, `bits`: the entry at the next `bits` input bits,
      * read least significant first, is `symbol << 4 | length` for the code those bits start with, and `0` where no code does. A code
      * shorter than `bits` fills every entry its bits prefix, so one read decodes any symbol.
      */
    final private class Decoder(val table: Array[Int], val bits: Int)

    private def decoder(lengths: Array[Int], n: Int): Decoder =
        val counts = new Array[Int](MaxCodeBits + 1)
        var i      = 0
        while i < n do
            counts(lengths(i)) += 1
            i += 1
        counts(0) = 0
        // A set of lengths that claims more codes than a tree of this depth has is not a code, and a stream carrying
        // one is corrupt. Saying so here is what keeps two codes from claiming the same table entries.
        var left = 1
        var len  = 1
        while len <= MaxCodeBits do
            left <<= 1
            left -= counts(len)
            if left < 0 then throw new DataFormatException("A code table in the stream claims more codes than it can hold")
            len += 1
        end while
        var bits = MaxCodeBits
        while bits > 0 && counts(bits) == 0 do bits -= 1
        // An incomplete table, which RFC 1951 allows, leaves entries empty, and a stream that reads one is corrupt.
        val table = new Array[Int](1 << bits)
        val codes = canonicalCodes(java.util.Arrays.copyOf(lengths, n))
        i = 0
        while i < n do
            val length = lengths(i)
            if length != 0 then
                val entry = (i << 4) | length
                var index = reverseBits(codes(i), length)
                while index < table.length do
                    table(index) = entry
                    index += 1 << length
                end while
            end if
            i += 1
        end while
        new Decoder(table, bits)
    end decoder

    private val FixedLitDecoder  = decoder(FixedLitLengths, LitLenCodes)
    private val FixedDistDecoder = decoder(FixedDistLengths, 32)

    /** The canonical codes for a set of lengths, as RFC 1951 3.2.2 derives them. */
    private def canonicalCodes(lengths: Array[Int]): Array[Int] =
        val countPerLength = new Array[Int](MaxCodeBits + 1)
        var i              = 0
        while i < lengths.length do
            if lengths(i) > 0 then countPerLength(lengths(i)) += 1
            i += 1
        val nextCode = new Array[Int](MaxCodeBits + 2)
        var code     = 0
        var bits     = 1
        while bits <= MaxCodeBits do
            code = (code + countPerLength(bits - 1)) << 1
            nextCode(bits) = code
            bits += 1
        end while
        val codes = new Array[Int](lengths.length)
        i = 0
        while i < lengths.length do
            val len = lengths(i)
            if len > 0 then
                codes(i) = nextCode(len)
                nextCode(len) += 1
            i += 1
        end while
        codes
    end canonicalCodes

    /** The fixed codes as the bit writer takes them, reversed once here rather than at every symbol. */
    private val FixedLitCodes  = reversedCodes(FixedLitLengths)
    private val FixedDistCodes = reversedCodes(FixedDistLengths)

    private def reversedCodes(lengths: Array[Int]): Array[Int] =
        val codes = canonicalCodes(lengths)
        var i     = 0
        while i < codes.length do
            codes(i) = reverseBits(codes(i), lengths(i))
            i += 1
        codes
    end reversedCodes

    /** A Huffman code is written most significant bit first into a stream that is read least significant bit first. */
    private def reverseBits(value: Int, count: Int): Int =
        var result = 0
        var i      = 0
        var v      = value
        while i < count do
            result = (result << 1) | (v & 1)
            v >>>= 1
            i += 1
        end while
        result
    end reverseBits

    /** The length code for a match length, and the distance code for a distance. Both alphabets are small enough to scan. */
    private def lengthCode(length: Int): Int =
        var code = LengthBase.length - 1
        while code > 0 && LengthBase(code) > length do code -= 1
        code
    end lengthCode

    private def distanceCode(distance: Int): Int =
        var code = DistanceBase.length - 1
        while code > 0 && DistanceBase(code) > distance do code -= 1
        code
    end distanceCode

    /** Output bits, least significant first, over a growing byte buffer.
      *
      * Whole bytes are committed as they complete and can be handed out while the stream is still open; the bits of a byte in progress
      * stay here, which is what lets one block end mid-byte and the next begin in the same byte.
      */
    final private class BitWriter:
        private var buffer  = new Array[Byte](4096)
        private var length  = 0
        private var acc     = 0
        private var accBits = 0

        private def ensure(extra: Int): Unit =
            if length + extra > buffer.length then
                var size = buffer.length << 1
                while size < length + extra do size <<= 1
                val grown = new Array[Byte](size)
                Array.copy(buffer, 0, grown, 0, length)
                buffer = grown
            end if
        end ensure

        def writeBits(value: Int, count: Int): Unit =
            if count > 0 then
                acc |= (value & ((1 << count) - 1)) << accBits
                accBits += count
                ensure((accBits >> 3) + 1)
                while accBits >= 8 do
                    buffer(length) = (acc & 0xff).toByte
                    length += 1
                    acc >>>= 8
                    accBits -= 8
                end while
            end if
        end writeBits

        def committed: Int = length

        /** Bits written after the last whole byte, which a stored block's alignment pads out. */
        def pendingBits: Int = accBits

        def alignToByte(): Unit =
            if accBits > 0 then writeBits(0, 8 - accBits)

        def writeBytes(src: Array[Byte], off: Int, count: Int): Unit =
            ensure(count)
            Array.copy(src, off, buffer, length, count)
            length += count
        end writeBytes

        /** Moves up to `count` committed bytes into `target`, keeping the rest for the next call. */
        def drain(target: Array[Byte], off: Int, count: Int): Int =
            val n = if count < length then count else length
            if n > 0 then
                Array.copy(buffer, 0, target, off, n)
                Array.copy(buffer, n, buffer, 0, length - n)
                length -= n
            end if
            n
        end drain
    end BitWriter

    /** The compressing half. `level` is the `java.util.zip` scale: -1 for the default, 0 for stored blocks, 1 to 9 for how hard the match
      * search works. `noWrap` writes raw DEFLATE instead of the ZLIB frame.
      */
    final class Deflater(level: Int, noWrap: Boolean):
        private val effectiveLevel = if level < 0 then 6 else level
        private val out            = new BitWriter
        private val adler          = new Adler32
        private val head           = new Array[Int](1 << 15)
        private val prev           = new Array[Int](BlockLimit)
        private val tokens         = new Array[Int](BlockLimit)
        private var tokenCount     = 0
        private var input          = new Array[Byte](BlockLimit * 2)
        private var inputLength    = 0
        private var strategy       = 0
        private var headerWritten  = noWrap
        private var finishing      = false
        private var flushed        = false
        private var complete       = false
        private var read: Long     = 0

        def setStrategy(value: Int): Unit = strategy = value

        def setInput(bytes: Array[Byte]): Unit =
            if inputLength + bytes.length > input.length then
                val grown = new Array[Byte](inputLength + bytes.length)
                Array.copy(input, 0, grown, 0, inputLength)
                input = grown
            end if
            Array.copy(bytes, 0, input, inputLength, bytes.length)
            inputLength += bytes.length
            read += bytes.length
            flushed = false
            if !noWrap then adler.update(bytes)
        end setInput

        def finish(): Unit = finishing = true

        /** Every byte handed over is taken into this deflater's own buffer, so it is always ready for the next one. */
        def needsInput: Boolean = true

        /** Finished once the last block is written and the caller has taken every byte of it. */
        def finished: Boolean = complete && out.committed == 0

        def getBytesRead: Long = read

        def end(): Unit = ()

        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int =
            if !headerWritten then
                // CM = 8 (deflate), CINFO = 7 (a 32 KB window), no preset dictionary, and the check bits that make the two
                // bytes a multiple of 31.
                val header = 0x7800 | (levelBits << 6)
                val framed = header + (31 - (header % 31))
                out.writeBits((framed >>> 8) & 0xff, 8)
                out.writeBits(framed & 0xff, 8)
                headerWritten = true
            end if
            if !complete then
                if finishing then
                    while inputLength > BlockLimit do emitBlock(BlockLimit, last = false)
                    emitBlock(inputLength, last = true)
                    out.alignToByte()
                    if !noWrap then
                        val checksum = adler.getValue
                        out.writeBits((checksum >>> 24).toInt & 0xff, 8)
                        out.writeBits((checksum >>> 16).toInt & 0xff, 8)
                        out.writeBits((checksum >>> 8).toInt & 0xff, 8)
                        out.writeBits(checksum.toInt & 0xff, 8)
                    end if
                    complete = true
                else if (flush == SyncFlush || flush == FullFlush) && !flushed then
                    while inputLength > BlockLimit do emitBlock(BlockLimit, last = false)
                    if inputLength > 0 then emitBlock(inputLength, last = false)
                    // An empty stored block is what a reader takes as a flush point, and it leaves the stream byte aligned.
                    out.writeBits(0, 3)
                    out.alignToByte()
                    out.writeBits(0x0000, 16)
                    out.writeBits(0xffff, 16)
                    // The caller asks again until nothing comes back, and the marker belongs to the input just given, not
                    // to each of those asks.
                    flushed = true
                else
                    while inputLength >= BlockLimit do emitBlock(BlockLimit, last = false)
                end if
            end if
            out.drain(target, off, len)
        end deflate

        /** The FLEVEL bits of a ZLIB header: the fastest and the slowest levels say so, everything else is the default. */
        private def levelBits: Int =
            if effectiveLevel <= 1 then 0
            else if effectiveLevel >= 9 then 3
            else if effectiveLevel < 6 then 1
            else 2

        /** Writes one block of the first `count` bytes of the pending input and drops them from it.
          *
          * The block is stored as it is when that is smaller than its fixed-code form, which is what input that repays no match comes to:
          * under the fixed codes every byte from 144 up costs nine bits. A stored block costs its three-bit header, the padding to the next
          * byte, and 32 bits of length, so the choice is made on the exact bit count of both.
          */
        private def emitBlock(count: Int, last: Boolean): Unit =
            if effectiveLevel == 0 then storedBlock(count, last)
            else
                val fixedBits  = 3 + tokenize(count) + FixedLitLengths(EndOfBlock)
                val storedBits = 3 + ((8 - ((out.pendingBits + 3) & 7)) & 7) + 32 + count * 8
                if storedBits < fixedBits then storedBlock(count, last)
                else fixedBlock(last)
            end if
            if count < inputLength then Array.copy(input, count, input, 0, inputLength - count)
            inputLength -= count
        end emitBlock

        private def storedBlock(count: Int, last: Boolean): Unit =
            // A stored block carries its length in 16 bits, which is why the block limit is 32 KB and not more.
            out.writeBits(if last then 1 else 0, 1)
            out.writeBits(0, 2)
            out.alignToByte()
            out.writeBits(count & 0xffff, 16)
            out.writeBits((~count) & 0xffff, 16)
            out.writeBytes(input, 0, count)
        end storedBlock

        /** Finds the literals and matches of the first `count` bytes of the pending input, into `tokens`, and answers what writing them
          * with the fixed codes costs in bits. A literal is its byte value, `0` to `255`; a match is `-(length << 16 | distance)`.
          */
        private def tokenize(count: Int): Int =
            tokenCount = 0
            var bits = 0
            if strategy == HuffmanOnly then
                var i = 0
                while i < count do
                    val symbol = input(i) & 0xff
                    tokens(i) = symbol
                    bits += FixedLitLengths(symbol)
                    i += 1
                end while
                tokenCount = count
            else
                val chainLimit = maxChain
                java.util.Arrays.fill(head, -1)
                var position = 0
                while position < count do
                    var bestLength   = 0
                    var bestDistance = 0
                    if position + MinMatch <= count then
                        val key   = hash(position)
                        var chain = head(key)
                        var steps = 0
                        while chain >= 0 && steps < chainLimit do
                            var length = 0
                            val limit  = if count - position < MaxMatch then count - position else MaxMatch
                            while length < limit && input(chain + length) == input(position + length) do length += 1
                            if length >= MinMatch && length > bestLength then
                                bestLength = length
                                bestDistance = position - chain
                            end if
                            chain = prev(chain)
                            steps += 1
                        end while
                        prev(position) = head(key)
                        head(key) = position
                    end if
                    if bestLength >= MinMatch then
                        // The positions a match covers still need hashing, or the next match cannot see them.
                        var covered = position + 1
                        val until   = position + bestLength
                        while covered < until do
                            if covered + MinMatch <= count then
                                val key = hash(covered)
                                prev(covered) = head(key)
                                head(key) = covered
                            end if
                            covered += 1
                        end while
                        tokens(tokenCount) = -((bestLength << 16) | bestDistance)
                        tokenCount += 1
                        val lc = lengthCode(bestLength)
                        val dc = distanceCode(bestDistance)
                        bits += FixedLitLengths(257 + lc) + LengthExtra(lc) + FixedDistLengths(dc) + DistanceExtra(dc)
                        position += bestLength
                    else
                        val symbol = input(position) & 0xff
                        tokens(tokenCount) = symbol
                        tokenCount += 1
                        bits += FixedLitLengths(symbol)
                        position += 1
                    end if
                end while
            end if
            bits
        end tokenize

        /** Writes the tokens `tokenize` found as one block of fixed codes. */
        private def fixedBlock(last: Boolean): Unit =
            out.writeBits(if last then 1 else 0, 1)
            out.writeBits(1, 2)
            var i = 0
            while i < tokenCount do
                val token = tokens(i)
                if token >= 0 then out.writeBits(FixedLitCodes(token), FixedLitLengths(token))
                else
                    val length   = (-token) >>> 16
                    val distance = (-token) & 0xffff
                    val lc       = lengthCode(length)
                    out.writeBits(FixedLitCodes(257 + lc), FixedLitLengths(257 + lc))
                    out.writeBits(length - LengthBase(lc), LengthExtra(lc))
                    val dc = distanceCode(distance)
                    out.writeBits(FixedDistCodes(dc), FixedDistLengths(dc))
                    out.writeBits(distance - DistanceBase(dc), DistanceExtra(dc))
                end if
                i += 1
            end while
            out.writeBits(FixedLitCodes(EndOfBlock), FixedLitLengths(EndOfBlock))
        end fixedBlock

        private def hash(at: Int): Int =
            (((input(at) & 0xff) << 10) ^ ((input(at + 1) & 0xff) << 5) ^ (input(at + 2) & 0xff)) & 0x7fff

        /** How many earlier positions sharing the same three bytes the search walks. This is what the level buys. */
        private def maxChain: Int =
            effectiveLevel match
                case 1 => 4
                case 2 => 8
                case 3 => 16
                case 4 => 32
                case 5 => 64
                case 6 => 128
                case 7 => 256
                case 8 => 1024
                case _ => 4096
    end Deflater

    /** The decompressing half. `noWrap` reads raw DEFLATE instead of the ZLIB frame.
      *
      * Bits are taken from the input into an `Int` a byte at a time, least significant first: `position` is the next byte to take and
      * `bitCount` how many taken bits are not consumed yet. Nothing here reads more than 16 bits at once, so the buffer never holds more than
      * 23. A symbol is decoded with one lookup in its code's table ([[Decoder]]) and a mark of all three taken before it, so input that
      * runs out part way restores them and leaves nothing half read.
      */
    final class Inflater(noWrap: Boolean):
        import Inflater.*

        private var state       = if noWrap then BlockStart else ZlibHeader
        private var input       = Array.empty[Byte]
        private var position    = 0
        private var bitBuffer   = 0
        private var bitCount    = 0
        private var markAt      = 0
        private var markBuffer  = 0
        private var markCount   = 0
        private var starved     = true
        private var lastBlock   = false
        private var storedLeft  = 0
        private var litDecoder  = FixedLitDecoder
        private var distDecoder = FixedDistDecoder
        private var written     = 0L

        // Adler-32 over the output, kept as the running sums so a byte costs two adds on the path that writes it.
        private var adlerA     = 1
        private var adlerB     = 0
        private var adlerCount = 0

        // Decoded output, long enough to hold a window of history plus what one pass writes ahead of it.
        private val window      = new Array[Byte](WindowSize * 2 + MaxMatch)
        private var windowEnd   = 0
        private var windowStart = 0

        def setInput(bytes: Array[Byte]): Unit =
            dropConsumedInput()
            if input.isEmpty then input = bytes
            else
                val joined = new Array[Byte](input.length + bytes.length)
                Array.copy(input, 0, joined, 0, input.length)
                Array.copy(bytes, 0, joined, input.length, bytes.length)
                input = joined
            end if
            starved = false
        end setInput

        // Both answers wait on the window: output already decoded is owed to the caller, and a caller that reads these
        // to decide whether to call `inflate` again would otherwise stop with bytes still here.
        def needsInput: Boolean = starved && windowStart == windowEnd

        def finished: Boolean = state == Finished && windowStart == windowEnd

        def getBytesWritten: Long = written

        def end(): Unit = ()

        /** The input bytes the stream did not reach: what follows a finished stream, and nothing while one is still running. A byte the
          * stream read any bit of is reached.
          */
        def remainingBytes: Array[Byte] =
            val consumed = position - (bitCount >> 3)
            if consumed >= input.length then Array.empty
            else java.util.Arrays.copyOfRange(input, consumed, input.length)
        end remainingBytes

        def inflate(target: Array[Byte]): Int =
            var produced = 0
            var decoding = true
            var running  = true
            while running && produced < target.length do
                if windowEnd > windowStart then
                    val ready = windowEnd - windowStart
                    val room  = target.length - produced
                    val n     = if ready < room then ready else room
                    Array.copy(window, windowStart, target, produced, n)
                    windowStart += n
                    produced += n
                else if !decoding || state == Finished then running = false
                else
                    makeRoom()
                    decoding = decodeStep()
                end if
            end while
            produced
        end inflate

        /** Keeps a window of history and starts writing again after it, so a back-reference always has its bytes. */
        private def makeRoom(): Unit =
            if windowEnd + MaxMatch > window.length then
                Array.copy(window, windowEnd - WindowSize, window, 0, WindowSize)
                windowEnd = WindowSize
                windowStart = WindowSize
        end makeRoom

        /** Drops the input bytes already consumed. Whole bytes taken into the bit buffer and not consumed go back to the input first. */
        private def dropConsumedInput(): Unit =
            untake()
            if position > 0 then
                val kept = new Array[Byte](input.length - position)
                Array.copy(input, position, kept, 0, kept.length)
                input = kept
                position = 0
            end if
        end dropConsumedInput

        /** Returns the whole bytes in the bit buffer to the input, keeping the bits of a byte partly consumed. */
        private def untake(): Unit =
            val whole = bitCount >> 3
            if whole > 0 then
                position -= whole
                bitCount -= whole << 3
                bitBuffer &= (1 << bitCount) - 1
            end if
        end untake

        /** Takes input bytes until the bit buffer holds `count` bits, at most 16, and says whether the input had them. */
        private def fill(count: Int): Boolean =
            while bitCount < count && position < input.length do
                bitBuffer |= (input(position) & 0xff) << bitCount
                position += 1
                bitCount += 8
            end while
            bitCount >= count
        end fill

        /** Consumes `count` bits the buffer holds, `fill` having said it does. */
        private def take(count: Int): Int =
            val value = bitBuffer & ((1 << count) - 1)
            bitBuffer >>>= count
            bitCount -= count
            value
        end take

        /** Consumes the bits up to the next byte boundary. */
        private def alignToByte(): Unit =
            val _ = take(bitCount & 7)

        private def mark(): Unit =
            markAt = position
            markBuffer = bitBuffer
            markCount = bitCount
        end mark

        /** Rewinds to the mark, so the next call decodes whole what the input ran out in the middle of. */
        private def starve(): Boolean =
            position = markAt
            bitBuffer = markBuffer
            bitCount = markCount
            starved = true
            false
        end starve

        private def emit(byte: Byte): Unit =
            window(windowEnd) = byte
            windowEnd += 1
            written += 1
            if !noWrap then
                adlerA += byte & 0xff
                adlerB += adlerA
                adlerCount += 1
                if adlerCount == AdlerRun then
                    adlerA %= 65521
                    adlerB %= 65521
                    adlerCount = 0
                end if
            end if
        end emit

        private def adlerValue: Long =
            adlerA %= 65521
            adlerB %= 65521
            adlerCount = 0
            ((adlerB.toLong << 16) | adlerA.toLong) & 0xffffffffL
        end adlerValue

        /** The next symbol of `table`'s code, or `Starved` when the input ends before its code does. */
        private def decodeSymbol(table: Decoder): Int =
            if table.bits == 0 then throw new DataFormatException("A Huffman code in the stream matches no symbol")
            val whole  = fill(table.bits)
            val entry  = table.table(bitBuffer & ((1 << table.bits) - 1))
            val length = entry & 15
            if length != 0 && length <= bitCount then
                bitBuffer >>>= length
                bitCount -= length
                entry >>> 4
            else if whole then throw new DataFormatException("A Huffman code in the stream matches no symbol")
            else Starved
            end if
        end decodeSymbol

        private def decodeStep(): Boolean =
            state match
                case ZlibHeader  => readZlibHeader()
                case BlockStart  => readBlockHeader()
                case StoredBytes => readStored()
                case Symbols     => readSymbols()
                case ZlibTrailer => readTrailer()
                case _           => false

        private def readZlibHeader(): Boolean =
            mark()
            if !fill(16) then starve()
            else
                val cmf = take(8)
                val flg = take(8)
                if (cmf & 0x0f) != 8 then
                    throw new DataFormatException(s"Only deflate (8) compression method is supported, present: ${cmf & 0x0f}")
                else if ((cmf << 8) | flg) % 31 != 0 then throw new DataFormatException("The ZLIB header check bits do not match")
                else if (flg & 0x20) != 0 then throw new DataFormatException("A preset dictionary is not supported")
                else
                    state = BlockStart
                    true
                end if
            end if
        end readZlibHeader

        private def readBlockHeader(): Boolean =
            mark()
            if !fill(3) then starve()
            else
                lastBlock = take(1) == 1
                take(2) match
                    case 0 =>
                        // A stored block starts on a byte boundary and carries its length twice, the second time inverted.
                        alignToByte()
                        if !fill(16) then starve()
                        else
                            val length = take(16)
                            if !fill(16) then starve()
                            else
                                val inverse = take(16)
                                if length != (inverse ^ 0xffff) then
                                    throw new DataFormatException("A stored block's length and its complement do not agree")
                                storedLeft = length
                                state = StoredBytes
                                true
                            end if
                        end if
                    case 1 =>
                        litDecoder = FixedLitDecoder
                        distDecoder = FixedDistDecoder
                        state = Symbols
                        true
                    case 2 =>
                        readDynamicTables()
                    case _ =>
                        throw new DataFormatException("A block in the stream declares the reserved type 3")
                end match
            end if
        end readBlockHeader

        /** Reads the two code tables of a dynamic block. On short input it rewinds to the block header, marked by the caller, and reads
          * them again later.
          */
        private def readDynamicTables(): Boolean =
            if !fill(14) then starve()
            else
                val litCount    = take(5) + 257
                val distCount   = take(5) + 1
                val codeCount   = take(4) + 4
                val codeLengths = new Array[Int](19)
                var i           = 0
                var short       = false
                while !short && i < codeCount do
                    if !fill(3) then short = true
                    else
                        codeLengths(CodeLengthOrder(i)) = take(3)
                        i += 1
                end while
                if short then starve()
                else
                    val codeDecoder = decoder(codeLengths, 19)
                    val lengths     = new Array[Int](litCount + distCount)
                    var filled      = 0
                    while !short && filled < lengths.length do
                        val symbol = decodeSymbol(codeDecoder)
                        if symbol == Starved then short = true
                        else if symbol < 16 then
                            lengths(filled) = symbol
                            filled += 1
                        else
                            val extraBits = if symbol == 16 then 2 else if symbol == 17 then 3 else 7
                            if symbol == 16 && filled == 0 then
                                throw new DataFormatException("A code length repeats before any length is given")
                            if !fill(extraBits) then short = true
                            else
                                val repeat = take(extraBits) + (if symbol == 18 then 11 else 3)
                                val value  = if symbol == 16 then lengths(filled - 1) else 0
                                if filled + repeat > lengths.length then
                                    throw new DataFormatException("The code lengths in the stream run past the alphabet they describe")
                                var k = 0
                                while k < repeat do
                                    lengths(filled) = value
                                    filled += 1
                                    k += 1
                                end while
                            end if
                        end if
                    end while
                    if short then starve()
                    else
                        val litLengths  = new Array[Int](litCount)
                        val distLengths = new Array[Int](distCount)
                        Array.copy(lengths, 0, litLengths, 0, litCount)
                        Array.copy(lengths, litCount, distLengths, 0, distCount)
                        litDecoder = decoder(litLengths, litCount)
                        distDecoder = decoder(distLengths, distCount)
                        state = Symbols
                        true
                    end if
                end if
            end if
        end readDynamicTables

        /** Copies a stored block's bytes to the window, as many as the input, the window and the block all have. */
        private def readStored(): Boolean =
            if storedLeft == 0 then endBlock()
            else
                // The block starts on a byte boundary, so the bit buffer holds whole bytes only, and those go back to the input.
                untake()
                val available = input.length - position
                if available == 0 then
                    starved = true
                    false
                else
                    var count = window.length - windowEnd
                    if count > storedLeft then count = storedLeft
                    if count > available then count = available
                    Array.copy(input, position, window, windowEnd, count)
                    if !noWrap then
                        var i     = windowEnd
                        val until = windowEnd + count
                        while i < until do
                            adlerA += window(i) & 0xff
                            adlerB += adlerA
                            adlerCount += 1
                            if adlerCount == AdlerRun then
                                adlerA %= 65521
                                adlerB %= 65521
                                adlerCount = 0
                            end if
                            i += 1
                        end while
                    end if
                    windowEnd += count
                    written += count
                    position += count
                    storedLeft -= count
                    true
                end if
            end if
        end readStored

        /** Decodes symbols until the block ends, the input runs out, or the window has no room for another maximum-length match. */
        private def readSymbols(): Boolean =
            var running = true
            var result  = true
            while running do
                if windowEnd + MaxMatch > window.length then running = false
                else
                    mark()
                    val symbol = decodeSymbol(litDecoder)
                    if symbol == Starved then
                        result = starve()
                        running = false
                    else if symbol < EndOfBlock then emit(symbol.toByte)
                    else if symbol == EndOfBlock then
                        result = endBlock()
                        running = false
                    else
                        val code = symbol - 257
                        if code >= LengthBase.length then throw new DataFormatException(s"A length code past the alphabet: $symbol")
                        if !fill(LengthExtra(code)) then
                            result = starve()
                            running = false
                        else
                            val length   = LengthBase(code) + take(LengthExtra(code))
                            val distance = decodeSymbol(distDecoder)
                            if distance == Starved then
                                result = starve()
                                running = false
                            else if distance >= DistanceBase.length then
                                throw new DataFormatException(s"A distance code past the alphabet: $distance")
                            else if !fill(DistanceExtra(distance)) then
                                result = starve()
                                running = false
                            else
                                val back = DistanceBase(distance) + take(DistanceExtra(distance))
                                if back > windowEnd then
                                    throw new DataFormatException("A back-reference reaches past the start of the output")
                                var from = windowEnd - back
                                var i    = 0
                                while i < length do
                                    emit(window(from))
                                    from += 1
                                    i += 1
                                end while
                            end if
                        end if
                    end if
                end if
            end while
            result
        end readSymbols

        private def endBlock(): Boolean =
            if lastBlock then
                alignToByte()
                if noWrap then
                    state = Finished
                    false
                else
                    state = ZlibTrailer
                    true
                end if
            else
                state = BlockStart
                true
        end endBlock

        private def readTrailer(): Boolean =
            mark()
            if !fill(16) then starve()
            else
                val high = take(16)
                if !fill(16) then starve()
                else
                    val low = take(16)
                    // Read least significant bit first, each 16 bits hold two bytes in stream order, the first in the low eight.
                    val b0       = (high & 0xff).toLong
                    val b1       = ((high >>> 8) & 0xff).toLong
                    val b2       = (low & 0xff).toLong
                    val b3       = ((low >>> 8) & 0xff).toLong
                    val expected = ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3) & 0xffffffffL
                    if adlerValue != expected then throw new DataFormatException("The ZLIB checksum does not match the data")
                    state = Finished
                    false
                end if
            end if
        end readTrailer
    end Inflater

    object Inflater:
        private val ZlibHeader  = 0
        private val BlockStart  = 1
        private val StoredBytes = 2
        private val Symbols     = 3
        private val ZlibTrailer = 4
        private val Finished    = 5

        /** The answer from `decodeSymbol` that is not a symbol: the input ran out before the code did. */
        private val Starved = -1
    end Inflater
end PortableZip
