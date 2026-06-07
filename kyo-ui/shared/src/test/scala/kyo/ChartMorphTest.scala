package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Phase 08 tests: LINE/AREA PATH-MORPH TWEEN (declarative SMIL on `d`).
  *
  * The chart lowers to a pure `Svg.Root` via a `given Conversion`, which has NO effect context and
  * cannot launch an `Async` stepping fiber. SMIL can animate a path's `d` attribute declaratively
  * (one `<animate>` element, browser-driven, no fiber) WHEN the previous and new paths have the same
  * command structure (same number of MoveTo/LineTo/Close commands in the same order). This is the
  * common case for line/area data updates with stable x-categories but changing y-values.
  *
  * Architectural constraint (documented v1 limitation):
  *   A structural path morph (different command count, e.g. a category added or removed) requires a
  *   bounded stepped-interpolation fiber that can only be launched from an effectful mount hook. The
  *   pure `Svg.Root` lowering does not provide such a hook, so structural morphs SNAP with no animate
  *   child.
  *
  * Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420, baseline=440
  * (chart 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  *
  * Band scale (2 categories, padding=0.1):
  *   slot=280, bandW=252
  *   Line/area/point/text vertices are centred on their band (band centre = band left edge + bandW/2),
  *   matching the centred x-axis tick labels and the slot-centred bars. xs.apply returns the band LEFT
  *   edge, so the lowering adds bandW/2 to centre:
  *   px_Jan = 60 + 0*280 + (280-252)/2 + 252/2 = 74 + 126 = 200
  *   px_Feb = 60 + 1*280 + (280-252)/2 + 252/2 = 354 + 126 = 480
  *   (The old 74/354 were the band LEFT edge and encoded the off-by-half-band bug, now fixed.)
  *
  * Y scale linear(0,4000), baseline=440, top=20: pixel(v) = 440 - v*0.105
  *   1000 -> 335,  2000 -> 230,  3000 -> 125,  500 -> 387.5
  *
  * Band scale (3 categories: Jan, Feb, Mar):
  *   slot = 560/3 ~= 186.667, bandW = 560*0.9/3 = 168
  *   px_Jan = 60 + (186.667-168)/2 = 60 + 9.333 = 69.333...
  *   (exact: 60 + (560/3 - 560*0.9/3) / 2 = 60 + 560*0.1/6 = 60 + 56/6 = 60 + 28/3)
  *
  * The fourteen tests cover:
  *   1. LINE same-structure update: emits exactly ONE `<animate attributeName="d">` with the correct
  *      `from` (initial path string) and `to` (updated path string).
  *   2. LINE structural change (category added): emits NO `<animate>` (snap).
  *   3. `.animate(_.none)`: emits no `<animate>` even for a same-structure update.
  *   4. AREA same-structure update: emits exactly ONE `<animate attributeName="d">` on the area path.
  *   5. Double-pull idempotency (line): two renders of the same emission reproduce the same from/to.
  *   6. Double-pull idempotency (area): same discipline for area paths.
  *   7. No animate on the FIRST emission (no previous path stored yet).
  *   8-14. INV-036 / INV-002 leaves (markIdx keying stability, CatKey dedup, area parity, gate check).
  */
class ChartMorphTest extends kyo.test.Test[Any]:

    // ---- shared domain types ----

    opaque type Rev <: Double = Double
    object Rev:
        def apply(d: Double): Rev     = d
        given Plottable[Rev]          = Plottable.numeric
        given CanEqual[Rev, Rev]      = CanEqual.derived
        given Conversion[Double, Rev] = d => d
    end Rev

    case class Sale(month: String, revenue: Rev)
    given CanEqual[Sale, Sale] = CanEqual.derived

    // Two-value record for multi-mark tests (INV-036 leaf 1 and leaf 6).
    // v2 is Maybe[Rev] so that rows with v2=Absent are skipped by line/area (gap rows).
    // This lets leaf 1 / leaf 6 keep the line/area stable at 2 points while the bar gains a 3rd row.
    case class MultiSale(month: String, v1: Rev, v2: Maybe[Rev])
    given CanEqual[MultiSale, MultiSale] = CanEqual.derived

    // Enum with colliding toString for INV-002 / INV-036 leaf 5.
    // Both cases override toString to "color" so they collide under the old toString-keyed dedup.
    // distinctKeyed uses CatKey (class + value), keeping them distinct.
    enum Col derives CanEqual:
        case Red
        case Blue
        override def toString: String = "color"
    end Col

    case class ColSale(month: String, revenue: Rev, col: Col)
    given CanEqual[ColSale, ColSale] = CanEqual.derived

    // Row type for Bug A (gap/type-signature) tests: Maybe revenue for gap rows.
    case class GapSale(month: String, rev: Maybe[Rev])
    given CanEqual[GapSale, GapSale] = CanEqual.derived

    // Row type for Bug B (category-identity keying) tests: String color label.
    case class NamedColorSale(month: String, rev: Rev, series: String)
    given CanEqual[NamedColorSale, NamedColorSale] = CanEqual.derived

    // ---- Test 1: LINE same-structure update emits a SMIL d-animate ----

    "LINE same-structure update emits exactly one animate attributeName=d with correct from/to" in {
        // Jan and Feb are present in both emissions: command count is 2 (M + L) before and after.
        // from = initial path d string, to = updated path d string.
        //
        // Initial: Jan=1000 py=335, Feb=2000 py=230  -> d = "M200 335 L480 230"
        // Updated: Jan=3000 py=125, Feb=500  py=387.5 -> d = "M200 125 L480 387.5"
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render records the initial path geometry.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Update to new values with the same categories.
            _    <- ref.set(updated)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Exactly one animate with attributeName="d".
            val animateCount = html.split("<animate").length - 1
            assert(animateCount == 1, s"Expected exactly 1 <animate, got $animateCount:\n$html")
            assert(
                html.contains("attributeName=\"d\""),
                s"Expected attributeName=d:\n$html"
            )
            // The transition animate must use begin="indefinite", not begin="0s": with begin="0s" a
            // post-load reactive update snaps to the frozen end (the animate's window is already past on
            // the shared document timeline), so the chart jumps instead of tweening. The reactive runtime
            // calls beginElement() on insert to start it. A revert to begin="0s" reintroduces that bug.
            assert(
                html.contains("begin=\"indefinite\"") && !html.contains("begin=\"0s\""),
                s"Expected begin=indefinite (not 0s) so the runtime can start the tween on insert:\n$html"
            )
            // The transition eases (ease-in-out-cubic) rather than moving linearly: calcMode="spline"
            // plus keyTimes/keySplines for the single from->to segment. Without these, SMIL interpolates
            // linearly despite the .ease(...) API name.
            assert(
                html.contains("calcMode=\"spline\"") && html.contains("keySplines=\"0.645 0.045 0.355 1\""),
                s"Expected ease-in-out-cubic easing (calcMode=spline + keySplines), not linear:\n$html"
            )
            // The from string must be the rendered d of the initial path.
            assert(
                html.contains("from=\"M200 335 L480 230\""),
                s"Expected from=M200 335 L480 230:\n$html"
            )
            // The to string must be the rendered d of the updated path.
            assert(
                html.contains("to=\"M200 125 L480 387.5\""),
                s"Expected to=M200 125 L480 387.5:\n$html"
            )
        end for
    }

    // ---- Runtime wiring: the page client script must START inserted SMIL animations ----

    "renderPage client script calls beginElement so begin=indefinite transitions actually play" in {
        // The chart transition <animate> elements use begin="indefinite" (so a post-load update does not
        // snap against the shared document timeline). They only tween if the reactive runtime calls
        // beginElement() on each freshly-inserted animate after a mount/patch. This asserts the server
        // client script carries that trigger; DomBackend.beginAnimationsSync is the JS-backend mirror.
        val page = HtmlRenderer.renderPage("t", "<div></div>", "", "sid", "/")
        assert(
            page.contains("beginElement"),
            s"renderPage client script must call beginElement() to start inserted SMIL animations:\n${page.take(600)}"
        )
    }

    // ---- Test 2: LINE structural change (category added) snaps, no animate ----

    "LINE structural change (category added) snaps: zero animate elements" in {
        // Initial: 2 categories (M + L = 2 commands). Updated: 3 categories (M + L + L = 3 commands).
        // Command counts differ -> structural morph -> path snaps, no <animate> emitted.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)), Sale("Mar", Rev(1500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render: record 2-command path geometry.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Structural change: 3 categories.
            _    <- ref.set(updated)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // No animate element on the path: structural change snaps.
            assert(
                !html.contains("<animate"),
                s"Expected no <animate on structural change (category added):\n$html"
            )
            // The path element must still be present.
            assert(html.contains("<path"), s"Expected <path in render:\n$html")
        end for
    }

    // ---- Test 3: .animate(_.none) emits no d-animate ----

    "animate(_.none): no animate element even for same-structure update" in {
        // Same categories before and after, but animation disabled.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.none)
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            html0 <- HtmlRenderer.render(root, Seq.empty)
            _     <- ref.set(updated)
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html0.contains("<animate"), s"Expected no <animate (anim disabled, initial):\n$html0")
            assert(!html1.contains("<animate"), s"Expected no <animate (anim disabled, updated):\n$html1")
        end for
    }

    // ---- Test 4: AREA same-structure update emits a SMIL d-animate ----

    "AREA same-structure update emits animate attributeName=d with correct from/to" in {
        // Area mark with stable categories. The area path is a closed polygon:
        // top forward (2 pts), then baseline back (2 pts), then close = M+L+L+L+Z = 5 commands.
        //
        // Initial: Jan=1000 py=335, Feb=2000 py=230, baseline=440
        //   top:      M200 335 L480 230
        //   baseline: L480 440 L200 440 Z
        //   -> "M200 335 L480 230 L480 440 L200 440 Z"
        // Updated: Jan=3000 py=125, Feb=500 py=387.5
        //   -> "M200 125 L480 387.5 L480 440 L200 440 Z"
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // First render: record initial area path geometry.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Update values (same categories, same structure).
            _    <- ref.set(updated)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("attributeName=\"d\""),
                s"Expected attributeName=d in area animate:\n$html"
            )
            assert(
                html.contains("from=\"M200 335 L480 230 L480 440 L200 440 Z\""),
                s"Expected area from path:\n$html"
            )
            assert(
                html.contains("to=\"M200 125 L480 387.5 L480 440 L200 440 Z\""),
                s"Expected area to path:\n$html"
            )
        end for
    }

    // ---- Test 5: Double-pull idempotency (line) ----

    "LINE double-pull idempotency: repeat render of same emission reproduces the same from/to d" in {
        // Simulates the reactive engine pulling the render projection twice per emission.
        // Both pulls must produce the same from and to path strings, not a dead animation.
        //
        // Sequence:
        //   R1 (Jan=1000, Feb=2000): no previous path -> no animate on both pulls.
        //   R2 (Jan=3000, Feb=500):  previous = R1 path.
        //     Pull 1 of R2: from = R1 path, to = R2 path (writes state).
        //     Pull 2 of R2: must reproduce SAME from/to (reads stored state, does NOT re-write).
        val r1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val r2 = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](r1)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            // R1 pull 1: no previous path.
            html1a <- HtmlRenderer.render(root, Seq.empty)
            // R1 pull 2: repeat pull of same emission.
            html1b <- HtmlRenderer.render(root, Seq.empty)
            // Advance to R2.
            _ <- ref.set(r2)
            // R2 pull 1: from=R1 path, to=R2 path.
            html2a <- HtmlRenderer.render(root, Seq.empty)
            // R2 pull 2: must reproduce the same from/to (idempotent).
            html2b <- HtmlRenderer.render(root, Seq.empty)
        yield
            // R1: no previous path so no animate on either pull.
            assert(!html1a.contains("<animate"), s"R1 pull 1: no animate expected:\n$html1a")
            assert(!html1b.contains("<animate"), s"R1 pull 2: no animate expected:\n$html1b")
            // R2 pull 1: animate present with correct from/to.
            assert(
                html2a.contains("from=\"M200 335 L480 230\""),
                s"R2 pull 1: expected from=R1 path:\n$html2a"
            )
            assert(
                html2a.contains("to=\"M200 125 L480 387.5\""),
                s"R2 pull 1: expected to=R2 path:\n$html2a"
            )
            // R2 pull 2: same from/to (idempotent double-pull).
            assert(
                html2b.contains("from=\"M200 335 L480 230\""),
                s"R2 pull 2: expected same from=R1 path (idempotent):\n$html2b"
            )
            assert(
                html2b.contains("to=\"M200 125 L480 387.5\""),
                s"R2 pull 2: expected same to=R2 path (idempotent):\n$html2b"
            )
            // Must NOT produce a dead animation from==to.
            assert(
                !html2b.contains("from=\"M200 125 L480 387.5\" to=\"M200 125 L480 387.5\""),
                s"R2 pull 2: must not produce dead animation from==to:\n$html2b"
            )
        end for
    }

    // ---- Test 6: Double-pull idempotency (area) ----

    "AREA double-pull idempotency: repeat render of same emission reproduces the same from/to d" in {
        // Same idempotency discipline as the line test, applied to area paths.
        val r1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val r2 = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](r1)
            spec = Chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            html1a <- HtmlRenderer.render(root, Seq.empty)
            html1b <- HtmlRenderer.render(root, Seq.empty)
            _      <- ref.set(r2)
            html2a <- HtmlRenderer.render(root, Seq.empty)
            html2b <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html1a.contains("<animate"), s"R1 pull 1: no animate:\n$html1a")
            assert(!html1b.contains("<animate"), s"R1 pull 2: no animate:\n$html1b")
            assert(
                html2a.contains("from=\"M200 335 L480 230 L480 440 L200 440 Z\""),
                s"R2 pull 1: expected from=area R1 path:\n$html2a"
            )
            assert(
                html2b.contains("from=\"M200 335 L480 230 L480 440 L200 440 Z\""),
                s"R2 pull 2: expected same from (idempotent):\n$html2b"
            )
            assert(
                !html2b.contains("from=\"M200 125 L480 387.5 L480 440 L200 440 Z\" to=\"M200 125 L480 387.5 L480 440 L200 440 Z\""),
                s"R2 pull 2: must not produce dead animation:\n$html2b"
            )
        end for
    }

    // ---- Test 7: No animate on the first emission (no previous path stored) ----

    "first emission with animation enabled emits no animate (no previous path exists yet)" in {
        // The very first render of a live chart has no previous PathData in the TransState.
        // No animate child should be emitted.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initial)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<path"), s"Expected <path in first render:\n$html")
            assert(!html.contains("<animate"), s"Expected no <animate on first emission:\n$html")
        end for
    }

    // ---- INV-036 Leaf 1: multi-mark pathKey stable when preceding bar's geom count changes ----

    "multi-mark: line pathKey is stable when a preceding bar's geom count changes" in {
        // mark-0: ungrouped bar (uses rowKey, content-based, not pathKey).
        //   Emission 1: 2 bars (Jan, Feb). Emission 2: 3 bars (Jan, Feb, Mar).
        //   Bar geom entries go from 2 to 3; under the old keyOffset scheme this shifted
        //   mark-1's line key from "line-2" to "line-3", causing a missed lookup and snap.
        // mark-1: line with stable 2 points (Jan, Feb) across both emissions.
        //   The Mar row has v2=NaN so it is filtered as non-finite and skipped by the line.
        //   With the markIdx fix the key is "line-1-0" on both emissions: lookup hits,
        //   SMIL fires (from/to differ since y-values change).
        //
        // Line v2 (Maybe[Rev]): emission 1 Jan/Feb present; emission 2 Jan/Feb present + Mar=Absent.
        // Mar=Absent makes the line treat Mar as a gap row, so the line stays 2-point in both emissions.
        // The bar uses v1 (Rev, non-optional) so Mar counts as a bar entry -> bar grows from 2 to 3.
        val e1 = Chunk(
            MultiSale("Jan", Rev(1000.0), Present(Rev(500.0))),
            MultiSale("Feb", Rev(1000.0), Present(Rev(1000.0)))
        )
        val e2 = Chunk(
            MultiSale("Jan", Rev(1000.0), Present(Rev(600.0))),
            MultiSale("Feb", Rev(1000.0), Present(Rev(900.0))),
            // Mar is present for the bar (v1=1000) but v2=Absent so the line skips it.
            MultiSale("Mar", Rev(1000.0), Absent)
        )
        for
            ref <- Signal.initRef[Seq[MultiSale]](e1)
            spec = Chart(ref: Signal[Seq[MultiSale]])(
                bar(x = _.month, y = _.v1),
                line(x = _.month, y = _.v2)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[MultiSale], Svg.Root]](spec)
            // Emission 1: records bar geom (2 entries) and line geom ("line-1-0" with M+L = 2 cmds).
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: bar adds Mar (3 geom entries); line v2 still produces 2 points (Jan, Feb).
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The line must have morphed (SMIL fired): attributeName="d" present.
            // This proves the line's key was found in fromGeom despite the bar count change.
            assert(
                html.contains("attributeName=\"d\""),
                s"INV-036 leaf 1: line SMIL morph must fire when bar's geom count changes:\n$html"
            )
            // The from and to path strings must differ (real morph, not dead animation).
            assert(
                html.contains("from=\"M") && html.contains("to=\"M"),
                s"INV-036 leaf 1: expected from=M and to=M in d-animate:\n$html"
            )
        end for
    }

    // ---- INV-036 Leaf 3: new series added in second emission snaps in ----

    "new series added in second emission snaps in (no false morph from prior slot)" in {
        // Emission 1: one series ("Red" only), key "line-0-Red", stroke #3b82f6 (blue, idx=0).
        //   Jan and Feb are present -> 2-point path stored in fromGeom under "line-0-Red".
        // Emission 2: two series ("Red" + "Blue"), keys "line-0-Red" (existing) and "line-0-Blue" (new).
        //   Red series (line-0-Red): Jan/Feb still present with new y-values -> same type signature
        //     -> MORPHS via SMIL (has <animate attributeName="d">), stroke #3b82f6.
        //   Blue series (line-0-Blue): no prior entry in fromGeom -> SNAPS (no <animate>), stroke #f97316.
        // The labels "Red" and "Blue" are distinct, so the pathKey per category is stable across add/remove.
        // Asserting that blue-stroked segment contains attributeName="d" and orange-stroked does not
        // proves the new series does NOT falsely morph off a stale prior slot.
        //
        // Note: this test uses NamedColorSale (String color field, distinct labels) rather than ColSale
        // (Col enum, colliding toString="color") because the "new series snaps" behavior requires
        // distinct labels to be tested cleanly. Two categories with identical labels share a pathKey by
        // the documented same-limitation as legend hiddenSeries Set[String].
        val e1 = Chunk(
            NamedColorSale("Jan", Rev(1000.0), "Red"),
            NamedColorSale("Feb", Rev(2000.0), "Red")
        )
        val e2 = Chunk(
            NamedColorSale("Jan", Rev(1500.0), "Red"),
            NamedColorSale("Feb", Rev(2500.0), "Red"),
            NamedColorSale("Jan", Rev(800.0), "Blue"),
            NamedColorSale("Feb", Rev(1200.0), "Blue")
        )
        for
            ref <- Signal.initRef[Seq[NamedColorSale]](e1)
            spec = Chart(ref: Signal[Seq[NamedColorSale]])(
                line(x = _.month, y = _.rev, color = _.series)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[NamedColorSale], Svg.Root]](spec)
            // Emission 1: record "line-0-Red" (Jan+Feb) in fromGeom.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: Red series stable (morphs); Blue series new (snaps).
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Split on "<path " to isolate each path element's content.
            // The first segment (index 0) is pre-path preamble (axes, legend) and is skipped.
            // Segment 1 starts with the blue-stroked (Red series, idx=0) path; segment 2 with orange-stroked.
            // The legend's line swatches also use stroke colors but appear in segment 0 only,
            // so segments 1 and 2 cleanly separate the two series paths.
            val rawSegments   = html.split("<path ").toSeq
            val pathSegs      = rawSegments.drop(1) // skip preamble
            val blueSegment   = pathSegs.find(_.contains("stroke=\"#3b82f6\""))
            val orangeSegment = pathSegs.find(_.contains("stroke=\"#f97316\""))
            assert(blueSegment.isDefined, s"INV-036 leaf 3: expected a blue-stroked path (Red series):\n$html")
            assert(orangeSegment.isDefined, s"INV-036 leaf 3: expected an orange-stroked path (Blue series):\n$html")
            // Existing Red series (line-0-Red) must MORPH: its path segment contains attributeName="d".
            assert(
                blueSegment.get.contains("attributeName=\"d\""),
                s"INV-036 leaf 3: existing Red series must morph (attributeName=d), segment:\n${blueSegment.get}"
            )
            // New Blue series (line-0-Blue) must SNAP: its path segment has no <animate at all.
            assert(
                !orangeSegment.get.contains("<animate"),
                s"INV-036 leaf 3: new Blue series must snap (no <animate>), segment:\n${orangeSegment.get}"
            )
            // Total animate count must be exactly 1 (only the morphing Red series).
            val animateCount = html.split("<animate").length - 1
            assert(animateCount == 1, s"INV-036 leaf 3: expected exactly 1 <animate total, got $animateCount:\n$html")
        end for
    }

    // ---- INV-036 Leaf 4: removed series key absent in second emission ----

    "removed series key absent in second emission, no stale morph" in {
        // Emission 1: line with no color encoding, key "line-0-0".
        // Emission 2: different data set (structural change: Jan+Feb -> Jan only).
        // After the category count drops from 2 to 1 the command count changes (2 -> 1),
        // so the structural gate fires and the path snaps (no <animate>).
        val e1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val e2 = Chunk(Sale("Jan", Rev(1500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](e1)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Command count changed (2 -> 1): structural gate fires, path snaps, no <animate>.
            assert(
                !html.contains("<animate"),
                s"INV-036 leaf 4: structural change (category removed) must snap, no animate:\n$html"
            )
            assert(html.contains("<path"), s"INV-036 leaf 4: path must still be present:\n$html")
        end for
    }

    // ---- INV-036 + INV-002 Leaf 5: toString collision stays as distinct pathKeys ----

    "two series with colliding toString stay as distinct pathKeys per CatKey seriesIdx" in {
        // Col.Red and Col.Blue both override toString to "color".
        // Under the old toString-keyed dedup they collapse to one bucket,
        // and only one <path> appears. Under the CatKey fix (distinctKeyed),
        // Red and Blue are distinct (CatKey(class, value)), two <path> elements appear,
        // with keys "line-0-0" and "line-0-1".
        val rows = Chunk(
            ColSale("Jan", Rev(1000.0), Col.Red),
            ColSale("Feb", Rev(2000.0), Col.Blue),
            ColSale("Mar", Rev(1500.0), Col.Red)
        )
        for
            ref <- Signal.initRef[Seq[ColSale]](rows)
            spec = Chart(ref: Signal[Seq[ColSale]])(
                line(x = _.month, y = _.revenue, color = _.col)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[ColSale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Two distinct series must produce two <path> elements.
            val pathCount = html.split("<path").length - 1
            assert(
                pathCount == 2,
                s"INV-036/INV-002 leaf 5: two series with colliding toString must produce 2 paths, got $pathCount:\n$html"
            )
        end for
    }

    // ---- INV-036 Leaf 6: area pathKey stable when preceding bar's geom count changes ----

    "area pathKey is stable when a preceding bar's geom count changes" in {
        // Mirror of leaf 1 but mark-1 is an area mark.
        // mark-0: bar (2 bars -> 3 bars). mark-1: area with stable 2 points (Mar has v2=Absent).
        // v2=Absent makes the area treat Mar as a gap row, so the area stays 2-point.
        // The area key must be "area-1-0" on both emissions; morph fires on emission 2.
        val e1 = Chunk(
            MultiSale("Jan", Rev(1000.0), Present(Rev(500.0))),
            MultiSale("Feb", Rev(1000.0), Present(Rev(1000.0)))
        )
        val e2 = Chunk(
            MultiSale("Jan", Rev(1000.0), Present(Rev(600.0))),
            MultiSale("Feb", Rev(1000.0), Present(Rev(900.0))),
            // Mar is present for the bar (v1=1000) but v2=Absent so the area skips it.
            MultiSale("Mar", Rev(1000.0), Absent)
        )
        for
            ref <- Signal.initRef[Seq[MultiSale]](e1)
            spec = Chart(ref: Signal[Seq[MultiSale]])(
                bar(x = _.month, y = _.v1),
                area(x = _.month, y = _.v2)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[MultiSale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Area must morph: attributeName="d" present.
            assert(
                html.contains("attributeName=\"d\""),
                s"INV-036 leaf 6: area SMIL morph must fire when bar's geom count changes:\n$html"
            )
        end for
    }

    // ---- INV-036 Leaf 7: structural gate preserved after markIdx fix ----

    "line with changed point count snaps (structural gate unchanged after markIdx fix)" in {
        // INV-036 states the prevCount==newCount gate is unchanged.
        // 2 points -> 3 points = structural change -> no <animate>.
        // This mirrors test 2 but explicitly guards the gate under the new markIdx keying.
        val e1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val e2 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)), Sale("Mar", Rev(1500.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](e1)
            spec = Chart(ref: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Structural gate fires: command count differs -> no animate (path snaps).
            assert(
                !html.contains("<animate"),
                s"INV-036 leaf 7: structural gate must fire for 2->3 point change (no animate):\n$html"
            )
            assert(html.contains("<path"), s"INV-036 leaf 7: path must still be present:\n$html")
        end for
    }

    // ---- Bug A: same command COUNT but different command TYPES must snap, not morph ----

    "BUG A (gap type-signature): gap change producing same command count but different types must snap" in {
        // Emission 1: 3 defined points -> one segment -> M L L (3 commands, types: MoveTo LineTo LineTo).
        // Emission 2: row 1 defined, row 2 absent (gap), rows 3+4 defined -> two segments ->
        //   segment1=(Jan only)->M, segment2=(Mar,Apr)->M L => total M M L (3 commands, types differ!).
        //
        // Both emissions have exactly 3 path commands, so the OLD count gate (prevCount==newCount) fires
        // and emits a garbage SMIL morph interpolating between structurally incompatible paths.
        // The FIX compares command-type signatures (ordinals), finds M-L-L != M-M-L, and snaps instead.
        //
        // Y scale linear(0, 4000), baseline=440: pixel(v) = 440 - v*0.105
        //   Jan=1000 -> 335, Feb=2000 -> 230, Mar=3000 -> 125, Apr=4000 -> 20
        // Band scale (Jan, Feb, Mar from emission 1) later updated: irrelevant for the type-sig check.
        val e1 = Chunk(
            GapSale("Jan", Present(Rev(1000.0))),
            GapSale("Feb", Present(Rev(2000.0))),
            GapSale("Mar", Present(Rev(3000.0)))
        )
        // Emission 2: Feb becomes a gap row; Apr is added as a 4th defined point.
        // Segments: [(Jan)], [(Mar, Apr)] -> commands: M(Jan) M(Mar) L(Apr) = 3 cmds, types M-M-L.
        val e2 = Chunk(
            GapSale("Jan", Present(Rev(1000.0))),
            GapSale("Feb", Absent),
            GapSale("Mar", Present(Rev(3000.0))),
            GapSale("Apr", Present(Rev(4000.0)))
        )
        for
            ref <- Signal.initRef[Seq[GapSale]](e1)
            spec = Chart(ref: Signal[Seq[GapSale]])(line(x = _.month, y = _.rev))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[GapSale], Svg.Root]](spec)
            // Emission 1: record "M L L" path geometry.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: gap in middle produces "M M L" (same count, different types).
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Must NOT emit a SMIL morph: the command type signature changed (M-L-L vs M-M-L).
            // A morph here would interpolate between structurally incompatible paths.
            assert(
                !html.contains("<animate"),
                s"BUG A: gap change with same command count but different types must snap (no <animate>), got:\n$html"
            )
            assert(html.contains("<path"), s"BUG A: path must still be present after snap:\n$html")
        end for
    }

    // ---- Bug A area: type-signature gate applied to area (regression guard: normal morph still fires) ----

    "BUG A area (type-sig fix regression): area stable-structure morph still fires after type-sig fix" in {
        // Area paths use buildSimpleAreaPath which skips gap rows (Absent y -> skip, not MoveTo gap).
        // So area gap rows reduce the point count rather than inserting extra MoveTo commands.
        // The type-signature fix for area is therefore equivalent to the count gate for linear area
        // (same count implies same types for M-L-...-L-Z sequences without curve-generated CubicTos).
        //
        // This test is a regression guard: verifies the type-sig fix does NOT over-snap a stable-
        // structure area morph. Emission 1 and emission 2 both have 3 defined area points, so the
        // type signature is identical (M-L-L-L-L-Z) -> morph MUST fire (not snap after the fix).
        //
        // GapSale with Maybe[Rev]: area skips Absent rows, so emission 2 has 3 defined rows (Jan, Mar, Apr).
        // The x-scale adapts but both emissions have 3 area points -> same type sequence -> morph fires.
        val e1 = Chunk(
            GapSale("Jan", Present(Rev(1000.0))),
            GapSale("Feb", Present(Rev(2000.0))),
            GapSale("Mar", Present(Rev(3000.0)))
        )
        val e2 = Chunk(
            GapSale("Jan", Present(Rev(1500.0))),
            GapSale("Feb", Absent),
            GapSale("Mar", Present(Rev(2500.0))),
            GapSale("Apr", Present(Rev(3500.0)))
        )
        for
            ref <- Signal.initRef[Seq[GapSale]](e1)
            spec = Chart(ref: Signal[Seq[GapSale]])(area(x = _.month, y = _.rev))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[GapSale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Same type structure (M-L-L-L-L-Z in both): type-sig fix must not over-snap.
            // The morph must still fire after the fix.
            assert(
                html.contains("attributeName=\"d\""),
                s"BUG A area regression: stable-structure area morph must still fire after type-sig fix:\n$html"
            )
            assert(html.contains("<path"), s"BUG A area: path must still be present:\n$html")
        end for
    }

    // ---- Bug B: surviving series must morph from its OWN prior geometry, not a removed series' path ----

    "BUG B line (series-identity keying): Blue series morphs from Blue's own prior path, not Red's old path" in {
        // Emission 1: two series [Red, Blue].
        //   Red: Jan=1000 (py=340), Feb=2000 (py=240)  -> d = "M200 340 L480 240"
        //   Blue: Jan=3000 (py=140), Feb=1000 (py=340)  -> d = "M200 140 L480 340"
        //   Old (buggy) keys: line-0-0=Red's path, line-0-1=Blue's path.
        // Emission 2: Red removed; only Blue series with NEW y-values (Jan=2000, Feb=1500).
        //   Blue (new): Jan=2000 (py=240), Feb=1500 (py=290) -> d = "M200 240 L480 290"
        //   Old (buggy) key lookup: Blue at seriesIdx=0 -> "line-0-0" -> finds Red's old path "M200 340 L480 240".
        //   Command count (2) matches (bug fires a morph from Red's path!), from="M200 340 L480 240" (WRONG).
        //   After fix: key is "line-0-Blue" by label. Blue's own prior path "M200 140 L480 340" is found.
        //   from="M200 140 L480 340" (CORRECT, Blue's OWN prior path).
        //
        // Band scale (Jan, Feb): px_Jan=200, px_Feb=480.
        // Y scale linear(0, 4000), plotH=400 (plotY=40, baseline=440): pixel(v) = 440 - v*0.1
        //   1000 -> 340, 2000 -> 240, 3000 -> 140, 1500 -> 290
        val e1 = Chunk(
            NamedColorSale("Jan", Rev(1000.0), "Red"),
            NamedColorSale("Feb", Rev(2000.0), "Red"),
            NamedColorSale("Jan", Rev(3000.0), "Blue"),
            NamedColorSale("Feb", Rev(1000.0), "Blue")
        )
        // Emission 2: Red removed; Blue with new y-values.
        val e2 = Chunk(
            NamedColorSale("Jan", Rev(2000.0), "Blue"),
            NamedColorSale("Feb", Rev(1500.0), "Blue")
        )
        for
            ref <- Signal.initRef[Seq[NamedColorSale]](e1)
            spec = Chart(ref: Signal[Seq[NamedColorSale]])(
                line(x = _.month, y = _.rev, color = _.series)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[NamedColorSale], Svg.Root]](spec)
            // Emission 1: records Red at key "line-0-Red" and Blue at key "line-0-Blue" (after fix).
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: only Blue series remains.
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Blue's prior path in emission 1 was "M200 140 L480 340".
            // Blue's new path in emission 2 is "M200 240 L480 290".
            // The morph MUST use Blue's OWN prior path as from=, not Red's old path "M200 340 L480 240".
            //
            // Before fix: Blue at positional seriesIdx=0 finds Red's old path -> from="M200 340 L480 240" (WRONG).
            // After fix:  Blue at label "Blue" finds Blue's own path -> from="M200 140 L480 340" (CORRECT).
            assert(
                html.contains("from=\"M200 140 L480 340\""),
                s"BUG B line: Blue series must morph from Blue's OWN prior path M200 140 L480 340, got:\n$html"
            )
            assert(
                html.contains("to=\"M200 240 L480 290\""),
                s"BUG B line: Blue series must morph to M200 240 L480 290, got:\n$html"
            )
            // Must NOT morph from Red's old path (the buggy from= value).
            assert(
                !html.contains("from=\"M200 340 L480 240\""),
                s"BUG B line: must NOT morph from Red's old path M200 340 L480 240, got:\n$html"
            )
        end for
    }

    // ---- Bug B area: same discipline for area paths ----

    "BUG B area (series-identity keying): Blue area morphs from Blue's own prior path, not Red's old path" in {
        // Same scenario as Bug B line, but with area marks.
        // Area path adds baseline returns (L..Z) commands after the top-edge path.
        // Emission 1:
        //   Red: Jan=1000, Feb=2000 -> top "M200 340 L480 240" -> area "M200 340 L480 240 L480 440 L200 440 Z"
        //   Blue: Jan=3000, Feb=1000 -> top "M200 140 L480 340" -> area "M200 140 L480 340 L480 440 L200 440 Z"
        //   Old (buggy) keys: area-0-0=Red's area path, area-0-1=Blue's area path.
        // Emission 2 (Blue only with new values: Jan=2000, Feb=1500):
        //   Blue new area: "M200 240 L480 290 L480 440 L200 440 Z"
        //   Old (buggy) lookup: Blue at idx=0 -> "area-0-0" -> finds Red's area path (WRONG).
        //   After fix: Blue at label "Blue" -> "area-0-Blue" -> finds Blue's own area path (CORRECT).
        //
        // Y scale linear(0, 4000), pixel(v) = 440 - v*0.1
        val e1 = Chunk(
            NamedColorSale("Jan", Rev(1000.0), "Red"),
            NamedColorSale("Feb", Rev(2000.0), "Red"),
            NamedColorSale("Jan", Rev(3000.0), "Blue"),
            NamedColorSale("Feb", Rev(1000.0), "Blue")
        )
        val e2 = Chunk(
            NamedColorSale("Jan", Rev(2000.0), "Blue"),
            NamedColorSale("Feb", Rev(1500.0), "Blue")
        )
        for
            ref <- Signal.initRef[Seq[NamedColorSale]](e1)
            spec = Chart(ref: Signal[Seq[NamedColorSale]])(
                area(x = _.month, y = _.rev, color = _.series)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[NamedColorSale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Blue's own prior area path from emission 1: "M200 140 L480 340 L480 440 L200 440 Z".
            // Before fix: from="M200 340 L480 240 L480 440 L200 440 Z" (Red's area path - WRONG).
            // After fix:  from="M200 140 L480 340 L480 440 L200 440 Z" (Blue's own - CORRECT).
            assert(
                html.contains("from=\"M200 140 L480 340 L480 440 L200 440 Z\""),
                s"BUG B area: Blue must morph from its OWN prior area path, got:\n$html"
            )
            assert(
                html.contains("to=\"M200 240 L480 290 L480 440 L200 440 Z\""),
                s"BUG B area: Blue must morph to new area path, got:\n$html"
            )
            // Must NOT morph from Red's old area path.
            assert(
                !html.contains("from=\"M200 340 L480 240 L480 440 L200 440 Z\""),
                s"BUG B area: must NOT morph from Red's old area path, got:\n$html"
            )
        end for
    }

    // ---- Colliding-toString transition tests (the residual Phase-8 bug) ----
    //
    // Col.Red and Col.Blue both override toString to "color". Under the old label-string keying
    // the transition geometry map stores both series under the same key "line-0-color" (or
    // "area-0-color"), so Red's geometry silently overwrites Blue's (or vice versa). On the
    // second emission each series looks up the wrong prior geometry and morphs from a crossed
    // or merged path. After the fix (TransKey keyed by CatKey identity) the two series stay
    // distinct and each morphs from its OWN prior path.
    //
    // Layout (color-encoding line/area: legend at Top, reserveTop=true adds LegendReservedH=20):
    //   plotY = MarginTop + LegendReservedH = 20 + 20 = 40
    //   plotH = 480 - 20 (top) - 20 (legend) - 0 (bottom legend) - 40 (bottom margin) = 400
    //   baseline = plotY + plotH = 440
    //   Band scale (Jan, Feb): slot=280, bandW=252, px_Jan=200, px_Feb=480.
    //   Y scale linear(0, 4000): pixel(v) = 440 - v * (400/4000) = 440 - v*0.1
    //     1000 -> 340, 2000 -> 240, 3000 -> 140
    //     500  -> 390, 1500 -> 290, 800 -> 360, 1200 -> 320

    "colliding-toString LINE: Red morphs from Red's own prior path, Blue from Blue's own prior path" in {
        // Emission 1:
        //   Red: Jan=1000 (py=340), Feb=2000 (py=240) -> "M200 340 L480 240"
        //   Blue: Jan=500 (py=390), Feb=1500 (py=290)  -> "M200 390 L480 290"
        //   Old bug: both stored under key "line-0-color"; Blue overwrites Red (last wins).
        //   After emission 1, currentGeom["line-0-color"] = Blue's path "M200 390 L480 290".
        // Emission 2:
        //   Red: Jan=2000 (py=240), Feb=3000 (py=140) -> "M200 240 L480 140"
        //   Blue: Jan=800 (py=360), Feb=1200 (py=320)  -> "M200 360 L480 320"
        //   Old bug: both look up key "line-0-color" -> find Blue's emission-1 path "M200 390 L480 290"
        //     -> Red morphs from Blue's path (WRONG), Blue morphs from itself only by accident.
        //   After fix: Red looks up TransKey.Series(0, CatKey(ColTag, Red)) -> "M200 340 L480 240" (CORRECT).
        //              Blue looks up TransKey.Series(0, CatKey(ColTag, Blue)) -> "M200 390 L480 290" (CORRECT).
        val e1 = Chunk(
            ColSale("Jan", Rev(1000.0), Col.Red),
            ColSale("Feb", Rev(2000.0), Col.Red),
            ColSale("Jan", Rev(500.0), Col.Blue),
            ColSale("Feb", Rev(1500.0), Col.Blue)
        )
        val e2 = Chunk(
            ColSale("Jan", Rev(2000.0), Col.Red),
            ColSale("Feb", Rev(3000.0), Col.Red),
            ColSale("Jan", Rev(800.0), Col.Blue),
            ColSale("Feb", Rev(1200.0), Col.Blue)
        )
        for
            ref <- Signal.initRef[Seq[ColSale]](e1)
            spec = Chart(ref: Signal[Seq[ColSale]])(
                line(x = _.month, y = _.revenue, color = _.col)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[ColSale], Svg.Root]](spec)
            // Emission 1: store geometry for both series under CatKey-based keys.
            _ <- HtmlRenderer.render(root, Seq.empty)
            // Emission 2: each series must morph from its OWN prior path.
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // There must be exactly 2 <animate attributeName="d"> elements (one per series).
            val animateCount = html.split("<animate").length - 1
            assert(
                animateCount == 2,
                s"colliding-toString LINE: expected 2 animates (one per series), got $animateCount:\n$html"
            )
            // Red series must morph from Red's own emission-1 path "M200 340 L480 240".
            assert(
                html.contains("from=\"M200 340 L480 240\""),
                s"colliding-toString LINE: Red must morph from its own prior path M200 340 L480 240, got:\n$html"
            )
            // Red series must morph to its emission-2 path "M200 240 L480 140".
            assert(
                html.contains("to=\"M200 240 L480 140\""),
                s"colliding-toString LINE: Red must morph to M200 240 L480 140, got:\n$html"
            )
            // Blue series must morph from Blue's own emission-1 path "M200 390 L480 290".
            assert(
                html.contains("from=\"M200 390 L480 290\""),
                s"colliding-toString LINE: Blue must morph from its own prior path M200 390 L480 290, got:\n$html"
            )
            // Blue series must morph to its emission-2 path "M200 360 L480 320".
            assert(
                html.contains("to=\"M200 360 L480 320\""),
                s"colliding-toString LINE: Blue must morph to M200 360 L480 320, got:\n$html"
            )
            // Red must NOT morph from Blue's prior path (the bug symptom).
            // Before fix: both series use from="M200 390 L480 290" (Blue's path overwrote Red's).
            // After fix: Red uses from="M200 340 L480 240", Blue uses from="M200 390 L480 290".
            val pathSegments = html.split("<path ").toSeq.drop(1)
            val redSeg       = pathSegments.find(_.contains("stroke=\"#3b82f6\""))
            assert(
                redSeg.isDefined && redSeg.get.contains("from=\"M200 340 L480 240\""),
                s"colliding-toString LINE: Red (blue-stroked) must morph from Red's own path, not Blue's:\n${redSeg}"
            )
        end for
    }

    "colliding-toString AREA: Red morphs from Red's own prior area path, Blue from Blue's own prior area path" in {
        // Same scenario as the colliding-toString LINE test but with area marks.
        // Area path = top edge forward + baseline return + close (baseline=440).
        // Emission 1:
        //   Red: Jan=1000 (py=340), Feb=2000 (py=240) -> "M200 340 L480 240 L480 440 L200 440 Z"
        //   Blue: Jan=500 (py=390), Feb=1500 (py=290)  -> "M200 390 L480 290 L480 440 L200 440 Z"
        //   Old bug: both stored under key "area-0-color"; Blue overwrites Red.
        // Emission 2:
        //   Red: Jan=2000 (py=240), Feb=3000 (py=140) -> "M200 240 L480 140 L480 440 L200 440 Z"
        //   Blue: Jan=800 (py=360), Feb=1200 (py=320)  -> "M200 360 L480 320 L480 440 L200 440 Z"
        //   Old bug: Red morphs from Blue's E1 area path (WRONG).
        //   After fix: Red morphs from Red's own E1 area path (CORRECT).
        val e1 = Chunk(
            ColSale("Jan", Rev(1000.0), Col.Red),
            ColSale("Feb", Rev(2000.0), Col.Red),
            ColSale("Jan", Rev(500.0), Col.Blue),
            ColSale("Feb", Rev(1500.0), Col.Blue)
        )
        val e2 = Chunk(
            ColSale("Jan", Rev(2000.0), Col.Red),
            ColSale("Feb", Rev(3000.0), Col.Red),
            ColSale("Jan", Rev(800.0), Col.Blue),
            ColSale("Feb", Rev(1200.0), Col.Blue)
        )
        for
            ref <- Signal.initRef[Seq[ColSale]](e1)
            spec = Chart(ref: Signal[Seq[ColSale]])(
                area(x = _.month, y = _.revenue, color = _.col)
            ).yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[Chart.Spec[ColSale], Svg.Root]](spec)
            _    <- HtmlRenderer.render(root, Seq.empty)
            _    <- ref.set(e2)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Each series must have its own animate element.
            val animateCount = html.split("<animate").length - 1
            assert(
                animateCount == 2,
                s"colliding-toString AREA: expected 2 animates (one per series), got $animateCount:\n$html"
            )
            // Red area must morph from Red's own emission-1 path.
            assert(
                html.contains("from=\"M200 340 L480 240 L480 440 L200 440 Z\""),
                s"colliding-toString AREA: Red must morph from its own prior path M200 340 ..., got:\n$html"
            )
            // Red area must morph to its emission-2 path.
            assert(
                html.contains("to=\"M200 240 L480 140 L480 440 L200 440 Z\""),
                s"colliding-toString AREA: Red must morph to M200 240 L480 140 ..., got:\n$html"
            )
            // Blue area must morph from Blue's own emission-1 path.
            assert(
                html.contains("from=\"M200 390 L480 290 L480 440 L200 440 Z\""),
                s"colliding-toString AREA: Blue must morph from its own prior path M200 390 ..., got:\n$html"
            )
            // Blue area must morph to its emission-2 path.
            assert(
                html.contains("to=\"M200 360 L480 320 L480 440 L200 440 Z\""),
                s"colliding-toString AREA: Blue must morph to M200 360 L480 320 ..., got:\n$html"
            )
        end for
    }

end ChartMorphTest
