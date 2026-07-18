package kyo.internal

/** Pure-Scala PNG encoder for RGBA pixel buffers (the WebGL `readRenderTargetPixels` output the client
  * `ThreeToImage` converts to a byte array before calling this).
  *
  * Encodes a flat RGBA `Array[Byte]` (rows bottom-to-top, OpenGL convention) into a valid PNG byte stream
  * (rows top-to-bottom, PNG convention). The zlib stream uses DEFLATE stored blocks (RFC 1951 BTYPE=00),
  * which are valid per RFC 1950/1951 and produce larger but fully correct PNG files suitable for thumbnails
  * and visual review. CRC32 and Adler-32 are computed in pure Scala (no external dependencies), making the
  * encoder portable across the JS and Wasm backends without any `node:zlib` import.
  *
  * The stored-block approach handles arbitrarily large images by chunking the filtered-row data into
  * 65535-byte blocks (the maximum for a single stored block); only the final block sets BFINAL=1.
  *
  * Row flip: WebGL readPixels delivers row 0 as the bottom row; PNG row 0 is the top row. The encoder
  * iterates source rows from `height-1` downto `0` when building the filtered-row buffer.
  *
  * Color type 6 (RGBA, 4 bytes per pixel) matches the `RGBAFormat` default of `WebGLRenderTarget`.
  */
