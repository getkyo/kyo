package kyo

import kyo.Chart.*
import kyo.internal.ChartAxes
import kyo.internal.ChartContrast
import scala.language.implicitConversions

/** Tests for the palette-versus-theme reconciliation.
  *
  * `Chart.Palette.Okabe` is documented as the color-vision-accessible choice, so it is what an
  * accessibility-minded caller picks, and `.dark` is what they pair it with. Okabe-Ito's first entry is pure
  * black by definition (the palette is specified against white), and the dark theme's panel is `#1f2937`, so
  * series 1, the one most charts give their headline metric, rendered at 1.43:1 against its own background.
  * WCAG 2.1 SC 1.4.11 requires 3:1 for a non-text graphical object.
  */
class ChartContrastTest extends kyo.test.Test[Any]:

    private val DarkPanel  = Style.Color.hex("#1f2937").getOrElse(Style.Color.black)
    private val LightPanel = Style.Color.white

    private def ratio(a: Style.Color, b: Style.Color)(using Frame, kyo.test.AssertScope): Double =
        ChartContrast.contrastRatio(a, b) match
            case Present(v) => v
            case Absent     => fail(s"expected a computable contrast ratio for $a on $b")

    "contrast arithmetic" - {

        "matches the WCAG reference values" in {
            // Black on white is the definition of 21:1; a color against itself is 1:1.
            assert(math.abs(ratio(Style.Color.black, Style.Color.white) - 21.0) < 0.01)
            assert(math.abs(ratio(Style.Color.white, Style.Color.white) - 1.0) < 0.001)
            // The reported measurement: rgb(0,0,0) on #1f2937.
            assert(math.abs(ratio(Style.Color.rgb(0, 0, 0), DarkPanel) - 1.43) < 0.01)
        }

        "reads rgb, rgba and every hex length" in {
            assert(ChartContrast.rgbOf(Style.Color.rgb(1, 2, 3)) == Present((1, 2, 3)))
            assert(ChartContrast.rgbOf(Style.Color.rgba(1, 2, 3, 0.5)) == Present((1, 2, 3)))
            assert(ChartContrast.rgbOf(Style.Color.hex("#ff8000").getOrElse(Style.Color.black)) == Present((255, 128, 0)))
            assert(ChartContrast.rgbOf(Style.Color.hex("#f80").getOrElse(Style.Color.black)) == Present((255, 136, 0)))
            assert(ChartContrast.rgbOf(Style.Color.transparent).isEmpty)
            assert(ChartContrast.rgbOf(Style.Color.variable("accent")).isEmpty)
        }
    }

    "reconcile" - {

        "leaves a color that already clears the threshold exactly as specified" in {
            val orange = Style.Color.rgb(230, 159, 0) // Okabe-Ito entry 2, 6.8:1 on the dark panel
            assert(ratio(orange, DarkPanel) >= 3.0)
            assert(ChartContrast.reconcile(orange, DarkPanel) == orange)
        }

        "lightens a color that is invisible on a dark panel" in {
            val fixed = ChartContrast.reconcile(Style.Color.rgb(0, 0, 0), DarkPanel)
            assert(fixed != Style.Color.rgb(0, 0, 0))
            assert(ratio(fixed, DarkPanel) >= 3.0)
        }

        "darkens a color that is invisible on a light panel" in {
            val nearWhite = Style.Color.rgb(250, 250, 250)
            assert(ratio(nearWhite, LightPanel) < 3.0)
            val fixed = ChartContrast.reconcile(nearWhite, LightPanel)
            assert(ratio(fixed, LightPanel) >= 3.0)
        }

        "keeps the hue: a dark blue rescued on a dark panel is still more blue than red" in {
            val darkBlue = Style.Color.rgb(0, 0, 60)
            val fixed    = ChartContrast.reconcile(darkBlue, DarkPanel)
            ChartContrast.rgbOf(fixed) match
                case Present((r, g, b)) => assert(b > r && b > g, s"expected a blue-dominant result, got ($r, $g, $b)")
                case Absent             => fail("expected a readable color")
        }

        "passes a color through unchanged when its channels are not knowable" in {
            val v = Style.Color.variable("accent")
            assert(ChartContrast.reconcile(v, DarkPanel) == v)
        }
    }

    "a named palette is reconciled against a panel it was not published against" - {

        def onDark(p: Palette)(using Frame, kyo.test.AssertScope): Unit =
            val theme   = Theme.default.dark.palette(p)
            val panel   = ChartAxes.panelBackground(theme)
            val palette = ChartAxes.themePalette(theme)
            assert(palette.size == Palette.colors(p).size)
            palette.foreach { c =>
                assert(ratio(c, panel) >= 3.0, s"$p entry $c is ${ratio(c, panel)}:1 on $panel")
            }
        end onDark

        "Okabe on dark clears 3:1, starting with the entry that did not" in {
            // The exact reported failure: series 1 was rgb(0, 0, 0) on #1f2937, at 1.43:1.
            val theme   = Theme.default.dark.palette(Palette.Okabe)
            val palette = ChartAxes.themePalette(theme)
            assert(palette.head != Style.Color.rgb(0, 0, 0))
            onDark(Palette.Okabe)
        }
        "Viridis on dark clears 3:1" in { onDark(Palette.Viridis) }
        "Tableau10 on dark clears 3:1" in { onDark(Palette.Tableau10) }
        "Default on dark clears 3:1" in { onDark(Palette.Default) }

        "an entry that already clears the bar on dark is left exactly as published" in {
            val published  = Palette.colors(Palette.Okabe)
            val reconciled = ChartAxes.themePalette(Theme.default.dark.palette(Palette.Okabe))
            val panel      = ChartAxes.panelBackground(Theme.default.dark)
            published.zip(reconciled).foreach { (before, after) =>
                if ratio(before, panel) >= 3.0 then assert(after == before, s"$before was readable and should not have moved")
            }
        }

        "on the light panel the palettes are published untouched" in {
            // The named palettes are specified against white, and not all of them clear 3:1 there (Okabe-Ito's
            // yellow does not). Rewriting them on their own panel would restyle every existing chart to chase
            // a bar the published palette never met; kyo reconciles only where IT paired the palette with a
            // panel the palette was not written for.
            List(Palette.Default, Palette.Okabe, Palette.Viridis, Palette.Tableau10).foreach { p =>
                assert(ChartAxes.themePalette(Theme.default.light.palette(p)) == Palette.colors(p))
            }
            assert(ChartAxes.themePalette(Theme.default) == ChartAxes.themePalette(Theme.default))
        }

        "an explicit color list is the caller's own and is never reconciled" in {
            val invisible = Chunk(Style.Color.rgb(0, 0, 0), Style.Color.rgb(10, 10, 10))
            val theme     = Theme.default.dark.palette(invisible.toSeq)
            assert(ChartAxes.themePalette(theme) == invisible)
        }
    }

    "a mark's default color is the reconciled one" in {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 1.0), Row("b", 2.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
            .theme(_.dark.palette(Palette.Okabe))
        spec.lower.map { root =>
            val strokes = root.children.flatMap {
                case g: Svg.G =>
                    g.children.flatMap {
                        case p: Svg.Path =>
                            p.svgAttrs.stroke match
                                case Present(Svg.Paint.Color(c)) => Chunk(c)
                                case _                           => Chunk.empty
                        case _ => Chunk.empty
                    }
                case _ => Chunk.empty
            }
            assert(strokes.nonEmpty, "expected at least one stroked line path")
            assert(!strokes.contains(Style.Color.rgb(0, 0, 0)), "the headline series is black on a near-black panel")
            val panel = ChartAxes.panelBackground(Theme.default.dark)
            strokes.foreach(c => assert(ratio(c, panel) >= 3.0, s"stroke $c is ${ratio(c, panel)}:1 on $panel"))
        }
    }

end ChartContrastTest
