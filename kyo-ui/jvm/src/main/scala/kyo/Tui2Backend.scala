package kyo

import kyo.internal.tui2.*
import kyo.internal.tui2.widget.Render as WidgetRender

/** TUI2 backend for kyo-ui — immediate-mode tree walk, CellStyle opaque type, CPS layout.
  *
  * Renders the UI AST directly each frame (no flattening pass). Full alternate-screen TUI with complete Style support.
  */
object Tui2Backend extends UIBackend:

    /** How often to poll for terminal input when none is available. */
    private val InputPollInterval = 16.millis

    /** Debounce delay after a signal change before re-rendering. */
    private val RenderDebounce = 4.millis

    /** Cursor blink interval — cursor toggles visibility at this rate. */
    private val CursorBlinkInterval = 500.millis

    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
        val resolved = ResolvedTheme.resolve(theme)
        for
            rendered      <- Signal.initRef[UI](UI.empty)
            renderTrigger <- Signal.initRef[Int](0)
            terminal <- Sync.defer {
                val t = new Terminal
                t.enter()
                t
            }
            _ <- Scope.ensure(terminal.exit())
            screen   = new Screen(terminal.cols, terminal.rows)
            canvas   = new Canvas(screen)
            focus    = new FocusRing
            signals  = new SignalCollector(256)
            overlays = new OverlayCollector(4)
            ctx      = new RenderCtx(screen, canvas, focus, resolved, signals, overlays)
            _     <- Sync.defer { ctx.terminal = kyo.Maybe.Present(terminal) }
            _     <- Sync.defer(doRender(ui, terminal, ctx))
            _     <- rendered.set(ui)
            fiber <- Fiber.init(mainLoop(ui, terminal, ctx, rendered, renderTrigger))
        yield new UISession(fiber, rendered)
        end for
    end render

    // ---- Main Loop ----

    private def mainLoop(
        ui: UI,
        terminal: Terminal,
        ctx: RenderCtx,
        rendered: SignalRef[UI],
        renderTrigger: SignalRef[Int]
    )(using Frame): Unit < Async =
        Async.race(
            inputLoop(ui, terminal, ctx, renderTrigger),
            renderLoop(ui, terminal, ctx, rendered, renderTrigger)
        ).unit

    // ---- Input Loop ----

    private def inputLoop(
        ui: UI,
        terminal: Terminal,
        ctx: RenderCtx,
        renderTrigger: SignalRef[Int]
    )(using Frame): Unit < Async =
        import AllowUnsafe.embrace.danger
        for
            readBuf  <- Sync.defer(new Array[Byte](256))
            accumBuf <- Sync.defer(new Array[Byte](1024))
            _ <- Loop(0) { (remainderLen: Int) =>
                for
                    n <- Sync.defer(terminal.read(readBuf))
                    r <-
                        if n > 0 then
                            var parsedEvents: Chunk[InputEvent] = Chunk.empty
                            var newRemainderLen: Int            = 0
                            for
                                _ <- Sync.defer {
                                    val totalLen = remainderLen + n
                                    java.lang.System.arraycopy(readBuf, 0, accumBuf, remainderLen, n)
                                    val all = Chunk.from(java.util.Arrays.copyOfRange(accumBuf, 0, totalLen))
                                    InputParser.parse(all) { (events, leftover) =>
                                        parsedEvents = events
                                        newRemainderLen = leftover.size
                                        if newRemainderLen > 0 then
                                            copyLeftover(leftover, accumBuf, 0, newRemainderLen)
                                    }
                                }
                                quit <- dispatchEvents(parsedEvents, ui, terminal, ctx, 0)
                                tc   <- renderTrigger.get
                                _    <- renderTrigger.set(tc + 1)
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

    private def copyLeftover(src: Chunk[Byte], dst: Array[Byte], i: Int, len: Int): Unit =
        import scala.annotation.tailrec
        @tailrec def loop(idx: Int): Unit =
            if idx < len then
                dst(idx) = src(idx)
                loop(idx + 1)
        loop(i)
    end copyLeftover

    private[kyo] def dispatchEvents(
        events: Chunk[InputEvent],
        ui: UI,
        terminal: Terminal,
        ctx: RenderCtx,
        i: Int
    )(using Frame, AllowUnsafe): Boolean < Sync =
        if i >= events.size then false
        else
            events(i) match
                case InputEvent.Key(UI.Keyboard.Char('c'), true, false, false) => true
                case e =>
                    Sync.defer {
                        EventDispatch.dispatch(ui, e, terminal.cols, terminal.rows, ctx)
                    }.andThen {
                        dispatchEvents(events, ui, terminal, ctx, i + 1)
                    }
    end dispatchEvents

    // ---- Render Loop ----

    private def renderLoop(
        ui: UI,
        terminal: Terminal,
        ctx: RenderCtx,
        rendered: SignalRef[UI],
        renderTrigger: SignalRef[Int]
    )(using Frame): Nothing < Async =
        import AllowUnsafe.embrace.danger
        var cursorOn = true
        Loop.forever {
            val sigCount = ctx.signals.size
            for
                contentChanged <- Async.race(Array.tabulate(sigCount + 2)(i =>
                    if i < sigCount then ctx.signals(i).next.andThen(true)
                    else if i == sigCount then renderTrigger.next.andThen(true)
                    else Async.sleep(CursorBlinkInterval).andThen(false)
                ))
                _ <- Async.sleep(RenderDebounce)
                _ <- Sync.defer {
                    if contentChanged then cursorOn = true
                    else cursorOn = !cursorOn
                    if terminal.pollResize() then
                        ctx.screen.resize(terminal.cols, terminal.rows)
                        ctx.screen.invalidate()
                    doRender(ui, terminal, ctx, cursorOn)
                }
                _ <- rendered.set(ui)
            yield ()
            end for
        }
    end renderLoop

    // ---- Synchronous Pipeline ----

    private[kyo] def doRender(
        ui: UI,
        terminal: Terminal,
        ctx: RenderCtx,
        cursorOn: Boolean = true
    )(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val w = terminal.cols
        val h = terminal.rows

        // Reset state for new frame
        ctx.beginFrame()
        ctx.screen.resetClip()
        ctx.screen.clear()

        // Scan focus ring
        ctx.focus.scan(ui, ctx)

        // Render tree
        WidgetRender.render(ui, 0, 0, w, h, ctx)

        // Render overlays (second pass)
        renderOverlays(ctx, w, h)

        // Render dropdown (after overlays, clip already reset)
        ctx.screen.resetClip()
        ctx.renderDropdown()

        // Flush screen to terminal
        ctx.screen.flush(Screen.StreamSink(terminal.outputStream), Screen.TrueColor)

        // Emit raw image sequences (after cell flush, before cursor)
        import scala.annotation.tailrec
        @tailrec def emitRaw(i: Int): Unit =
            if i < ctx.canvas.rawSequences.size then
                terminal.outputStream.write(ctx.canvas.rawSequences(i))
                emitRaw(i + 1)
        emitRaw(0)
        ctx.canvas.rawSequences.reset()

        // Cursor
        if cursorOn && ctx.focus.cursorScreenX >= 0 && ctx.focus.cursorScreenY >= 0 &&
            ctx.focus.cursorScreenX < w && ctx.focus.cursorScreenY < h
        then
            terminal.showCursor(ctx.focus.cursorScreenX, ctx.focus.cursorScreenY)
        else
            terminal.hideCursor()
        end if

        terminal.flush()
    end doRender

    private def renderOverlays(ctx: RenderCtx, w: Int, h: Int)(using Frame, AllowUnsafe): Unit =
        import scala.annotation.tailrec
        @tailrec def loop(i: Int): Unit =
            if i < ctx.overlays.size then
                val elem = ctx.overlays(i)
                WidgetRender.renderElement(elem, 0, 0, w, h, ctx)
                loop(i + 1)
        loop(0)
    end renderOverlays

    /** Render a single frame to a plain-text string grid. No TTY needed. For debugging/testing. */
    def renderToString(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Default)(using Frame): String =
        import AllowUnsafe.embrace.danger
        val resolved = ResolvedTheme.resolve(theme)
        val screen   = new Screen(cols, rows)
        val canvas   = new Canvas(screen)
        val focus    = new FocusRing
        val signals  = new SignalCollector(256)
        val overlays = new OverlayCollector(4)
        val ctx      = new RenderCtx(screen, canvas, focus, resolved, signals, overlays)

        ctx.beginFrame()
        screen.clear()
        focus.scan(ui, ctx)
        WidgetRender.render(ui, 0, 0, cols, rows, ctx)
        renderOverlays(ctx, cols, rows)
        screen.resetClip()
        ctx.renderDropdown()

        // Flush to buffer with no-color tier to get just cursor positioning + text
        val baos = new java.io.ByteArrayOutputStream()
        screen.flush(Screen.StreamSink(baos), Screen.NoColor)
        val raw = baos.toString("UTF-8")

        // Strip all ANSI escape sequences, keeping only cursor positioning and text
        val grid = Array.fill(rows)(Array.fill(cols)(' '))
        val stripped = raw.replaceAll("\u001b\\[[0-9;]*m", "") // strip SGR
            .replaceAll("\u001b\\[\\?[^\u001b]*", "") // strip private modes
        val pattern  = java.util.regex.Pattern.compile("\u001b\\[(\\d+);(\\d+)H([^\u001b]*)")
        val matcher  = pattern.matcher(stripped)
        // regex iteration — not a hot path, debug-only
        import scala.annotation.tailrec
        @tailrec def matchLoop(): Unit =
            if matcher.find() then
                val r    = matcher.group(1).toInt - 1
                val c    = matcher.group(2).toInt - 1
                val text = matcher.group(3)
                @tailrec def charLoop(i: Int): Unit =
                    if i < text.length then
                        val col = c + i
                        if r >= 0 && r < rows && col >= 0 && col < cols then
                            grid(r)(col) = text.charAt(i)
                        charLoop(i + 1)
                charLoop(0)
                matchLoop()
        matchLoop()
        grid.map(_.mkString).mkString("\n")
    end renderToString

end Tui2Backend
