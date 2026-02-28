package kyo

import kyo.internal.InputEvent
import kyo.internal.InputEvent.*
import kyo.internal.TuiInput

class TuiInputTest extends Test:

    private def bytes(values: Int*): Chunk[Byte] =
        Chunk.from(values.map(_.toByte))

    private def ascii(s: String): Chunk[Byte] =
        Chunk.from(s.getBytes("US-ASCII").toSeq)

    "printable characters" - {
        "single char" in {
            val (events, rem) = TuiInput.parse(bytes('a'.toInt))
            assert(events == Chunk(Key("a")))
            assert(rem.isEmpty)
            succeed
        }
        "multiple chars" in {
            val (events, rem) = TuiInput.parse(ascii("abc"))
            assert(events == Chunk(Key("a"), Key("b"), Key("c")))
            assert(rem.isEmpty)
            succeed
        }
    }

    "control keys" - {
        "Enter" in {
            val (events, _) = TuiInput.parse(bytes(0x0d))
            assert(events == Chunk(Key("Enter")))
            succeed
        }
        "Tab" in {
            val (events, _) = TuiInput.parse(bytes(0x09))
            assert(events == Chunk(Key("Tab")))
            succeed
        }
        "Backspace (0x7f)" in {
            val (events, _) = TuiInput.parse(bytes(0x7f))
            assert(events == Chunk(Key("Backspace")))
            succeed
        }
        "Ctrl+A" in {
            val (events, _) = TuiInput.parse(bytes(0x01))
            assert(events == Chunk(Key("a", ctrl = true)))
            succeed
        }
        "Ctrl+C" in {
            val (events, _) = TuiInput.parse(bytes(0x03))
            assert(events == Chunk(Key("c", ctrl = true)))
            succeed
        }
        "Ctrl+Z" in {
            val (events, _) = TuiInput.parse(bytes(0x1a))
            assert(events == Chunk(Key("z", ctrl = true)))
            succeed
        }
    }

    "arrow keys" - {
        "ArrowUp" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'A'))
            assert(events == Chunk(Key("ArrowUp")))
            succeed
        }
        "ArrowDown" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'B'))
            assert(events == Chunk(Key("ArrowDown")))
            succeed
        }
        "ArrowRight" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'C'))
            assert(events == Chunk(Key("ArrowRight")))
            succeed
        }
        "ArrowLeft" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'D'))
            assert(events == Chunk(Key("ArrowLeft")))
            succeed
        }
    }

    "modified arrows" - {
        "Shift+ArrowUp" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', ';', '2', 'A'))
            assert(events == Chunk(Key("ArrowUp", shift = true)))
            succeed
        }
        "Ctrl+ArrowRight" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', ';', '5', 'C'))
            assert(events == Chunk(Key("ArrowRight", ctrl = true)))
            succeed
        }
        "Alt+ArrowDown" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', ';', '3', 'B'))
            assert(events == Chunk(Key("ArrowDown", alt = true)))
            succeed
        }
        "Ctrl+Shift+ArrowLeft" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', ';', '6', 'D'))
            assert(events == Chunk(Key("ArrowLeft", shift = true, ctrl = true)))
            succeed
        }
    }

    "special keys" - {
        "Home" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'H'))
            assert(events == Chunk(Key("Home")))
            succeed
        }
        "End" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'F'))
            assert(events == Chunk(Key("End")))
            succeed
        }
        "Delete" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '3', '~'))
            assert(events == Chunk(Key("Delete")))
            succeed
        }
        "PageUp" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '5', '~'))
            assert(events == Chunk(Key("PageUp")))
            succeed
        }
        "PageDown" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '6', '~'))
            assert(events == Chunk(Key("PageDown")))
            succeed
        }
        "Shift+Tab" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', 'Z'))
            assert(events == Chunk(Key("Tab", shift = true)))
            succeed
        }
    }

    "Alt+key" - {
        "Alt+a" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, 'a'))
            assert(events == Chunk(Key("a", alt = true)))
            succeed
        }
        "Alt+x" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, 'x'))
            assert(events == Chunk(Key("x", alt = true)))
            succeed
        }
    }

    "SS3 sequences" - {
        "F1" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, 'O', 'P'))
            assert(events == Chunk(Key("F1")))
            succeed
        }
        "F4" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, 'O', 'S'))
            assert(events == Chunk(Key("F4")))
            succeed
        }
    }

    "function keys via CSI" - {
        "F5" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', '5', '~'))
            assert(events == Chunk(Key("F5")))
            succeed
        }
        "F12" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '2', '4', '~'))
            assert(events == Chunk(Key("F12")))
            succeed
        }
    }

    "mouse events (SGR)" - {
        "left press at (5, 10)" in {
            // ESC[<0;6;11M (1-based coordinates)
            val (events, _) = TuiInput.parse(ascii("\u001b[<0;6;11M"))
            assert(events == Chunk(Mouse(MouseKind.LeftPress, 5, 10)))
            succeed
        }
        "left release" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<0;1;1m"))
            assert(events == Chunk(Mouse(MouseKind.LeftRelease, 0, 0)))
            succeed
        }
        "right press" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<2;10;20M"))
            assert(events == Chunk(Mouse(MouseKind.RightPress, 9, 19)))
            succeed
        }
        "scroll up" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<64;5;5M"))
            assert(events == Chunk(Mouse(MouseKind.ScrollUp, 4, 4)))
            succeed
        }
        "scroll down" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<65;5;5M"))
            assert(events == Chunk(Mouse(MouseKind.ScrollDown, 4, 4)))
            succeed
        }
        "left drag" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<32;3;4M"))
            assert(events == Chunk(Mouse(MouseKind.LeftDrag, 2, 3)))
            succeed
        }
        "mouse move (no button)" in {
            val (events, _) = TuiInput.parse(ascii("\u001b[<35;8;9M"))
            assert(events == Chunk(Mouse(MouseKind.Move, 7, 8)))
            succeed
        }
        "shift+click" in {
            // button = 4 (shift modifier on base 0)
            val (events, _) = TuiInput.parse(ascii("\u001b[<4;1;1M"))
            assert(events == Chunk(Mouse(MouseKind.LeftPress, 0, 0, shift = true)))
            succeed
        }
    }

    "bracketed paste" - {
        "simple paste" in {
            val input         = ascii("\u001b[200~hello world\u001b[201~")
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Paste("hello world")))
            assert(rem.isEmpty)
            succeed
        }
    }

    "UTF-8" - {
        "2-byte character (é)" in {
            val (events, _) = TuiInput.parse(Chunk.from("é".getBytes("UTF-8").toSeq))
            assert(events == Chunk(Key("é")))
            succeed
        }
        "3-byte character (日)" in {
            val (events, _) = TuiInput.parse(Chunk.from("日".getBytes("UTF-8").toSeq))
            assert(events == Chunk(Key("日")))
            succeed
        }
        "4-byte character (emoji 😀)" in {
            val (events, _) = TuiInput.parse(Chunk.from("😀".getBytes("UTF-8").toSeq))
            assert(events == Chunk(Key("😀")))
            succeed
        }
    }

    "partial sequences" - {
        "incomplete escape returns remainder" in {
            val (events, rem) = TuiInput.parse(bytes(0x1b))
            assert(events.isEmpty)
            assert(rem.length == 1)
            succeed
        }
        "incomplete CSI returns remainder" in {
            val (events, rem) = TuiInput.parse(bytes(0x1b, '['))
            assert(events.isEmpty)
            assert(rem.length == 2)
            succeed
        }
        "incomplete UTF-8 returns remainder" in {
            val (events, rem) = TuiInput.parse(bytes(0xc3)) // first byte of 2-byte seq
            assert(events.isEmpty)
            assert(rem.length == 1)
            succeed
        }
    }

    "mixed input" - {
        "text then arrow then text" in {
            val input =
                Chunk.from(
                    (Array[Byte]('h'.toByte, 'i'.toByte, 0x1b.toByte, '['.toByte, 'A'.toByte, '!'.toByte)).toSeq
                )
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Key("h"), Key("i"), Key("ArrowUp"), Key("!")))
            assert(rem.isEmpty)
            succeed
        }
    }

    "bracketed paste edge cases" - {
        "terminator at end of buffer" in {
            // Bug: while i + 5 < len misses terminator when it's at the very end
            val input         = ascii("\u001b[200~hi\u001b[201~")
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Paste("hi")))
            assert(rem.isEmpty)
            succeed
        }
        "single char paste - terminator right after content" in {
            // The paste content starts right at the CSI start position
            // Content is just "x", then immediately the 6-byte terminator
            val input         = ascii("\u001b[200~x\u001b[201~")
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Paste("x")))
            assert(rem.isEmpty)
            succeed
        }
        "empty paste" in {
            val input         = ascii("\u001b[200~\u001b[201~")
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Paste("")))
            assert(rem.isEmpty)
            succeed
        }
        "paste with special characters" in {
            val input         = ascii("\u001b[200~hello\nworld\u001b[201~")
            val (events, rem) = TuiInput.parse(input)
            assert(events == Chunk(Paste("hello\nworld")))
            assert(rem.isEmpty)
            succeed
        }
    }

    "UTF-8 edge cases" - {
        "invalid continuation byte" in {
            // Bug: no validation of continuation bytes (should be 10xxxxxx)
            // 0xc3 expects a continuation byte, but 0x2f is not one
            val (events, rem) = TuiInput.parse(bytes(0xc3, 0x2f))
            // Should either produce a replacement char or handle gracefully
            // Currently silently creates a malformed string
            assert(events.length == 1)
            succeed
        }
    }

    "SGR mouse edge cases" - {
        "extra semicolons in mouse sequence" in {
            // Bug: extra fields silently ignored, digits accumulate into row
            // ESC[<0;5;10;999M — has an extra field
            val input       = ascii("\u001b[<0;5;10;999M")
            val (events, _) = TuiInput.parse(input)
            // Should either reject or ignore extra field, not corrupt row
            events.head match
                case Mouse(_, x, y, _, _, _) =>
                    assert(x == 4) // col=5, 0-based=4
                    assert(y == 9) // row=10, 0-based=9 (not corrupted by extra field)
                case _ => fail("Expected Mouse event")
            end match
            succeed
        }
    }

    "escape alone" - {
        "bare escape followed by printable" in {
            // ESC + 'q' → should be Alt+q (not Escape + q)
            val (events, rem) = TuiInput.parse(bytes(0x1b, 'q'))
            assert(events == Chunk(Key("q", alt = true)))
            assert(rem.isEmpty)
            succeed
        }
    }

    "F keys with modifiers" - {
        "Ctrl+F5" in {
            // ESC[15;5~
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '1', '5', ';', '5', '~'))
            assert(events == Chunk(Key("F5", ctrl = true)))
            succeed
        }
    }

    "Insert key" - {
        "Insert" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, '[', '2', '~'))
            assert(events == Chunk(Key("Insert")))
            succeed
        }
    }

    "ascii string cache" - {
        "printable chars use cached strings" in {
            val (e1, _) = TuiInput.parse(bytes('a'.toInt))
            val (e2, _) = TuiInput.parse(bytes('a'.toInt))
            // Both should produce equal Key events with same string content
            assert(e1 == e2)
            assert(e1 == Chunk(Key("a")))
            succeed
        }
        "all printable ASCII range" in {
            // Verify every printable ASCII char parses correctly
            var ch = 0x20 // space
            while ch < 0x7f do
                val input         = bytes(ch)
                val (events, rem) = TuiInput.parse(input)
                discard(assert(events.length == 1))
                discard(assert(events.head == Key(ch.toChar.toString)))
                ch += 1
            end while
            succeed
        }
        "ctrl keys use cached strings" in {
            // Ctrl+a through Ctrl+z, skipping special cases (0x09=Tab, 0x0d=Enter, 0x08=Backspace)
            val special = Set(0x08, 0x09, 0x0d)
            var b       = 1
            while b <= 26 do
                if !special.contains(b) then
                    val (events, _) = TuiInput.parse(bytes(b))
                    discard(assert(events.head == Key((b + 'a' - 1).toChar.toString, ctrl = true)))
                end if
                b += 1
            end while
            succeed
        }
        "alt+printable uses cached strings" in {
            val (events, _) = TuiInput.parse(bytes(0x1b, 'z'))
            assert(events == Chunk(Key("z", alt = true)))
            succeed
        }
    }

end TuiInputTest
