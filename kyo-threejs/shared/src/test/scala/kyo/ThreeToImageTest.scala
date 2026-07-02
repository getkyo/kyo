package kyo

import kyo.internal.Image
import kyo.internal.Png
import kyo.internal.Reconciler
import scala.scalajs.js.typedarray.Uint8Array

/** Tests for [[ThreeToImage]] and the pure [[kyo.internal.Png]] encoder.
  *
  * Compile-level fixtures verify the effect row shape (no Browser in the row). Encoder fixtures feed
  * known synthetic RGBA data through Png.encode and assert structural PNG validity plus a byte-exact
  * round-trip after decompressing the stored-block zlib stream. Both fixture groups run in the Node
  * environment on both JS and Wasm backends.
  */
class ThreeToImageTest extends ThreeTest:

    // ---- shared scene + camera values used by compile-check fixtures ----

    private val scene  = Three.scene()
    private val camera = Three.Camera.perspective()

    // ---- compile-level effect-row fixtures: these never execute at runtime ----

    "effect row compiles as Image < (Async & Scope & Abort[ThreeException])" in {
        val _: Image < (Async & Scope & Abort[ThreeException]) = Three.toImage(scene, camera)
        succeed
    }

    "row carries no Browser effect" in {
        // The type ascription below must type-check with no Browser in scope.
        // If the implementation introduced a Browser effect, the ascription would fail to compile.
        val _: Image < (Async & Scope & Abort[ThreeException]) = Three.toImage(scene, camera)
        succeed
    }

    // ---- prop-level signal current-value fill (the headless capture analog of subscribeRegions) ----

    "fillBoundRefsOnce applies a signal material color's current value to the live material, not the seed" in {
        // A mesh whose material color is set via .color(signal) with the signal currently emitting RED.
        // The materialize seed for a signal-bound color is white; the toImage capture path must apply
        // the signal's current value so the captured frame shows RED. Asserts on the live three.js
        // material color directly (no renderer needed). This is the exact path toImage runs after
        // materialize + structural fill.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    colorRef <- Signal.initRef(Three.Color.red)
                    mesh = Three.mesh(
                        Three.Geometry.box(),
                        Three.Material.standard().color(colorRef)
                    )
                    scene = Three.scene(mesh)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    seededHex <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh))
                            .map(_.obj.material.color.getHex().asInstanceOf[Int])
                            .getOrElse(-1)
                    }
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    _ <- ThreeMount.fillBoundRefsOnce(mounted)
                    filledHex <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh))
                            .map(_.obj.material.color.getHex().asInstanceOf[Int])
                            .getOrElse(-1)
                    }
                yield
                    assert(
                        seededHex == Three.Color.white.packed,
                        s"materialize seed must be white (0xffffff), got 0x${seededHex.toHexString}"
                    )
                    assert(
                        filledHex == Three.Color.red.packed,
                        s"fillBoundRefsOnce must apply the signal's current value (RED 0x${Three.Color.red.packed.toHexString}), " +
                            s"got 0x${filledHex.toHexString}"
                    )
                end for
            }
        }
    }

    "fillBoundRefsOnce applies a signal mesh position's current value to the live object, not the default" in {
        // A mesh whose transform position is set via .position(signal); the materialize path skips
        // signal-bound transforms, so the live object stays at the three.js default origin. The
        // capture fill must move it to the signal's current position.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Three.Vec3(3, 4, 5))
                    mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
                        .position(posRef)
                    scene = Three.scene(mesh)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    seeded <- liveXyz(mounted, mesh)
                    _      <- Reconciler.fillReactiveRegionsOnce(mounted)
                    _      <- ThreeMount.fillBoundRefsOnce(mounted)
                    filled <- liveXyz(mounted, mesh)
                yield
                    assert(
                        seeded == (0.0, 0.0, 0.0),
                        s"materialize must leave a signal-bound position at the origin (default), got $seeded"
                    )
                    assert(filled == (3.0, 4.0, 5.0), s"fillBoundRefsOnce must apply the current position (3,4,5), got $filled")
                end for
            }
        }
    }

    // ---- Png encoder fixtures: real round-trip on known synthetic RGBA ----

    "Png.encode produces a valid 8-byte PNG signature" in {
        import AllowUnsafe.embrace.danger
        val w      = 2
        val h      = 2
        val pixels = syntheticRgba(w, h)
        val png    = Png.encode(pixels, w, h)
        assert(png.length >= 8, "PNG must be at least 8 bytes")
        val sig = Array(0x89.toByte, 0x50.toByte, 0x4e.toByte, 0x47.toByte, 0x0d.toByte, 0x0a.toByte, 0x1a.toByte, 0x0a.toByte)
        assert(sig.sameElements(png.slice(0, 8)), "first 8 bytes must be the PNG magic number")
    }

    "Png.encode produces IHDR with correct width, height, and color type 6" in {
        import AllowUnsafe.embrace.danger
        val w      = 4
        val h      = 3
        val pixels = syntheticRgba(w, h)
        val png    = Png.encode(pixels, w, h)
        // IHDR starts at byte 8: 4-byte len + 4-byte type + 13-byte data + 4-byte crc
        // Length field at offset 8 must be 13
        val ihdrLen = readBe32(png, 8)
        assert(ihdrLen == 13, s"IHDR length must be 13, got $ihdrLen")
        // Type bytes at 12..15 must be 'I','H','D','R'
        val typeName = new String(png.slice(12, 16), "ISO-8859-1")
        assert(typeName == "IHDR", s"chunk type must be IHDR, got $typeName")
        // IHDR data at 16..28
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
        import AllowUnsafe.embrace.danger
        val w = 2
        val h = 2
        // Input: row 0 (bottom in OpenGL) = red pixel + green pixel;
        //        row 1 (top in OpenGL)    = blue pixel + white pixel.
        // After row-flip in PNG: row 0 (top in PNG) = blue+white, row 1 = red+green.
        val pixels = new Uint8Array(w * h * 4)
        // Row 0 (bottom, OpenGL): pixel (0,0)=red, (1,0)=green
        pixels(0) = 255; pixels(1) = 0; pixels(2) = 0; pixels(3) = 255
        pixels(4) = 0; pixels(5) = 255; pixels(6) = 0; pixels(7) = 255
        // Row 1 (top, OpenGL): pixel (0,1)=blue, (1,1)=white
        pixels(8) = 0; pixels(9) = 0; pixels(10) = 255; pixels(11) = 255
        pixels(12) = 255; pixels(13) = 255; pixels(14) = 255; pixels(15) = 255

        val png          = Png.encode(pixels, w, h)
        val idatData     = extractIdatData(png)
        val filteredRows = inflateStored(idatData)

        // PNG row 0 (top in PNG) must be the original row 1 (top in OpenGL): blue + white.
        // Each row: 1 filter byte + w*4 RGBA bytes.
        val rowStride = 1 + w * 4
        assert(filteredRows(0) == 0.toByte, "filter byte must be 0 (None)")
        // blue pixel
        assert((filteredRows(1) & 0xff) == 0, s"R of blue pixel = ${filteredRows(1) & 0xff}")
        assert((filteredRows(2) & 0xff) == 0, s"G of blue pixel = ${filteredRows(2) & 0xff}")
        assert((filteredRows(3) & 0xff) == 255, s"B of blue pixel = ${filteredRows(3) & 0xff}")
        assert((filteredRows(4) & 0xff) == 255, s"A of blue pixel = ${filteredRows(4) & 0xff}")
        // white pixel
        assert((filteredRows(5) & 0xff) == 255, s"R of white pixel = ${filteredRows(5) & 0xff}")
        assert((filteredRows(6) & 0xff) == 255, s"G of white pixel = ${filteredRows(6) & 0xff}")
        assert((filteredRows(7) & 0xff) == 255, s"B of white pixel = ${filteredRows(7) & 0xff}")
        assert((filteredRows(8) & 0xff) == 255, s"A of white pixel = ${filteredRows(8) & 0xff}")

        // PNG row 1 (bottom in PNG) must be the original row 0 (bottom in OpenGL): red + green.
        assert(filteredRows(rowStride) == 0.toByte, "filter byte row 1 must be 0")
        // red pixel
        assert((filteredRows(rowStride + 1) & 0xff) == 255, s"R of red = ${filteredRows(rowStride + 1) & 0xff}")
        assert((filteredRows(rowStride + 2) & 0xff) == 0, s"G of red = ${filteredRows(rowStride + 2) & 0xff}")
        assert((filteredRows(rowStride + 3) & 0xff) == 0, s"B of red = ${filteredRows(rowStride + 3) & 0xff}")
        // green pixel
        assert((filteredRows(rowStride + 5) & 0xff) == 0, s"R of green = ${filteredRows(rowStride + 5) & 0xff}")
        assert((filteredRows(rowStride + 6) & 0xff) == 255, s"G of green = ${filteredRows(rowStride + 6) & 0xff}")
        assert((filteredRows(rowStride + 7) & 0xff) == 0, s"B of green = ${filteredRows(rowStride + 7) & 0xff}")
    }

    "Png.encode round-trips a non-square 3x2 image with row-flip via stored-block inflate" in {
        import AllowUnsafe.embrace.danger
        val w = 3
        val h = 2
        // Distinct colors per pixel so a width/height transposition would produce wrong values.
        // Layout (OpenGL origin, row 0 = bottom):
        //   row 0 (bottom): red(255,0,0), green(0,255,0), blue(0,0,255)
        //   row 1 (top):    white(255,255,255), yellow(255,255,0), cyan(0,255,255)
        val pixels = new Uint8Array(w * h * 4)
        // Row 0 pixel 0: red
        pixels(0) = 255; pixels(1) = 0; pixels(2) = 0; pixels(3) = 255
        // Row 0 pixel 1: green
        pixels(4) = 0; pixels(5) = 255; pixels(6) = 0; pixels(7) = 255
        // Row 0 pixel 2: blue
        pixels(8) = 0; pixels(9) = 0; pixels(10) = 255; pixels(11) = 255
        // Row 1 pixel 0: white
        pixels(12) = 255; pixels(13) = 255; pixels(14) = 255; pixels(15) = 255
        // Row 1 pixel 1: yellow
        pixels(16) = 255; pixels(17) = 255; pixels(18) = 0; pixels(19) = 255
        // Row 1 pixel 2: cyan
        pixels(20) = 0; pixels(21) = 255; pixels(22) = 255; pixels(23) = 255

        val png          = Png.encode(pixels, w, h)
        val idatData     = extractIdatData(png)
        val filteredRows = inflateStored(idatData)

        // Each row in the PNG: 1 filter byte + w*4 RGBA bytes.
        val rowStride = 1 + w * 4
        assert(filteredRows.length == h * rowStride, s"inflated length ${filteredRows.length} != ${h * rowStride}")

        // PNG row 0 (top in PNG) must be original row 1 (top in OpenGL): white, yellow, cyan.
        assert(filteredRows(0) == 0.toByte, "filter byte row 0 must be 0 (None)")
        // white pixel at PNG row 0, pixel 0
        assert((filteredRows(1) & 0xff) == 255, s"white R = ${filteredRows(1) & 0xff}")
        assert((filteredRows(2) & 0xff) == 255, s"white G = ${filteredRows(2) & 0xff}")
        assert((filteredRows(3) & 0xff) == 255, s"white B = ${filteredRows(3) & 0xff}")
        assert((filteredRows(4) & 0xff) == 255, s"white A = ${filteredRows(4) & 0xff}")
        // yellow pixel at PNG row 0, pixel 1
        assert((filteredRows(5) & 0xff) == 255, s"yellow R = ${filteredRows(5) & 0xff}")
        assert((filteredRows(6) & 0xff) == 255, s"yellow G = ${filteredRows(6) & 0xff}")
        assert((filteredRows(7) & 0xff) == 0, s"yellow B = ${filteredRows(7) & 0xff}")
        assert((filteredRows(8) & 0xff) == 255, s"yellow A = ${filteredRows(8) & 0xff}")
        // cyan pixel at PNG row 0, pixel 2
        assert((filteredRows(9) & 0xff) == 0, s"cyan R = ${filteredRows(9) & 0xff}")
        assert((filteredRows(10) & 0xff) == 255, s"cyan G = ${filteredRows(10) & 0xff}")
        assert((filteredRows(11) & 0xff) == 255, s"cyan B = ${filteredRows(11) & 0xff}")
        assert((filteredRows(12) & 0xff) == 255, s"cyan A = ${filteredRows(12) & 0xff}")

        // PNG row 1 (bottom in PNG) must be original row 0 (bottom in OpenGL): red, green, blue.
        assert(filteredRows(rowStride) == 0.toByte, "filter byte row 1 must be 0 (None)")
        // red pixel at PNG row 1, pixel 0
        assert((filteredRows(rowStride + 1) & 0xff) == 255, s"red R = ${filteredRows(rowStride + 1) & 0xff}")
        assert((filteredRows(rowStride + 2) & 0xff) == 0, s"red G = ${filteredRows(rowStride + 2) & 0xff}")
        assert((filteredRows(rowStride + 3) & 0xff) == 0, s"red B = ${filteredRows(rowStride + 3) & 0xff}")
        assert((filteredRows(rowStride + 4) & 0xff) == 255, s"red A = ${filteredRows(rowStride + 4) & 0xff}")
        // green pixel at PNG row 1, pixel 1
        assert((filteredRows(rowStride + 5) & 0xff) == 0, s"green R = ${filteredRows(rowStride + 5) & 0xff}")
        assert((filteredRows(rowStride + 6) & 0xff) == 255, s"green G = ${filteredRows(rowStride + 6) & 0xff}")
        assert((filteredRows(rowStride + 7) & 0xff) == 0, s"green B = ${filteredRows(rowStride + 7) & 0xff}")
        assert((filteredRows(rowStride + 8) & 0xff) == 255, s"green A = ${filteredRows(rowStride + 8) & 0xff}")
        // blue pixel at PNG row 1, pixel 2
        assert((filteredRows(rowStride + 9) & 0xff) == 0, s"blue R = ${filteredRows(rowStride + 9) & 0xff}")
        assert((filteredRows(rowStride + 10) & 0xff) == 0, s"blue G = ${filteredRows(rowStride + 10) & 0xff}")
        assert((filteredRows(rowStride + 11) & 0xff) == 255, s"blue B = ${filteredRows(rowStride + 11) & 0xff}")
        assert((filteredRows(rowStride + 12) & 0xff) == 255, s"blue A = ${filteredRows(rowStride + 12) & 0xff}")
    }

    "Png.encode produces multiple stored blocks for large images" in {
        import AllowUnsafe.embrace.danger
        // 256x256 RGBA = 262144 bytes of raw pixel data.
        // Filtered rows = 256 * (1 + 256*4) = 256 * 1025 = 262400 bytes.
        // 262400 / 65535 = 4.004, so at least 5 stored blocks.
        val w        = 256
        val h        = 256
        val pixels   = syntheticRgba(w, h)
        val png      = Png.encode(pixels, w, h)
        val idatData = extractIdatData(png)
        // 2-byte zlib header + first block's 1-byte flag: flag == 0x00 means non-final.
        // If there's only one block, the flag would be 0x01.
        assert(idatData.length > 2, "IDAT data must have content beyond the zlib header")
        val firstBlockFlag = idatData(2) & 0xff
        assert(firstBlockFlag == 0x00, s"first block must be non-final (0x00), got 0x${firstBlockFlag.toHexString}")
        // Inflate must also succeed and produce the correct byte count.
        val filteredRows = inflateStored(idatData)
        val expectedLen  = h * (1 + w * 4)
        assert(filteredRows.length == expectedLen, s"inflated length $filteredRows.length != expected $expectedLen")
    }

    "Png.encode CRC32 of IHDR chunk is correct" in {
        import AllowUnsafe.embrace.danger
        val w      = 1
        val h      = 1
        val pixels = syntheticRgba(w, h)
        val png    = Png.encode(pixels, w, h)
        // IHDR: offset 8..28+4 = 8..32
        // type at 12..15, data at 16..28, stored crc at 29..32
        val typeBytes = png.slice(12, 16)
        val data      = png.slice(16, 29)
        val storedCrc = readBe32(png, 29)
        val combined  = typeBytes ++ data
        val expected  = computeCrc32(combined)
        assert(storedCrc == expected, f"stored CRC 0x$storedCrc%08x != computed 0x$expected%08x")
    }

    // ---- Helpers ----

    /** Reads the live three.js object's `(position.x, position.y, position.z)` for the AST node. */
    private def liveXyz(mounted: Reconciler.Mounted, node: Three)(using Frame): (Double, Double, Double) < Sync =
        Sync.Unsafe.defer {
            mounted.live.get(new Reconciler.IdentityKey(node))
                .map { live =>
                    val p = live.obj.position
                    (p.x.asInstanceOf[Double], p.y.asInstanceOf[Double], p.z.asInstanceOf[Double])
                }
                .getOrElse((Double.NaN, Double.NaN, Double.NaN))
        }

    /** Builds a synthetic RGBA Uint8Array with distinct colors per pixel for use in encoder tests. */
    private def syntheticRgba(w: Int, h: Int): Uint8Array =
        val buf = new Uint8Array(w * h * 4)
        var i   = 0
        while i < w * h do
            buf(i * 4) = (i * 37 & 0xff).toShort
            buf(i * 4 + 1) = (i * 71 & 0xff).toShort
            buf(i * 4 + 2) = (i * 113 & 0xff).toShort
            buf(i * 4 + 3) = 255.toShort
            i += 1
        end while
        buf
    end syntheticRgba

    /** Reads a big-endian 32-bit int from `buf` at `off`. */
    private def readBe32(buf: Array[Byte], off: Int): Int =
        ((buf(off) & 0xff) << 24) | ((buf(off + 1) & 0xff) << 16) |
            ((buf(off + 2) & 0xff) << 8) | (buf(off + 3) & 0xff)

    /** Reads a little-endian 16-bit int from `buf` at `off`. */
    private def readLe16(buf: Array[Byte], off: Int): Int =
        (buf(off) & 0xff) | ((buf(off + 1) & 0xff) << 8)

    /** Extracts the raw IDAT chunk data bytes (the zlib stream) from a PNG byte array.
      * Finds the IDAT chunk (first occurrence) and returns its data field.
      */
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

    /** Decompresses a zlib stored-block stream (RFC 1950 with RFC 1951 BTYPE=00 blocks) and returns
      * the uncompressed data. Works because stored blocks contain the literal bytes with no compression.
      * Format: 2-byte zlib header + (1-byte BFINAL/BTYPE + 2-byte LEN + 2-byte NLEN + LEN bytes)* + 4-byte Adler-32.
      */
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

    /** Computes CRC32 over the bytes (polynomial 0xedb88320), used to validate the PNG chunk CRC field. */
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

end ThreeToImageTest
