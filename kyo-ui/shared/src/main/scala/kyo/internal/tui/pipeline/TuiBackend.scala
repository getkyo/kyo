package kyo.internal.tui.pipeline

import kyo.*

/** TUI backend: wires Pipeline with terminal I/O. Shared code — platform-specific TerminalIO implementations live in jvm/js/native.
  *
  * The render loop is pure computation composition (Rule 3). Terminal I/O methods return computations. No AllowUnsafe in the loop body.
  */
object TuiBackend:

    /** Start a TUI session. Sets up terminal, renders initial frame, runs the render loop. Returns a UISession that can be awaited or
      * stopped.
      */
    def render(terminal: TerminalIO, ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
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
            fiber <- Fiber.init(mainLoop(terminal, ui, state, rendered))
        yield new UISession(fiber, rendered)
    end render

    /** Render one frame: query terminal size, run pipeline, write ANSI output. */
    private def renderFrame(terminal: TerminalIO, ui: UI, state: ScreenState)(using Frame): Unit < (Async & Scope) =
        terminal.size.map { (cols, rows) =>
            Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows)).map { ansi =>
                terminal.write(ansi).andThen(terminal.flush)
            }
        }

    /** Main loop: wait for input, dispatch, re-render. Sequential — no concurrent ScreenState access. */
    private def mainLoop(
        terminal: TerminalIO,
        ui: UI,
        state: ScreenState,
        rendered: SignalRef[UI]
    )(using Frame): Nothing < (Async & Scope) =
        Loop.forever {
            for
                event <- terminal.readEvent
                _     <- Pipeline.dispatchEvent(event, state)
                _     <- renderFrame(terminal, ui, state)
                _     <- rendered.set(ui)
            yield ()
        }

end TuiBackend