private[kyo] object Png:

    private val PngSignature: Array[Byte] = Array(0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    /** Precomputed CRC32 lookup table (polynomial 0xEDB88320, reflected). */
    private val Crc32Table: Array[Int] =
        val table = new Array[Int](256)
        var i     = 0
        while i < 256 do
            var c = i
            var k = 0
            while k < 8 do
                if (c & 1) != 0 then c = 0xedb88320 ^ (c >>> 1)
                else c = c >>> 1
                k += 1
            end while
            table(i) = c
            i += 1
        end while
        table
    end Crc32Table

    /** Updates a running CRC32 over a slice of bytes. */
    private def crc32Update(crc: Int, buf: Array[Byte], off: Int, len: Int): Int =
        var c = crc ^ 0xffffffff
        var i = off
        while i < off + len do
            c = Crc32Table((c ^ buf(i)) & 0xff) ^ (c >>> 8)
            i += 1
        end while
        c ^ 0xffffffff
    end crc32Update

    /** Computes CRC32 over the concatenation of a 4-byte chunk type name and the chunk data bytes. */
    private def chunkCrc(typeBytes: Array[Byte], data: Array[Byte]): Int =
        val c1 = crc32Update(0, typeBytes, 0, typeBytes.length)
        crc32Update(c1, data, 0, data.length)
    end chunkCrc

    /** Writes a 32-bit value as 4 big-endian bytes into `buf` at `off`. */
    private def writeBe32(buf: Array[Byte], off: Int, v: Int): Unit =
        buf(off) = ((v >>> 24) & 0xff).toByte
        buf(off + 1) = ((v >>> 16) & 0xff).toByte
        buf(off + 2) = ((v >>> 8) & 0xff).toByte
        buf(off + 3) = (v & 0xff).toByte
    end writeBe32

    /** Writes a 16-bit value as 2 little-endian bytes into `buf` at `off`. */
    private def writeLe16(buf: Array[Byte], off: Int, v: Int): Unit =
        buf(off) = (v & 0xff).toByte
        buf(off + 1) = ((v >>> 8) & 0xff).toByte
    end writeLe16

    /** Serializes one PNG chunk: 4-byte length + 4-byte type + data + 4-byte CRC. */
    private def chunk(typeName: String, data: Array[Byte]): Array[Byte] =
        val typeBytes = typeName.getBytes("ISO-8859-1")
        val len       = data.length
        val out       = new Array[Byte](4 + 4 + len + 4)
        writeBe32(out, 0, len)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(data, 0, out, 8, len)
        val crc = chunkCrc(typeBytes, data)
        writeBe32(out, 8 + len, crc)
        out
    end chunk

    /** Builds the IHDR chunk payload: width, height, bit depth 8, color type 6 (RGBA), compression 0,
      * filter 0, interlace 0.
      */
    private def ihdrData(width: Int, height: Int): Array[Byte] =
        val data = new Array[Byte](13)
        writeBe32(data, 0, width)
        writeBe32(data, 4, height)
        data(8) = 8.toByte
        data(9) = 6.toByte
        data(10) = 0.toByte
        data(11) = 0.toByte
        data(12) = 0.toByte
        data
    end ihdrData

    /** Computes the Adler-32 checksum of `buf[off .. off+len)`. */
    private def adler32(buf: Array[Byte], off: Int, len: Int): Int =
        var s1 = 1
        var s2 = 0
        var i  = off
        while i < off + len do
            s1 = (s1 + (buf(i) & 0xff)) % 65521
            s2 = (s2 + s1)              % 65521
            i += 1
        end while
        (s2 << 16) | s1
    end adler32

    /** Wraps raw data in a zlib stream using DEFLATE stored blocks (RFC 1951 BTYPE=00):
      * 2-byte zlib header (0x78 0x01) + one-or-more stored blocks + 4-byte big-endian Adler-32.
      * Each stored block: 1-byte BFINAL/BTYPE flag + 2-byte LEN (LE) + 2-byte NLEN (LE) + LEN bytes.
      * The final block sets BFINAL=1; all others set BFINAL=0.
      */
    private def zlibStored(data: Array[Byte]): Array[Byte] =
        val maxBlock  = 65535
        val total     = data.length
        val numBlocks = if total == 0 then 1 else (total + maxBlock - 1) / maxBlock
        // Each block header: 1 + 2 + 2 = 5 bytes; plus 2-byte zlib header and 4-byte Adler-32 trailer.
        val outSize = 2 + numBlocks * 5 + total + 4
        val out     = new Array[Byte](outSize)
        // zlib header: CMF=0x78, FLG=0x01 (FCHECK so (0x78*256+0x01) % 31 == 0).
        out(0) = 0x78.toByte
        out(1) = 0x01.toByte
        var outPos  = 2
        var dataPos = 0
        var block   = 0
        while block < numBlocks do
            val blockLen = math.min(maxBlock, total - dataPos)
            val isFinal  = block == numBlocks - 1
            out(outPos) = (if isFinal then 0x01 else 0x00).toByte
            outPos += 1
            writeLe16(out, outPos, blockLen)
            outPos += 2
            writeLe16(out, outPos, ~blockLen & 0xffff)
            outPos += 2
            System.arraycopy(data, dataPos, out, outPos, blockLen)
            outPos += blockLen
            dataPos += blockLen
            block += 1
        end while
        val a32 = adler32(data, 0, total)
        writeBe32(out, outPos, a32)
        out
    end zlibStored

    /** Builds the filtered-row buffer: one filter-byte (0x00 = None) plus 4 RGBA bytes per pixel per row,
      * rows ordered top-to-bottom (PNG convention). Source rows in `pixels` are bottom-to-top (OpenGL
      * convention), so row `i` in PNG output corresponds to row `height-1-i` in the pixel buffer.
      */
    private def filteredRows(pixels: Array[Byte], width: Int, height: Int): Array[Byte] =
        val rowStride = width * 4
        val buf       = new Array[Byte](height * (1 + rowStride))
        var pngRow    = 0
        while pngRow < height do
            val srcRow = height - 1 - pngRow
            val srcOff = srcRow * rowStride
            val dstOff = pngRow * (1 + rowStride)
            buf(dstOff) = 0.toByte
            var px = 0
            while px < rowStride do
                buf(dstOff + 1 + px) = pixels(srcOff + px)
                px += 1
            end while
            pngRow += 1
        end while
        buf
    end filteredRows

    /** Encodes an RGBA pixel buffer into a valid PNG byte array.
      *
      * @param pixels a flat RGBA `Array[Byte]` of length `width * height * 4`, rows bottom-to-top.
      * @param width  the image width in pixels.
      * @param height the image height in pixels.
      * @return a PNG byte array with rows top-to-bottom, color type 6 (RGBA).
      */
    def encode(pixels: Array[Byte], width: Int, height: Int): Array[Byte] =
        val rows     = filteredRows(pixels, width, height)
        val zlib     = zlibStored(rows)
        val sig      = PngSignature
        val ihdr     = chunk("IHDR", ihdrData(width, height))
        val idat     = chunk("IDAT", zlib)
        val iend     = chunk("IEND", Array.emptyByteArray)
        val totalLen = sig.length + ihdr.length + idat.length + iend.length
        val out      = new Array[Byte](totalLen)
        var pos      = 0
        System.arraycopy(sig, 0, out, pos, sig.length); pos += sig.length
        System.arraycopy(ihdr, 0, out, pos, ihdr.length); pos += ihdr.length
        System.arraycopy(idat, 0, out, pos, idat.length); pos += idat.length
        System.arraycopy(iend, 0, out, pos, iend.length)
        out
    end encode

end Png
