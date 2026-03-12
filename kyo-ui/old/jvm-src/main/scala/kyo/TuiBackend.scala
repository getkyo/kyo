package kyo

import kyo.internal.*

/** TUI backend for kyo-ui.
  *
  * Renders the UI AST to a terminal using ANSI escape codes. Full alternate-screen TUI with complete Style support.
  */
object TuiBackend extends UIBackend:

    def render(ui: UI)(using Frame): UISession < (Async & Scope) =
        for
            rendered <- Signal.initRef[UI](UI.empty)
            terminal <- Sync.defer {
                val t = new TuiTerminal
                t.enter()
                t
            }
            _        <- Scope.ensure(terminal.exit())
            layout   <- Sync.defer(new TuiLayout(512))
            signals  <- Sync.defer(new TuiSignalCollector(256))
            renderer <- Sync.defer(new TuiRenderer(terminal.cols, terminal.rows))
            focus    <- Sync.defer(new TuiFocus)
            _        <- Sync.defer(doRender(ui, terminal, layout, signals, renderer, focus))
            _        <- rendered.set(ui)
            fiber    <- Fiber.init(mainLoop(ui, terminal, layout, signals, renderer, focus, rendered))
        yield new UISession(fiber, rendered)

    // ──────────────────────── Main Loop ────────────────────────

    private def mainLoop(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus,
        rendered: SignalRef[UI]
    )(using Frame): Unit < Async =
        Async.race(
            inputLoop(terminal, focus, layout),
            renderLoop(ui, terminal, layout, signals, renderer, focus, rendered)
        ).unit

    // ──────────────────────── Input Loop ────────────────────────

    private def inputLoop(
        terminal: TuiTerminal,
        focus: TuiFocus,
        layout: TuiLayout
    )(using Frame): Unit < Async =
        import AllowUnsafe.embrace.danger
        for
            readBuf <- Sync.defer(new Array[Byte](256))
            _ <- Loop(Chunk.empty[Byte]) { (remainder: Chunk[Byte]) =>
                for
                    n <- Sync.defer(terminal.read(readBuf))
                    r <-
                        if n > 0 then
                            for
                                parsed <- Sync.defer {
                                    val bytes = Chunk.from(java.util.Arrays.copyOf(readBuf, n))
                                    val all   = if remainder.isEmpty then bytes else remainder ++ bytes
                                    TuiInput.parse(all)
                                }
                                (events, leftover) = parsed
                                quit <- dispatchEvents(events, focus, layout, 0)
                            yield
                                if quit then Loop.done[Chunk[Byte], Unit](())
                                else Loop.continue(leftover)
                        else
                            Async.sleep(16.millis).andThen(Loop.continue(remainder))
                yield r
            }
        yield ()
        end for
    end inputLoop

    private[kyo] def dispatchEvents(
        events: Chunk[InputEvent],
        focus: TuiFocus,
        layout: TuiLayout,
        i: Int
    )(using Frame, AllowUnsafe): Boolean < Sync =
        if i >= events.size then false
        else
            events(i) match
                case InputEvent.Key("c", true, false, false) => true
                case e =>
                    focus.dispatch(e, layout).andThen {
                        dispatchEvents(events, focus, layout, i + 1)
                    }
    end dispatchEvents

    // ──────────────────────── Render Loop ────────────────────────

    private def renderLoop(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus,
        rendered: SignalRef[UI]
    )(using Frame): Nothing < Async =
        Loop.forever {
            for
                sigs <- Sync.defer(signals.toSpan)
                _ <-
                    if sigs.isEmpty then Async.sleep(100.millis)
                    else Async.race(Seq.tabulate(sigs.size)(i => sigs(i).next.unit))
                _ <- Async.sleep(4.millis)
                _ <- Sync.defer {
                    if terminal.pollResize() then
                        renderer.resize(terminal.cols, terminal.rows)
                        renderer.invalidate()
                    doRender(ui, terminal, layout, signals, renderer, focus)
                }
                _ <- rendered.set(ui)
            yield ()
            end for
        }
    end renderLoop

    // ──────────────────────── Synchronous Pipeline ────────────────────────

    private[kyo] def doRender(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus
    )(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        TuiFlatten.flatten(ui, layout, signals, terminal.cols, terminal.rows)
        TuiLayout.measure(layout)
        TuiLayout.arrange(layout, terminal.cols, terminal.rows)
        TuiPainter.inheritStyles(layout)
        focus.scan(layout)
        focus.applyFocusStyle(layout)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
        renderer.flush(terminal.outputStream)
        terminal.flush()
    end doRender

    /** Render a single frame to a plain-text string grid. No TTY needed. For debugging. */
    def renderToString(ui: UI, cols: Int, rows: Int)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        val renderer = new TuiRenderer(cols, rows)
        val focus    = new TuiFocus
        TuiFlatten.flatten(ui, layout, signals, cols, rows)
        TuiLayout.measure(layout)
        TuiLayout.arrange(layout, cols, rows)
        TuiPainter.inheritStyles(layout)
        focus.scan(layout)
        focus.applyFocusStyle(layout)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, TuiRenderer.NoColor)
        val raw = baos.toString("UTF-8")
        // Strip all ANSI escape sequences, keeping only cursor positioning and text
        val grid = Array.fill(rows)(Array.fill(cols)(' '))
        val stripped = raw.replaceAll("\u001b\\[[0-9;]*m", "") // strip SGR
            .replaceAll("\u001b\\[\\?[^\u001b]*", "") // strip private modes
        val pattern  = java.util.regex.Pattern.compile("\u001b\\[(\\d+);(\\d+)H([^\u001b]*)")
        val matcher  = pattern.matcher(stripped)
        while matcher.find() do
            val r    = matcher.group(1).toInt - 1
            val c    = matcher.group(2).toInt - 1
            val text = matcher.group(3)
            var i    = 0
            while i < text.length do
                val col = c + i
                if r >= 0 && r < rows && col >= 0 && col < cols then
                    grid(r)(col) = text.charAt(i)
                i += 1
            end while
        end while
        grid.map(_.mkString).mkString("\n")
    end renderToString

    /** Render a single frame to raw ANSI string. For debugging. */
    def renderToRawAnsi(ui: UI, cols: Int, rows: Int)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        val renderer = new TuiRenderer(cols, rows)
        TuiFlatten.flatten(ui, layout, signals, cols, rows)
        TuiLayout.measure(layout)
        TuiLayout.arrange(layout, cols, rows)
        TuiPainter.inheritStyles(layout)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, TuiRenderer.NoColor)
        baos.toString("UTF-8")
    end renderToRawAnsi

    /** Dump layout tree for debugging. */
    def renderLayoutDump(ui: UI, cols: Int, rows: Int)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val layout  = new TuiLayout(512)
        val signals = new TuiSignalCollector(256)
        TuiFlatten.flatten(ui, layout, signals, cols, rows)
        TuiLayout.measure(layout)
        TuiLayout.arrange(layout, cols, rows)
        val sb  = new StringBuilder
        var idx = 0
        while idx < layout.count do
            val depth =
                var d = 0; var p = layout.parent(idx);
                while p >= 0 do
                    d += 1; p = layout.parent(p)
                ; d
            end depth
            val indent = "  " * depth
            val nt     = layout.nodeType(idx)
            val txt    = if layout.text(idx).isDefined then s" text=\"${layout.text(idx).get.take(40)}\"" else ""
            val elem   = if layout.element(idx).isDefined then s" elem=${layout.element(idx).get.getClass.getSimpleName}" else ""
            val flags  = layout.lFlags(idx)
            val dir    = if TuiLayout.isRow(flags) then " ROW" else ""
            val hidden = if TuiLayout.isHidden(flags) then " HIDDEN" else ""
            val pad =
                if layout.padT(idx) + layout.padR(idx) + layout.padB(idx) + layout.padL(idx) > 0
                then s" pad=${layout.padT(idx)},${layout.padR(idx)},${layout.padB(idx)},${layout.padL(idx)}"
                else ""
            val mar =
                if layout.marT(idx) + layout.marR(idx) + layout.marB(idx) + layout.marL(idx) > 0
                then s" mar=${layout.marT(idx)},${layout.marR(idx)},${layout.marB(idx)},${layout.marL(idx)}"
                else ""
            sb.append(f"$indent[$idx%3d] x=${layout.x(idx)}%3d y=${layout.y(idx)}%3d w=${layout.w(idx)}%3d h=${layout.h(idx)}%3d" +
                f" intrW=${layout.intrW(idx)}%3d intrH=${layout.intrH(idx)}%3d nt=$nt%2d$dir$hidden$pad$mar$txt$elem\n")
            idx += 1
        end while
        sb.toString
    end renderLayoutDump

end TuiBackend
