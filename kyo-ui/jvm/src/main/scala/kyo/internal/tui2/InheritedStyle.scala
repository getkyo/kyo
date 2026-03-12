package kyo.internal.tui2

/** Bundles all CSS-inherited style properties into a single object.
  *
  * Pre-allocated on RenderCtx with a save/restore stack for zero-allocation tree traversal. Replaces the 8 loose mutable fields that were
  * previously scattered across RenderCtx.
  */
final private[kyo] class InheritedStyle:
    var fg: Int                                              = ColorUtils.Absent
    var bg: Int                                              = ColorUtils.Absent
    var bold, italic, underline, strikethrough, dim: Boolean = false
    var textAlign: Int                                       = 0
    var textTransform: Int                                   = 0
    var textWrap: Boolean                                    = true  // CSS: white-space: normal = wrap
    var textOverflow: Boolean                                = false // Not CSS-inherited, but forwarded for TUI drawText

    def cellStyle: CellStyle =
        CellStyle(fg, bg, bold, italic, underline, strikethrough, dim)

    /** Copy all fields from another InheritedStyle. Zero-allocation. */
    def copyFrom(other: InheritedStyle): Unit =
        fg = other.fg; bg = other.bg
        bold = other.bold; italic = other.italic; underline = other.underline
        strikethrough = other.strikethrough; dim = other.dim
        textAlign = other.textAlign; textTransform = other.textTransform
        textWrap = other.textWrap; textOverflow = other.textOverflow
    end copyFrom

    /** Apply resolved style fields onto this inherited style. */
    def applyFrom(rs: ResolvedStyle): Unit =
        fg = rs.fg; bg = rs.bg
        bold = rs.bold; italic = rs.italic; underline = rs.underline
        strikethrough = rs.strikethrough; dim = rs.dim
        textAlign = rs.textAlign; textTransform = rs.textTransform
        textWrap = rs.textWrap; textOverflow = rs.textOverflow
    end applyFrom

    def reset(): Unit =
        fg = ColorUtils.Absent; bg = ColorUtils.Absent
        bold = false; italic = false; underline = false
        strikethrough = false; dim = false
        textAlign = 0; textTransform = 0
        textWrap = true; textOverflow = false
    end reset

end InheritedStyle
