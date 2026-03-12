package kyo

import kyo.internal.*

/** TUI backend for kyo-ui.
  *
  * Renders the UI AST to a terminal using ANSI escape codes. Full alternate-screen TUI with complete Style support.
  */
object TuiBackend extends UIBackend:

    /** How often to poll for terminal input when none is available. */
    private val InputPollInterval = 16.millis

    /** Debounce delay after a signal change before re-rendering. */
    private val RenderDebounce = 4.millis

    /** Cursor blink interval — cursor toggles visibility at this rate. */
    private val CursorBlinkInterval = 500.millis

    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
        val resolved = TuiResolvedTheme.resolve(theme)
        for
            rendered      <- Signal.initRef[UI](UI.empty)
            renderTrigger <- Signal.initRef[Int](0)
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
            _        <- Sync.defer(doRender(ui, terminal, layout, signals, renderer, focus, resolved))
            _        <- rendered.set(ui)
            fiber    <- Fiber.init(mainLoop(ui, terminal, layout, signals, renderer, focus, rendered, renderTrigger, resolved))
        yield new UISession(fiber, rendered)
        end for
    end render

    // ---- Main Loop ----

    private def mainLoop(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus,
        rendered: SignalRef[UI],
        renderTrigger: SignalRef[Int],
        theme: TuiResolvedTheme
    )(using Frame): Unit < Async =
        Async.race(
            inputLoop(terminal, focus, layout, renderTrigger),
            renderLoop(ui, terminal, layout, signals, renderer, focus, rendered, renderTrigger, theme)
        ).unit

    // ---- Input Loop ----

    private def inputLoop(
        terminal: TuiTerminal,
        focus: TuiFocus,
        layout: TuiLayout,
        renderTrigger: SignalRef[Int]
    )(using Frame): Unit < Async =
        import AllowUnsafe.embrace.danger
        for
            readBuf  <- Sync.defer(new Array[Byte](256))
            accumBuf <- Sync.defer(new Array[Byte](1024)) // pre-allocated accumulation buffer
            _ <- Loop(0) { (remainderLen: Int) =>
                for
                    n <- Sync.defer(terminal.read(readBuf))
                    r <-
                        if n > 0 then
                            var parsedEvents: Chunk[InputEvent] = Chunk.empty
                            var newRemainderLen: Int            = 0
                            for
                                _ <- Sync.defer {
                                    // Copy new bytes after remainder in accumulation buffer
                                    val totalLen = remainderLen + n
                                    java.lang.System.arraycopy(readBuf, 0, accumBuf, remainderLen, n)
                                    val all = Chunk.from(java.util.Arrays.copyOfRange(accumBuf, 0, totalLen))
                                    TuiInput.parse(all) { (events, leftover) =>
                                        parsedEvents = events
                                        // Copy leftover bytes to start of accumBuf for next iteration
                                        newRemainderLen = leftover.size
                                        if newRemainderLen > 0 then
                                            // unsafe: while for byte copy
                                            var i = 0
                                            while i < newRemainderLen do
                                                accumBuf(i) = leftover(i)
                                                i += 1
                                            end while
                                        end if
                                    }
                                }
                                quit <- dispatchEvents(parsedEvents, focus, layout, 0)
                                // Notify render loop that visual state may have changed (focus, cursor, hover)
                                tc <- renderTrigger.get
                                _  <- renderTrigger.set(tc + 1)
                            yield
                                if quit then Loop.done[Int, Unit](())
                                else Loop.continue(newRemainderLen)
                            end for
                        else
                            Async.sleep(InputPollInterval).andThen(Loop.continue(remainderLen))
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
                case InputEvent.Key(UI.Keyboard.Char('c'), true, false, false) => true
                case e =>
                    focus.dispatch(e, layout).andThen {
                        dispatchEvents(events, focus, layout, i + 1)
                    }
    end dispatchEvents

    // ---- Render Loop ----

    private def renderLoop(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus,
        rendered: SignalRef[UI],
        renderTrigger: SignalRef[Int],
        theme: TuiResolvedTheme
    )(using Frame): Nothing < Async =
        import AllowUnsafe.embrace.danger
        var cursorOn = true
        Loop.forever {
            for
                sigs <- Sync.defer(signals.toSpan)
                // Race: signal/trigger changes return true; blink timeout returns false
                contentChanged <- Async.race(Array.tabulate(sigs.size + 2)(i =>
                    if i < sigs.size then sigs(i).next.andThen(true)
                    else if i == sigs.size then renderTrigger.next.andThen(true)
                    else Async.sleep(CursorBlinkInterval).andThen(false)
                ))
                _ <- Async.sleep(RenderDebounce)
                _ <- Sync.defer {
                    // Reset blink on content/focus change; toggle on blink timeout
                    if contentChanged then cursorOn = true
                    else cursorOn = !cursorOn
                    if terminal.pollResize() then
                        renderer.resize(terminal.cols, terminal.rows)
                        renderer.invalidate()
                    doRender(ui, terminal, layout, signals, renderer, focus, theme, cursorOn)
                }
                _ <- rendered.set(ui)
            yield ()
            end for
        }
    end renderLoop

    // ---- Synchronous Pipeline ----

    private[kyo] def doRender(
        ui: UI,
        terminal: TuiTerminal,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        renderer: TuiRenderer,
        focus: TuiFocus,
        theme: TuiResolvedTheme,
        cursorOn: Boolean = true
    )(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        TuiFlatten.flatten(ui, layout, signals, terminal.cols, terminal.rows, theme)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, terminal.cols, terminal.rows)
        // Second pass: text wrapping during arrange may increase text heights.
        // Re-measure propagates those heights to parents, then re-arrange positions correctly.
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, terminal.cols, terminal.rows)
        focus.scan(layout)
        focus.adjustTextScroll(layout)
        TuiStyle.inherit(layout)
        TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
        renderer.flush(terminal.outputStream)
        // Show/hide cursor based on focused text input and blink state (CPS to avoid tuple allocation)
        focus.getCursorPosition(layout) { (cx, cy) =>
            if cursorOn && cx >= 0 && cy >= 0 && cx < terminal.cols && cy < terminal.rows then
                terminal.showCursor(cx, cy)
            else
                terminal.hideCursor()
        }
        terminal.flush()
    end doRender

    /** Render a single frame to a plain-text string grid. No TTY needed. For debugging. */
    def renderToString(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Default)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val resolved = TuiResolvedTheme.resolve(theme)
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        val renderer = new TuiRenderer(cols, rows)
        val focus    = new TuiFocus
        TuiFlatten.flatten(ui, layout, signals, cols, rows, resolved)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        focus.scan(layout)
        focus.adjustTextScroll(layout)
        TuiStyle.inherit(layout)
        TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
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
        // unsafe: while for regex iteration
        while matcher.find() do
            val r    = matcher.group(1).toInt - 1
            val c    = matcher.group(2).toInt - 1
            val text = matcher.group(3)
            var i    = 0
            // unsafe: while for character iteration
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
    def renderToRawAnsi(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Default)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val resolved = TuiResolvedTheme.resolve(theme)
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        val renderer = new TuiRenderer(cols, rows)
        TuiFlatten.flatten(ui, layout, signals, cols, rows, resolved)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        TuiStyle.inherit(layout)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, TuiRenderer.NoColor)
        baos.toString("UTF-8")
    end renderToRawAnsi

    /** Dump layout tree for debugging. */
    def renderLayoutDump(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Default)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val resolved = TuiResolvedTheme.resolve(theme)
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        TuiFlatten.flatten(ui, layout, signals, cols, rows, resolved)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        val sb  = new StringBuilder
        var idx = 0
        // unsafe: while for array iteration
        while idx < layout.count do
            val depth =
                var d = 0; var p = layout.parent(idx);
                // unsafe: while for parent chain traversal
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
