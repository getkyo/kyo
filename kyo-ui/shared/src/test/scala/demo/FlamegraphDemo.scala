package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Interactive flamegraph built entirely on the kyo-ui SVG layer and served as a server-push app via `UI.runHandlers`.
  *
  * This is a reference for using the SVG API to render and animate a data visualization. It parses a real captured
  * async-profiler CPU profile (collapsed/folded stacks) of the kyo HTTP stack into a frame tree, lays the tree out as
  * nested `Svg.rect`s (x and width from each frame's sample fraction within the visible domain, y from tree depth),
  * colors each frame by a deterministic palette hash of its package, attaches a native `Svg.title` tooltip, and draws
  * truncated `Svg.text` labels.
  *
  * The interactive core is the visible x-domain `[lo, hi]`, held in a single `SignalRef`. Clicking a frame tweens the
  * domain to that frame's sample interval over ~300ms with an eased interpolation stepped by `Async.sleep`, so the rects
  * re-layout smoothly through the reactive boundary. Hovering dims frames outside the hovered ancestor/descendant chain,
  * the mouse wheel zooms about the cursor, and Reset tweens back to the full domain.
  *
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.Flamegraph'` (optional port as the first argument).
  */
object FlamegraphDemo extends KyoApp:

    // ---- data ----

    /** A node in the parsed flamegraph tree. Named `FrameNode` (not `Frame`) so it does not shadow `kyo.Frame`. */
    final case class FrameNode(name: String, total: Long, self: Long, children: Chunk[FrameNode]) derives CanEqual

    /** A laid-out, culled flamegraph cell ready to render as one `Svg.rect` plus its label and tooltip. */
    final case class Cell(
        name: String,
        total: Long,
        x: Double,
        y: Double,
        w: Double,
        depth: Int,
        s0: Double,
        s1: Double,
        color: Style.Color
    ) derives CanEqual

    /** The reactive view state: the visible x-domain `[lo, hi]` over the root's `[0, 1]` sample fraction, and the
      * currently hovered frame name (for the dim-others highlight).
      */
    final case class ViewState(lo: Double, hi: Double, hover: Maybe[String]) derives CanEqual

    /** A real kyo-http CPU profile (async-profiler, collapsed/folded stacks), pruned to a representative legible subtree.
      *
      * Captured from a kyo HTTP server benchmark under `-prof async;output=collapsed;event=cpu`, rerooted at
      * `Worker.run` with low-sample frames pruned for legibility. Every frame name is real. The demo reads the pruned
      * subset from the module's test resources with `kyo.Path` (cross-platform), parsing it into the frame tree on
      * startup. The path is relative to the kyo-ui module directory, which is sbt's working directory for `runMain`.
      */
    val profilePath: Path =
        Path("src", "test", "resources", "flamegraph", "http-server-demo.collapsed")

    // ---- parser (pure) ----

    /** Parse collapsed/folded stacks (`a;b;c <count>` per line) into a frame tree.
      *
      * When the input has a single common root frame, that frame is the tree root; otherwise a synthetic `"root"` node
      * wraps the distinct roots. `total` accumulates on every node along each stack; `self` accumulates only on the
      * terminal frame of each stack.
      */
    def parse(input: String): FrameNode =
        // Each non-comment line is `frame1;frame2;...;frameN <count>`; parse the valid ones into (frames, count) pairs.
        val stacks: Chunk[(Chunk[String], Long)] =
            Chunk.from(input.linesIterator)
                .map(_.trim)
                .filter(line => line.nonEmpty && !line.startsWith("#") && line.lastIndexOf(' ') > 0)
                .map { line =>
                    val sp     = line.lastIndexOf(' ')
                    val frames = Chunk.from(line.substring(0, sp).split(';'))
                    val count  = Maybe.fromOption(line.substring(sp + 1).toLongOption).getOrElse(0L)
                    (frames, count)
                }

        // Build the node reached by `routed`: `total` sums every count flowing through it, `self` the counts whose
        // path ends here, and children group the deeper stacks by their next frame in first-appearance order.
        def build(name: String, routed: Chunk[(Chunk[String], Long)]): FrameNode =
            val total  = routed.foldLeft(0L)(_ + _._2)
            val self   = routed.foldLeft(0L)((acc, s) => if s._1.isEmpty then acc + s._2 else acc)
            val deeper = routed.filter(_._1.nonEmpty)
            val children = Chunk.from(deeper.map(_._1.head).distinct).map { head =>
                build(head, deeper.collect { case (frames, c) if frames.head == head => (frames.drop(1), c) })
            }
            FrameNode(name, total, self, children)
        end build

        // A single shared root frame becomes the tree root; otherwise a synthetic `root` wraps the distinct tops.
        val rootNode = build("root", stacks)
        if rootNode.children.size == 1 then rootNode.children.head else rootNode
    end parse

    /** The package-prefix of a frame name (everything before the first `.`), used for the deterministic color hash. */
    private[demo] def packageOf(name: String): String =
        val dot = name.indexOf('.')
        if dot < 0 then name else name.substring(0, dot)

    // ---- color (deterministic palette + hash) ----

    private val palette: Chunk[Style.Color] = Chunk(
        Style.Color.rgb(78, 121, 167),
        Style.Color.rgb(242, 142, 43),
        Style.Color.rgb(225, 87, 89),
        Style.Color.rgb(118, 183, 178),
        Style.Color.rgb(89, 161, 79),
        Style.Color.rgb(237, 201, 72),
        Style.Color.rgb(176, 122, 161),
        Style.Color.rgb(255, 157, 167),
        Style.Color.rgb(156, 117, 95),
        Style.Color.rgb(186, 176, 172)
    )

    /** Map a frame's package to a stable palette color (same package => same color), via an FNV-1a hash. */
    private[demo] def colorFor(name: String): Style.Color =
        val pkg = packageOf(name)
        @scala.annotation.tailrec
        def fnv(i: Int, h: Long): Long =
            if i >= pkg.length then h
            else fnv(i + 1, (h ^ pkg.charAt(i).toLong) * 16777619L)
        val idx = (math.abs(fnv(0, 2166136261L)) % palette.length.toLong).toInt
        palette(idx)
    end colorFor

    // ---- layout (pure) ----

    private val canvasW   = 1200.0
    private val rowHeight = 18.0
    private val rowGap    = 1.0
    private val minPx     = 1.5 // cull rects narrower than this

    private def clamp01(v: Double): Double = if v < 0.0 then 0.0 else if v > 1.0 then 1.0 else v

    /** Lay out the tree into renderable cells within the visible domain `[lo, hi]` (fractions of the root's samples).
      *
      * Each node occupies a contiguous sample interval `[s0, s1)` of the root range; children split their parent's
      * interval left to right in proportion to their `total`. The domain window maps fraction `f -> (f - lo) / (hi - lo)`
      * before scaling to pixels. Cells narrower than `minPx` (and their subtrees) are culled, so the returned count is
      * exactly the number of `<rect>`s the renderer emits.
      */
    def layout(root: FrameNode, lo: Double, hi: Double): Chunk[Cell] =
        val span = if hi - lo <= 0.0 then 1.0e-9 else hi - lo

        def go(node: FrameNode, s0: Double, s1: Double, depth: Int): Chunk[Cell] =
            val f0 = clamp01((s0 - lo) / span)
            val f1 = clamp01((s1 - lo) / span)
            val px = f0 * canvasW
            val pw = (f1 - f0) * canvasW
            if pw < minPx then Chunk.empty
            else
                val cell = Cell(
                    name = node.name,
                    total = node.total,
                    x = px,
                    y = depth * rowHeight,
                    w = pw,
                    depth = depth,
                    s0 = s0,
                    s1 = s1,
                    color = colorFor(node.name)
                )
                // children occupy the parent's interval after its own `self` share, left to right.
                val selfFrac   = node.self.toDouble / math.max(node.total.toDouble, 1.0)
                val childWidth = (s1 - s0) * (1.0 - selfFrac)
                val start      = s0 + (s1 - s0) * selfFrac
                val sum        = math.max(childFractionSum(node), 1.0e-12)
                val childCells =
                    node.children
                        .foldLeft((Chunk.empty[Cell], start)) { case ((acc, cursor), c) =>
                            val frac = if node.total <= 0L then 0.0 else c.total.toDouble / node.total.toDouble
                            val cw   = childWidth * (frac / sum)
                            (acc.concat(go(c, cursor, cursor + cw, depth + 1)), cursor + cw)
                        }
                        ._1
                Chunk(cell).concat(childCells)
            end if
        end go

        go(root, 0.0, 1.0, 0)
    end layout

    private def childFractionSum(node: FrameNode): Double =
        if node.total <= 0L then 0.0
        else node.children.foldLeft(0.0)((acc, c) => acc + c.total.toDouble / node.total.toDouble)

    /** Truncate a frame label to fit a cell of pixel width `w` (roughly 7px per char); empty when too narrow. */
    def truncateLabel(name: String, w: Double): String =
        val maxChars = ((w - 4.0) / 7.0).toInt
        if maxChars < 2 then ""
        else if name.length <= maxChars then name
        else name.substring(0, math.max(1, maxChars - 2)) + ".."
    end truncateLabel

    private def pctOf(total: Long, rootTotal: Long): String =
        val p = if rootTotal <= 0L then 0.0 else total.toDouble * 100.0 / rootTotal.toDouble
        f"$p%.1f"

    /** A stable per-cell id stem, derived from its depth and sample interval.
      *
      * Each cell renders three elements with distinct ids built from this stem: the enclosing `<g>` (`cellId`, which
      * carries the handlers), the `<rect>` (`rectId`), and the label `<text>` (`labelId`).
      */
    def cellStem(c: Cell): String =
        c.depth.toString + "-" + (c.s0 * 1.0e6).toLong.toString

    /** Id of the enclosing `<g>` cell that carries the click/hover/scroll handlers. */
    def cellId(c: Cell): String = "cell-" + cellStem(c)

    /** Id of the cell's `<rect>` (the colored bar). */
    def rectId(c: Cell): String = "rect-" + cellStem(c)

    /** Id of the cell's label `<text>` (drawn on top of the rect; clicking it must still zoom). */
    def labelId(c: Cell): String = "label-" + cellStem(c)

    // ---- render ----

    private def depthOf(n: FrameNode): Int =
        if n.children.isEmpty then 1 else 1 + n.children.map(depthOf).max

    private def viewBox(maxDepth: Int): Svg.ViewBox = Svg.ViewBox(0, 0, canvasW, maxDepth * rowHeight)

    /** Is `frame` on the hovered ancestor/descendant chain (so it stays bright)? Uses sample-interval containment. */
    private def onHoverChain(cells: Chunk[Cell], hov: String, target: Cell): Boolean =
        cells.exists { h =>
            h.name == hov && (
                (target.s0 <= h.s0 && target.s1 >= h.s1) || (target.s0 >= h.s0 && target.s1 <= h.s1)
            )
        }

    /** Render one flamegraph frame for `state`, wiring each cell's `<g>` to the click/hover/wheel actions.
      *
      * Handlers live on the enclosing `<g>`, not on the `<rect>`. SVG hit-tests the topmost element under the cursor,
      * which over a labelled cell is the `<text>` drawn on top of the rect. kyo-ui dispatch delegates to ancestors (a
      * handler fires for any element whose path is a prefix of the target's), so a `<g>` handler fires whether the
      * pointer is over the rect, the text, or the title. A rect-only handler would be missed over the sibling `<text>`.
      */
    private def renderFlamegraph(
        tree: FrameNode,
        maxDepth: Int,
        state: ViewState,
        onCellClick: Cell => Unit < Async,
        hoverSet: Maybe[String] => Unit < Async,
        wheelZoom: Double => Unit < Async
    )(using Frame): Svg.Root =
        val cells     = layout(tree, state.lo, state.hi)
        val rootTotal = tree.total
        Svg.svg.width(canvasW.toInt).height((maxDepth * rowHeight).toInt).viewBox(viewBox(maxDepth))(
            cells.map { c =>
                val label   = truncateLabel(c.name, c.w)
                val bright  = state.hover.forall(h => onHoverChain(cells, h, c))
                val opacity = if bright then 1.0 else 0.3
                val tip     = s"${c.name}: ${c.total} samples (${pctOf(c.total, rootTotal)}%)"
                val rect = Svg.rect
                    .id(rectId(c))
                    .x(c.x).y(c.y).width(c.w).height(rowHeight - rowGap)
                    .fill(Svg.Paint.Color(c.color))
                    .fillOpacity(opacity)(
                        Svg.title(tip)
                    )
                val cell = Svg.g
                    .id(cellId(c))
                    .onClick(onCellClick(c))
                    .onHover((e: UI.MouseEvent) => hoverSet(Present(c.name)))
                    .onUnhover(hoverSet(Absent))
                    .onScroll((w: UI.WheelEvent) => wheelZoom(w.deltaY))
                if label.isEmpty then cell(rect)
                else
                    cell(
                        rect,
                        Svg.text.id(labelId(c)).x(c.x + 3).y(c.y + rowHeight - 6)(label)
                    )
                end if
            }*
        )
    end renderFlamegraph

    // ---- tween ----

    private val tweenSteps  = 20
    private val tweenStepMs = 15

    private def easeInOutCubic(t: Double): Double =
        if t < 0.5 then 4.0 * t * t * t
        else 1.0 - math.pow(-2.0 * t + 2.0, 3.0) / 2.0

    /** Tween the domain ref from its current value to `[t0, t1]` over ~300ms, stepping via `Async.sleep` (never blocks).
      *
      * Each step writes the ref so the reactive boundary re-renders smoothly along the eased interpolation.
      */
    def tweenTo(state: SignalRef[ViewState], t0: Double, t1: Double)(using Frame): Unit < Async =
        for
            cur <- state.get
            c0 = cur.lo
            c1 = cur.hi
            _ <- Loop(1) { step =>
                val t  = step.toDouble / tweenSteps.toDouble
                val e  = easeInOutCubic(t)
                val lo = c0 + (t0 - c0) * e
                val hi = c1 + (t1 - c1) * e
                state.updateAndGet(_.copy(lo = lo, hi = hi)).andThen {
                    if step >= tweenSteps then Loop.done
                    else Async.sleep(tweenStepMs.millis).andThen(Loop.continue(step + 1))
                }
            }
            _ <- state.updateAndGet(_.copy(lo = t0, hi = t1))
        yield ()

    // ---- styles ----

    private val rule   = Color.rgb(221, 221, 221)
    private val accent = Color.rgb(0, 123, 255)

    private val pageStyle = Style.column.padding(16.px).gap(10.px).fontFamily(_.Monospace)
    private val barStyle  = Style.row.gap(8.px).align(_.center)
    private val btnStyle  = Style.padding(6.px, 12.px).bg(accent).color(_.white).border(0.px, accent).cursor(_.pointer)
    private val hintStyle = Style.fontSize(12.px).color(_.gray)
    private val svgWrap   = Style.border(1.px, rule).maxWidth(100.pct)

    // ---- app ----

    /** Zoom the viewport about its center by `factor` (< 1 zooms in), clamped to `[0, 1]`. */
    private def zoom(s: ViewState, factor: Double): ViewState =
        val mid  = (s.lo + s.hi) / 2.0
        val half = (s.hi - s.lo) / 2.0 * factor
        val lo   = math.max(0.0, mid - half)
        val hi   = math.min(1.0, mid + half)
        if hi - lo < 1.0e-4 then s else s.copy(lo = lo, hi = hi)
    end zoom

    private[demo] def app(tree: FrameNode): UI < Async =
        val maxDepth = depthOf(tree)
        for
            state <- Signal.initRef(ViewState(0.0, 1.0, Absent))

            onCellClick = (c: Cell) => tweenTo(state, c.s0, c.s1)
            hoverSet    = (m: Maybe[String]) => state.updateAndGet(_.copy(hover = m)).unit
            wheelZoom = (deltaY: Double) =>
                state.updateAndGet(s => zoom(s, if deltaY < 0 then 0.85 else 1.0 / 0.85)).unit
            reset = tweenTo(state, 0.0, 1.0).andThen(state.updateAndGet(_.copy(hover = Absent)).unit)

            region = state.render(s => renderFlamegraph(tree, maxDepth, s, onCellClick, hoverSet, wheelZoom))
        yield UI.div.style(pageStyle)(
            UI.div.style(barStyle)(
                UI.button("Reset").id("reset").style(btnStyle).onClick(reset),
                UI.span("click a frame to zoom, hover to highlight, wheel to zoom, Reset to restore").style(hintStyle)
            ),
            UI.div.style(svgWrap)(region)
        )
        end for
    end app

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            folded <- profilePath.read
            tree = parse(folded)
            handlers <- UI.runHandlers("/")(app(tree))
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Flamegraph running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end FlamegraphDemo
