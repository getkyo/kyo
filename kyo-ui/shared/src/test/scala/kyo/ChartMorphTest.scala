package kyo

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
  *   child. `AnimateConfig.morphSteps` is reserved for a future effectful chart mount API.
  *
  * Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420, baseline=440
  * (chart 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  *
  * Band scale (2 categories, padding=0.1):
  *   slot=280, bandW=252
  *   px_Jan = 60 + 0*280 + (280-252)/2 = 60 + 14 = 74
  *   px_Feb = 60 + 1*280 + (280-252)/2 = 60 + 294 = 354
  *
  * Y scale linear(0,4000), baseline=440, top=20: pixel(v) = 440 - v*0.105
  *   1000 -> 335,  2000 -> 230,  3000 -> 125,  500 -> 387.5
  *
  * Band scale (3 categories: Jan, Feb, Mar):
  *   slot = 560/3 ~= 186.667, bandW = 560*0.9/3 = 168
  *   px_Jan = 60 + (186.667-168)/2 = 60 + 9.333 = 69.333...
  *   (exact: 60 + (560/3 - 560*0.9/3) / 2 = 60 + 560*0.1/6 = 60 + 56/6 = 60 + 28/3)
  *
  * The seven tests cover:
  *   1. LINE same-structure update: emits exactly ONE `<animate attributeName="d">` with the correct
  *      `from` (initial path string) and `to` (updated path string).
  *   2. LINE structural change (category added): emits NO `<animate>` (snap).
  *   3. `.animate(_.none)`: emits no `<animate>` even for a same-structure update.
  *   4. AREA same-structure update: emits exactly ONE `<animate attributeName="d">` on the area path.
  *   5. Double-pull idempotency (line): two renders of the same emission reproduce the same from/to.
  *   6. Double-pull idempotency (area): same discipline for area paths.
  *   7. No animate on the FIRST emission (no previous path stored yet).
  */
class ChartMorphTest extends Test:

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

    // ---- Test 1: LINE same-structure update emits a SMIL d-animate ----

    "LINE same-structure update emits exactly one animate attributeName=d with correct from/to" in run {
        // Jan and Feb are present in both emissions: command count is 2 (M + L) before and after.
        // from = initial path d string, to = updated path d string.
        //
        // Initial: Jan=1000 py=335, Feb=2000 py=230  -> d = "M74 335 L354 230"
        // Updated: Jan=3000 py=125, Feb=500  py=387.5 -> d = "M74 125 L354 387.5"
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef(initial)
            spec = Chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
            // The from string must be the rendered d of the initial path.
            assert(
                html.contains("from=\"M74 335 L354 230\""),
                s"Expected from=M74 335 L354 230:\n$html"
            )
            // The to string must be the rendered d of the updated path.
            assert(
                html.contains("to=\"M74 125 L354 387.5\""),
                s"Expected to=M74 125 L354 387.5:\n$html"
            )
        end for
    }

    // ---- Test 2: LINE structural change (category added) snaps, no animate ----

    "LINE structural change (category added) snaps: zero animate elements" in run {
        // Initial: 2 categories (M + L = 2 commands). Updated: 3 categories (M + L + L = 3 commands).
        // Command counts differ -> structural morph -> path snaps, no <animate> emitted.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)), Sale("Mar", Rev(1500.0)))
        for
            ref <- Signal.initRef(initial)
            spec = Chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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

    "animate(_.none): no animate element even for same-structure update" in run {
        // Same categories before and after, but animation disabled.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef(initial)
            spec = Chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.none)
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html0 <- HtmlRenderer.render(root, Seq.empty)
            _     <- ref.set(updated)
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html0.contains("<animate"), s"Expected no <animate (anim disabled, initial):\n$html0")
            assert(!html1.contains("<animate"), s"Expected no <animate (anim disabled, updated):\n$html1")
        end for
    }

    // ---- Test 4: AREA same-structure update emits a SMIL d-animate ----

    "AREA same-structure update emits animate attributeName=d with correct from/to" in run {
        // Area mark with stable categories. The area path is a closed polygon:
        // top forward (2 pts), then baseline back (2 pts), then close = M+L+L+L+Z = 5 commands.
        //
        // Initial: Jan=1000 py=335, Feb=2000 py=230, baseline=440
        //   top:      M74 335 L354 230
        //   baseline: L354 440 L74 440 Z
        //   -> "M74 335 L354 230 L354 440 L74 440 Z"
        // Updated: Jan=3000 py=125, Feb=500 py=387.5
        //   -> "M74 125 L354 387.5 L354 440 L74 440 Z"
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val updated = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef(initial)
            spec = Chart(ref: Signal[Chunk[Sale]])(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
                html.contains("from=\"M74 335 L354 230 L354 440 L74 440 Z\""),
                s"Expected area from path:\n$html"
            )
            assert(
                html.contains("to=\"M74 125 L354 387.5 L354 440 L74 440 Z\""),
                s"Expected area to path:\n$html"
            )
        end for
    }

    // ---- Test 5: Double-pull idempotency (line) ----

    "LINE double-pull idempotency: repeat render of same emission reproduces the same from/to d" in run {
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
            ref <- Signal.initRef(r1)
            spec = Chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
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
                html2a.contains("from=\"M74 335 L354 230\""),
                s"R2 pull 1: expected from=R1 path:\n$html2a"
            )
            assert(
                html2a.contains("to=\"M74 125 L354 387.5\""),
                s"R2 pull 1: expected to=R2 path:\n$html2a"
            )
            // R2 pull 2: same from/to (idempotent double-pull).
            assert(
                html2b.contains("from=\"M74 335 L354 230\""),
                s"R2 pull 2: expected same from=R1 path (idempotent):\n$html2b"
            )
            assert(
                html2b.contains("to=\"M74 125 L354 387.5\""),
                s"R2 pull 2: expected same to=R2 path (idempotent):\n$html2b"
            )
            // Must NOT produce a dead animation from==to.
            assert(
                !html2b.contains("from=\"M74 125 L354 387.5\" to=\"M74 125 L354 387.5\""),
                s"R2 pull 2: must not produce dead animation from==to:\n$html2b"
            )
        end for
    }

    // ---- Test 6: Double-pull idempotency (area) ----

    "AREA double-pull idempotency: repeat render of same emission reproduces the same from/to d" in run {
        // Same idempotency discipline as the line test, applied to area paths.
        val r1 = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val r2 = Chunk(Sale("Jan", Rev(3000.0)), Sale("Feb", Rev(500.0)))
        for
            ref <- Signal.initRef(r1)
            spec = Chart(ref: Signal[Chunk[Sale]])(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html1a <- HtmlRenderer.render(root, Seq.empty)
            html1b <- HtmlRenderer.render(root, Seq.empty)
            _      <- ref.set(r2)
            html2a <- HtmlRenderer.render(root, Seq.empty)
            html2b <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html1a.contains("<animate"), s"R1 pull 1: no animate:\n$html1a")
            assert(!html1b.contains("<animate"), s"R1 pull 2: no animate:\n$html1b")
            assert(
                html2a.contains("from=\"M74 335 L354 230 L354 440 L74 440 Z\""),
                s"R2 pull 1: expected from=area R1 path:\n$html2a"
            )
            assert(
                html2b.contains("from=\"M74 335 L354 230 L354 440 L74 440 Z\""),
                s"R2 pull 2: expected same from (idempotent):\n$html2b"
            )
            assert(
                !html2b.contains("from=\"M74 125 L354 387.5 L354 440 L74 440 Z\" to=\"M74 125 L354 387.5 L354 440 L74 440 Z\""),
                s"R2 pull 2: must not produce dead animation:\n$html2b"
            )
        end for
    }

    // ---- Test 7: No animate on the first emission (no previous path stored) ----

    "first emission with animation enabled emits no animate (no previous path exists yet)" in run {
        // The very first render of a live chart has no previous PathData in the TransState.
        // No animate child should be emitted.
        val initial = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        for
            ref <- Signal.initRef(initial)
            spec = Chart(ref: Signal[Chunk[Sale]])(line(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
                .animate(_.ease(300.millis))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<path"), s"Expected <path in first render:\n$html")
            assert(!html.contains("<animate"), s"Expected no <animate on first emission:\n$html")
        end for
    }

end ChartMorphTest
