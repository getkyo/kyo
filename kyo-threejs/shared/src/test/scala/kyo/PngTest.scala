package kyo

import kyo.internal.Png

/** Tests for the pure [[kyo.internal.Png]] encoder: known synthetic RGBA data through `Png.encode`,
  * asserting structural PNG validity plus a byte-exact round-trip after decompressing the stored-block
  * zlib stream. `Png` is FFI-free, so this runs on all four platforms.
  */
class PngTest extends ThreeTest:

    "Png.encode produces a valid 8-byte PNG signature" in {
        val w      = 2
        val h      = 2
        val pixels = syntheticRgba(w, h)
        val png    = Png.encode(pixels, w, h)
        assert(png.length >= 8, "PNG must be at least 8 bytes")
        val sig = Array(0x89.toByte, 0x50.toByte, 0x4e.toByte, 0x47.toByte, 0x0d.toByte, 0x0a.toByte, 0x1a.toByte, 0x0a.toByte)
        assert(sig.sameElements(png.slice(0, 8)), "first 8 bytes must be the PNG magic number")
    }

    "Png.encode produces IHDR with correct width, height, and color type 6" in {
        val w       = 4
        val h       = 3
        val pixels  = syntheticRgba(w, h)
        val png     = Png.encode(pixels, w, h)
        val ihdrLen = readBe32(png, 8)
        assert(ihdrLen == 13, s"IHDR length must be 13, got $ihdrLen")
        val typeName = new String(png.slice(12, 16), "ISO-8859-1")
        assert(typeName == "IHDR", s"chunk type must be IHDR, got $typeName")
        val widthInPng  = readBe32(png, 16)
        val heightInPng = readBe32(png, 20)
        val bitDepth    = png(24)
        val colorType   = png(25)
        assert(widthInPng == w, s"IHDR width must be $w, got $widthInPng")
        assert(heightInPng == h, s"IHDR height must be $h, got $heightInPng")
        assert(bitDepth == 8.toByte, s"IHDR bit depth must be 8, got $bitDepth")
        assert(colorType == 6.toByte, s"IHDR color type must be 6 (RGBA), got $colorType")
    }

    "Png.encode round-trips RGBA pixels with correct row-flip via stored-block inflate" in {
        val w = 2
        val h = 2
        // Row 0 (bottom, OpenGL): pixel (0,0)=red, (1,0)=green; row 1 (top): (0,1)=blue, (1,1)=white.
        // After row-flip: PNG row 0 = blue+white, PNG row 1 = red+green.
        val pixels = rgba(
            255, 0, 0, 255, 0, 255, 0, 255,
            0, 0, 255, 255, 255, 255, 255, 255
        )
        val png          = Png.encode(pixels, w, h)
        val idatData     = extractIdatData(png)
        val filteredRows = inflateStored(idatData)

        val rowStride = 1 + w * 4
        assert(filteredRows(0) == 0.toByte, "filter byte must be 0 (None)")
        assert((filteredRows(1) & 0xff) == 0, s"R of blue pixel = ${filteredRows(1) & 0xff}")
        assert((filteredRows(2) & 0xff) == 0, s"G of blue pixel = ${filteredRows(2) & 0xff}")
        assert((filteredRows(3) & 0xff) == 255, s"B of blue pixel = ${filteredRows(3) & 0xff}")
        assert((filteredRows(4) & 0xff) == 255, s"A of blue pixel = ${filteredRows(4) & 0xff}")
        assert((filteredRows(5) & 0xff) == 255, s"R of white pixel = ${filteredRows(5) & 0xff}")
        assert((filteredRows(6) & 0xff) == 255, s"G of white pixel = ${filteredRows(6) & 0xff}")
        assert((filteredRows(7) & 0xff) == 255, s"B of white pixel = ${filteredRows(7) & 0xff}")
        assert((filteredRows(8) & 0xff) == 255, s"A of white pixel = ${filteredRows(8) & 0xff}")
        assert(filteredRows(rowStride) == 0.toByte, "filter byte row 1 must be 0")
        assert((filteredRows(rowStride + 1) & 0xff) == 255, s"R of red = ${filteredRows(rowStride + 1) & 0xff}")
        assert((filteredRows(rowStride + 2) & 0xff) == 0, s"G of red = ${filteredRows(rowStride + 2) & 0xff}")
        assert((filteredRows(rowStride + 3) & 0xff) == 0, s"B of red = ${filteredRows(rowStride + 3) & 0xff}")
        assert((filteredRows(rowStride + 5) & 0xff) == 0, s"R of green = ${filteredRows(rowStride + 5) & 0xff}")
        assert((filteredRows(rowStride + 6) & 0xff) == 255, s"G of green = ${filteredRows(rowStride + 6) & 0xff}")
        assert((filteredRows(rowStride + 7) & 0xff) == 0, s"B of green = ${filteredRows(rowStride + 7) & 0xff}")
    }

    "Png.encode round-trips a non-square 3x2 image with row-flip via stored-block inflate" in {
        val w = 3
        val h = 2
        // Row 0 (bottom): red, green, blue; row 1 (top): white, yellow, cyan.
        val pixels = rgba(
            255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255,
            255, 255, 255, 255, 255, 255, 0, 255, 0, 255, 255, 255
        )
        val png          = Png.encode(pixels, w, h)
        val idatData     = extractIdatData(png)
        val filteredRows = inflateStored(idatData)

        val rowStride = 1 + w * 4
        assert(filteredRows.length == h * rowStride, s"inflated length ${filteredRows.length} != ${h * rowStride}")
        // PNG row 0 (top) == original row 1: white, yellow, cyan.
        assert(filteredRows(0) == 0.toByte, "filter byte row 0 must be 0 (None)")
        assert((filteredRows(1) & 0xff) == 255, s"white R = ${filteredRows(1) & 0xff}")
        assert((filteredRows(2) & 0xff) == 255, s"white G = ${filteredRows(2) & 0xff}")
        assert((filteredRows(3) & 0xff) == 255, s"white B = ${filteredRows(3) & 0xff}")
        assert((filteredRows(5) & 0xff) == 255, s"yellow R = ${filteredRows(5) & 0xff}")
        assert((filteredRows(6) & 0xff) == 255, s"yellow G = ${filteredRows(6) & 0xff}")
        assert((filteredRows(7) & 0xff) == 0, s"yellow B = ${filteredRows(7) & 0xff}")
        assert((filteredRows(9) & 0xff) == 0, s"cyan R = ${filteredRows(9) & 0xff}")
        assert((filteredRows(10) & 0xff) == 255, s"cyan G = ${filteredRows(10) & 0xff}")
        assert((filteredRows(11) & 0xff) == 255, s"cyan B = ${filteredRows(11) & 0xff}")
        // PNG row 1 (bottom) == original row 0: red, green, blue.
        assert(filteredRows(rowStride) == 0.toByte, "filter byte row 1 must be 0 (None)")
        assert((filteredRows(rowStride + 1) & 0xff) == 255, s"red R = ${filteredRows(rowStride + 1) & 0xff}")
        assert((filteredRows(rowStride + 2) & 0xff) == 0, s"red G = ${filteredRows(rowStride + 2) & 0xff}")
        assert((filteredRows(rowStride + 3) & 0xff) == 0, s"red B = ${filteredRows(rowStride + 3) & 0xff}")
        assert((filteredRows(rowStride + 5) & 0xff) == 0, s"green R = ${filteredRows(rowStride + 5) & 0xff}")
        assert((filteredRows(rowStride + 6) & 0xff) == 255, s"green G = ${filteredRows(rowStride + 6) & 0xff}")
        assert((filteredRows(rowStride + 9) & 0xff) == 0, s"blue R = ${filteredRows(rowStride + 9) & 0xff}")
        assert((filteredRows(rowStride + 11) & 0xff) == 255, s"blue B = ${filteredRows(rowStride + 11) & 0xff}")
    }

    "Png.encode produces multiple stored blocks for large images" in {
        // 256x256 filtered rows = 256 * (1 + 256*4) = 262400 bytes > 65535, so at least 5 stored blocks.
        val w        = 256
        val h        = 256
        val pixels   = syntheticRgba(w, h)
        val png      = Png.encode(pixels, w, h)
        val idatData = extractIdatData(png)
        assert(idatData.length > 2, "IDAT data must have content beyond the zlib header")
        val firstBlockFlag = idatData(2) & 0xff
        assert(firstBlockFlag == 0x00, s"first block must be non-final (0x00), got 0x${firstBlockFlag.toHexString}")
        val filteredRows = inflateStored(idatData)
        val expectedLen  = h * (1 + w * 4)
        assert(filteredRows.length == expectedLen, s"inflated length ${filteredRows.length} != expected $expectedLen")
    }

    "Png.encode CRC32 of IHDR chunk is correct" in {
        val w         = 1
        val h         = 1
        val pixels    = syntheticRgba(w, h)
        val png       = Png.encode(pixels, w, h)
        val typeBytes = png.slice(12, 16)
        val data      = png.slice(16, 29)
        val storedCrc = readBe32(png, 29)
        val combined  = typeBytes ++ data
        val expected  = computeCrc32(combined)
        assert(storedCrc == expected, f"stored CRC 0x$storedCrc%08x != computed 0x$expected%08x")
    }

    // ---- Helpers (all pure) ----

    /** Builds a flat RGBA byte array from unsigned 0-255 ints. */
    private def rgba(values: Int*): Array[Byte] = values.iterator.map(_.toByte).toArray

    /** A synthetic RGBA byte array with distinct colors per pixel for use in encoder tests. */
    private def syntheticRgba(w: Int, h: Int): Array[Byte] =
        val buf = new Array[Byte](w * h * 4)
        var i   = 0
        while i < w * h do
            buf(i * 4) = (i * 37 & 0xff).toByte
            buf(i * 4 + 1) = (i * 71 & 0xff).toByte
            buf(i * 4 + 2) = (i * 113 & 0xff).toByte
            buf(i * 4 + 3) = 255.toByte
            i += 1
        end while
        buf
    end syntheticRgba

    private def readBe32(buf: Array[Byte], off: Int): Int =
        ((buf(off) & 0xff) << 24) | ((buf(off + 1) & 0xff) << 16) |
            ((buf(off + 2) & 0xff) << 8) | (buf(off + 3) & 0xff)

    private def readLe16(buf: Array[Byte], off: Int): Int =
        (buf(off) & 0xff) | ((buf(off + 1) & 0xff) << 8)

    private def extractIdatData(png: Array[Byte]): Array[Byte] =
        var off = 8
        while off < png.length - 12 do
            val len      = readBe32(png, off)
            val typeName = new String(png.slice(off + 4, off + 8), "ISO-8859-1")
            if typeName == "IDAT" then return png.slice(off + 8, off + 8 + len)
            off += 4 + 4 + len + 4
        end while
        Array.emptyByteArray
    end extractIdatData

    private def inflateStored(data: Array[Byte]): Array[Byte] =
        var pos  = 2 // skip CMF + FLG
        val out  = scala.collection.mutable.ArrayBuffer.empty[Byte]
        var done = false
        while !done do
            val flag    = data(pos) & 0xff
            val isFinal = (flag & 0x01) != 0
            pos += 1
            val len = readLe16(data, pos)
            pos += 2
            pos += 2 // skip NLEN
            out ++= data.slice(pos, pos + len)
            pos += len
            if isFinal then done = true
        end while
        out.toArray
    end inflateStored

    private def computeCrc32(data: Array[Byte]): Int =
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
        var crc = 0xffffffff
        var j   = 0
        while j < data.length do
            crc = table((crc ^ data(j)) & 0xff) ^ (crc >>> 8)
            j += 1
        end while
        crc ^ 0xffffffff
    end computeCrc32

end PngTest
