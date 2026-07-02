package kyo

/** `object Three` mixes this trait in, so [[Color]] resolves as `Three.Color` to every consumer while
  * the opaque type + companion stay in this file (`ColorTest.scala` stays a valid 1:1-named test file).
  */
private[kyo] trait ThreeColorOps:

    /** A packed `0xRRGGBB` color, an opaque `Int` boundary shared by materials, lights, and the renderer
      * background.
      *
      * The opaque representation keeps a color a single machine word with no wrapper allocation, while the
      * companion factories make construction total: [[Color.hex]] returns [[Maybe]] (never throws on a
      * malformed string), and [[Color.rgb]] clamps each channel into `0..255`. Named constants cover the
      * common cases.
      */
    opaque type Color = Int

    object Color:

        /** Wraps a packed `0xRRGGBB` integer, masking to the low 24 bits. */
        def apply(packed: Int): Color = packed & 0xffffff

        /** Parses a `#rrggbb` or `rrggbb` hex string. Returns [[Absent]] on any malformed input; never throws. */
        def hex(s: String): Maybe[Color] =
            val body = if s.startsWith("#") then s.substring(1) else s
            if body.length != 6 then Absent
            else
                val parsed =
                    try Maybe(java.lang.Integer.parseInt(body, 16))
                    catch case _: NumberFormatException => Absent
                parsed.map(v => v & 0xffffff)
            end if
        end hex

        /** Builds a color from red, green, and blue channels, clamping each to `0..255`. */
        def rgb(r: Int, g: Int, b: Int): Color =
            (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b)

        /** Builds a color from HSL where `h` is `0..360` degrees and `s`, `l` are `0..1`. */
        def hsl(h: Double, s: Double, l: Double): Color =
            val hh = ((h % 360.0) + 360.0) % 360.0
            val ss = math.max(0.0, math.min(1.0, s))
            val ll = math.max(0.0, math.min(1.0, l))
            val c  = (1.0 - math.abs(2.0 * ll - 1.0)) * ss
            val x  = c * (1.0 - math.abs((hh / 60.0) % 2.0 - 1.0))
            val m  = ll - c / 2.0
            val (r1, g1, b1) =
                if hh < 60.0 then (c, x, 0.0)
                else if hh < 120.0 then (x, c, 0.0)
                else if hh < 180.0 then (0.0, c, x)
                else if hh < 240.0 then (0.0, x, c)
                else if hh < 300.0 then (x, 0.0, c)
                else (c, 0.0, x)
            rgb(
                math.round((r1 + m) * 255.0).toInt,
                math.round((g1 + m) * 255.0).toInt,
                math.round((b1 + m) * 255.0).toInt
            )
        end hsl

        private def clampByte(c: Int): Int =
            if c < 0 then 0 else if c > 255 then 255 else c

        val white: Color   = Color(0xffffff)
        val black: Color   = Color(0x000000)
        val red: Color     = Color(0xff0000)
        val green: Color   = Color(0x00ff00)
        val blue: Color    = Color(0x0000ff)
        val yellow: Color  = Color(0xffff00)
        val cyan: Color    = Color(0x00ffff)
        val magenta: Color = Color(0xff00ff)
        val gray: Color    = Color(0x808080)

        extension (c: Color)
            /** The packed `0xRRGGBB` integer. */
            def packed: Int = c

            /** The red channel `0..255`. */
            def r: Int = (c >> 16) & 0xff

            /** The green channel `0..255`. */
            def g: Int = (c >> 8) & 0xff

            /** The blue channel `0..255`. */
            def b: Int = c & 0xff
        end extension

        given CanEqual[Color, Color] = CanEqual.derived
    end Color
end ThreeColorOps
