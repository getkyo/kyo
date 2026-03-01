package kyo.internal

import kyo.Style.Color

/** Packed 24-bit RGB color utilities for the TUI backend.
  *
  * Colors are represented as plain Int: (r << 16) | (g << 8) | b. The sentinel value -1 means "absent" (terminal default). No boxing, no
  * Maybe.
  */
private[kyo] object TuiColor:

    /** Sentinel for absent color (terminal default). */
    inline val Absent = -1

    // ---- Pack / unpack ----

    inline def r(packed: Int): Int = (packed >>> 16) & 0xff
    inline def g(packed: Int): Int = (packed >>> 8) & 0xff
    inline def b(packed: Int): Int = packed & 0xff

    inline def pack(r: Int, g: Int, b: Int): Int =
        ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)

    // ---- Resolve from Style.Color ----

    def resolve(color: Color): Int = color match
        case Color.Rgb(r, g, b)     => pack(r, g, b)
        case Color.Rgba(r, g, b, _) => pack(r, g, b) // alpha handled via OpacityProp
        case Color.Hex(value)       => parseHex(value)

    private def parseHex(value: String): Int =
        if value == "transparent" then Absent
        else if value.length == 4 && value.charAt(0) == '#' then
            // "#rgb" → expand to "#rrggbb"
            val rc = hexDigit(value.charAt(1))
            val gc = hexDigit(value.charAt(2))
            val bc = hexDigit(value.charAt(3))
            pack(rc | (rc << 4), gc | (gc << 4), bc | (bc << 4))
        else if value.length == 7 && value.charAt(0) == '#' then
            val rv = (hexDigit(value.charAt(1)) << 4) | hexDigit(value.charAt(2))
            val gv = (hexDigit(value.charAt(3)) << 4) | hexDigit(value.charAt(4))
            val bv = (hexDigit(value.charAt(5)) << 4) | hexDigit(value.charAt(6))
            pack(rv, gv, bv)
        else
            Absent // unrecognized → absent
    end parseHex

    private inline def hexDigit(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else 0

    // ---- Blending ----

    /** Blend src over dst with the given alpha (0.0 = fully dst, 1.0 = fully src). */
    def blend(src: Int, dst: Int, alpha: Float): Int =
        if src == Absent then dst
        else if dst == Absent then src
        else if alpha >= 1.0f then src
        else if alpha <= 0.0f then dst
        else
            val inv = 1.0f - alpha
            val br  = (r(src) * alpha + r(dst) * inv + 0.5f).toInt
            val bg  = (g(src) * alpha + g(dst) * inv + 0.5f).toInt
            val bb  = (b(src) * alpha + b(dst) * inv + 0.5f).toInt
            pack(br, bg, bb)

    // ---- Quantization to 256-color palette ----

    /** ANSI 256-color cube levels: indices 0-5 map to these intensities. */
    private val cube6 = Array(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)

    /** Map an 8-bit channel value to the nearest 6-level cube index. */
    private inline def nearestCube(v: Int): Int =
        if v < 48 then 0
        else if v < 115 then 1
        else (v - 35) / 40

    /** Convert packed 24-bit RGB to the nearest 256-color palette index (16-255). */
    def to256(packed: Int): Int =
        val rv = r(packed); val gv = g(packed); val bv = b(packed)

        // Candidate 1: nearest in the 6×6×6 color cube (indices 16-231)
        val ci       = nearestCube(rv); val cj = nearestCube(gv); val ck = nearestCube(bv)
        val cubeR    = cube6(ci); val cubeG    = cube6(cj); val cubeB    = cube6(ck)
        val cubeIdx  = 16 + 36 * ci + 6 * cj + ck
        val cubeDist = sq(rv - cubeR) + sq(gv - cubeG) + sq(bv - cubeB)

        // Candidate 2: nearest in the 24-step grayscale ramp (indices 232-255)
        // Grayscale values: 8, 18, 28, ..., 238 (step 10, starting at 8)
        val gray     = ((rv + gv + bv) / 3 - 8 + 5) / 10 // nearest gray index
        val grayIdx  = math.max(0, math.min(23, gray))
        val grayVal  = 8 + 10 * grayIdx
        val grayDist = sq(rv - grayVal) + sq(gv - grayVal) + sq(bv - grayVal)

        if cubeDist <= grayDist then cubeIdx else 232 + grayIdx
    end to256

    // ---- Quantization to 16-color ANSI palette ----

    /** Standard ANSI 16-color palette (0-15) approximate RGB values. */
    private val ansi16 = Array(
        pack(0, 0, 0),       // 0  black
        pack(128, 0, 0),     // 1  red
        pack(0, 128, 0),     // 2  green
        pack(128, 128, 0),   // 3  yellow
        pack(0, 0, 128),     // 4  blue
        pack(128, 0, 128),   // 5  magenta
        pack(0, 128, 128),   // 6  cyan
        pack(192, 192, 192), // 7  white
        pack(128, 128, 128), // 8  bright black (gray)
        pack(255, 0, 0),     // 9  bright red
        pack(0, 255, 0),     // 10 bright green
        pack(255, 255, 0),   // 11 bright yellow
        pack(0, 0, 255),     // 12 bright blue
        pack(255, 0, 255),   // 13 bright magenta
        pack(0, 255, 255),   // 14 bright cyan
        pack(255, 255, 255)  // 15 bright white
    )

    /** Convert packed 24-bit RGB to the nearest ANSI color index (0-15). */
    def to16(packed: Int): Int =
        val rv = r(packed); val gv = g(packed); val bv = b(packed)
        @scala.annotation.tailrec
        def loop(i: Int, best: Int, bestDist: Int): Int =
            if i >= 16 then best
            else
                val ar   = r(ansi16(i)); val ag = g(ansi16(i)); val ab = b(ansi16(i))
                val dist = sq(rv - ar) + sq(gv - ag) + sq(bv - ab)
                if dist < bestDist then loop(i + 1, i, dist)
                else loop(i + 1, best, bestDist)
        loop(0, 0, Int.MaxValue)
    end to16

    private inline def sq(x: Int): Int = x * x

end TuiColor
