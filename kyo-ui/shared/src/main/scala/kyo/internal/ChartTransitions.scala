package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** SMIL animate element assembly and keyed-transition orchestration for live charts.
  *
  * Provides `MarkGeom` (the per-key geometry snapshot), `TransKey` (the per-key identity discriminator),
  * `TransState` (the three-slot animation-state carrier), and the family of transition-aware mark lowerers
  * (`lowerBarSimpleWithTransitions`, `lowerLineWithTransitions`, `lowerAreaWithTransitions`) that emit
  * declarative SMIL children for bars, lines, and areas. The entry point for the reactive rendering engine
  * is `marksRegionWithTransitions`, which reads and writes the chart-private `AtomicRef.Unsafe[TransState]`
  * held in `lowerLive`.
  */
private[kyo] object ChartTransitions:

    import ChartLayout.Layout

    /** Per-key geometry captured from one render frame.
      *
      * `Bar` stores the scaled height and y-coordinate so the next render can compute SMIL `from`/`to`
      * values for bar SMIL transitions.
      *
      * `LinePath` stores the full `PathData` produced by the previous emission. On the next emission,
      * if the new path has the same command count as the stored one (a structural match: same number
      * of MoveTo/LineTo/Close in the same order), a declarative SMIL `animate` is emitted on the `d`
      * attribute with `from` = the previous rendered `d` string and `to` = the new one. The browser
      * drives the interpolation; no fiber is required, which fits the pure `Svg.Root` lowering.
      *
      * If the command counts DIFFER (e.g. a category was added or removed), the path SNAPS with no
      * animate child. This is a documented v1 limitation: a structural path morph requires a bounded
      * stepped-interpolation fiber that can only be launched from an effectful mount hook, which the
      * pure `Svg.Root` lowering does not provide.
      */
    sealed private[kyo] trait MarkGeom
    private[kyo] object MarkGeom:
        final case class Bar(height: Double, y: Double)   extends MarkGeom
        final case class LinePath(pathData: Svg.PathData) extends MarkGeom
    end MarkGeom

    /** Key type for the transition geometry maps (`fromGeom` / `currentGeom`).
      *
      * Keys transition geometry by value identity rather than by a display-label string, so two distinct
      * color categories whose `toString` collides (e.g. both return `"color"`) stay distinct and never
      * overwrite each other's stored geometry:
      *
      *   - `BarSlot(rowKey)`: a simple bar row, keyed by the x-domain string. The bar transition
      *     uses `rowKey(spec, mark, row)` (x-domain string); `BarSlot` wraps it so it
      *     never collides with a line/area key even if the strings are identical.
      *   - `Series(markIdx, cat)`: a line or area color-category series keyed by `(markIdx, CatKey)`.
      *     `CatKey` pairs the raw value with its compile-time-derived `ConcreteTag`, so two enum cases
      *     with identical `toString` stay distinct.
      *   - `SingleSeries(markIdx)`: a line or area with NO `color` encoding (single-path series). Uses
      *     only `markIdx` as the key, so it cannot collide with any user-supplied category key.
      */
    private[kyo] enum TransKey derives CanEqual:
        case BarSlot(rowKey: String)
        case Series(markIdx: Int, cat: ChartFoundations.CatKey)
        case SingleSeries(markIdx: Int)
    end TransKey

    /** Three-slot transition state for a live chart, held in a chart-private `AtomicRef.Unsafe`.
      *
      * Slots:
      *   - `lastRows`: the row chunk used in the most-recently-committed render.
      *   - `fromGeom`: the geometry that was current BEFORE the last row change (the animation origin).
      *   - `currentGeom`: the geometry produced BY the last row change (the animation target).
      *
      * The three-slot design makes the render projection idempotent across repeated pulls of the same
      * emission. On each call to `marksRegionWithTransitions(rows)`:
      *   - if `rows == lastRows` (repeat pull of the same emission): use the stored `fromGeom` and
      *     `currentGeom` unchanged; do NOT write the ref. Every repeat pull reproduces the same
      *     from-to pair.
      *   - else (a genuinely new emission): compute `newGeom` from the incoming rows; write
      *     `TransState(rows, fromGeom = currentGeom, currentGeom = newGeom)`. This write happens
      *     exactly once per distinct emission regardless of how many times the engine re-pulls it.
      *
      * Invariant: writes occur only when `rows != lastRows`. Repeated pulls of one emission are
      * therefore idempotent: they always see the same stable `fromGeom`/`currentGeom` pair and
      * produce identical SVG output. Writes are serialized by the reactive engine's single-threaded
      * emission model, so no concurrent-write hazard exists.
      */
    final private[kyo] case class TransState[A](
        lastRows: Chunk[A],
        fromGeom: Map[TransKey, MarkGeom],
        currentGeom: Map[TransKey, MarkGeom]
    )

    private[kyo] object TransState:
        /** Initial state: empty sentinel last-rows so the first genuine emission is always treated as new.
          *
          * Both geometry maps are empty, so every key in the first emission is an ENTER (animates from
          * the baseline). Repeat pulls of that first emission compare `rows == Chunk.empty` and reuse
          * the stored from/to, which is the correct stable ENTER animation.
          */
        def empty[A]: TransState[A] = TransState(Chunk.empty[A], Map.empty, Map.empty)
    end TransState

    /** Compute the string key for a row in a given mark, using the spec's key function or falling back to
      * the x encoding.
      *
      * Default: the x encoding's domain string (`domainKey`). Override: `spec.key` when `Present`.
      */
    private def rowKey[A](spec: Chart[A], mark: Mark[A], row: A): String =
        spec.key match
            case Present(kf) => kf(row)
            case Absent =>
                mark match
                    case m: Mark.Bar[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case m: Mark.Line[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case m: Mark.Area[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case m: Mark.Point[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case m: Mark.Text[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case m: Mark.ErrorBar[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => ChartScales.domainKey(d)
                            case Absent     => ""
                    case _: Mark.Rule[A] => ""
    end rowKey

    /** Format a `Duration` as a CSS/SMIL duration string (e.g. "0.3s"). */
    private def formatDur(d: Duration): String =
        val ms = d.toMillis
        if ms % 1000 == 0 then s"${ms / 1000}s"
        else s"${ms / 1000.0}s"
    end formatDur

    /** Cubic Bezier control points for the easeInOutCubic timing curve, in SMIL `keySplines` form
      * (`x1 y1 x2 y2`). With `calcMode="spline"` + `keyTimes="0;1"` this eases the single from->to segment
      * so the transition accelerates out of the start and decelerates into the end instead of moving
      * linearly. Matches the `AnimateConfig` scaladoc's documented ease-in-out-cubic curve.
      */
    private val EaseInOutCubicSplines = "0.645 0.045 0.355 1"

    /** Build one SMIL `Svg.Animate` child that animates a single numeric attribute over the config duration.
      *
      * Uses `begin("indefinite")` so the animation does NOT auto-play against the shared SVG document
      * timeline (which would make any post-load reactive update snap straight to the frozen `to` value,
      * since `begin="0s"` resolves to page-load time). Instead the reactive runtime calls `beginElement()`
      * on each freshly-inserted `<animate>` after a mount/patch (DomBackend.beginAnimationsSync and the
      * server client-script `ba(...)`), which starts the tween relative to insertion time. `repeatCount("1")`
      * plays it once. No `url(#id)` references are emitted.
      */
    private def smilAnimate(attributeName: String, from: Double, to: Double, dur: String)(using Frame): Svg.Animate =
        Svg.animate
            .attributeName(attributeName)
            .from(from)
            .to(to)
            .dur(dur)
            .calcMode(Svg.CalcMode.Spline)
            .keyTimes("0;1")
            .keySplines(EaseInOutCubicSplines)
            .begin("indefinite")
            .repeatCount("1")
    end smilAnimate

    /** Build one SMIL `Svg.Animate` child that animates the `d` path attribute using string `from`/`to`.
      *
      * Used for the declarative line/area path morph: when the previous and new paths have the same
      * command structure (same count and types), the browser interpolates the vertex coordinates.
      * No `url(#id)` references are emitted.
      */
    private def smilAnimatePath(fromD: String, toD: String, dur: String)(using Frame): Svg.Animate =
        Svg.animate
            .attributeName("d")
            .from(fromD)
            .to(toD)
            .dur(dur)
            .calcMode(Svg.CalcMode.Spline)
            .keyTimes("0;1")
            .keySplines(EaseInOutCubicSplines)
            .begin("indefinite")
            .repeatCount("1")
    end smilAnimatePath

    /** Render a `PathData` to its SVG `d` attribute string, using the same format as `HtmlRenderer`.
      *
      * The result is identical to what `HtmlRenderer` would write for `svgAttr(sb, "d", ...)` so the
      * SMIL `from`/`to` strings round-trip correctly through the browser's SMIL engine. Integers are
      * rendered without a decimal point; fractions use the shortest representation.
      *
      * Used for path-morph SMIL `animate` children: the previous path's rendered string becomes `from`
      * and the new path's rendered string becomes `to`.
      */
    private def renderPathDataStr(d: Svg.PathData): String =
        def fmtD(v: Double): String = NumberFormat.double(v)
        def cmd(c: Svg.PathCommand): String = c match
            case Svg.PathCommand.MoveTo(x, y)   => s"M${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.MoveBy(dx, dy) => s"m${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.LineTo(x, y)   => s"L${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.LineBy(dx, dy) => s"l${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.HLineTo(x)     => s"H${fmtD(x)}"
            case Svg.PathCommand.HLineBy(dx)    => s"h${fmtD(dx)}"
            case Svg.PathCommand.VLineTo(y)     => s"V${fmtD(y)}"
            case Svg.PathCommand.VLineBy(dy)    => s"v${fmtD(dy)}"
            case Svg.PathCommand.CubicTo(c1x, c1y, c2x, c2y, x, y) =>
                s"C${fmtD(c1x)} ${fmtD(c1y)} ${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.CubicBy(c1x, c1y, c2x, c2y, dx, dy) =>
                s"c${fmtD(c1x)} ${fmtD(c1y)} ${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.SmoothCubicTo(c2x, c2y, x, y) =>
                s"S${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.SmoothCubicBy(c2x, c2y, dx, dy) =>
                s"s${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.QuadTo(cx, cy, x, y)   => s"Q${fmtD(cx)} ${fmtD(cy)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.QuadBy(cx, cy, dx, dy) => s"q${fmtD(cx)} ${fmtD(cy)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.SmoothQuadTo(x, y)     => s"T${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.SmoothQuadBy(dx, dy)   => s"t${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.ArcTo(rx, ry, xRot, largeArc, sweep, x, y) =>
                val la = if largeArc then 1 else 0
                val sw = if sweep then 1 else 0
                s"A${fmtD(rx)} ${fmtD(ry)} ${fmtD(xRot)} $la $sw ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.ArcBy(rx, ry, xRot, largeArc, sweep, dx, dy) =>
                val la = if largeArc then 1 else 0
                val sw = if sweep then 1 else 0
                s"a${fmtD(rx)} ${fmtD(ry)} ${fmtD(xRot)} $la $sw ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.Close => "Z"
        Svg.PathData.commands(d).map(cmd).mkString(" ")
    end renderPathDataStr

    /** Attach a SMIL path-morph animate to `rawPath` when the previous and new paths have the same
      * command-type signature and animation is enabled; otherwise return `rawPath` unchanged.
      *
      * Used by both `lowerLineWithTransitions` and `lowerAreaWithTransitions` to avoid duplicating the
      * prevSig/newSig comparison and smilAnimatePath call. `animOk` is checked first so callers do not
      * need to guard the call themselves.
      */
    private def morphedPath(
        rawPath: Svg.Path,
        fromGeom: Map[TransKey, MarkGeom],
        transKey: TransKey,
        newPd: Svg.PathData,
        animOk: Boolean,
        durStr: String
    )(using Frame): Svg.SvgElement =
        if !animOk then rawPath
        else
            Maybe.fromOption(fromGeom.get(transKey)) match
                case Present(MarkGeom.LinePath(prevPd)) =>
                    // Compare command-type signatures (ordered ordinals), not just counts.
                    // M-L-L and M-M-L both have 3 commands but different ordinals: the count
                    // gate alone would wrongly morph between structurally incompatible paths.
                    val prevSig = Svg.PathData.commands(prevPd).map(_.ordinal)
                    val newSig  = Svg.PathData.commands(newPd).map(_.ordinal)
                    if prevSig == newSig && prevSig.nonEmpty then
                        val fromD = renderPathDataStr(prevPd)
                        val toD   = renderPathDataStr(newPd)
                        rawPath(smilAnimatePath(fromD, toD, durStr))
                    else rawPath
                    end if
                case Absent | Present(_: MarkGeom.Bar) => rawPath
    end morphedPath

    /** Lower a simple bar mark with keyed enter/update SMIL transitions.
      *
      * For each row:
      *   - UPDATE (key in `fromGeom`): emit a rect with two `Svg.Animate` children: one for `height`
      *     (from previous height to new) and one for `y` (from previous y to new).
      *   - ENTER (key absent in `fromGeom`): emit a rect with two `Svg.Animate` children where `from`
      *     is the baseline (height=0, y=baseline), animating from the baseline up to the new position.
      *   - DISABLED (animation not enabled): emit the rect with no animate children.
      *
      * Accumulates the new per-key `MarkGeom.Bar` into `newGeom` and returns it alongside the shapes.
      * `fromGeom` is the stable animation-origin map for this emission (pre-computed by the caller from
      * the `TransState`; not modified here).
      */
    private def lowerBarSimpleWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        xs: Scale,
        ys: Scale,
        defaultFill: Style.Color,
        spec: Chart[A],
        fromGeom: Map[TransKey, MarkGeom],
        newGeom: Map[TransKey, MarkGeom],
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[ChartInteraction.Highlight[A]] = Absent
    )(using Frame): (Chunk[Svg.SvgElement], Map[TransKey, MarkGeom]) =
        // Use ys.apply(0) as the zero-line baseline (same reason as lowerBarSimple).
        val baseline = ys.apply(Domain.Continuous(0.0))
        val durStr   = formatDur(spec.animateCfg.duration)
        val animOk   = spec.animateCfg.enabled
        @scala.annotation.tailrec
        def loop(
            i: Int,
            acc: Chunk[(A, Svg.SvgElement)],
            geom: Map[TransKey, MarkGeom],
            labels: Chunk[Svg.SvgElement]
        ): (Chunk[(A, Svg.SvgElement)], Map[TransKey, MarkGeom], Chunk[Svg.SvgElement]) =
            if i >= rows.size then (acc, geom, labels)
            else
                val row     = rows(i)
                val yDomain = mark.y.plottable.toDomain(mark.y.accessor(row))
                val nextResult = yDomain match
                    case Absent => (acc, geom, labels)
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => (acc, geom, labels)
                            case Present(xd) =>
                                val barX = xs.apply(xd)
                                val barW = xs.bandwidth
                                val barY = ys.apply(yd)
                                // min/abs ensure a non-negative rect height for negative data values.
                                val rectY    = math.min(barY, baseline)
                                val rectH    = math.abs(baseline - barY)
                                val transKey = TransKey.BarSlot(rowKey(spec, mark, row))
                                val newG2    = geom.updated(transKey, MarkGeom.Bar(rectH, rectY))
                                val iAttrs   = ChartInteraction.buildInteractionAttrs(row, spec, internalHoverRef)
                                val baseRect =
                                    Svg.rect.x(barX).y(rectY).width(barW).height(rectH).fill(Svg.Paint.Color(defaultFill)).withAttrs(iAttrs)
                                val (decoratedRect, labelEls) =
                                    ChartMarks.applyBarEncodings(baseRect, mark, row, barX, barW, rectY, defaultFill)
                                val r: Svg.SvgElement =
                                    if !animOk then decoratedRect
                                    else
                                        val (fromH, fromY) = Maybe.fromOption(fromGeom.get(transKey)) match
                                            case Present(MarkGeom.Bar(ph, py)) => (ph, py)
                                            case _                             => (0.0, baseline) // enter from baseline
                                        // `decoratedRect` is a `Svg.Rect` (applyBarEncodings returns the rect with
                                        // opacity/title applied), so the SMIL animate children attach directly.
                                        // Child order: tooltip (<title>) added first by applyBarEncodings, animates
                                        // follow: [<title>, <animate height>, <animate y>].
                                        decoratedRect(
                                            smilAnimate("height", fromH, rectH, durStr),
                                            smilAnimate("y", fromY, rectY, durStr)
                                        )
                                (acc.append((row, r)), newG2, labels ++ labelEls)
                        end match
                loop(i + 1, nextResult._1, nextResult._2, nextResult._3)
        val (bars, finalGeom, labels) = loop(0, Chunk.empty, newGeom, Chunk.empty)
        (ChartInteraction.withHighlight(bars, highlight) ++ labels, finalGeom)
    end lowerBarSimpleWithTransitions

    /** Lower a line mark with keyed-transition awareness, emitting a declarative SMIL path morph when
      * the previous and new paths have the same command structure.
      *
      * When animation is enabled and a previous `MarkGeom.LinePath` entry exists in `fromGeom` for the
      * same path slot, the command-type signatures of the previous and new paths are compared:
      *   - Same type signature (structural match, stable x-categories, changing y-values): the path is
      *     emitted with one `Svg.animate` child `attributeName="d" from={prevD} to={newD}`. The browser
      *     drives the interpolation declaratively; no fiber or mount hook is required.
      *   - Different type signature (structural change, e.g. a gap introduction that inserts an extra
      *     MoveTo, or a category added/removed): the path snaps with no animate child. This is a
      *     documented v1 limitation: a structural path morph requires a bounded stepped-interpolation
      *     fiber that can only be launched from an effectful mount hook, which the pure `Svg.Root`
      *     lowering does not provide.
      *
      * The type-signature comparison (ordered list of PathCommand ordinals) is strictly stronger than a
      * count comparison: `M L L` and `M M L` both have 3 commands but different ordinal sequences and
      * MUST NOT morph (SVG `d` interpolation requires identical command-type sequences). A count-only gate
      * would wrongly morph when a gap introduction keeps the total count the same but changes the command
      * types (e.g. one segment with 3 points -> two segments with 1 and 2 points after a gap).
      *
      * The current `PathData` is always recorded in `newGeom` under a `TransKey` so the next emission can
      * use it as the `from` path. Color-category series use `TransKey.Series(markIdx, catKey)`, where
      * `catKey` is a `CatKey` (compile-time tag plus raw value) from `collectColorCategoriesWithRaw`; the
      * no-color single-series case uses `TransKey.SingleSeries(markIdx)`. Because the key is keyed by
      * category value identity, it is stable across series add/remove/reorder, a prior mark's
      * geometry-count change does NOT shift a later mark's key, and two color categories with identical
      * display labels (e.g. both `toString` = "color") stay distinct.
      *
      * No `url(#id)` references are emitted.
      */
    private def lowerLineWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Chart[A],
        fromGeom: Map[TransKey, MarkGeom],
        newGeom: Map[TransKey, MarkGeom],
        markIdx: Int,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[ChartInteraction.Highlight[A]] = Absent
    )(using Frame): (Chunk[Svg.SvgElement], Map[TransKey, MarkGeom]) =
        val animOk = spec.animateCfg.enabled
        val durStr = formatDur(spec.animateCfg.duration)
        // rawPathsWithKey carries the TransKey (CatKey-based series identity) and the series-representative
        // row alongside each path. TransKey.Series keys by value identity (CatKey = tag + value), so two
        // enum cases with colliding toString stay distinct. TransKey.SingleSeries is used when there is no
        // color encoding. Both keys are stable across series add/remove/reorder.
        // The representative row (repRow) is needed for withHighlight (mirrors lowerLine's tagged approach).
        val rawPathsWithKey: Chunk[(Svg.Path, TransKey, Maybe[A])] = mark.color match
            case Absent =>
                // No color encoding: single series. Use TransKey.SingleSeries(markIdx) as a stable key so
                // it cannot collide with any color-encoding series key.
                val path = ChartMarks.lowerLineSeries(rows, mark, xs, ys, defaultColor, Present(spec), internalHoverRef)
                Chunk((path, TransKey.SingleSeries(markIdx), rows.headMaybe))
            case Present(colorEnc) =>
                // resolvePalette honors an explicit categorical/sequential colorScale so the animated line
                // agrees with the legend, and falls back to theme.palette then DefaultPalette otherwise.
                // collectColorCategoriesWithRaw and distinctKeyed dedupe by the SAME categoryKey, so
                // resolved[seriesIdx] aligns with distinct[seriesIdx].
                val cats: Chunk[(String, Any)]   = ChartLegend.collectColorCategoriesWithRaw(rows, colorEnc)
                val resolved: Chunk[Style.Color] = ChartLegend.resolvePalette(spec, cats)
                val distinct = ChartFoundations.distinctKeyed(
                    rows,
                    r => ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(r))
                )
                // Pre-build a single-pass groupBy so each catKey lookup is O(1) rather than O(N).
                val rowsByKey = ChartFoundations.groupByKey(
                    rows,
                    r => ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(r))
                )
                distinct.zipWithIndex.map:
                    case ((catKey, rep), seriesIdx) =>
                        val seriesRows = rowsByKey.getOrElse(catKey, Chunk.empty)
                        val strokeColor =
                            if resolved.isEmpty then ChartAxes.DefaultPalette(seriesIdx % ChartAxes.DefaultPalette.size)
                            else resolved(seriesIdx                                     % resolved.size)
                        // TransKey.Series keys by (markIdx, CatKey): value identity, not toString label.
                        // Two enum cases with identical toString stay distinct because CatKey pairs the
                        // raw value with its compile-time ConcreteTag.
                        val transKey = TransKey.Series(markIdx, catKey)
                        val path     = ChartMarks.lowerLineSeries(seriesRows, mark, xs, ys, strokeColor, Present(spec), internalHoverRef)
                        (path, transKey, seriesRows.headMaybe)
        // For each raw path: optionally attach a SMIL animate on `d`, then record the new PathData.
        // TransKey is stable per (mark identity, series category identity): a prior mark's geometry-count
        // change does NOT shift this mark's key, and removing/reordering a color series does not cause a
        // surviving series to look up a different series' prior geometry. Colliding-toString categories
        // stay distinct because TransKey uses CatKey (tag + value), not the display label string.
        val (tagged, updatedGeom) = rawPathsWithKey.foldLeft((Chunk.empty[(A, Svg.SvgElement)], newGeom)):
            case ((accTagged, accGeom), (rawPath, transKey, repRowMaybe)) =>
                val newPd       = rawPath.svgAttrs.d.getOrElse(Svg.PathData.empty)
                val newGeom2    = accGeom.updated(transKey, MarkGeom.LinePath(newPd))
                val emittedPath = morphedPath(rawPath, fromGeom, transKey, newPd, animOk, durStr)
                val nextTagged = repRowMaybe match
                    case Present(r) => accTagged.append((r, emittedPath))
                    case Absent     => accTagged
                (nextTagged, newGeom2)
        (ChartInteraction.withHighlight(tagged, highlight), updatedGeom)
    end lowerLineWithTransitions

    /** Lower an area mark with keyed-transition awareness, emitting a declarative SMIL path morph when
      * the previous and new paths have the same command structure.
      *
      * Mirrors `lowerLineWithTransitions` for area paths. A simple (non-stacked) area mark produces one
      * closed path; the SMIL morph fires when the previous path's command count equals the new one.
      *
      * Stacked area marks fall through to the plain `lowerArea` because multi-path stacking uses
      * per-group path indices that would need per-group geometry tracking not yet plumbed in v1.
      *
      * Structural path morphs (different command-type signature) snap: see `lowerLineWithTransitions`
      * scaladoc for the v1 limitation note and the type-signature rationale.
      *
      * No `url(#id)` references are emitted.
      */
    private def lowerAreaWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Chart[A],
        fromGeom: Map[TransKey, MarkGeom],
        newGeom: Map[TransKey, MarkGeom],
        markIdx: Int,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[ChartInteraction.Highlight[A]] = Absent
    )(using Frame): (Chunk[Svg.SvgElement], Map[TransKey, MarkGeom]) =
        // Stacked area falls through to plain lowerArea: per-group path tracking for the SMIL morph is
        // not plumbed for stacked marks, so they snap rather than animate.
        val isStacked = mark.y.isDefined && mark.stack.group.isDefined
        if isStacked then
            val elems = ChartMarks.lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec), internalHoverRef, highlight)
            (elems, newGeom)
        else
            val animOk = spec.animateCfg.enabled
            val durStr = formatDur(spec.animateCfg.duration)
            // highlight is withheld from this lowerArea call: lowerArea applies withHighlight internally,
            // which wraps paths in Reactive[Svg.G] nodes that the `.collect` below (Svg.Path only) would
            // drop. withHighlight is instead applied after SMIL is attached, using the repRows computed below.
            val rawPaths: Chunk[Svg.Path] =
                ChartMarks.lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec), internalHoverRef).collect:
                    case p: Svg.Path => p
            // Compute TransKeys and representative rows for each series so the key for each area path
            // is keyed by CatKey value identity rather than the label string. TransKey.Series(markIdx, catKey)
            // keeps colliding-toString categories distinct. lowerArea emits one path per category in the
            // ordinal order of collectColorCategoriesWithRaw, so rawPaths(i) corresponds to transKeys(i).
            // For the no-color single-series case, use TransKey.SingleSeries(markIdx).
            val (seriesTransKeys, seriesRepRows): (Chunk[TransKey], Chunk[Maybe[A]]) = mark.color match
                case Absent => (Chunk(TransKey.SingleSeries(markIdx)), Chunk(rows.headMaybe))
                case Present(colorEnc) =>
                    val colorEncAny: Encoding[A, ?] = colorEnc
                    val cats                        = ChartLegend.collectColorCategoriesWithRaw(rows, colorEncAny)
                    val catKeys: Chunk[ChartFoundations.CatKey] =
                        cats.map { case (_, raw) => ChartFoundations.categoryKey(colorEncAny.tag, raw) }
                    val transKeys = catKeys.map(ck => TransKey.Series(markIdx, ck))
                    // Pre-build a single-pass groupBy so each catKey lookup is O(1) rather than O(N).
                    val rowsByKey =
                        ChartFoundations.groupByKey(rows, r => ChartFoundations.categoryKey(colorEncAny.tag, colorEncAny.accessor(r)))
                    val repRows = catKeys.map(catKey => rowsByKey.getOrElse(catKey, Chunk.empty).headMaybe)
                    (transKeys, repRows)
            // TransKey is stable per (mark identity, series category identity): a prior mark's
            // geometry-count change does NOT shift this mark's key, and removing/reordering a color
            // series does not cause a surviving series to look up a different series' prior geometry.
            // Colliding-toString categories stay distinct because TransKey uses CatKey (tag + value).
            val (tagged, updatedGeom) = rawPaths.zipWithIndex.foldLeft((Chunk.empty[(A, Svg.SvgElement)], newGeom)):
                case ((accTagged, accGeom), (rawPath, seriesIdx)) =>
                    val transKey = if seriesIdx < seriesTransKeys.size then seriesTransKeys(seriesIdx) else TransKey.SingleSeries(markIdx)
                    val newPd    = rawPath.svgAttrs.d.getOrElse(Svg.PathData.empty)
                    val newGeom2 = accGeom.updated(transKey, MarkGeom.LinePath(newPd))
                    val emittedPath = morphedPath(rawPath, fromGeom, transKey, newPd, animOk, durStr)
                    val repRowMaybe = if seriesIdx < seriesRepRows.size then seriesRepRows(seriesIdx) else Absent
                    val nextTagged = repRowMaybe match
                        case Present(r) => accTagged.append((r, emittedPath))
                        case Absent     => accTagged
                    (nextTagged, newGeom2)
            (ChartInteraction.withHighlight(tagged, highlight), updatedGeom)
        end if
    end lowerAreaWithTransitions

    /** Build the marks region `Svg.G` for a reactive emission with keyed enter/update transitions.
      *
      * `stateRef` is the chart-private `AtomicRef.Unsafe[TransState[A]]` created once in `lowerLive`.
      * On each call:
      *   1. Read the current `TransState` `t`.
      *   2. If `rows.equals(t.lastRows)` (repeat pull of the same emission): use `t.fromGeom` as the
      *      animation origin and `t.currentGeom` as the target; do NOT write `stateRef`. Every repeat
      *      pull sees the same stable pair and produces identical SVG output.
      *   3. If `rows` differ from `t.lastRows` (genuinely new emission): produce shapes AND geometry
      *      in a single pass; write `TransState(rows, fromGeom = t.currentGeom, currentGeom = newGeom)`
      *      exactly once.
      *
      * The `==` comparison uses Chunk's structural equality. Writes are serialized by the reactive
      * engine's single-threaded emission model, so no concurrent-write hazard exists.
      *
      * Unsafe: `stateRef.get()` and `stateRef.set()` bypass kyo effects tracking. This is sound
      * because the ref is private to this chart instance and writes occur only on genuine row changes.
      */
    private[kyo] def marksRegionWithTransitions[A](
        rows: Chunk[A],
        spec: Chart[A],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        stateRef: AtomicRef.Unsafe[TransState[A]],
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame, AllowUnsafe): Svg.G =
        // Unsafe: stateRef is chart-private and its writes are serialized by the reactive engine's
        // single-threaded emission model; reading it synchronously here (pure SVG projection, no
        // suspension) is sound and carries no concurrent-write hazard.
        val t            = stateRef.get()
        val animOk       = spec.animateCfg.enabled
        val isRepeatPull = rows.equals(t.lastRows)
        // The animation origin is the geometry from the previous emission.
        // For a repeat pull it is t.fromGeom (already stored); for a new emission it is t.currentGeom.
        val fromGeom: Map[TransKey, MarkGeom] =
            if isRepeatPull then t.fromGeom else t.currentGeom
        // Produce SVG shapes and accumulate new geometry in a single pass.
        // Each mark with no explicit color encoding uses a distinct palette color by its index (per-mark default).
        val (shapes, newGeom) = spec.marks.zipWithIndex.foldLeft((Chunk.empty[Svg.SvgElement], Map.empty[TransKey, MarkGeom])):
            case ((accElems, accGeom), (mark, markIdx)) =>
                val markColor = ChartAxes.markDefaultColor(spec.theme, markIdx)
                val ys        = if ChartAxes.markAxisOf(mark) == Axis.Right then ysR.getOrElse(ysL) else ysL
                val highlight = ChartInteraction.resolveHighlight(Present(spec))
                mark match
                    case m: Mark.Bar[A, ?, ?] if m.stack.group.isEmpty && m.color.isEmpty =>
                        // Simple ungrouped bar: supports keyed SMIL transitions.
                        // fromGeom is the stable animation origin for this call (same on every pull of this emission).
                        val (elems, geom) = lowerBarSimpleWithTransitions(
                            rows,
                            m,
                            xs,
                            ys,
                            markColor,
                            spec,
                            fromGeom,
                            accGeom,
                            internalHoverRef,
                            highlight
                        )
                        (accElems ++ elems, geom)
                    case m: Mark.Line[A, ?, ?] if animOk =>
                        // Line path: declarative SMIL `d` morph when command counts match; snaps otherwise.
                        // fromGeom carries the previous PathData for the `from` side of the SMIL animate.
                        val (elems, geom) = lowerLineWithTransitions(
                            rows,
                            m,
                            xs,
                            ys,
                            markColor,
                            spec,
                            fromGeom,
                            accGeom,
                            markIdx,
                            internalHoverRef,
                            highlight
                        )
                        (accElems ++ elems, geom)
                    case m: Mark.Area[A, ?, ?] if animOk =>
                        // Area path: same declarative SMIL `d` morph discipline as line.
                        val (elems, geom) = lowerAreaWithTransitions(
                            rows,
                            m,
                            layout,
                            xs,
                            ys,
                            markColor,
                            spec,
                            fromGeom,
                            accGeom,
                            markIdx,
                            internalHoverRef,
                            highlight
                        )
                        (accElems ++ elems, geom)
                    case m: Mark.Bar[A, ?, ?] =>
                        (accElems ++ ChartMarks.lowerBar(rows, m, layout, xs, ys, markColor, Present(spec), internalHoverRef), accGeom)
                    // Static fallback for a non-animated line/area (animOk false): no SMIL morph, no geometry tracking.
                    case m: Mark.Line[A, ?, ?] => (
                            accElems ++ ChartMarks.lowerLine(
                                rows,
                                m,
                                xs,
                                ys,
                                markColor,
                                Present(spec),
                                internalHoverRef,
                                highlight
                            ),
                            accGeom
                        )
                    case m: Mark.Area[A, ?, ?] => (
                            accElems ++ ChartMarks.lowerArea(
                                rows,
                                m,
                                layout,
                                xs,
                                ys,
                                markColor,
                                Present(spec),
                                internalHoverRef,
                                highlight
                            ),
                            accGeom
                        )
                    case m: Mark.Point[A, ?, ?] =>
                        (
                            accElems ++ ChartMarks.lowerPoint(
                                rows,
                                m,
                                layout,
                                xs,
                                ys,
                                markColor,
                                Present(spec),
                                internalHoverRef,
                                spec.theme,
                                highlight
                            ),
                            accGeom
                        )
                    case m: Mark.Rule[A] => (accElems ++ ChartMarks.lowerRule(m, layout, xs, ys), accGeom)
                    // Text and errorBar produce no morph geometry; their elements are emitted as-is.
                    case m: Mark.Text[A, ?, ?] =>
                        (
                            accElems ++ ChartMarks.lowerText(m, rows, xs, ys, markColor, spec.theme, Present(spec), highlight),
                            accGeom
                        )
                    case m: Mark.ErrorBar[A, ?, ?] =>
                        (
                            accElems ++ ChartMarks.lowerErrorBar(
                                m,
                                rows,
                                xs,
                                ys,
                                markColor,
                                spec.theme,
                                Present(spec),
                                highlight
                            ),
                            accGeom
                        )
                end match
        // Write the new state only when this is a genuinely new emission.
        // On repeat pulls, the ref is left untouched so the next real emission still sees the correct fromGeom.
        if !isRepeatPull then
            stateRef.set(TransState(rows, fromGeom = t.currentGeom, currentGeom = newGeom))
        shapes.foldLeft(Svg.g): (g, el) =>
            el match
                case r: Svg.Rect   => g(r)
                case p: Svg.Path   => g(p)
                case c: Svg.Circle => g(c)
                case l: Svg.Line   => g(l)
                case inner: Svg.G  => g(inner)
                // `shapes` holds `Svg.SvgElement`s, and `SvgElement` is a member of the `SvgChild` union, so the
                // residual `other` arm is accepted by `g` directly.
                case other => g(other)
    end marksRegionWithTransitions

end ChartTransitions
