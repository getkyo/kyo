package kyo.internal.tui2

import kyo.Style.Color

/** Packed 24-bit RGB color utilities.
  *
  * Colors are plain Int: (r << 16) | (g << 8) | b. Sentinel -1 means "absent" (terminal default).
  */
private[kyo] object ColorUtils:

    inline val Absent = -1

    inline def r(packed: Int): Int = (packed >>> 16) & 0xff
    inline def g(packed: Int): Int = (packed >>> 8) & 0xff
    inline def b(packed: Int): Int = packed & 0xff

    inline def pack(r: Int, g: Int, b: Int): Int =
        ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)

    def resolve(color: Color): Int = color match
        case Color.Rgb(r, g, b)     => pack(r, g, b)
        case Color.Rgba(r, g, b, _) => pack(r, g, b)
        case Color.Transparent      => Absent
        case Color.Hex(value)       => parseHex(value)

    private def parseHex(value: String): Int =
        if value.length == 4 && value.charAt(0) == '#' then
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
            Absent

    private inline def hexDigit(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else 0

    def blend(src: Int, dst: Int, alpha: Double): Int =
        if src == Absent then dst
        else if dst == Absent then src
        else if alpha >= 1.0 then src
        else if alpha <= 0.0 then dst
        else
            val inv = 1.0 - alpha
            pack(clampD(r(src) * alpha + r(dst) * inv), clampD(g(src) * alpha + g(dst) * inv), clampD(b(src) * alpha + b(dst) * inv))

    // ---- Quantization ----

    private val cube6 = Array(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)

    private inline def nearestCube(v: Int): Int =
        if v < 48 then 0
        else if v < 115 then 1
        else (v - 35) / 40

    def to256(packed: Int): Int =
        val rv       = r(packed); val gv       = g(packed); val bv       = b(packed)
        val ci       = nearestCube(rv); val cj = nearestCube(gv); val ck = nearestCube(bv)
        val cubeR    = cube6(ci); val cubeG    = cube6(cj); val cubeB    = cube6(ck)
        val cubeIdx  = 16 + 36 * ci + 6 * cj + ck
        val cubeDist = sq(rv - cubeR) + sq(gv - cubeG) + sq(bv - cubeB)
        val gray     = ((rv + gv + bv) / 3 - 8 + 5) / 10
        val grayIdx  = math.max(0, math.min(23, gray))
        val grayVal  = 8 + 10 * grayIdx
        val grayDist = sq(rv - grayVal) + sq(gv - grayVal) + sq(bv - grayVal)
        if cubeDist <= grayDist then cubeIdx else 232 + grayIdx
    end to256

    private val ansi16 = Array(
        pack(0, 0, 0),
        pack(128, 0, 0),
        pack(0, 128, 0),
        pack(128, 128, 0),
        pack(0, 0, 128),
        pack(128, 0, 128),
        pack(0, 128, 128),
        pack(192, 192, 192),
        pack(128, 128, 128),
        pack(255, 0, 0),
        pack(0, 255, 0),
        pack(255, 255, 0),
        pack(0, 0, 255),
        pack(255, 0, 255),
        pack(0, 255, 255),
        pack(255, 255, 255)
    )

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

    private inline def sq(x: Int): Int        = x * x
    private inline def clampD(v: Double): Int = math.max(0, math.min(255, (v + 0.5).toInt))

    // ---- Filters ----

    def brightness(packed: Int, factor: Double): Int =
        if packed == Absent then packed
        else pack(clampD(r(packed) * factor), clampD(g(packed) * factor), clampD(b(packed) * factor))

    def contrast(packed: Int, factor: Double): Int =
        if packed == Absent then packed
        else
            val mid = 128.0
            pack(
                clampD((r(packed) - mid) * factor + mid),
                clampD((g(packed) - mid) * factor + mid),
                clampD((b(packed) - mid) * factor + mid)
            )

    def grayscale(packed: Int, amount: Double): Int =
        if packed == Absent then packed
        else
            val rv  = r(packed); val gv = g(packed); val bv = b(packed)
            val lum = 0.2126 * rv + 0.7152 * gv + 0.0722 * bv
            val inv = 1.0 - amount
            pack(clampD(rv * inv + lum * amount), clampD(gv * inv + lum * amount), clampD(bv * inv + lum * amount))

    def sepia(packed: Int, amount: Double): Int =
        if packed == Absent then packed
        else
            val rv  = r(packed).toDouble; val gv = g(packed).toDouble; val bv = b(packed).toDouble
            val sr  = rv * 0.393 + gv * 0.769 + bv * 0.189
            val sg  = rv * 0.349 + gv * 0.686 + bv * 0.168
            val sb  = rv * 0.272 + gv * 0.534 + bv * 0.131
            val inv = 1.0 - amount
            pack(clampD(rv * inv + sr * amount), clampD(gv * inv + sg * amount), clampD(bv * inv + sb * amount))

    def invert(packed: Int, amount: Double): Int =
        if packed == Absent then packed
        else
            val rv  = r(packed); val gv = g(packed); val bv = b(packed)
            val inv = 1.0 - amount
            pack(clampD(rv * inv + (255 - rv) * amount), clampD(gv * inv + (255 - gv) * amount), clampD(bv * inv + (255 - bv) * amount))

    def saturate(packed: Int, factor: Double): Int =
        if packed == Absent then packed
        else
            val rv = r(packed).toDouble / 255.0; val gv = g(packed).toDouble / 255.0; val bv = b(packed).toDouble / 255.0
            val mx = math.max(rv, math.max(gv, bv))
            val mn = math.min(rv, math.min(gv, bv))
            val l  = (mx + mn) / 2.0
            if mx == mn then packed
            else
                val d  = mx - mn
                val s  = if l > 0.5 then d / (2.0 - mx - mn) else d / (mx + mn)
                val h  = hue(rv, gv, bv, mx, d)
                val ns = math.max(0.0, math.min(1.0, s * factor))
                hslToRgb(h, ns, l)
            end if

    def hueRotate(packed: Int, degrees: Double): Int =
        if packed == Absent then packed
        else
            val rv = r(packed).toDouble / 255.0; val gv = g(packed).toDouble / 255.0; val bv = b(packed).toDouble / 255.0
            val mx = math.max(rv, math.max(gv, bv))
            val mn = math.min(rv, math.min(gv, bv))
            val l  = (mx + mn) / 2.0
            if mx == mn then packed
            else
                val d  = mx - mn
                val s  = if l > 0.5 then d / (2.0 - mx - mn) else d / (mx + mn)
                val h  = hue(rv, gv, bv, mx, d)
                val nh = ((h + degrees / 360.0) % 1.0 + 1.0) % 1.0
                hslToRgb(nh, s, l)
            end if

    private def hue(r: Double, g: Double, b: Double, mx: Double, d: Double): Double =
        val raw =
            if mx == r then (g - b) / d + (if g < b then 6.0 else 0.0)
            else if mx == g then (b - r) / d + 2.0
            else (r - g) / d + 4.0
        raw / 6.0
    end hue

    private def hslToRgb(h: Double, s: Double, l: Double): Int =
        if s == 0.0 then
            val v = clampD(l * 255.0)
            pack(v, v, v)
        else
            val q = if l < 0.5 then l * (1.0 + s) else l + s - l * s
            val p = 2.0 * l - q
            pack(
                clampD(hue2rgb(p, q, h + 1.0 / 3.0) * 255.0),
                clampD(hue2rgb(p, q, h) * 255.0),
                clampD(hue2rgb(p, q, h - 1.0 / 3.0) * 255.0)
            )

    private def hue2rgb(p: Double, q: Double, t0: Double): Double =
        val t = if t0 < 0.0 then t0 + 1.0 else if t0 > 1.0 then t0 - 1.0 else t0
        if t < 1.0 / 6.0 then p + (q - p) * 6.0 * t
        else if t < 1.0 / 2.0 then q
        else if t < 2.0 / 3.0 then p + (q - p) * (2.0 / 3.0 - t) * 6.0
        else p
        end if
    end hue2rgb

end ColorUtils
