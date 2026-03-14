package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class PipelineTest extends Test:

    import AllowUnsafe.embrace.danger

    "renderToString" - {
        "div with text contains hello" in run {
            RenderToString.render(UI.div("hello"), 20, 5).map { s =>
                assert(s.contains("hello"))
            }
        }

        "nested divs" in run {
            RenderToString.render(UI.div(UI.div("inner")), 20, 5).map { s =>
                assert(s.contains("inner"))
            }
        }

        "empty renders without error" in run {
            RenderToString.render(UI.empty, 20, 5).map { s =>
                assert(s.nonEmpty) // grid is all spaces
            }
        }
    }

    "Pipeline.renderFrame" - {
        "returns ANSI bytes" in run {
            Sync.Unsafe.defer {
                val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                Pipeline.renderFrame(UI.div("test"), state, Rect(0, 0, 20, 5)).map { ansi =>
                    assert(ansi.nonEmpty)
                }
            }
        }

        "updates prevLayout and prevGrid" in run {
            Sync.Unsafe.defer {
                val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                Pipeline.renderFrame(UI.div("test"), state, Rect(0, 0, 20, 5)).andThen {
                    assert(state.prevLayout.nonEmpty)
                    assert(state.prevGrid.nonEmpty)
                }
            }
        }
    }

    "Pipeline.dispatchEvent" - {
        "no prevLayout does not crash" in run {
            Sync.Unsafe.defer {
                val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Enter, false, false, false), state).andThen(succeed)
            }
        }
    }

end PipelineTest
