package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** End-to-end tests: render UI → CellGrid → ANSI bytes → terminal emulator → verify screen matches. This catches bugs in the Differ's ANSI
  * output that gridToString tests miss.
  */
class AnsiEndToEndTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Render a UI, produce ANSI bytes via Differ, feed through AnsiTerminalEmulator, return emulator screen. */
    def renderViaAnsi(ui: UI, cols: Int, rows: Int): (String, String) < (Async & Scope) =
        val state     = new ScreenState(ResolvedTheme.resolve(Theme.Default))
        val viewport  = Rect(0, 0, cols, rows)
        val emptyGrid = CellGrid.empty(cols, rows)
        Pipeline.renderFrame(ui, state, viewport).map { ansiBytes =>
            val gridStr = state.prevGrid match
                case Present(grid) => RenderToString.gridToString(grid)
                case _             => ""
            val emu = new AnsiTerminalEmulator(cols, rows)
            emu.feed(ansiBytes)
            (gridStr, emu.screen)
        }
    end renderViaAnsi

    /** Render, dispatch event, re-render. Return emulator screen after second render. */
    def renderDispatchRender(ui: UI, cols: Int, rows: Int, event: InputEvent): (String, String) < (Async & Scope) =
        val state    = new ScreenState(ResolvedTheme.resolve(Theme.Default))
        val viewport = Rect(0, 0, cols, rows)
        for
            firstAnsi  <- Pipeline.renderFrame(ui, state, viewport)
            _          <- Pipeline.dispatchEvent(event, state)
            secondAnsi <- Pipeline.renderFrame(ui, state, viewport)
        yield
            val gridStr = state.prevGrid match
                case Present(grid) => RenderToString.gridToString(grid)
                case _             => ""
            // Feed BOTH renders through emulator (second is incremental diff)
            val emu = new AnsiTerminalEmulator(cols, rows)
            emu.feed(firstAnsi)
            emu.feed(secondAnsi)
            (gridStr, emu.screen)
        end for
    end renderDispatchRender

    "initial render" - {
        "simple text matches" in run {
            renderViaAnsi(UI.div("hello"), 10, 1).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }

        "multi-line matches" in run {
            renderViaAnsi(
                UI.div(
                    UI.span("top"),
                    UI.span("bot")
                ),
                10,
                3
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }

        "bordered box matches" in run {
            renderViaAnsi(
                UI.div.style(
                    Style.border(1.px, Style.Color.rgb(128, 128, 128))
                        .width(10.px).height(3.px)
                )("hi"),
                10,
                3
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }

        "form with labels and inputs matches" in run {
            renderViaAnsi(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value("John"),
                    UI.label("Email:"),
                    UI.input.value("john@ex.com")
                ),
                30,
                8
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }

        "row layout matches" in run {
            renderViaAnsi(
                UI.div.style(Style.row)(
                    UI.div.style(Style.width(10.px))("left"),
                    UI.div.style(Style.width(10.px))("right")
                ),
                20,
                1
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }

        "demo-like layout matches" in run {
            renderViaAnsi(
                UI.div(
                    UI.h1("Title"),
                    UI.hr,
                    UI.div.style(Style.row.gap(2.px))(
                        UI.div.style(Style.width(20.px))(
                            UI.span("left content")
                        ),
                        UI.div.style(Style.width(20.px))(
                            UI.span("right content")
                        )
                    )
                ),
                50,
                10
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"Grid:\n$grid\nANSI:\n$ansi")
            }
        }
    }

    "after dispatch" - {
        "tab then re-render preserves content" in run {
            renderDispatchRender(
                UI.div(
                    UI.input.value("test"),
                    UI.button("Go")
                ),
                20,
                5,
                InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false)
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"After tab:\nGrid:\n$grid\nANSI:\n$ansi")
            }
        }

        "click then re-render preserves content" in run {
            renderDispatchRender(
                UI.div(
                    UI.label("Name:"),
                    UI.input.value("hello"),
                    UI.span("footer")
                ),
                30,
                5,
                InputEvent.Mouse(MouseKind.LeftPress, 5, 1)
            ).map { (grid, ansi) =>
                assert(grid == ansi, s"After click:\nGrid:\n$grid\nANSI:\n$ansi")
            }
        }
    }

    "multi-cycle" - {
        "type character preserves all content" in run {
            val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val ref   = SignalRef.Unsafe.init("")
            val ui = UI.div(
                UI.label("Name:"),
                UI.input.value(ref.safe),
                UI.span("footer")
            )
            val viewport = Rect(0, 0, 30, 5)
            val emu      = new AnsiTerminalEmulator(30, 5)

            for
                ansi1 <- Pipeline.renderFrame(ui, state, viewport)
                _ = emu.feed(ansi1)

                // Tab to focus input
                _ <- Pipeline.dispatchEvent(
                    InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false),
                    state
                )
                ansi2 <- Pipeline.renderFrame(ui, state, viewport)
                _ = emu.feed(ansi2)

                // Type 'A'
                _ <- Pipeline.dispatchEvent(
                    InputEvent.Key(UI.Keyboard.Char('A'), ctrl = false, alt = false, shift = false),
                    state
                )
                ansi3 <- Pipeline.renderFrame(ui, state, viewport)
                _ = emu.feed(ansi3)
            yield
                val gridStr = state.prevGrid match
                    case Present(grid) => RenderToString.gridToString(grid)
                    case _             => ""
                val emuStr = emu.screen
                assert(emuStr.contains("Name:"), s"Name: missing from ANSI output:\n$emuStr")
                assert(emuStr.contains("footer"), s"footer missing from ANSI output:\n$emuStr")
                assert(gridStr == emuStr, s"Grid vs ANSI mismatch after typing:\nGrid:\n$gridStr\nANSI:\n$emuStr")
            end for
        }
    }

    "ansi diff inspection" - {
        "diff after typing does not contain screen-clearing sequences" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
            yield
                val ui = UI.div(
                    UI.label("Name:"),
                    UI.input.value(nameRef),
                    UI.label("Email:"),
                    UI.input.value(emailRef),
                    UI.span("footer")
                )
                val state    = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                val viewport = Rect(0, 0, 40, 10)

                for
                    _             <- Pipeline.renderFrame(ui, state, viewport)
                    _             <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Tab, false, false, false), state)
                    _             <- Pipeline.renderFrame(ui, state, viewport)
                    _             <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('A'), false, false, false), state)
                    ansiAfterType <- Pipeline.renderFrame(ui, state, viewport)
                yield
                    val str = new String(ansiAfterType, "UTF-8")
                    // Should NOT contain screen clear \e[2J or erase-to-end \e[J or \e[0J
                    assert(!str.contains("\u001b[2J"), s"diff contains screen clear!\nDiff: ${str.replace("\u001b", "ESC")}")
                    assert(!str.contains("\u001b[J"), s"diff contains erase-to-end!\nDiff: ${str.replace("\u001b", "ESC")}")
                    assert(!str.contains("\u001b[0J"), s"diff contains erase-below!\nDiff: ${str.replace("\u001b", "ESC")}")
                    // The diff should be small — only the changed cells
                    assert(
                        ansiAfterType.length < 500,
                        s"diff suspiciously large: ${ansiAfterType.length} bytes\nDiff: ${str.replace("\u001b", "ESC")}"
                    )
                end for
        }
    }

    "exact demo reproduction at 80x24" - {
        "type in name field preserves rest of form" in run {
            for
                nameRef  <- Signal.initRef("")
                emailRef <- Signal.initRef("")
                passRef  <- Signal.initRef("")
                roleRef  <- Signal.initRef("developer")
            yield
                val ui = UI.div(
                    UI.h1("Kyo UI — Form Demo"),
                    UI.hr,
                    UI.div.style(Style.row.gap(2.px))(
                        UI.div.style(Style.width(40.px))(
                            UI.h2("New Entry"),
                            UI.form(
                                UI.div(
                                    UI.label("Name:"),
                                    UI.input.value(nameRef).placeholder("John Doe")
                                ),
                                UI.div(
                                    UI.label("Email:"),
                                    UI.email.value(emailRef).placeholder("john@example.com")
                                ),
                                UI.div(
                                    UI.label("Password:"),
                                    UI.password.value(passRef).placeholder("dots")
                                ),
                                UI.div(
                                    UI.label("Role:"),
                                    UI.select.value(roleRef)(
                                        UI.option.value("developer")("Developer"),
                                        UI.option.value("designer")("Designer"),
                                        UI.option.value("manager")("Manager")
                                    )
                                ),
                                UI.div.style(Style.row)(
                                    UI.checkbox.checked(true),
                                    UI.span(" I agree")
                                ),
                                UI.button("Submit")
                            )
                        ),
                        UI.div.style(Style.width(50.px))(
                            UI.h2("Submissions")
                        )
                    )
                )
                val state    = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                val viewport = Rect(0, 0, 80, 24)
                val emu      = new AnsiTerminalEmulator(80, 24)

                for
                    // Initial render
                    ansi1 <- Pipeline.renderFrame(ui, state, viewport)
                    _             = emu.feed(ansi1)
                    initialScreen = emu.screen

                    // Verify initial content
                    _ = assert(initialScreen.contains("Name:"), s"initial: Name: missing")
                    _ = assert(initialScreen.contains("Email:"), s"initial: Email: missing")
                    _ = assert(initialScreen.contains("Password:"), s"initial: Password: missing")
                    _ = assert(initialScreen.contains("Submit"), s"initial: Submit missing")

                    // Tab to focus name input
                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi2 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = emu.feed(ansi2)

                    // Type 'A'
                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Char('A'), ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi3 <- Pipeline.renderFrame(ui, state, viewport)
                    _      = emu.feed(ansi3)
                    afterA = emu.screen

                    // Check everything is still there after typing
                    _ = assert(afterA.contains("A"), s"after A: typed char missing\n$afterA")
                    _ = assert(afterA.contains("Email:"), s"after A: Email: vanished!\n$afterA")
                    _ = assert(afterA.contains("Password:"), s"after A: Password: vanished!\n$afterA")

                    // Type more
                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Char('l'), ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi4 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = emu.feed(ansi4)

                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Char('i'), ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi5 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = emu.feed(ansi5)

                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Char('c'), ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi6 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = emu.feed(ansi6)

                    _ <- Pipeline.dispatchEvent(
                        InputEvent.Key(UI.Keyboard.Char('e'), ctrl = false, alt = false, shift = false),
                        state
                    )
                    ansi7 <- Pipeline.renderFrame(ui, state, viewport)
                    _          = emu.feed(ansi7)
                    afterAlice = emu.screen
                yield
                    // THE critical assertion — does content below the input survive?
                    // Input renders with intrinsic width in column layout; scroll shows last char 'e'.
                    assert(afterAlice.contains("e"), s"after Alice: last typed char missing\n$afterAlice")
                    assert(afterAlice.contains("Email:"), s"after Alice: Email: vanished!\n$afterAlice")
                    assert(afterAlice.contains("Password:"), s"after Alice: Password: vanished!\n$afterAlice")
                    assert(afterAlice.contains("Role:"), s"after Alice: Role: vanished!\n$afterAlice")
                    assert(afterAlice.contains("Submit"), s"after Alice: Submit vanished!\n$afterAlice")
                    assert(afterAlice.contains("Submissions"), s"after Alice: Submissions vanished!\n$afterAlice")
                end for
        }
    }

end AnsiEndToEndTest
