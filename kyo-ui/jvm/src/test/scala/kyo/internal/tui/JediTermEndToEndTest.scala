package kyo.internal.tui

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.Terminal
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalMode
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import kyo.*
import kyo.Length.*
import kyo.Test
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

/** End-to-end tests using JetBrains JediTerm as the ANSI interpreter. This is a production-grade terminal emulator — if our ANSI output
  * looks wrong here, it will look wrong in real terminals.
  */
class JediTermEndToEndTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Create a JediTerm terminal, feed ANSI bytes, return screen content as string. */
    def jediTermScreen(ansiBytes: Array[Byte], cols: Int, rows: Int): String =
        val styleState = new StyleState
        val textBuffer = new TerminalTextBuffer(cols, rows, styleState)
        val display = new TerminalDisplay:
            def setCursor(x: Int, y: Int): Unit                                = ()
            def setCursorShape(shape: com.jediterm.terminal.CursorShape): Unit = ()
            def beep(): Unit                                                   = ()
            def scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int): Unit =
                textBuffer.scrollArea(scrollRegionTop, dy, scrollRegionTop + scrollRegionSize - 1)
            def setCursorVisible(visible: Boolean): Unit                                         = ()
            def setScrollingEnabled(enabled: Boolean): Unit                                      = ()
            def setBlinkingCursor(enabled: Boolean): Unit                                        = ()
            def setWindowTitle(name: String): Unit                                               = ()
            def terminalMouseModeSet(mode: com.jediterm.terminal.emulator.mouse.MouseMode): Unit = ()
            def setMouseFormat(format: com.jediterm.terminal.emulator.mouse.MouseFormat): Unit   = ()
            def getSelection(): com.jediterm.terminal.model.TerminalSelection                    = null
            def getWindowTitle(): String                                                         = ""
            def useAlternateScreenBuffer(enabled: Boolean): Unit                                 = ()
            def ambiguousCharsAreDoubleWidth(): Boolean                                          = false
            override def getWindowForeground(): com.jediterm.core.Color = new com.jediterm.core.Color(255, 255, 255)
            override def getWindowBackground(): com.jediterm.core.Color = new com.jediterm.core.Color(0, 0, 0)

        val terminal = new JediTerminal(display, textBuffer, styleState)
        val stream   = new ArrayTerminalDataStream(new String(ansiBytes, "UTF-8").toCharArray)
        val emulator = new JediEmulator(stream, terminal)

        // Process all bytes
        while emulator.hasNext do
            emulator.next()

        // Extract screen content
        val sb = new StringBuilder
        for row <- 0 until rows do
            val line = textBuffer.getLine(row)
            for col <- 0 until cols do
                if col < line.getText.length then
                    sb.append(line.getText.charAt(col))
                else
                    sb.append(' ')
            end for
            if row < rows - 1 then sb.append('\n')
        end for
        sb.toString
    end jediTermScreen

    "initial render matches gridToString" - {
        "simple text" in run {
            val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            Pipeline.renderFrame(UI.div("hello"), state, Rect(0, 0, 20, 1)).map { ansi =>
                val grid = state.prevGrid match
                    case Present(g) => RenderToString.gridToString(g)
                    case _          => ""
                val jedi = jediTermScreen(ansi, 20, 1)
                assert(grid == jedi, s"Grid:\n$grid\nJediTerm:\n$jedi")
            }
        }

        "form with inputs" in run {
            val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val ui = UI.div(
                UI.label("Name:"),
                UI.input.value("John"),
                UI.label("Email:"),
                UI.input.value("test@example.com")
            )
            Pipeline.renderFrame(ui, state, Rect(0, 0, 40, 8)).map { ansi =>
                val grid = state.prevGrid match
                    case Present(g) => RenderToString.gridToString(g)
                    case _          => ""
                val jedi = jediTermScreen(ansi, 40, 8)
                assert(grid == jedi, s"Grid:\n$grid\nJediTerm:\n$jedi")
            }
        }
    }

    "after typing — the critical test" - {
        "content below input survives re-render" in run {
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
                    ansi1 <- Pipeline.renderFrame(ui, state, viewport)

                    // Tab to focus name
                    _     <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Tab, false, false, false), state)
                    ansi2 <- Pipeline.renderFrame(ui, state, viewport)

                    // Type A
                    _     <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('A'), false, false, false), state)
                    ansi3 <- Pipeline.renderFrame(ui, state, viewport)
                yield
                    // Feed all three renders through JediTerm
                    val combined = ansi1 ++ ansi2 ++ ansi3
                    val jedi     = jediTermScreen(combined, 40, 10)

                    val grid = state.prevGrid match
                        case Present(g) => RenderToString.gridToString(g)
                        case _          => ""

                    // THE critical assertions
                    assert(jedi.contains("Name:"), s"JediTerm: Name: missing\n$jedi")
                    assert(jedi.contains("Email:"), s"JediTerm: Email: vanished!\n$jedi")
                    assert(jedi.contains("footer"), s"JediTerm: footer vanished!\n$jedi")
                    assert(jedi.contains("A"), s"JediTerm: typed A missing\n$jedi")
                    assert(grid == jedi, s"Grid vs JediTerm mismatch:\nGrid:\n$grid\nJediTerm:\n$jedi")
                end for
        }

        "exact demo UI at 80x24 survives typing Alice" in run {
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
                                UI.div(UI.label("Name:"), UI.input.value(nameRef).placeholder("John Doe")),
                                UI.div(UI.label("Email:"), UI.email.value(emailRef).placeholder("john@example.com")),
                                UI.div(UI.label("Password:"), UI.password.value(passRef)),
                                UI.div(
                                    UI.label("Role:"),
                                    UI.select.value(roleRef)(
                                        UI.option.value("developer")("Developer"),
                                        UI.option.value("designer")("Designer"),
                                        UI.option.value("manager")("Manager")
                                    )
                                ),
                                UI.div.style(Style.row)(UI.checkbox.checked(true), UI.span(" I agree")),
                                UI.button("Submit")
                            )
                        ),
                        UI.div.style(Style.width(50.px))(UI.h2("Submissions"))
                    )
                )
                val state    = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                val viewport = Rect(0, 0, 80, 24)
                val allAnsi  = new java.io.ByteArrayOutputStream()

                for
                    ansi1 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(ansi1)

                    // Tab to focus name
                    _     <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Tab, false, false, false), state)
                    ansi2 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(ansi2)

                    // Type "Alice"
                    _  <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('A'), false, false, false), state)
                    a3 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(a3)
                    _  <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('l'), false, false, false), state)
                    a4 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(a4)
                    _  <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('i'), false, false, false), state)
                    a5 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(a5)
                    _  <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('c'), false, false, false), state)
                    a6 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(a6)
                    _  <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('e'), false, false, false), state)
                    a7 <- Pipeline.renderFrame(ui, state, viewport)
                    _ = allAnsi.write(a7)
                yield
                    val jedi = jediTermScreen(allAnsi.toByteArray, 80, 24)
                    assert(jedi.contains("Alice"), s"JediTerm: Alice missing\n$jedi")
                    assert(jedi.contains("Email:"), s"JediTerm: Email: vanished after typing!\n$jedi")
                    assert(jedi.contains("Password:"), s"JediTerm: Password: vanished!\n$jedi")
                    assert(jedi.contains("Role:"), s"JediTerm: Role: vanished!\n$jedi")
                    assert(jedi.contains("Submit"), s"JediTerm: Submit vanished!\n$jedi")
                    assert(jedi.contains("Submissions"), s"JediTerm: Submissions vanished!\n$jedi")
                end for
        }
    }

    "with real JvmTerminalIO output capture" - {
        "full TuiBackend render cycle matches" in run {
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
                // Capture ALL bytes that would go to the real terminal
                val capturedOutput = new java.io.ByteArrayOutputStream()
                // Simulate terminal response to size query: \e[24;80R
                val sizeResponse = "\u001b[24;80R".getBytes
                val fakeInput    = new java.io.PipedInputStream()
                val inputFeeder  = new java.io.PipedOutputStream(fakeInput)

                val terminal = new JvmTerminalIO(capturedOutput, fakeInput)

                // Feed size response so querySize works
                inputFeeder.write(sizeResponse)
                inputFeeder.flush()

                for
                    // Simulate TuiBackend setup
                    _ <- terminal.enterRawMode // stty call — no stdout output
                    _ <- terminal.enterAlternateScreen
                    _ <- terminal.enableMouseTracking
                    _ <- terminal.hideCursor

                    // Initial render (triggers size query)
                    size <- terminal.size
                    (cols, rows) = size
                yield
                    val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))
                    for
                        ansi1 <- Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))
                        _     <- terminal.write(ansi1)
                        _     <- terminal.flush

                        // Tab
                        _     <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Tab, false, false, false), state)
                        ansi2 <- Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))
                        _     <- terminal.write(ansi2)
                        _     <- terminal.flush

                        // Type A
                        _     <- Pipeline.dispatchEvent(InputEvent.Key(UI.Keyboard.Char('A'), false, false, false), state)
                        ansi3 <- Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))
                        _     <- terminal.write(ansi3)
                        _     <- terminal.flush
                    yield
                        // Feed ALL captured bytes through JediTerm
                        val allBytes = capturedOutput.toByteArray
                        val jedi     = jediTermScreen(allBytes, cols, rows)

                        assert(jedi.contains("Name:"), s"Name: missing from terminal output\n$jedi")
                        assert(jedi.contains("Email:"), s"Email: vanished from terminal output!\n$jedi")
                        assert(jedi.contains("footer"), s"footer vanished from terminal output!\n$jedi")
                        assert(jedi.contains("A"), s"typed A missing from terminal output\n$jedi")
                    end for
                end for
        }
    }

end JediTermEndToEndTest
