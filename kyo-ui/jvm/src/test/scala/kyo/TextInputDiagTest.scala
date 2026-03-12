package kyo

import kyo.UIDsl.*
import scala.language.implicitConversions

/** Focused diagnostic for text input issues in the tui2 pipeline. Uses TerminalEmulator's built-in diagnostics.
  */
class TextInputDiagTest extends Test:

    def mkSig(v: String)(using Frame, AllowUnsafe): SignalRef[String] =
        Sync.Unsafe.evalOrThrow(Signal.initRef(v))

    def mkSigInt(v: Int)(using Frame, AllowUnsafe): SignalRef[Int] =
        Sync.Unsafe.evalOrThrow(Signal.initRef(v))

    "text input diagnostics" - {
        import AllowUnsafe.embrace.danger

        "basic input with signalref" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(input.value(sig).placeholder("Type here"), cols = 30, rows = 3)
            // First focusable is auto-focused after initial scan
            emu.typeText("hello")
            emu.assertContains("hello")
            emu.assertFocusedValue("hello")
            emu.assertSignalValue(sig, "hello")
            succeed
        }

        "input inside div" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(
                div.style(Style.gap(1.px))(
                    div.style(Style.row.gap(2.px))(
                        label("Name:"),
                        input.value(sig).placeholder("Type your name...")
                    )
                ),
                cols = 50,
                rows = 5
            )
            emu.typeText("abc")
            emu.assertContains("abc")
            emu.assertSignalValue(sig, "abc")
            succeed
        }

        "input inside reactive signal.map" in run {
            val sig  = mkSig("")
            val page = mkSigInt(0)

            val emu = TerminalEmulator(
                div(
                    page.map[UI] { p =>
                        p match
                            case 0 => div(
                                    label("Name:"),
                                    input.value(sig).placeholder("Type...")
                                )
                            case _ => span("other")
                        end match
                    }
                ),
                cols = 40,
                rows = 5
            )

            assert(emu.focusableCount > 0, s"No focusables found:\n${emu.focusRingDump}")
            assert(emu.focusedHasRef, s"Focused element has no SignalRef:\n${emu.focusRingDump}")

            emu.typeText("test")
            emu.assertSignalValue(sig, "test")
            emu.assertContains("test")
            succeed
        }

        "multiple inputs tab navigation" in run {
            val sig1 = mkSig("")
            val sig2 = mkSig("")
            val emu = TerminalEmulator(
                div(
                    input.value(sig1).placeholder("First"),
                    input.value(sig2).placeholder("Second")
                ),
                cols = 30,
                rows = 6
            )
            // First input is auto-focused
            emu.typeText("aaa")
            emu.assertSignalValue(sig1, "aaa")

            emu.tab() // focus second
            emu.typeText("bbb")
            emu.assertSignalValue(sig2, "bbb")

            emu.assertContains("aaa")
            emu.assertContains("bbb")
            succeed
        }

        "textarea type multiline" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(
                textarea.value(sig).style(Style.width(30.em).height(4.em)),
                cols = 40,
                rows = 8
            )
            emu.typeText("line1")
            emu.enter()
            emu.typeText("line2")
            emu.assertContains("line1")
            emu.assertContains("line2")
            assert(emu.signalValue(sig).contains("line1"), s"Signal should contain 'line1': ${emu.signalValue(sig)}")
            assert(emu.signalValue(sig).contains("line2"), s"Signal should contain 'line2': ${emu.signalValue(sig)}")
        }

        "password masking" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(password.value(sig), cols = 30, rows = 3)
            emu.typeText("secret")
            val after = emu.frame
            assert(!after.contains("secret"), s"Password should be masked:\n$after")
            emu.assertSignalValue(sig, "secret")
            succeed
        }

        "input with onInput handler" in run {
            val sig = mkSig("")
            val log = mkSig("")
            val emu = TerminalEmulator(
                div(
                    input.value(sig).onInput(v => log.set(s"typing: $v")),
                    span(log.map(l => s"log=$l"))
                ),
                cols = 40,
                rows = 5
            )
            emu.typeText("hi")
            emu.assertContains("hi")
            emu.assertSignalValue(sig, "hi")
            // onInput fires asynchronously — wait for the handler fiber to complete
            emu.waitForEffects()
            emu.assertContains("log=typing: hi")
            succeed
        }

        "showcase page 1 structure" in run {
            val nameVal     = mkSig("")
            val emailVal    = mkSig("")
            val passwordVal = mkSig("hunter2")
            val textareaVal = mkSig("")
            val log         = mkSig("")

            val page1 = fragment(
                h2("Text Input"),
                div.style(Style.gap(1.px))(
                    div.style(Style.row.gap(2.px))(
                        label("Name:"),
                        input.value(nameVal).placeholder("Type your name...").onInput(v => log.set(s"typing: $v"))
                    ),
                    div.style(Style.row.gap(2.px))(
                        label("Email:"),
                        email.value(emailVal).placeholder("user@example.com")
                    ),
                    div.style(Style.row.gap(2.px))(
                        label("Password:"),
                        password.value(passwordVal).placeholder("secret")
                    ),
                    div.style(Style.row.gap(2.px))(
                        label("Read-only:"),
                        input.value("Cannot edit this").readOnly(true)
                    )
                ),
                h2("Textarea"),
                textarea.value(textareaVal).placeholder("Write multi-line text here...")
                    .style(Style.width(40.em).height(4.em))
            )

            val emu = TerminalEmulator(page1, cols = 60, rows = 20)

            // First input (Name) is auto-focused
            assert(emu.focusedHasRef, s"Name input should have ref:\n${emu.focusRingDump}")
            emu.typeText("Alice")
            emu.assertSignalValue(nameVal, "Alice")
            emu.assertContains("Alice")

            // Tab to Email
            emu.tab()
            emu.typeText("alice@test.com")
            emu.assertSignalValue(emailVal, "alice@test.com")

            // Tab to Password
            emu.tab()
            emu.assertFocusedValue("hunter2")

            // Tab past read-only to textarea
            emu.tab() // read-only
            emu.tab() // textarea
            emu.typeText("Notes here")
            emu.assertSignalValue(textareaVal, "Notes here")
            succeed
        }
    }

    "mouse event dispatch" - {
        import AllowUnsafe.embrace.danger

        "click to focus input inside reactive node" in run {
            val sig1 = mkSig("")
            val sig2 = mkSig("")
            val page = mkSigInt(0)
            val emu = TerminalEmulator(
                div(
                    page.map[UI] { _ =>
                        div.style(Style.gap(1.px))(
                            input.value(sig1).placeholder("First"),
                            input.value(sig2).placeholder("Second")
                        )
                    }
                ),
                cols = 30,
                rows = 6
            )
            // First input is auto-focused via tab index
            emu.typeText("aaa")
            emu.assertSignalValue(sig1, "aaa")

            // Click on second input — this is the real-world scenario that fails
            val f         = emu.frame
            val lines     = f.split("\n")
            val secondRow = lines.indexWhere(_.contains("Second"))
            assert(secondRow >= 0, s"Could not find 'Second' in frame:\n$f")
            emu.click(5, secondRow)
            emu.typeText("bbb")
            emu.assertSignalValue(sig2, "bbb")
            succeed
        }

        "click to focus second input" in run {
            val sig1 = mkSig("")
            val sig2 = mkSig("")
            val emu = TerminalEmulator(
                div.style(Style.gap(1.px))(
                    input.value(sig1).placeholder("First"),
                    input.value(sig2).placeholder("Second")
                ),
                cols = 30,
                rows = 6
            )
            // First input is auto-focused
            emu.typeText("aaa")
            emu.assertSignalValue(sig1, "aaa")

            // Click on second input (row 2 in a gap(1) layout: row 0 = border of first, row 2 = border of second, row 3 = content of second)
            // Render to see actual positions
            val f = emu.frame
            // Find which row has "Second" placeholder
            val lines     = f.split("\n")
            val secondRow = lines.indexWhere(_.contains("Second"))
            assert(secondRow >= 0, s"Could not find 'Second' in frame:\n$f")
            emu.click(5, secondRow)
            emu.typeText("bbb")
            emu.assertSignalValue(sig2, "bbb")
            succeed
        }

        "click places cursor in text input" in run {
            val sig = mkSig("hello world")
            val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 3)
            // Render initial frame to establish positions
            val f = emu.frame
            // Text input typically renders at row 0 or 1 (depending on border)
            // Find which row has "hello world"
            val lines   = f.split("\n")
            val textRow = lines.indexWhere(_.contains("hello world"))
            assert(textRow >= 0, s"Could not find 'hello world' in frame:\n$f")
            val textCol = lines(textRow).indexOf("hello world")
            // Click at position of 'w' (index 6 in "hello world")
            emu.click(textCol + 6, textRow)
            // Type a character — it should insert at the click position
            emu.typeText("X")
            val expected = "hello Xworld"
            emu.assertSignalValue(sig, expected)
            succeed
        }

        "drag to select in text input" in run {
            val sig     = mkSig("hello world")
            val emu     = TerminalEmulator(input.value(sig), cols = 30, rows = 3)
            val f       = emu.frame
            val lines   = f.split("\n")
            val textRow = lines.indexWhere(_.contains("hello world"))
            assert(textRow >= 0, s"Could not find 'hello world' in frame:\n$f")
            val textCol = lines(textRow).indexOf("hello world")
            // Click at 'w' (offset 6), drag to 'd' (offset 11 = end)
            emu.click(textCol + 6, textRow)
            emu.drag(textCol + 11, textRow)
            // Type to replace selection
            emu.typeText("X")
            emu.assertSignalValue(sig, "hello X")
            succeed
        }

        "range input click sets value" in run {
            val sig = Sync.Unsafe.evalOrThrow(Signal.initRef(0.0))
            val emu = TerminalEmulator(
                rangeInput.value(sig).min(0.0).max(100.0).step(1.0).style(Style.width(20.em)),
                cols = 30,
                rows = 3
            )
            val f     = emu.frame
            val lines = f.split("\n")
            // Range renders as block chars — find the row with them
            val rangeRow = lines.indexWhere(l => l.contains("\u2588") || l.contains("\u2591"))
            assert(rangeRow >= 0, s"Could not find range slider in frame:\n$f")
            // Find the bounds of the range content
            val line       = lines(rangeRow)
            val firstBlock = line.indexWhere(c => c == '\u2588' || c == '\u2591')
            assert(firstBlock >= 0, s"No block chars in range row: $line")
            // Click at ~50% of the range width (10 chars into a 20-char range)
            emu.click(firstBlock + 10, rangeRow)
            val value = emu.signalValue(sig)
            // Value should be approximately 50% of 100 = 50, with some tolerance
            assert(value >= 40.0 && value <= 60.0, s"Expected range value ~50 but got $value")
            succeed
        }

        "scroll down on scrollable container" in run {
            val content = (1 to 20).map(i => div(s"Line $i"))
            val emu = TerminalEmulator(
                div.style(Style.height(5.em).overflow(Style.Overflow.scroll))(content*),
                cols = 30,
                rows = 7
            )
            // Initial frame should show Line 1
            emu.assertContains("Line 1")
            // Scroll down
            emu.scrollDown(5, 2)
            emu.scrollDown(5, 2)
            emu.scrollDown(5, 2)
            val f = emu.frame
            // After scrolling, Line 1 may no longer be visible
            // and later lines should appear
            assert(f.contains("Line 4") || f.contains("Line 5"), s"Expected later lines after scroll:\n$f")
            succeed
        }

        "click then type inserts at click position" in run {
            val sig     = mkSig("abcdef")
            val emu     = TerminalEmulator(input.value(sig), cols = 30, rows = 3)
            val f       = emu.frame
            val lines   = f.split("\n")
            val textRow = lines.indexWhere(_.contains("abcdef"))
            assert(textRow >= 0, s"Could not find 'abcdef' in frame:\n$f")
            val textCol = lines(textRow).indexOf("abcdef")
            // Click at position 3 (between 'c' and 'd')
            emu.click(textCol + 3, textRow)
            emu.typeText("XY")
            emu.assertSignalValue(sig, "abcXYdef")
            succeed
        }

        "multiple inputs click directly without tab" in run {
            val sig1 = mkSig("")
            val sig2 = mkSig("")
            val sig3 = mkSig("")
            val emu = TerminalEmulator(
                div.style(Style.gap(1.px))(
                    input.value(sig1).placeholder("First"),
                    input.value(sig2).placeholder("Second"),
                    input.value(sig3).placeholder("Third")
                ),
                cols = 30,
                rows = 10
            )
            val f     = emu.frame
            val lines = f.split("\n")

            // Click directly on third input
            val thirdRow = lines.indexWhere(_.contains("Third"))
            assert(thirdRow >= 0, s"Could not find 'Third' in frame:\n$f")
            emu.click(5, thirdRow)
            emu.typeText("ccc")
            emu.assertSignalValue(sig3, "ccc")

            // Click back on first input
            val firstRow = lines.indexWhere(_.contains("First"))
            assert(firstRow >= 0, s"Could not find 'First' in frame:\n$f")
            emu.click(5, firstRow)
            emu.typeText("aaa")
            emu.assertSignalValue(sig1, "aaa")

            // Second should still be empty
            assert(emu.signalValue(sig2) == "", s"Second should be empty but got: ${emu.signalValue(sig2)}")
            succeed
        }
        "click checkbox to toggle" in run {
            val sig = Sync.Unsafe.evalOrThrow(Signal.initRef(false))
            val emu = TerminalEmulator(
                checkbox.checked(sig),
                cols = 20,
                rows = 3
            )
            assert(!emu.signalValue(sig), "Should start unchecked")
            val f     = emu.frame
            val lines = f.split("\n")
            // Find the checkbox row (has [ ] or [x])
            val cbRow = lines.indexWhere(l => l.contains("[ ]") || l.contains("[x]"))
            assert(cbRow >= 0, s"Could not find checkbox in frame:\n$f")
            emu.click(1, cbRow)
            assert(emu.signalValue(sig), s"Checkbox should be checked after click")
            emu.click(1, cbRow)
            assert(!emu.signalValue(sig), s"Checkbox should be unchecked after second click")
            succeed
        }

        "click select opens dropdown and arrow+enter selects" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(
                select.value(sig)(
                    option.value("a")("Apple"),
                    option.value("b")("Banana"),
                    option.value("c")("Cherry")
                ),
                cols = 20,
                rows = 10
            )
            val f      = emu.frame
            val lines  = f.split("\n")
            val selRow = lines.indexWhere(l => l.contains("Apple") || l.contains("\u25bc"))
            assert(selRow >= 0, s"Could not find select in frame:\n$f")

            // Click should open dropdown (not cycle)
            emu.click(1, selRow)
            val afterClick = emu.signalValue(sig)
            assert(afterClick == "a", s"Value should still be 'a' after opening dropdown, got '$afterClick'")

            // Dropdown should be visible
            val expanded = emu.frame
            assert(expanded.contains("Apple"), s"Dropdown should show Apple:\n$expanded")
            assert(expanded.contains("Banana"), s"Dropdown should show Banana:\n$expanded")
            assert(expanded.contains("Cherry"), s"Dropdown should show Cherry:\n$expanded")
            assert(expanded.contains("\u25b2"), s"Should show up-arrow when open:\n$expanded")

            // Arrow down to Banana, then Enter to select
            emu.key(UI.Keyboard.ArrowDown)
            emu.key(UI.Keyboard.Enter)
            val afterSelect = emu.signalValue(sig)
            assert(afterSelect == "b", s"Expected 'b' after selecting Banana, got '$afterSelect'")

            // Dropdown should be closed
            val closed = emu.frame
            assert(closed.contains("Banana"), s"Should show selected Banana:\n$closed")
            assert(!closed.contains("Cherry"), s"Dropdown should be closed:\n$closed")
            succeed
        }

        "click on dropdown option selects it" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(
                select.value(sig)(
                    option.value("a")("Apple"),
                    option.value("b")("Banana"),
                    option.value("c")("Cherry")
                ),
                cols = 20,
                rows = 10
            )
            // Open dropdown
            val f      = emu.frame
            val lines  = f.split("\n")
            val selRow = lines.indexWhere(l => l.contains("Apple") || l.contains("\u25bc"))
            assert(selRow >= 0, s"Could not find select in frame:\n$f")
            emu.click(1, selRow)

            // Find Cherry in the expanded dropdown and click it
            val expanded  = emu.frame
            val expLines  = expanded.split("\n")
            val cherryRow = expLines.indexWhere(_.contains("Cherry"))
            assert(cherryRow >= 0, s"Could not find Cherry in dropdown:\n$expanded")
            emu.click(5, cherryRow)

            // Should have selected Cherry
            val afterSelect = emu.signalValue(sig)
            assert(afterSelect == "c", s"Expected 'c' after clicking Cherry, got '$afterSelect'")

            // Dropdown should be closed
            val closed = emu.frame
            assert(closed.contains("Cherry"), s"Should show selected Cherry:\n$closed")
            assert(!closed.contains("Banana"), s"Dropdown should be closed:\n$closed")
            succeed
        }

        "escape closes dropdown without selecting" in run {
            val sig = mkSig("")
            val emu = TerminalEmulator(
                select.value(sig)(
                    option.value("a")("Apple"),
                    option.value("b")("Banana"),
                    option.value("c")("Cherry")
                ),
                cols = 20,
                rows = 10
            )
            // Open dropdown
            val f      = emu.frame
            val lines  = f.split("\n")
            val selRow = lines.indexWhere(l => l.contains("Apple") || l.contains("\u25bc"))
            emu.click(1, selRow)

            // Move highlight to Banana
            emu.key(UI.Keyboard.ArrowDown)

            // Escape without selecting
            emu.key(UI.Keyboard.Escape)

            // Value should still be original (Apple)
            val afterEsc = emu.signalValue(sig)
            assert(afterEsc == "a", s"Value should still be 'a' after escape, got '$afterEsc'")

            // Dropdown should be closed
            val closed = emu.frame
            assert(!closed.contains("Banana"), s"Dropdown should be closed:\n$closed")
            succeed
        }
    }
end TextInputDiagTest
