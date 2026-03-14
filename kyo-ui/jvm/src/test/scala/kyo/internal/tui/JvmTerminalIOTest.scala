package kyo.internal.tui

import kyo.*
import kyo.Test
import kyo.internal.tui.pipeline.*

/** Tests JvmTerminalIO with captured I/O streams — no real terminal needed. */
class JvmTerminalIOTest extends Test:

    import AllowUnsafe.embrace.danger

    def capturedTerminal(inputBytes: Array[Byte] = Array.empty): (JvmTerminalIO, java.io.ByteArrayOutputStream) =
        val output = new java.io.ByteArrayOutputStream
        val input  = new java.io.ByteArrayInputStream(inputBytes)
        (new JvmTerminalIO(output, input), output)
    end capturedTerminal

    "terminal size" - {
        "parses ANSI size response" in run {
            // Simulate terminal response to \e[6n: \e[40;120R
            val response      = "\u001b[40;120R".getBytes
            val (terminal, _) = capturedTerminal(response)
            terminal.size.map { (cols, rows) =>
                assert(cols == 120, s"cols: $cols")
                assert(rows == 40, s"rows: $rows")
            }
        }
    }

    "ANSI output" - {
        "enterAlternateScreen writes escape sequence" in run {
            val (terminal, output) = capturedTerminal()
            terminal.enterAlternateScreen.andThen {
                val bytes = output.toString("UTF-8")
                assert(bytes.contains("\u001b[?1049h"), s"missing alternate screen: $bytes")
                assert(bytes.contains("\u001b[2J"), s"missing screen clear: $bytes")
                assert(bytes.contains("\u001b[H"), s"missing cursor home: $bytes")
            }
        }

        "exitAlternateScreen writes escape sequence" in run {
            val (terminal, output) = capturedTerminal()
            terminal.exitAlternateScreen.andThen {
                val bytes = output.toString("UTF-8")
                assert(bytes.contains("\u001b[?1049l"), s"missing exit alternate: $bytes")
            }
        }

        "hideCursor writes escape sequence" in run {
            val (terminal, output) = capturedTerminal()
            terminal.hideCursor.andThen {
                assert(output.toString("UTF-8").contains("\u001b[?25l"))
            }
        }

        "showCursor writes escape sequence" in run {
            val (terminal, output) = capturedTerminal()
            terminal.showCursor.andThen {
                assert(output.toString("UTF-8").contains("\u001b[?25h"))
            }
        }

        "enableMouseTracking writes SGR mode sequences" in run {
            val (terminal, output) = capturedTerminal()
            terminal.enableMouseTracking.andThen {
                val bytes = output.toString("UTF-8")
                assert(bytes.contains("\u001b[?1000h"), "missing mouse enable")
                assert(bytes.contains("\u001b[?1006h"), "missing SGR mode")
            }
        }

        "write outputs exact bytes" in run {
            val (terminal, output) = capturedTerminal()
            val data               = "hello".getBytes
            terminal.write(data).andThen(terminal.flush).andThen {
                assert(output.toString("UTF-8") == "hello")
            }
        }
    }

    "input parsing" - {
        "regular character" in run {
            val (terminal, _) = capturedTerminal(Array('A'.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('A'), ctrl = false, alt = false, shift = false))
            }
        }

        "enter key" in run {
            val (terminal, _) = capturedTerminal(Array(0x0d.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Enter, ctrl = false, alt = false, shift = false))
            }
        }

        "tab key" in run {
            val (terminal, _) = capturedTerminal(Array(0x09.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false))
            }
        }

        "backspace key" in run {
            val (terminal, _) = capturedTerminal(Array(0x7f.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Backspace, ctrl = false, alt = false, shift = false))
            }
        }

        "ctrl+a" in run {
            val (terminal, _) = capturedTerminal(Array(0x01.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('a'), ctrl = true, alt = false, shift = false))
            }
        }

        "arrow up escape sequence" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'A').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowUp, ctrl = false, alt = false, shift = false))
            }
        }

        "arrow down escape sequence" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'B').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowDown, ctrl = false, alt = false, shift = false))
            }
        }

        "arrow right escape sequence" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'C').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowRight, ctrl = false, alt = false, shift = false))
            }
        }

        "arrow left escape sequence" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'D').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowLeft, ctrl = false, alt = false, shift = false))
            }
        }

        "home key" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'H').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Home, ctrl = false, alt = false, shift = false))
            }
        }

        "end key" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'F').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.End, ctrl = false, alt = false, shift = false))
            }
        }

        "shift+tab" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', 'Z').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = true))
            }
        }

        "delete key" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', '3', '~').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Delete, ctrl = false, alt = false, shift = false))
            }
        }

        "F1 key (SS3)" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, 'O', 'P').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.F1, ctrl = false, alt = false, shift = false))
            }
        }

        "F5 key" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', '1', '5', '~').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.F5, ctrl = false, alt = false, shift = false))
            }
        }

        "ctrl+arrow up" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, '[', '1', ';', '5', 'A').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowUp, ctrl = true, alt = false, shift = false))
            }
        }

        "alt+key" in run {
            val (terminal, _) = capturedTerminal(Array(0x1b, 'x').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('x'), ctrl = false, alt = true, shift = false))
            }
        }

        "SGR mouse click" in run {
            val (terminal, _) = capturedTerminal("\u001b[<0;10;5M".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.LeftPress, 9, 4))
            }
        }

        "SGR mouse release" in run {
            val (terminal, _) = capturedTerminal("\u001b[<0;10;5m".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.LeftRelease, 9, 4))
            }
        }

        "SGR scroll up" in run {
            val (terminal, _) = capturedTerminal("\u001b[<64;10;5M".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.ScrollUp, 9, 4))
            }
        }

        "EOF returns Escape" in run {
            val (terminal, _) = capturedTerminal(Array.empty)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Escape, ctrl = false, alt = false, shift = false))
            }
        }
    }

end JvmTerminalIOTest
