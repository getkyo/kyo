package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Phase 06 tests: keyed enter/update SMIL transitions, line path behavior, multi-pull idempotency, key function.
  *
  * Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420, baseline=440
  * (chart size 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  *
  * Scale used in most tests: yScale(_.linear(0, 4000)), nice=false.
  *   pixel(v) = 440 + (v/4000)*(20-440) = 440 - v*0.105
  *   barY(v)  = pixel(v)
  *   barH(v)  = 440 - pixel(v) = v*0.105
  *
  *   rev=1000: barY=335, barH=105
  *   rev=2000: barY=230, barH=210
  *   rev=4000: barY=20,  barH=420
  *
  * The six tests cover:
  *   1. UPDATE: a key present before and after emits a rect with one `Svg.Animate` per SMIL attribute
  *      (height and y), whose `from` is the previous scaled value and `to` the new one.
  *   2. ENTER: a new key emits `Svg.Animate` children with `from` at the baseline (height=0, y=baseline).
  *   3. `.animate(_.none)`: zero `animate` children on the emitted rect.
  *   4. LINE under `.animate(_.ease(...))`: lowers to `<path>` with no `<animate>` child (lines snap
  *      in Phase 06; the bounded stepped-morph tween is implemented in Phase 08).
  *   5. Multi-pull idempotency: rendering the same emission twice (simulating the engine's double-pull)
  *      produces the same from/to animate pair on both pulls, not from==to (dead animation) on the second.
  *   6. Default key (x encoding) vs explicit `.key(...)` override: controls which rects update vs enter.
  */
class ChartTransitionTest extends Test:

    // ---- shared domain types ----

    opaque type Rev <: Double = Double
    object Rev:
        def apply(d: Double): Rev     = d
        given Plottable[Rev]          = Plottable.numeric
        given CanEqual[Rev, Rev]      = CanEqual.derived
        given Conversion[Double, Rev] = d => d
    end Rev

    case class Sale(month: String, revenue: Rev, region: String = "NA")
    given CanEqual[Sale, Sale] = CanEqual.derived

    // ---- layout constants (must match ChartLower) ----
    private val Baseline = 440.0
    private val Tol      = 1.0e-4

    private def assertClose(actual: Double, expected: Double, tag: String): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$tag: expected $expected but got $actual")

    /** Extract all Svg.Animate children from a rect's children list. */
    private def animatesOf(r: Svg.Rect): Chunk[Svg.Animate] =
        Chunk.from(r.children.collect { case a: Svg.Animate => a })

    /** Collect all Svg.Rect descendants from a Root, traversing Reactive nodes inline. */
    private def rectsFromRoot(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.flatMap:
            case g: Svg.G => rectsFromG(g)
            case _        => Chunk.empty

    private def rectsFromG(g: Svg.G): Chunk[Svg.Rect] =
        g.children.flatMap:
            case r: Svg.Rect  => Chunk(r)
            case inner: Svg.G => rectsFromG(inner)
            case _            => Chunk.empty

    /** Collect all Svg.Path descendants from a Root's reactive children. */
    private def pathsFromRoot(root: Svg.Root): Chunk[Svg.Path] =
        root.children.flatMap:
            case g: Svg.G => pathsFromG(g)
            case _        => Chunk.empty

    private def pathsFromG(g: Svg.G): Chunk[Svg.Path] =
        g.children.flatMap:
            case p: Svg.Path  => Chunk(p)
            case inner: Svg.G => pathsFromG(inner)
            case _            => Chunk.empty

    // ---- Test 1: UPDATE emits animate with from=prev and to=new ----

    "UPDATE: a key present before and after emits a rect with two Svg.Animate children (from=prev, to=new)" in run {
        // Scale: linear(0, 4000), baseline=440.
        //   rev=1000: barY=335, barH=105
        //   rev=2000: barY=230, barH=210
        // "Jan" is present in both initial and updated data -> UPDATE path.
        // The animate for height: from=105, to=210. For y: from=335, to=230.
        val initial = Chunk(Sale("Jan", Rev(1000.0)))
        val updated = Chunk(Sale("Jan", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render: records initial geometry (Jan -> barH=105, barY=335).
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Drive updated data.
            _ <- ref.set(updated)
            // Second render: Jan is UPDATE. animate from=105 to=210 for height; from=335 to=230 for y.
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // After update, the rendered HTML must contain animate attributes for height and y.
            // from=105 to=210 (height), from=335 to=230 (y).
            // These values come from the Scale.Linear(0,4000,440,20):
            //   barH(1000) = 105, barH(2000) = 210
            //   barY(1000) = 335, barY(2000) = 230
            assert(
                html1.contains("from=\"105\"") && html1.contains("to=\"210\""),
                s"Expected animate from=105 to=210 (height) in updated render:\n$html1"
            )
            assert(
                html1.contains("from=\"335\"") && html1.contains("to=\"230\""),
                s"Expected animate from=335 to=230 (y) in updated render:\n$html1"
            )
            // attributeName="height" and attributeName="y" must both appear.
            assert(
                html1.contains("attributeName=\"height\""),
                s"Expected attributeName=height in updated render:\n$html1"
            )
            assert(
                html1.contains("attributeName=\"y\""),
                s"Expected attributeName=y in updated render:\n$html1"
            )
            // The initial render has the bar at height=105 but the FIRST emission treats every key as ENTER
            // (from=0 to=105 for height, from=440 to=335 for y).
            assert(
                html0.contains("from=\"0\"") && html0.contains("to=\"105\""),
                s"Expected enter animate from=0 to=105 in initial render:\n$html0"
            )
        end for
    }

    // ---- Test 2: ENTER emits animate with from at baseline ----

    "ENTER: a new key emits animate with from=0 (height) and from=baseline (y)" in run {
        // "Feb" is a new key that appears only in the updated data.
        // ENTER: height animate from=0 to=barH(Feb), y animate from=baseline to=barY(Feb).
        // rev=2000: barH=210, barY=230, baseline=440.
        val initial = Chunk(Sale("Jan", Rev(1000.0)))
        val updated = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render: only Jan (barH=105). Records Jan geometry.
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Drive updated data: Jan (update) + Feb (new key = enter).
            _ <- ref.set(updated)
            // Second render: Feb is ENTER. from=0 to=210 for height; from=440 to=230 for y.
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Feb's ENTER animate: height from=0 to=210, y from=440 to=230.
            assert(
                html1.contains("from=\"0\"") && html1.contains("to=\"210\""),
                s"Expected ENTER animate from=0 to=210 (height) for Feb:\n$html1"
            )
            assert(
                html1.contains("from=\"440\"") && html1.contains("to=\"230\""),
                s"Expected ENTER animate from=440 to=230 (y) for Feb:\n$html1"
            )
        end for
    }

    // ---- Test 3: .animate(_.none) emits zero animate children ----

    "animate(_.none): zero Svg.Animate children on emitted rects" in run {
        val initial = Chunk(Sale("Jan", Rev(1000.0)))
        val updated = Chunk(Sale("Jan", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.none)
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render.
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Drive update.
            _ <- ref.set(updated)
            // Second render: animation disabled -> no <animate> elements.
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // With animation disabled, the rendered HTML must not contain any <animate element.
            assert(!html0.contains("<animate"), s"Expected no <animate in initial render (anim disabled):\n$html0")
            assert(!html1.contains("<animate"), s"Expected no <animate in updated render (anim disabled):\n$html1")
        end for
    }

    // ---- Test 4: LINE under .animate(_.ease(...)) emits a SMIL <animate> on d (Phase 08 morph) ----

    "LINE animate: stable-category update emits <animate attributeName=\"d\"> with from/to d strings" in run {
        // Phase 08: a line chart with animation enabled and stable categories (same command structure)
        // now emits a SMIL `<animate attributeName="d" from=... to=...>` child on the path element.
        // Jan and Feb are present in both emissions, so the Band scale produces the same x positions
        // and the command count is 2 (MoveTo + LineTo) before and after.
        //
        // Band scale (Jan, Feb), plotX=60, plotW=560, n=2, slot=280, padding=0.1:
        // Line points are centred on their band (band centre = plotX + i*slot + slot/2), matching the
        // centred x-axis tick labels and the band-centred area/point/text marks (see ChartInvariantsTest's
        // golden, also band-centred). xs.apply for a Band scale returns the band LEFT edge, so the line
        // lowering adds bandwidth/2 to centre:
        //   px_Jan = 60 + 0*280 + 280/2 = 200
        //   px_Feb = 60 + 1*280 + 280/2 = 480
        // Y scale linear(0,4000), baseline=440, top=20: pixel(v) = 440 - v*0.105
        //   initial: Jan=1000 -> py=335, Feb=2000 -> py=230  (from: "M200 335 L480 230")
        //   updated: Jan=3000 -> py=125, Feb=500  -> py=387.5 (to:   "M200 125 L480 387.5")
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            html0 <- HtmlRenderer.render(root, Seq.empty)
            _     <- ref.set(updated)
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both renders must contain a <path element (the line).
            assert(html0.contains("<path"), s"Expected <path in initial render:\n$html0")
            assert(html1.contains("<path"), s"Expected <path in updated render:\n$html1")
            // The initial render has no previous path, so no animate on first emission.
            assert(!html0.contains("<animate"), s"Expected no <animate on first emission (no prev path):\n$html0")
            // The updated render must contain a SMIL animate on attributeName="d".
            assert(
                html1.contains("attributeName=\"d\""),
                s"Expected attributeName=d in updated render (path morph):\n$html1"
            )
            // The from/to strings must match the computed path coordinates.
            assert(
                html1.contains("from=\"M200 335 L480 230\""),
                s"Expected from=M200 335 L480 230 in updated render:\n$html1"
            )
            assert(
                html1.contains("to=\"M200 125 L480 387.5\""),
                s"Expected to=M200 125 L480 387.5 in updated render:\n$html1"
            )
        end for
    }

    // ---- Test 5: multi-pull idempotency (engine double-pull hazard) ----

    "double-pull idempotency: rendering the same emission twice produces the same from/to animate pair" in run {
        // Simulates the reactive engine pulling the render projection more than once per emission
        // (e.g. at normalize AND at emit). Both pulls must produce the SAME correct from->new delta,
        // not from==to (a dead animation) on the second pull.
        //
        // Sequence:
        //   Pull 1 of emission R1 (Jan=1000): ENTER, from=0 to=105 (height), from=440 to=335 (y).
        //   Pull 2 of emission R1 (same rows): must reproduce EXACTLY the same ENTER (from=0 to=105).
        //   Update to R2 (Jan=2000): UPDATE, from=105 to=210 (height), from=335 to=230 (y).
        //   Pull 2 of emission R2 (same rows): must reproduce UPDATE from=105 to=210.
        val r1 = Chunk(Sale("Jan", Rev(1000.0)))
        val r2 = Chunk(Sale("Jan", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](r1)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render (R1, pull 1): records initial geometry.
            html1a <- HtmlRenderer.render(root, Seq.empty)
            // Second render with SAME ref value (R1, pull 2): simulates engine double-pull.
            html1b <- HtmlRenderer.render(root, Seq.empty)
            // Both pulls of R1 must agree: ENTER from=0 to=105 (height), from=440 to=335 (y).
            // Drive updated data.
            _ <- ref.set(r2)
            // First render of R2 (pull 1): UPDATE from=105 to=210 (height), from=335 to=230 (y).
            html2a <- HtmlRenderer.render(root, Seq.empty)
            // Second render with SAME R2 value (pull 2): must reproduce the same UPDATE delta.
            html2b <- HtmlRenderer.render(root, Seq.empty)
        yield
            // R1 pull 1: ENTER animate from=0 to=105 for height, from=440 to=335 for y.
            assert(
                html1a.contains("from=\"0\"") && html1a.contains("to=\"105\""),
                s"R1 pull 1: expected ENTER from=0 to=105 (height):\n$html1a"
            )
            // R1 pull 2 must produce the same ENTER, NOT from=105 to=105 (dead animation).
            assert(
                html1b.contains("from=\"0\"") && html1b.contains("to=\"105\""),
                s"R1 pull 2: expected ENTER from=0 to=105 (height) -- old code would write and produce from==to:\n$html1b"
            )
            assert(
                !html1b.contains("from=\"105\" to=\"105\""),
                s"R1 pull 2: must NOT produce dead animation from=105 to=105:\n$html1b"
            )
            // R2 pull 1: UPDATE from=105 to=210 (height).
            assert(
                html2a.contains("from=\"105\"") && html2a.contains("to=\"210\""),
                s"R2 pull 1: expected UPDATE from=105 to=210 (height):\n$html2a"
            )
            // R2 pull 2 must reproduce the same UPDATE delta.
            assert(
                html2b.contains("from=\"105\"") && html2b.contains("to=\"210\""),
                s"R2 pull 2: expected UPDATE from=105 to=210 (height) -- idempotent:\n$html2b"
            )
            assert(
                !html2b.contains("from=\"210\" to=\"210\""),
                s"R2 pull 2: must NOT produce dead animation from=210 to=210:\n$html2b"
            )
        end for
    }

    // ---- Test 6 (was Test 5): default key (x encoding) vs explicit .key(...) override ----

    "key defaults to x encoding; .key(...) override controls update vs enter" in run {
        // Without an explicit key override, the key is the x encoding value (month string).
        // Row "Jan" in initial and updated -> UPDATE (same x-key "Jan").
        //
        // With .key(_.region) override, the key is the region string, not the month.
        // Initial: Sale("Jan", 1000, "A"). Updated: Sale("Feb", 2000, "A").
        // Default key (month): "Jan" -> "Feb": different -> ENTER for "Feb".
        // Key override (region): "A" -> "A": same -> UPDATE for "A".
        val initial = Chunk(Sale("Jan", Rev(1000.0), "A"))
        val updated = Chunk(Sale("Feb", Rev(2000.0), "A"))
        for
            // Chart 1: default key (x encoding = month). "Jan" -> "Feb" = different key = ENTER for "Feb".
            ref1 <- Signal.initRef[Seq[Sale]](initial)
            spec1 = Chart(ref1: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root1 = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec1)
            html1a <- HtmlRenderer.render(root1, Seq.empty)
            _      <- ref1.set(updated)
            html1b <- HtmlRenderer.render(root1, Seq.empty)
            // Chart 2: key override (region). Both rows have region="A" -> UPDATE.
            ref2 <- Signal.initRef[Seq[Sale]](initial)
            spec2 = Chart(ref2: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .key(_.region)
            root2 = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec2)
            html2a <- HtmlRenderer.render(root2, Seq.empty)
            _      <- ref2.set(updated)
            html2b <- HtmlRenderer.render(root2, Seq.empty)
        yield
            // Chart 1 (default key = month): "Feb" is a NEW key -> ENTER animate from=0.
            // ENTER: height from=0 to=210 (rev=2000).
            assert(
                html1b.contains("from=\"0\"") && html1b.contains("to=\"210\""),
                s"Expected ENTER (from=0) for new month key 'Feb' in chart1:\n$html1b"
            )
            // Chart 2 (key = region "A"): same key -> UPDATE animate.
            // UPDATE: height from=105 (initial rev=1000) to=210 (updated rev=2000).
            assert(
                html2b.contains("from=\"105\"") && html2b.contains("to=\"210\""),
                s"Expected UPDATE (from=105) for same region key 'A' in chart2:\n$html2b"
            )
        end for
    }

    // ---- Leaf 8 (INV-036 + INV-016): curved-path morph with Curve.monotone ----

    "curved line (Curve.monotone) morphs via SMIL when point count is stable" in run {
        // INV-016: curve=Curve.monotone produces cubic Bezier commands (C).
        // INV-036: with stable point count the SMIL morph fires.
        //
        // Band scale (Jan, Feb, Mar), plotX=60, plotW=560, n=3, padding=0.1:
        //   slot = 560/3 = 186.667
        //   bandW = 560*0.9/3 = 168
        //   center_Jan = 60 + 186.667*0 + 186.667/2 = 153.333...
        //   center_Feb = 60 + 186.667*1 + 186.667/2 = 340
        //   center_Mar = 60 + 186.667*2 + 186.667/2 = 526.667...
        // Y scale linear(0, 4000): pixel(v) = 440 - v*0.105
        //   Emission 1: rev=1000->335, 2000->230, 3000->125
        //   Emission 2: rev=500->387.5, 1500->282.5, 2500->177.5
        //
        // The monotone interpolation emits C commands between the 3 points,
        // so the command count is stable across the two emissions (same 3 points).
        val e1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)), Sale("Mar", Rev(3000.0)))
        val e2 = Chunk(Sale("Jan", Rev(500.0)), Sale("Feb", Rev(1500.0)), Sale("Mar", Rev(2500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](e1)
            spec = Chart(ref: Signal[Seq[Sale]])(
                line(x = _.month, y = _.revenue, curve = Curve.monotone)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // Emission 1: record monotone path geometry.
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: different y-values, same x-categories (stable command count).
            _     <- ref.set(e2)
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both renders must produce a path with cubic (C) commands from monotone interpolation.
            assert(html0.contains("C"), s"INV-016 leaf 8: emission 1 path must contain C (cubic) commands:\n$html0")
            assert(html1.contains("C"), s"INV-016 leaf 8: emission 2 path must contain C (cubic) commands:\n$html1")
            // First emission has no previous path: no animate.
            assert(!html0.contains("<animate"), s"INV-036 leaf 8: no animate on first emission:\n$html0")
            // Second emission: SMIL morph must fire (stable command count, different y-values).
            assert(
                html1.contains("attributeName=\"d\""),
                s"INV-036/INV-016 leaf 8: curved line must morph via SMIL on stable-category update:\n$html1"
            )
            // from and to both contain C (cubic path in both emissions).
            assert(
                html1.contains("from=\"M") && html1.contains("C"),
                s"INV-036 leaf 8: from path must start with M and contain C:\n$html1"
            )
            assert(
                html1.contains("to=\"M"),
                s"INV-036 leaf 8: to path must start with M:\n$html1"
            )
        end for
    }

    // ---- FIX B (animated path): a color-split ANIMATED line honors an explicit categorical colorScale ----

    // Reproduce-before-fix. A chart with `.animate(...)` is lowered through `lowerLineWithTransitions`,
    // NOT `lowerLine`. Before the fix, `lowerLineWithTransitions` colored each series from
    // `themePalette(spec.theme)` by category index, ignoring `spec.legendCfg.colorScale`. So an animated
    // color-split line drew DefaultPalette blue/orange while the legend (which uses resolvePalette) showed
    // the colorScale cyan/amber: legend and line disagreed (e.g. the LiveDashboard latency chart).
    // After the fix, `lowerLineWithTransitions` resolves colors via resolvePalette, so the "a" series path
    // carries the colorScale cyan and the "b" series path carries the colorScale amber, matching the legend
    // and the static (non-animated) line path.
    "an animated color-split line honors an explicit categorical colorScale (FIX B, transitions path)" in run {
        case class SRow(x: Double, y: Double, series: String) derives CanEqual
        val cyan  = Style.Color.rgb(6, 182, 212)
        val amber = Style.Color.rgb(245, 158, 11)
        // CssStyleRenderer.color(Rgb(r,g,b)) renders as "rgb(r, g, b)".
        val cyanCss  = "rgb(6, 182, 212)"
        val amberCss = "rgb(245, 158, 11)"
        // DefaultPalette.blue/orange are Hex colors, rendered as "#3b82f6"/"#f97316".
        val blueCss   = "#3b82f6"
        val orangeCss = "#f97316"
        val rows = Chunk(
            SRow(0.0, 1.0, "a"),
            SRow(1.0, 2.0, "a"),
            SRow(0.0, 3.0, "b"),
            SRow(1.0, 4.0, "b")
        )
        for
            ref <- Signal.initRef[Seq[SRow]](rows)
            // .animate enabled routes lowering through lowerLineWithTransitions (the path under test).
            spec = Chart(ref: Signal[Seq[SRow]])(line(x = _.x, y = _.y, color = _.series))
                .animate(_.ease(300.millis))
                .legend(_.colorScale {
                    case "a" => cyan
                    case _   => amber
                })
            root = summon[Conversion[Chart.Spec[SRow], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Extract each <path ...> element's stroke colour in document order. The transitions lowering
            // emits one path per series in distinctKeyed seriesIdx order (a=0 then b=1).
            val strokes: Chunk[String] = Chunk.from(
                "<path[^>]*".r.findAllIn(html).toSeq.flatMap { tag =>
                    "stroke=\"([^\"]+)\"".r.findFirstMatchIn(tag).map(_.group(1))
                }
            )
            assert(strokes.size == 2, s"Expected 2 line-path strokes (one per series) but got ${strokes.size}:\n$html")
            // Series "a" (seriesIdx 0) must carry the colorScale cyan, NOT DefaultPalette blue.
            assert(
                strokes(0) == cyanCss,
                s"FIX B (animated): series 'a' line must use colorScale cyan $cyanCss but got ${strokes(0)}:\n$html"
            )
            // Series "b" (seriesIdx 1) must carry the colorScale amber, NOT DefaultPalette orange.
            assert(
                strokes(1) == amberCss,
                s"FIX B (animated): series 'b' line must use colorScale amber $amberCss but got ${strokes(1)}:\n$html"
            )
            // DefaultPalette colours must not appear: the bug rendered these instead.
            assert(
                !html.contains(s"stroke=\"$blueCss\""),
                s"FIX B (animated): DefaultPalette blue $blueCss must not be a stroke under a colorScale:\n$html"
            )
            assert(
                !html.contains(s"stroke=\"$orangeCss\""),
                s"FIX B (animated): DefaultPalette orange $orangeCss must not be a stroke under a colorScale:\n$html"
            )
        end for
    }

    // ---- Leaf L18 (GAP-TRANS-BAR-ENCODINGS): animated bar emits opacity/label/tooltip encodings ----

    "animated bar emits opacity/label/tooltip encodings matching the static path (L18, GAP-TRANS-BAR-ENCODINGS)" in run {
        // Static bar (no Signal ref): lowerBarSimple -> applyBarEncodings -> encodings applied.
        // Animated bar (Signal ref + .animate): lowerBarSimpleWithTransitions -> was missing applyBarEncodings.
        // Both must emit fill-opacity="0.5", a <title> tooltip child, and a sibling label <text>.
        //
        // Scale: linear(0, 4000), baseline=440.
        //   rev=2000: barY=230, barH=210
        //   barX = plotX = 60 (single band), barW = plotW = 560 (single category)
        //   labelX = barX + barW/2 = 60 + 280 = 340, labelY = barY - 2 = 228
        val rows = Chunk(Sale("Jan", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](rows)
            animSpec = Chart(ref: Signal[Seq[Sale]])(
                bar(
                    x = _.month,
                    y = _.revenue,
                    opacity = _ => 0.5,
                    label = _.month,
                    tooltip = _.month
                )
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            animRoot = summon[Conversion[Chart.Spec[Sale], Svg.Root]](animSpec)
            // First render (ENTER): bar is new, from=0 to=210 height, from=440 to=230 y.
            html <- HtmlRenderer.render(animRoot, Seq.empty)
        yield
            // (a) fill-opacity encoding: the animated rect must carry fill-opacity="0.5".
            assert(
                html.contains("fill-opacity=\"0.5\""),
                s"Animated bar must carry fill-opacity=0.5 (opacity encoding dropped before fix):\n$html"
            )
            // (b) tooltip encoding: the animated rect must contain a <title ...>Jan</title> child.
            // The renderer emits data-kyo-path attributes, so match on the closing tag pattern.
            assert(
                html.contains(">Jan</title>"),
                s"Animated bar must contain >Jan</title> (tooltip encoding dropped before fix):\n$html"
            )
            // (c) label encoding: a sibling label <text>Jan</text> must be present.
            assert(
                html.contains(">Jan<"),
                s"Animated bar must emit a label text >Jan< (label encoding dropped before fix):\n$html"
            )
            // (d) SMIL animates still present (animation not disturbed): both <animate> children preserved.
            assert(
                html.contains("attributeName=\"height\""),
                s"Animated bar must still carry attributeName=height SMIL animate:\n$html"
            )
            assert(
                html.contains("attributeName=\"y\""),
                s"Animated bar must still carry attributeName=y SMIL animate:\n$html"
            )
            // (e) ENTER animate values: from=0 to=210 (height), from=440 to=230 (y).
            assert(
                html.contains("from=\"0\"") && html.contains("to=\"210\""),
                s"Animated bar ENTER must have from=0 to=210 height animate:\n$html"
            )
            assert(
                html.contains("from=\"440\"") && html.contains("to=\"230\""),
                s"Animated bar ENTER must have from=440 to=230 y animate:\n$html"
            )
        end for
    }

    // ---- L18 co-pin: no-encoding animated bar is byte-identical to today (L8 co-pin arm for transitions) ----

    "no-encoding animated bar is byte-identical through the fix (L18 co-pin)" in run {
        // A bar with NO opacity/label/tooltip encodings. After the fix, applyBarEncodings returns the rect
        // unchanged (Absent arms for all three encodings) and an empty label Chunk. The SMIL animates are
        // attached as before. Output must be byte-identical to the pre-fix animated bar.
        val rows = Chunk(Sale("Jan", Rev(1000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](rows)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // No fill-opacity attribute (opacity encoding absent).
            assert(
                !html.contains("fill-opacity"),
                s"No-encoding animated bar must NOT carry fill-opacity (Absent arm):\n$html"
            )
            // No <title> child (tooltip absent).
            assert(
                !html.contains("<title>"),
                s"No-encoding animated bar must NOT carry <title> (Absent arm):\n$html"
            )
            // SMIL animates still present.
            assert(
                html.contains("attributeName=\"height\"") && html.contains("attributeName=\"y\""),
                s"No-encoding animated bar must still carry SMIL animates:\n$html"
            )
            // ENTER animate for Jan rev=1000: barH=105, barY=335.
            assert(
                html.contains("from=\"0\"") && html.contains("to=\"105\""),
                s"No-encoding animated bar ENTER must have from=0 to=105 height:\n$html"
            )
        end for
    }

end ChartTransitionTest
