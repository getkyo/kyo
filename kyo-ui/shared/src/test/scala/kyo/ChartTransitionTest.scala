package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.UI.mark.*
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
  *   6. Default key (x channel) vs explicit `.key(...)` override: controls which rects update vs enter.
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

    /** Render a Reactive root twice: once with `initial`, once after setting `ref` to `updated`.
      * Returns (html-before, rectsBefore, html-after, rectsAfter) from the rendered Reactive region.
      *
      * The `spec` is a live chart built from `ref`. After rendering with initial rows, the ref is updated
      * and the root is rendered again.
      */
    private def renderTwice(
        initial: Chunk[Sale],
        updated: Chunk[Sale],
        specBuilder: Signal[Chunk[Sale]] => ChartSpec[Sale]
    ): (Svg.Root, Svg.Root) < Async =
        for
            ref <- Signal.initRef(initial)
            spec = specBuilder(ref: Signal[Chunk[Sale]])
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            _ <- ref.set(updated)
            root2 = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        yield (root, root2)

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
            ref <- Signal.initRef(initial)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
            ref <- Signal.initRef(initial)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
            ref <- Signal.initRef(initial)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.none)
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
        // Band scale (Jan, Feb), plotX=60, plotW=560, n=2, slot=280, padding=0.1, bandW=252:
        //   px_Jan = 60 + 0*280 + (280-252)/2 = 60 + 14 = 74
        //   px_Feb = 60 + 1*280 + (280-252)/2 = 60 + 294 = 354
        // Y scale linear(0,4000), baseline=440, top=20: pixel(v) = 440 - v*0.105
        //   initial: Jan=1000 -> py=335, Feb=2000 -> py=230  (from: "M74 335 L354 230")
        //   updated: Jan=3000 -> py=125, Feb=500  -> py=387.5 (to:   "M74 125 L354 387.5")
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef(initial)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
                html1.contains("from=\"M74 335 L354 230\""),
                s"Expected from=M74 335 L354 230 in updated render:\n$html1"
            )
            assert(
                html1.contains("to=\"M74 125 L354 387.5\""),
                s"Expected to=M74 125 L354 387.5 in updated render:\n$html1"
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
            ref <- Signal.initRef(r1)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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

    // ---- Test 6 (was Test 5): default key (x channel) vs explicit .key(...) override ----

    "key defaults to x channel; .key(...) override controls update vs enter" in run {
        // Without an explicit key override, the key is the x channel value (month string).
        // Row "Jan" in initial and updated -> UPDATE (same x-key "Jan").
        //
        // With .key(_.region) override, the key is the region string, not the month.
        // Initial: Sale("Jan", 1000, "A"). Updated: Sale("Feb", 2000, "A").
        // Default key (month): "Jan" -> "Feb": different -> ENTER for "Feb".
        // Key override (region): "A" -> "A": same -> UPDATE for "A".
        val initial = Chunk(Sale("Jan", Rev(1000.0), "A"))
        val updated = Chunk(Sale("Feb", Rev(2000.0), "A"))
        for
            // Chart 1: default key (x channel = month). "Jan" -> "Feb" = different key = ENTER for "Feb".
            ref1 <- Signal.initRef(initial)
            spec1 = UI.chart(ref1: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root1 = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec1)
            html1a <- HtmlRenderer.render(root1, Seq.empty)
            _      <- ref1.set(updated)
            html1b <- HtmlRenderer.render(root1, Seq.empty)
            // Chart 2: key override (region). Both rows have region="A" -> UPDATE.
            ref2 <- Signal.initRef(initial)
            spec2 = UI.chart(ref2: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .key(_.region)
            root2 = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec2)
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
            ref <- Signal.initRef(e1)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(
                line(x = _.month, y = _.revenue, curve = Curve.monotone)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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

end ChartTransitionTest
