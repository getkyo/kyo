package kyo.internal.tui.pipeline

import kyo.*

/** TUI backend: wires Pipeline with terminal I/O. Shared code — platform-specific TerminalIO implementations live in jvm/js/native.
  *
  * The render loop is pure computation composition (Rule 3). Terminal I/O methods return computations. No AllowUnsafe in the loop body.
  */
object TuiBackend:

    /** Start a TUI session. Sets up terminal, renders initial frame, runs the render loop. Returns a UISession that can be awaited or
      * stopped.
      *
      * @param tickInterval
      *   If set, the render loop re-renders at this interval even without input. Useful for animations, timers, games. Only one fiber ever
      *   calls renderFrame — no concurrent ScreenState access.
      */
    def render(
        terminal: TerminalIO,
        ui: UI,
        theme: Theme = Theme.Default,
        tickInterval: Maybe[Duration] = Absent
    )(using Frame): UISession < (Async & Scope) =
        for
            // Create state (Rule 2 boundary)
            state <- Sync.Unsafe.defer {
                new ScreenState(ResolvedTheme.resolve(theme))
            }
            rendered <- Signal.initRef[UI](UI.empty)

            // Terminal setup — each step is a computation, Scope.ensure registers cleanup
            _ <- terminal.enterRawMode
            _ <- terminal.enterAlternateScreen
            _ <- terminal.enableMouseTracking
            _ <- terminal.hideCursor

            // Register cleanup for both scope exit AND process termination
            _ <- Scope.ensure {
                terminal.showCursor
                    .andThen(terminal.disableMouseTracking)
                    .andThen(terminal.exitAlternateScreen)
                    .andThen(terminal.exitRawMode)
            }
            _ <- terminal.registerShutdownHook

            // Initial render
            _ <- renderFrame(terminal, ui, state)
            _ <- rendered.set(ui)

            // Start render loop fiber
            fiber <- Fiber.init(mainLoop(terminal, ui, state, rendered, tickInterval))
        yield new UISession(fiber, rendered)
    end render

    /** Render one frame: query terminal size, run pipeline, write ANSI output, position cursor. */
    private def renderFrame(terminal: TerminalIO, ui: UI, state: ScreenState)(using Frame): Unit < (Async & Scope) =
        terminal.size.map { (cols, rows) =>
            Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows)).map { ansi =>
                terminal.write(ansi).andThen(terminal.flush).andThen {
                    // Position terminal cursor at the text cursor location (if any focused input)
                    positionCursor(terminal, state)
                }
            }
        }

    /** Position terminal native cursor at the focused input's cursor position. */
    private def positionCursor(terminal: TerminalIO, state: ScreenState)(using Frame): Unit < (Async & Scope) =
        Sync.Unsafe.defer {
            state.prevLayout match
                case Present(layout) =>
                    findCursorInLayout(layout.base) match
                        case Present((col, row)) =>
                            terminal.write(TerminalEscape.cursorPosition(row, col).getBytes)
                                .andThen(terminal.write(TerminalEscape.CursorStyleBar.getBytes))
                                .andThen(terminal.write(TerminalEscape.ShowCursor.getBytes))
                                .andThen(terminal.flush)
                        case _ =>
                            terminal.write(TerminalEscape.HideCursor.getBytes)
                                .andThen(terminal.flush)
                case _ => ()
        }

    private def findCursorInLayout(laid: Laid)(using AllowUnsafe): Maybe[(Int, Int)] = laid match
        case n: Laid.Node =>
            n.handlers.cursorPosition match
                case Present((col, row)) => Maybe((n.content.x + col, n.content.y + row))
                case _ =>
                    var i = 0
                    while i < n.children.size do
                        val found = findCursorInLayout(n.children(i))
                        if found.nonEmpty then return found
                        i += 1
                    end while
                    Absent
        case _ => Absent

    /** Render loop. When tickInterval is set, a reader fiber feeds events into a channel, and the main loop drains the channel with a
      * timeout — re-rendering on each tick whether or not input arrived. Without tickInterval, blocks on readEvent directly. Only the main
      * loop fiber calls renderFrame — no concurrent ScreenState access.
      */
    private def mainLoop(
        terminal: TerminalIO,
        ui: UI,
        state: ScreenState,
        rendered: SignalRef[UI],
        tickInterval: Maybe[Duration]
    )(using Frame): Unit < (Async & Scope) =

        def doRender: Unit < (Async & Scope) =
            renderFrame(terminal, ui, state).andThen(rendered.set(ui))

        tickInterval match
            case Present(interval) =>
                // Channel-based: reader fiber pushes events, main loop pops with timeout
                Channel.init[InputEvent](8).map { chan =>
                    // Reader fiber: blocks on readEvent, sends to channel
                    Fiber.init {
                        Loop.forever {
                            terminal.readEvent.map(event => chan.put(event))
                        }
                    }.andThen {
                        // Main loop: drain channel with timeout, render each tick
                        def tickLoop: Unit < (Async & Scope) =
                            Abort.run[Timeout | Closed](Async.timeout(interval)(chan.take)).map {
                                case Result.Success(InputEvent.Key(UI.Keyboard.Char('c'), ctrl, _, _)) if ctrl =>
                                    () // exit
                                case Result.Success(event) =>
                                    // Got input: dispatch, then drain any more pending events
                                    Pipeline.dispatchEvent(event, state)
                                        .andThen(drainChannel(chan, state))
                                        .andThen(doRender)
                                        .andThen(tickLoop)
                                case _ =>
                                    // Timeout or closed: just render
                                    doRender.andThen(tickLoop)
                            }

                        tickLoop
                    }
                }

            case _ =>
                // Blocking mode: wait for input, dispatch, render, repeat
                def blockLoop: Unit < (Async & Scope) =
                    terminal.readEvent.map { event =>
                        event match
                            case InputEvent.Key(UI.Keyboard.Char('c'), ctrl, _, _) if ctrl =>
                                () // exit
                            case _ =>
                                Pipeline.dispatchEvent(event, state)
                                    .andThen(Async.sleep(10.millis))
                                    .andThen(doRender)
                                    .andThen(blockLoop)
                    }
                blockLoop
        end match
    end mainLoop

    /** Drain any pending events from the channel without blocking. */
    private def drainChannel(chan: Channel[InputEvent], state: ScreenState)(using Frame): Unit < (Async & Scope) =
        Abort.run[Closed](chan.poll).map {
            case Result.Success(Maybe.Present(event)) =>
                Pipeline.dispatchEvent(event, state).andThen(drainChannel(chan, state))
            case _ => () // empty or closed
        }

end TuiBackend
