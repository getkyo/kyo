package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class TuiBackendTest extends Test:

    import AllowUnsafe.embrace.danger

    def mockTerminal(cols: Int, rows: Int): MockTerminalIO < (Async & Scope) =
        Channel.init[InputEvent](16).map { ch =>
            new MockTerminalIO(cols, rows, ch)
        }

    "terminal setup" - {
        "enters raw mode and alternate screen" in run {
            mockTerminal(20, 5).map { terminal =>
                TuiBackend.render(terminal, UI.div("hello")).map { session =>
                    session.stop.andThen {
                        assert(terminal.setupCalls.contains("enterRawMode"))
                        assert(terminal.setupCalls.contains("enterAlternateScreen"))
                        assert(terminal.setupCalls.contains("enableMouseTracking"))
                        assert(terminal.setupCalls.contains("hideCursor"))
                    }
                }
            }
        }

        "setup order is correct" in run {
            mockTerminal(20, 5).map { terminal =>
                TuiBackend.render(terminal, UI.div("hello")).map { session =>
                    session.stop.andThen {
                        val calls     = terminal.setupCalls.toSeq
                        val rawIdx    = calls.indexOf("enterRawMode")
                        val altIdx    = calls.indexOf("enterAlternateScreen")
                        val mouseIdx  = calls.indexOf("enableMouseTracking")
                        val cursorIdx = calls.indexOf("hideCursor")
                        assert(rawIdx < altIdx, "raw mode before alternate screen")
                        assert(altIdx < mouseIdx, "alternate screen before mouse tracking")
                        assert(mouseIdx < cursorIdx, "mouse tracking before hide cursor")
                    }
                }
            }
        }
    }

    "initial render" - {
        "writes ANSI output on start" in run {
            mockTerminal(20, 5).map { terminal =>
                TuiBackend.render(terminal, UI.div("hello")).map { session =>
                    session.stop.andThen {
                        assert(terminal.writtenBytes.nonEmpty, "should have written ANSI output")
                    }
                }
            }
        }
    }

    "input dispatch" - {
        "processes click event" in run {
            var clicked = false
            mockTerminal(20, 3).map { terminal =>
                val ui = UI.button.onClick { clicked = true }("Click")
                TuiBackend.render(terminal, ui).map { session =>
                    // Send a click event to the channel
                    terminal.eventChannel.put(InputEvent.Mouse(MouseKind.LeftPress, 2, 1)).andThen {
                        // Give the loop time to process
                        Async.sleep(100.millis).andThen {
                            session.stop.andThen {
                                assert(clicked, "button onClick should have fired")
                            }
                        }
                    }
                }
            }
        }
    }

end TuiBackendTest
