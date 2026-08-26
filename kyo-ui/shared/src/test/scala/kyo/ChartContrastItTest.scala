package kyo

import kyo.Browser.*
import kyo.Chart.*
import kyo.internal.ChartContrast
import scala.language.implicitConversions

/** The palette-versus-theme reconciliation, read back out of a real browser.
  *
  * `ChartContrastTest` covers the arithmetic and the lowering: it walks the `Svg` tree `Chart.lower` produces
  * and asserts the stroke it carries clears 3:1. That leaves one link untested, and it is the link the report
  * was written from: what `HtmlRenderer` puts on the page, and therefore what the viewer's eye actually
  * receives. A stroke correct in the AST but dropped, overridden, or shadowed by a CSS rule on the way to the
  * DOM would pass there and still render the headline series invisible.
  *
  * So this reads `getComputedStyle` off the live nodes in Chrome and recomputes the ratio against the panel's
  * own computed fill, which is the check the finding describes: with
  * `.theme(_.dark.palette(Chart.Palette.Okabe))`, series 1 must not come back `rgb(0, 0, 0)` on the near-black
  * panel, and every series must clear WCAG 2.1 SC 1.4.11's 3:1.
  */
class ChartContrastItTest extends UITest:

    private case class Row(x: String, y: Double)

    private val rows = Chunk(Row("a", 1.0), Row("b", 3.0), Row("c", 2.0))

    /** A chart carrying the pairing the report names: the accessible palette on the dark theme it is not
      * published against.
      */
    private def chartPage(using Frame): UI < Sync =
        for
            chart <- Chart(rows)(line(x = _.x, y = _.y))
                .theme(_.dark.palette(Palette.Okabe))
                .lower
        yield UI.div(chart).id("panel")

    /** Reads the panel's computed fill and every stroked path's computed stroke.
      *
      * The channels are pulled apart in the page rather than in Scala, so what crosses the boundary is
      * `r g b|r g b;r g b`, with no commas or quotes to unpick.
      *
      * The panel is taken as the widest `rect` because `ChartAxes.buildBackground` emits exactly one, and it
      * spans the whole SVG canvas rather than just the plot rectangle (deliberately, so the axis margins read
      * as dark too). Its fill is the same color `panelBackground` returns, which is the background the
      * reconciliation targets, so it is the right thing to measure the strokes against.
      */
    private val readColors: String =
        """(() => {
          |  const chan = (s) => {
          |    const m = String(s).match(/(\d+)\D+(\d+)\D+(\d+)/);
          |    return m ? (m[1] + ' ' + m[2] + ' ' + m[3]) : '';
          |  };
          |  const svg = document.querySelector('svg');
          |  if (!svg) return 'no-svg';
          |  const rects = Array.from(svg.querySelectorAll('rect'));
          |  if (rects.length === 0) return 'no-rect';
          |  const panel = rects.reduce((a, b) =>
          |    (b.getBoundingClientRect().width > a.getBoundingClientRect().width) ? b : a);
          |  const fill = chan(getComputedStyle(panel).fill);
          |  if (!fill) return 'no-panel-fill';
          |  const strokes = Array.from(svg.querySelectorAll('path'))
          |    .map(p => getComputedStyle(p).stroke)
          |    .filter(s => s && s !== 'none')
          |    .map(chan)
          |    .filter(s => s !== '');
          |  if (strokes.length === 0) return 'no-strokes';
          |  return fill + '|' + strokes.join(';');
          |})()""".stripMargin

    /** Parses the `r g b` form the script above emits. */
    private def parseTriple(s: String)(using Frame, kyo.test.AssertScope): Style.Color =
        val parts = s.trim.split(' ').filter(_.nonEmpty)
        if parts.length != 3 then fail(s"expected three channels, got [$s]")
        else
            val channels = parts.map(_.toIntOption)
            if channels.exists(_.isEmpty) then fail(s"non-numeric channel in [$s]")
            else Style.Color.rgb(channels(0).get, channels(1).get, channels(2).get)
        end if
    end parseTriple

    "the reconciled palette survives the render" - {

        "every series clears 3:1 against the panel, as the browser computes both" in {
            withUI(chartPage) {
                for raw <- Browser.eval(readColors)
                yield
                    assert(raw.contains("|"), s"the page reported a problem instead of colors: $raw")
                    val panelPart  = raw.takeWhile(_ != '|')
                    val strokePart = raw.dropWhile(_ != '|').drop(1)
                    val panel      = parseTriple(panelPart)
                    val strokes    = strokePart.split(';').toList.filter(_.nonEmpty).map(parseTriple)
                    assert(strokes.nonEmpty, s"no strokes came back: $raw")

                    strokes.foreach { color =>
                        // The exact rendering the finding reported, and the reason it exists: Okabe-Ito's
                        // first entry is pure black by definition, because the palette is specified against
                        // white.
                        assert(
                            color != Style.Color.rgb(0, 0, 0),
                            s"a series rendered pure black on the dark panel, which is the bug: $raw"
                        )
                        val ratio = ChartContrast.contrastRatio(color, panel)
                            .getOrElse(fail(s"no computable ratio for $color on $panel"))
                        assert(ratio >= 3.0, f"a series is $ratio%.2f:1 against the panel, below WCAG's 3:1 ($raw)")
                    }
            }
        }
    }

end ChartContrastItTest
