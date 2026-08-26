package kyo.internal

import kyo.*

/** WCAG contrast arithmetic, and the palette reconciliation that keeps a series visible on its panel.
  *
  * A categorical palette is specified against an assumed background. Okabe-Ito, the color-vision-accessible
  * set, begins with pure black because it is specified against white; Viridis begins with near-black purple
  * for the same reason. Offered as a peer of a dark theme with nothing reconciling the two, the documented
  * accessible choice renders its FIRST series, the one most charts put their headline metric in, at 1.4:1
  * against a `#1f2937` panel. WCAG 2.1 SC 1.4.11 asks for 3:1 on a non-text graphical object.
  *
  * So a palette color is not used as given: it is lightened (on a dark panel) or darkened (on a light one)
  * until it clears the threshold, which leaves every already-readable entry untouched and rescues only the
  * ones that would have been invisible.
  */
private[kyo] object ChartContrast:

    /** The WCAG 2.1 SC 1.4.11 minimum for a non-text graphical object. */
    private[kyo] val MinRatio: Double = 3.0

    /** The sRGB components of `c`, or `Absent` for a color whose channels are not knowable here (a CSS
      * variable, `transparent`, or a hex form with no opaque RGB reading).
      */
    private[kyo] def rgbOf(c: Style.Color): Maybe[(Int, Int, Int)] =
        c match
            case Style.Color.Rgb(r, g, b)     => Present((r, g, b))
            case Style.Color.Rgba(r, g, b, _) => Present((r, g, b))
            case Style.Color.Hex(v)           => hexRgb(v)
            case Style.Color.Transparent      => Absent
            case Style.Color.Var(_)           => Absent

    private def hexRgb(value: String): Maybe[(Int, Int, Int)] =
        val v                 = if value.startsWith("#") then value.substring(1) else value
        def pair(i: Int): Int = Integer.parseInt(v.substring(i, i + 2), 16)
        def single(i: Int): Int =
            val d = Integer.parseInt(v.substring(i, i + 1), 16); d * 16 + d
        try
            v.length match
                case 3 | 4 => Present((single(0), single(1), single(2)))
                case 6 | 8 => Present((pair(0), pair(2), pair(4)))
                case _     => Absent
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent
        end try
    end hexRgb

    /** The WCAG relative luminance of one sRGB channel value. */
    private def channel(v: Int): Double =
        val s = v / 255.0
        if s <= 0.03928 then s / 12.92 else math.pow((s + 0.055) / 1.055, 2.4)

    /** The WCAG relative luminance of a color, or `Absent` when its channels are unknown. */
    private[kyo] def relativeLuminance(c: Style.Color): Maybe[Double] =
        rgbOf(c).map { case (r, g, b) =>
            0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

    /** The WCAG contrast ratio between two colors, or `Absent` when either one's channels are unknown. */
    private[kyo] def contrastRatio(a: Style.Color, b: Style.Color): Maybe[Double] =
        for
            la <- relativeLuminance(a)
            lb <- relativeLuminance(b)
        yield
            val hi = math.max(la, lb)
            val lo = math.min(la, lb)
            (hi + 0.05) / (lo + 0.05)

    /** `color`, blended toward white or black until it clears `minRatio` against `background`.
      *
      * The blend direction is away from the background's own luminance, so a dark panel lightens and a light
      * one darkens. Blending toward a neutral keeps the hue: the orange stays orange, and only its lightness
      * moves. A color that already clears the threshold is returned unchanged, as is one whose channels are
      * not knowable (a CSS variable) or one no blend can rescue.
      */
    private[kyo] def reconcile(color: Style.Color, background: Style.Color, minRatio: Double = MinRatio): Style.Color =
        val current = contrastRatio(color, background)
        (current, rgbOf(color), relativeLuminance(background)) match
            case (Present(ratio), _, _) if ratio >= minRatio => color
            case (Present(_), Present((r, g, b)), Present(bgLum)) =>
                val toward                        = if bgLum < 0.5 then 255 else 0
                def blend(v: Int, t: Double): Int = math.round(v + (toward - v) * t).toInt
                @annotation.tailrec
                def loop(step: Int): Style.Color =
                    if step > 20 then Style.Color.rgb(toward, toward, toward)
                    else
                        val t         = step / 20.0
                        val candidate = Style.Color.rgb(blend(r, t), blend(g, t), blend(b, t))
                        contrastRatio(candidate, background) match
                            case Present(ratio) if ratio >= minRatio => candidate
                            case _                                   => loop(step + 1)
                loop(1)
            case _ => color
        end match
    end reconcile

    /** Every color in `palette`, reconciled against `background`. */
    private[kyo] def reconcilePalette(palette: Chunk[Style.Color], background: Style.Color): Chunk[Style.Color] =
        palette.map(reconcile(_, background))

end ChartContrast
