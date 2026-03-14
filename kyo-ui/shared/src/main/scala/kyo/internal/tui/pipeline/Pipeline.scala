package kyo.internal.tui.pipeline

import kyo.*

/** Wires all pipeline steps together.
  *
  * renderFrame: lower → style → layout → paint → composite → diff dispatchEvent: routes input to pre-composed handlers
  */
object Pipeline:

    /** Render a single frame. Returns ANSI escape bytes to update the terminal.
      *
      * Lower is effectful (materializes Signals via asRef on first frame). All other steps are side-effectful under AllowUnsafe or pure.
      */
    def renderFrame(ui: UI, state: ScreenState, viewport: Rect)(using Frame): Array[Byte] < (Async & Scope) =
        Sync.Unsafe.defer {
            state.widgetState.beginFrame()
        }.andThen {
            Lower.lower(ui, state).map { lowerResult =>
                Sync.Unsafe.defer {
                    state.focusableIds = lowerResult.focusableIds

                    val rootStyle    = FlatStyle.fromTheme(state.theme)
                    val styled       = Styler.style(lowerResult.tree, rootStyle)
                    val layoutResult = Layout.layout(styled, viewport)
                    val (base, pop)  = Painter.paint(layoutResult, viewport)
                    val composited   = Compositor.composite(base, pop)
                    val prev         = state.prevGrid.getOrElse(CellGrid.empty(viewport.w, viewport.h))
                    val ansi         = Differ.diff(prev, composited)

                    state.prevLayout = Maybe(layoutResult)
                    state.prevGrid = Maybe(composited)
                    state.widgetState.sweep()

                    ansi
                }
            }
        }
    end renderFrame

    /** Dispatch an input event against the most recent layout. */
    def dispatchEvent(event: InputEvent, state: ScreenState)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            state.prevLayout
        }.map {
            case Present(layout) => Dispatch.dispatch(event, layout, state)
            case _               => ()
        }

end Pipeline
