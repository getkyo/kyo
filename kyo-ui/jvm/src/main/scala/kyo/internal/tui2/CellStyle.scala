package kyo.internal.tui2

/** Packed cell style as an opaque Long.
  *
  * Bit layout (64 bits):
  *   - bits 0-4: attributes (bold=0, italic=1, underline=2, strikethrough=3, dim=4)
  *   - bit 5: reserved
  *   - bits 6-30: background color (25 bits: bit 24 = absent flag, bits 0-23 = RGB)
  *   - bits 31-55: foreground color (25 bits: bit 24 = absent flag, bits 0-23 = RGB)
  *   - bits 56-63: unused
  */
opaque type CellStyle = Long

object CellStyle:

    given CanEqual[CellStyle, CellStyle] = CanEqual.derived

    private val AbsentBits: Long = 0x1ffffffL // 25 bits all set = absent sentinel
    private val ColorMask: Long  = 0x1ffffffL // 25-bit mask
    private val BgShift          = 6
    private val FgShift          = 31

    /** Empty cell style: absent fg, absent bg, no attributes. */
    val Empty: CellStyle = (AbsentBits << FgShift) | (AbsentBits << BgShift)

    def apply(
        fg: Int,
        bg: Int,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean,
        dim: Boolean
    ): CellStyle =
        val fgBits: Long = if fg < 0 then AbsentBits else (fg.toLong & 0xffffffL)
        val bgBits: Long = if bg < 0 then AbsentBits else (bg.toLong & 0xffffffL)
        val attrs: Long =
            (if bold then 1L else 0L) |
                (if italic then 2L else 0L) |
                (if underline then 4L else 0L) |
                (if strikethrough then 8L else 0L) |
                (if dim then 16L else 0L)
        (fgBits << FgShift) | (bgBits << BgShift) | attrs
    end apply

    extension (s: CellStyle)

        def fg: Int =
            val raw = ((s >>> FgShift) & ColorMask).toInt
            if raw == AbsentBits.toInt then -1 else raw

        def bg: Int =
            val raw = ((s >>> BgShift) & ColorMask).toInt
            if raw == AbsentBits.toInt then -1 else raw

        def bold: Boolean          = (s & 1L) != 0
        def italic: Boolean        = (s & 2L) != 0
        def underline: Boolean     = (s & 4L) != 0
        def strikethrough: Boolean = (s & 8L) != 0
        def dim: Boolean           = (s & 16L) != 0

        def withFg(v: Int): CellStyle =
            val fgBits: Long = if v < 0 then AbsentBits else (v.toLong & 0xffffffL)
            (s & ~(ColorMask << FgShift)) | (fgBits << FgShift)

        def withBg(v: Int): CellStyle =
            val bgBits: Long = if v < 0 then AbsentBits else (v.toLong & 0xffffffL)
            (s & ~(ColorMask << BgShift)) | (bgBits << BgShift)

        /** Swap foreground and background colors (for selection highlight). */
        def swapColors: CellStyle =
            val fgVal = s.fg
            val bgVal = s.bg
            s.withFg(bgVal).withBg(fgVal)
        end swapColors

    end extension

end CellStyle
