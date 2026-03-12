package kyo

import kyo.internal.*

class TuiTerminalTest extends Test:

    // ──────────────────────── Initial State ────────────────────────

    "initial state" - {
        "defaults to 24 rows" in {
            val t = new TuiTerminal
            assert(t.rows == 24)
        }

        "defaults to 80 cols" in {
            val t = new TuiTerminal
            assert(t.cols == 80)
        }

        "outputStream throws before enter" in {
            val t = new TuiTerminal
            assertThrows[NoSuchElementException] {
                t.outputStream
            }
        }
    }

    // ──────────────────────── Enter Sequence ────────────────────────

    "enter sequence" - {
        "contains alternate screen enable" in {
            assert(TuiTerminal.EnterSequenceStr.contains("\u001b[?1049h"))
        }

        "contains hide cursor" in {
            assert(TuiTerminal.EnterSequenceStr.contains("\u001b[?25l"))
        }

        "contains all-motion mouse tracking" in {
            assert(TuiTerminal.EnterSequenceStr.contains("\u001b[?1003h"))
        }

        "contains SGR mouse encoding" in {
            assert(TuiTerminal.EnterSequenceStr.contains("\u001b[?1006h"))
        }

        "contains bracketed paste enable" in {
            assert(TuiTerminal.EnterSequenceStr.contains("\u001b[?2004h"))
        }

        "order: alternate screen before mouse tracking" in {
            val altIdx   = TuiTerminal.EnterSequenceStr.indexOf("?1049h")
            val mouseIdx = TuiTerminal.EnterSequenceStr.indexOf("?1003h")
            assert(altIdx < mouseIdx)
        }

        "order: hide cursor before mouse tracking" in {
            val cursorIdx = TuiTerminal.EnterSequenceStr.indexOf("?25l")
            val mouseIdx  = TuiTerminal.EnterSequenceStr.indexOf("?1003h")
            assert(cursorIdx < mouseIdx)
        }
    }

    // ──────────────────────── Exit Sequence ────────────────────────

    "exit sequence" - {
        "contains disable bracketed paste" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[?2004l"))
        }

        "contains disable SGR mouse" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[?1006l"))
        }

        "contains disable mouse tracking" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[?1003l"))
        }

        "contains show cursor" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[?25h"))
        }

        "contains exit alternate screen" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[?1049l"))
        }

        "contains SGR reset" in {
            assert(TuiTerminal.ExitSequenceStr.contains("\u001b[0m"))
        }

        "order: disable mouse before show cursor" in {
            val mouseIdx  = TuiTerminal.ExitSequenceStr.indexOf("?1003l")
            val cursorIdx = TuiTerminal.ExitSequenceStr.indexOf("?25h")
            assert(mouseIdx < cursorIdx)
        }

        "order: show cursor before exit alternate screen" in {
            val cursorIdx = TuiTerminal.ExitSequenceStr.indexOf("?25h")
            val altIdx    = TuiTerminal.ExitSequenceStr.indexOf("?1049l")
            assert(cursorIdx < altIdx)
        }

        "order: exit alternate screen before SGR reset" in {
            val altIdx = TuiTerminal.ExitSequenceStr.indexOf("?1049l")
            val sgrIdx = TuiTerminal.ExitSequenceStr.indexOf("[0m")
            assert(altIdx < sgrIdx)
        }
    }

    // ──────────────────────── Enter/Exit Symmetry ────────────────────────

    "enter/exit symmetry" - {
        "alternate screen enable and disable both present" in {
            assert(TuiTerminal.EnterSequenceStr.contains("?1049h"))
            assert(TuiTerminal.ExitSequenceStr.contains("?1049l"))
        }

        "cursor hide and show both present" in {
            assert(TuiTerminal.EnterSequenceStr.contains("?25l"))
            assert(TuiTerminal.ExitSequenceStr.contains("?25h"))
        }

        "mouse tracking enable and disable both present" in {
            assert(TuiTerminal.EnterSequenceStr.contains("?1003h"))
            assert(TuiTerminal.ExitSequenceStr.contains("?1003l"))
        }

        "SGR mouse enable and disable both present" in {
            assert(TuiTerminal.EnterSequenceStr.contains("?1006h"))
            assert(TuiTerminal.ExitSequenceStr.contains("?1006l"))
        }

        "bracketed paste enable and disable both present" in {
            assert(TuiTerminal.EnterSequenceStr.contains("?2004h"))
            assert(TuiTerminal.ExitSequenceStr.contains("?2004l"))
        }
    }

    // ──────────────────────── parseSize ────────────────────────

    "parseSize" - {
        "parses rows and cols from stty output" in {
            TuiTerminal.parseSize("24 80", 0, 0) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "parses large terminal" in {
            TuiTerminal.parseSize("50 200", 0, 0) { (r, c) =>
                assert(r == 50)
                assert(c == 200)
            }
        }

        "handles extra whitespace" in {
            TuiTerminal.parseSize("  24   80  ", 0, 0) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "handles trailing newline" in {
            TuiTerminal.parseSize("24 80\n", 0, 0) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "returns defaults for empty string" in {
            TuiTerminal.parseSize("", 24, 80) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "returns defaults for single number" in {
            TuiTerminal.parseSize("24", 24, 80) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "returns defaults for non-numeric input" in {
            TuiTerminal.parseSize("abc def", 24, 80) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "returns defaults for partially numeric input" in {
            TuiTerminal.parseSize("24 abc", 24, 80) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "handles tab-separated values" in {
            TuiTerminal.parseSize("24\t80", 0, 0) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }

        "ignores extra fields" in {
            TuiTerminal.parseSize("24 80 extra", 0, 0) { (r, c) =>
                assert(r == 24)
                assert(c == 80)
            }
        }
    }

    // ──────────────────────── Exit idempotent ────────────────────────

    "exit" - {
        "does not crash when called before enter" in {
            val t = new TuiTerminal
            t.exit() // should be a no-op
            succeed
        }
    }

end TuiTerminalTest
