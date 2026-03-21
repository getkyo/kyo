package kyo.internal.tui

import kyo.*
import kyo.Test
import kyo.internal.tui.pipeline.*

/** Tests terminal I/O: ANSI output via StreamTerminalIO, input parsing via JvmTerminalIO with injected streams. */
class JvmTerminalIOTest extends Test:

    import AllowUnsafe.embrace.danger

    def streamTerminal(): (StreamTerminalIO, java.io.ByteArrayOutputStream) =
        val output = new java.io.ByteArrayOutputStream
        val input  = new java.io.ByteArrayInputStream(Array.empty)
        (new StreamTerminalIO(output, input), output)
    end streamTerminal

    def inputTerminal(inputBytes: Array[Byte]): JvmTerminalIO =
        val output = new java.io.ByteArrayOutputStream
        val input  = new java.io.ByteArrayInputStream(inputBytes)
        new JvmTerminalIO(output, input)
    end inputTerminal

    "ANSI output" - {
        "enterAlternateScreen writes escape sequence" in run {
            val (terminal, output) = streamTerminal()
            terminal.enterAlternateScreen.andThen {
                val bytes = output.toString("UTF-8")
                assert(bytes.contains(TerminalEscape.EnterAlternateScreen), s"missing alternate screen: $bytes")
                assert(bytes.contains(TerminalEscape.ClearScreen), s"missing screen clear: $bytes")
                assert(bytes.contains(TerminalEscape.CursorHome), s"missing cursor home: $bytes")
            }
        }

        "exitAlternateScreen writes escape sequence" in run {
            val (terminal, output) = streamTerminal()
            terminal.exitAlternateScreen.andThen {
                assert(output.toString("UTF-8").contains(TerminalEscape.ExitAlternateScreen))
            }
        }

        "hideCursor writes escape sequence" in run {
            val (terminal, output) = streamTerminal()
            terminal.hideCursor.andThen {
                assert(output.toString("UTF-8").contains(TerminalEscape.HideCursor))
            }
        }

        "showCursor writes escape sequence" in run {
            val (terminal, output) = streamTerminal()
            terminal.showCursor.andThen {
                assert(output.toString("UTF-8").contains(TerminalEscape.ShowCursor))
            }
        }

        "enableMouseTracking writes SGR mode sequences" in run {
            val (terminal, output) = streamTerminal()
            terminal.enableMouseTracking.andThen {
                val bytes = output.toString("UTF-8")
                assert(bytes.contains(TerminalEscape.EnableAllMotionMouse), s"missing mouse enable: $bytes")
                assert(bytes.contains(TerminalEscape.EnableSgrMouse), s"missing SGR mode: $bytes")
            }
        }

        "write outputs exact bytes" in run {
            val (terminal, output) = streamTerminal()
            val data               = "hello".getBytes
            terminal.write(data).andThen(terminal.flush).andThen {
                assert(output.toString("UTF-8") == "hello")
            }
        }
    }

    "input parsing" - {
        "regular character" in run {
            val terminal = inputTerminal(Array('A'.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('A'), ctrl = false, alt = false, shift = false))
            }
        }

        "enter key" in run {
            val terminal = inputTerminal(Array(0x0d.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Enter, ctrl = false, alt = false, shift = false))
            }
        }

        "tab key" in run {
            val terminal = inputTerminal(Array(0x09.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false))
            }
        }

        "backspace key" in run {
            val terminal = inputTerminal(Array(0x7f.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Backspace, ctrl = false, alt = false, shift = false))
            }
        }

        "ctrl+a" in run {
            val terminal = inputTerminal(Array(0x01.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('a'), ctrl = true, alt = false, shift = false))
            }
        }

        "arrow up" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'A').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowUp, ctrl = false, alt = false, shift = false))
            }
        }

        "arrow down" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'B').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowDown, ctrl = false, alt = false, shift = false))
            }
        }

        "shift+tab" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'Z').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = true))
            }
        }

        "delete key" in run {
            val terminal = inputTerminal(Array(0x1b, '[', '3', '~').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Delete, ctrl = false, alt = false, shift = false))
            }
        }

        "F1 key (SS3)" in run {
            val terminal = inputTerminal(Array(0x1b, 'O', 'P').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.F1, ctrl = false, alt = false, shift = false))
            }
        }

        "ctrl+arrow up" in run {
            val terminal = inputTerminal(Array(0x1b, '[', '1', ';', '5', 'A').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowUp, ctrl = true, alt = false, shift = false))
            }
        }

        "arrow right" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'C').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowRight, ctrl = false, alt = false, shift = false))
            }
        }

        "arrow left" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'D').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.ArrowLeft, ctrl = false, alt = false, shift = false))
            }
        }

        "home key" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'H').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Home, ctrl = false, alt = false, shift = false))
            }
        }

        "end key" in run {
            val terminal = inputTerminal(Array(0x1b, '[', 'F').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.End, ctrl = false, alt = false, shift = false))
            }
        }

        "F5 key" in run {
            val terminal = inputTerminal(Array(0x1b, '[', '1', '5', '~').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.F5, ctrl = false, alt = false, shift = false))
            }
        }

        "alt+key" in run {
            val terminal = inputTerminal(Array(0x1b, 'x').map(_.toByte))
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Char('x'), ctrl = false, alt = true, shift = false))
            }
        }

        "SGR mouse click" in run {
            val terminal = inputTerminal("\u001b[<0;10;5M".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.LeftPress, 9, 4))
            }
        }

        "SGR mouse release" in run {
            val terminal = inputTerminal("\u001b[<0;10;5m".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.LeftRelease, 9, 4))
            }
        }

        "SGR scroll up" in run {
            val terminal = inputTerminal("\u001b[<64;10;5M".getBytes)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Mouse(MouseKind.ScrollUp, 9, 4))
            }
        }

        "EOF returns Escape" in run {
            val terminal = inputTerminal(Array.empty)
            terminal.readEvent.map { event =>
                assert(event == InputEvent.Key(UI.Keyboard.Escape, ctrl = false, alt = false, shift = false))
            }
        }
    }

end JvmTerminalIOTest
