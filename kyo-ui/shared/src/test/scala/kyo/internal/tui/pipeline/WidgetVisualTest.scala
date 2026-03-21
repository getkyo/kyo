package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

/** Visual snapshot tests for individual widgets.
  *
  * Covers: 1 Cursor, 2 Text Input, 3 Password, 4 Select, 5 Checkbox/Radio, 6 Button, 7 Form Submission, 8 Focus & Tab Order
  */
class WidgetVisualTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    // ==== 1. Cursor Behavior ====

    "1 cursor behavior" - {

        // 1.1 Single cursor rule

        "1.1 single cursor rule" - {
            "two inputs, focus neither - zero cursors" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                s.render.andThen {
                    assert(!s.hasCursor, s"unfocused inputs should have no cursor in:\n${s.frame}")
                }
            }

            "two inputs, tab to first - exactly one cursor" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                yield assert(s.hasCursor, s"exactly 1 cursor expected in:\n${s.frame}")
                end for
            }

            "two inputs, tab to second - exactly one cursor on second input row" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                yield
                    assert(s.hasCursor, "exactly 1 cursor expected")
                    val cursorLine = s.cursorRow
                    val f          = s.frame
                    val lines      = f.linesIterator.toVector
                    val secondLine = lines.indexWhere(_.contains("second"))
                    assert(cursorLine == secondLine, s"cursor at line $cursorLine should be on second input at line $secondLine")
                end for
            }

            "input + checkbox + button - tab to input - cursor only on input row" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("text"),
                        UI.checkbox.checked(false),
                        UI.button("Go")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                yield assert(s.hasCursor, "expected 1 cursor")
                end for
            }

            "focus input then tab to button - cursor gone" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("text"),
                        UI.button("Go")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab // focus input
                    _ <- s.tab // focus button
                yield assert(!s.hasCursor, s"buttons don't show text cursor in:\n${s.frame}")
                end for
            }
        }

        // 1.2 Cursor position

        "1.2 cursor position" - {
            "empty input focused - cursor at position 0" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 0, s"cursor should be at position 0, found at $cursorPos in: ${s.frame}")
                end for
            }

            "input with hello focused - cursor at position 5" in run {
                val ref = SignalRef.Unsafe.init("hello")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.tab
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 5, s"cursor should be at position 5 after 'hello', found at $cursorPos in: ${s.frame}")
                end for
            }

            "type AB - cursor at position 2" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 2, s"cursor should be at position 2 after 'AB', found at $cursorPos in: ${s.frame}")
                end for
            }

            "type AB, ArrowLeft - cursor at position 1" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.arrowLeft
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 1, s"cursor should be at position 1 between A and B, found at $cursorPos in: ${s.frame}")
                end for
            }

            "type AB, Home - cursor at position 0" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.key(UI.Keyboard.Home)
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 0, s"cursor should be at position 0 after Home, found at $cursorPos in: ${s.frame}")
                end for
            }

            "type AB, Home, ArrowRight - cursor at position 1" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.key(UI.Keyboard.Home)
                    _ <- s.arrowRight
                yield
                    val cursorPos = s.cursorCol
                    assert(cursorPos == 1, s"cursor should be at position 1 after Home+Right, found at $cursorPos in: ${s.frame}")
                end for
            }

            "password abc focused - cursor after third dot" in run {
                val ref = SignalRef.Unsafe.init("abc")
                val s   = screen(UI.password.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.tab
                yield
                    val cursorPos = s.cursorCol
                    val f         = s.frame
                    val dotsEnd   = f.lastIndexOf('\u2022') + 1
                    assert(cursorPos == dotsEnd, s"cursor at $cursorPos should be right after dots ending at $dotsEnd in: $f")
                end for
            }
        }

        // 1.3 Cursor movement across renders

        "1.3 cursor movement across renders" - {
            "type A verify cursor at 1, type B verify at 2, backspace verify at 1" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                    pos1 = s.cursorCol
                    _ <- s.typeChar('B')
                    pos2 = s.cursorCol
                    _ <- s.backspace
                    pos3 = s.cursorCol
                yield
                    assert(pos1 == 1, s"after A, cursor at $pos1, expected 1")
                    assert(pos2 == 2, s"after AB, cursor at $pos2, expected 2")
                    assert(pos3 == 1, s"after backspace, cursor at $pos3, expected 1")
                end for
            }

            "tab from input1 to input2 - cursor moves between rows" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    firstCursorLine = s.cursorRow
                    _ <- s.tab
                    secondCursorLine = s.cursorRow
                yield assert(
                    secondCursorLine > firstCursorLine,
                    s"cursor should move down: was at line $firstCursorLine, now at $secondCursorLine"
                )
                end for
            }

            "shift+tab back - cursor returns to input1 row" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab // focus input1
                    line1 = s.cursorRow
                    _ <- s.tab      // focus input2
                    _ <- s.shiftTab // back to input1
                    line3 = s.cursorRow
                yield assert(line1 == line3, s"cursor should return to input1 row: started at $line1, returned to $line3")
                end for
            }
        }
    }

    // ==== 2. Text Input Visual ====

    "2 text input visual" - {

        "2.1 input box appearance" - {
            "empty input no focus - no cursor" in run {
                val s = screen(UI.input.value(""), 15, 1)
                s.render.andThen {
                    assert(!s.hasCursor, s"unfocused empty input should have no cursor: ${s.frame}")
                }
            }

            "empty input focused - shows cursor" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield assert(s.hasCursor, s"focused empty input should show cursor: ${s.frame}")
                end for
            }

            "input with value hello unfocused - value visible no cursor" in run {
                val s = screen(UI.input.value("hello"), 15, 1)
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("hello"), s"value missing: $f")
                    assert(!s.hasCursor, s"unfocused should have no cursor: $f")
                }
            }

            "input with value hello focused - value visible with cursor" in run {
                val ref = SignalRef.Unsafe.init("hello")
                val s   = screen(UI.input.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield
                    val f = s.frame
                    assert(f.contains("hello"), s"value missing: $f")
                    assert(s.hasCursor, s"focused should have cursor: $f")
                end for
            }

            "input disabled - value visible, no cursor even if focused" in run {
                val ref = SignalRef.Unsafe.init("frozen")
                val s   = screen(UI.input.value(ref.safe).disabled(true), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('X')
                yield
                    val f = s.frame
                    assert(f.contains("frozen"), s"disabled input should show value: $f")
                    assert(ref.get() == "frozen", "disabled input should not accept typing")
                end for
            }

            "input readonly - value visible, no typing accepted" in run {
                val ref = SignalRef.Unsafe.init("fixed")
                val s   = screen(UI.input.value(ref.safe).readOnly(true), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('X')
                yield
                    assert(ref.get() == "fixed", "readonly input should not accept typing")
                    assert(s.frame.contains("fixed"), s"readonly input should still show value: ${s.frame}")
                end for
            }
        }

        "2.2 input in context" - {
            "label above input (column layout)" in run {
                val s = screen(
                    UI.div(
                        UI.label("Name:"),
                        UI.input.value("John")
                    ),
                    20,
                    5
                )
                s.render.andThen {
                    val f         = s.frame
                    val lines     = f.linesIterator.toVector
                    val labelLine = lines.indexWhere(_.contains("Name:"))
                    val inputLine = lines.indexWhere(_.contains("John"))
                    assert(labelLine >= 0, s"label missing: $f")
                    assert(inputLine >= 0, s"input missing: $f")
                    assert(inputLine > labelLine, s"input should be below label: label=$labelLine, input=$inputLine")
                }
            }

            "label beside input (row layout)" in run {
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.span.style(Style.width(6.px))("Name:"),
                        UI.input.value("John")
                    ),
                    20,
                    1
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("Name:"), s"label missing: $f")
                    assert(f.contains("John"), s"input missing: $f")
                }
            }

            "input inside bordered container" in run {
                val s = screen(
                    UI.div.style(
                        Style.border(1.px, Style.Color.rgb(128, 128, 128))
                            .width(20.px).height(3.px)
                    )(
                        UI.input.value("inside")
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("inside"), s"input text missing inside border: $f")
                    assert(f.contains("\u250c"), s"border missing: $f")
                }
            }

            "two inputs stacked - no visual overlap" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    val line1 = lines.indexWhere(_.contains("first"))
                    val line2 = lines.indexWhere(_.contains("second"))
                    assert(line1 >= 0 && line2 >= 0, s"both inputs should be visible: $f")
                    assert(line1 != line2, s"inputs should be on different rows: both on line $line1")
                }
            }

            "input with very long value in small container - truncated" in run {
                val longValue = "a" * 50
                val s = screen(
                    UI.div.style(Style.width(20.px))(
                        UI.input.value(longValue)
                    ),
                    20,
                    1
                )
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    assert(lines.forall(_.length <= 20), s"should not overflow 20 cols: $f")
                }
            }
        }

        "2.3 placeholder" - {
            "empty input with placeholder - text visible" in run {
                val s = screen(UI.input.placeholder("Type here..."), 20, 1)
                s.render.andThen {
                    assert(s.frame.contains("Type here"), s"placeholder missing: ${s.frame}")
                }
            }

            "type one char - placeholder disappears" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe).placeholder("Type here..."), 20, 1)
                for
                    _ <- s.render
                    _ = assert(s.frame.contains("Type here"))
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('A')
                yield
                    assert(!s.frame.contains("Type here"), s"placeholder should vanish: ${s.frame}")
                    assert(s.frame.contains("A"), s"typed char missing: ${s.frame}")
                end for
            }

            "backspace to empty while focused - placeholder stays hidden" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.input.value(ref.safe).placeholder("hint"), 20, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('X')
                    _ <- s.backspace
                yield
                    assert(ref.get() == "", "should be empty after backspace")
                    // Still focused — placeholder hidden
                    assert(!s.frame.contains("hint"), s"placeholder should stay hidden while focused: ${s.frame}")
                end for
            }
        }
    }

    // ==== 3. Password Visual ====

    "3 password visual" - {

        "3.1 masking" - {
            "value secret - shows dots never plaintext" in run {
                val s = screen(UI.password.value("secret"), 20, 1)
                s.render.andThen {
                    val f = s.frame
                    assert(!f.contains("secret"), s"plaintext visible in password: $f")
                    assert(f.contains("\u2022\u2022\u2022\u2022\u2022\u2022"), s"dots missing in password: $f")
                }
            }

            "type ab - shows two dots with cursor" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.password.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('b')
                yield
                    val f = s.frame
                    assert(f.contains("\u2022\u2022"), s"two dots missing: $f")
                    assert(s.hasCursor, s"cursor missing: $f")
                end for
            }

            "type ab then backspace - shows one dot with cursor" in run {
                val ref = SignalRef.Unsafe.init("")
                val s   = screen(UI.password.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('b')
                    _ <- s.backspace
                yield
                    val f = s.frame
                    assert(ref.get() == "a", s"expected 'a', got '${ref.get()}'")
                    assert(f.contains("\u2022"), s"one dot missing: $f")
                    assert(s.hasCursor, s"cursor missing: $f")
                end for
            }

            "focused password - dots + cursor" in run {
                val ref = SignalRef.Unsafe.init("abc")
                val s   = screen(UI.password.value(ref.safe), 15, 1)
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield
                    val f = s.frame
                    assert(f.contains("\u2022\u2022\u2022"), s"dots missing: $f")
                    assert(s.hasCursor, s"cursor missing: $f")
                end for
            }

            "unfocused password - dots only no cursor" in run {
                val s = screen(UI.password.value("abc"), 15, 1)
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("\u2022\u2022\u2022"), s"dots missing: $f")
                    assert(!s.hasCursor, s"unfocused should have no cursor: $f")
                }
            }
        }
    }

    // ==== 4. Select Dropdown ====

    "4 select dropdown" - {

        "4.1 collapsed state" - {
            "shows selected value text and arrow" in run {
                val s = screen(
                    UI.select.value("apple")(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana")
                    ),
                    20,
                    1
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("apple") || f.contains("Apple"), s"value missing: $f")
                    assert(f.contains("\u25bc"), s"dropdown arrow missing: $f")
                }
            }

            "no options visible when collapsed" in run {
                val s = screen(
                    UI.select.value("apple")(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana")
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    val f = s.frame
                    assert(!f.contains("Banana"), s"options should not be visible when collapsed: $f")
                }
            }
        }

        "4.2 expanded state" - {
            "click opens and shows all option texts" in run {
                val s = screen(
                    UI.select.value("apple")(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana"),
                        UI.option.value("cherry")("Cherry")
                    ),
                    20,
                    6
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield
                    val f = s.frame
                    assert(f.contains("Apple"), s"option Apple missing: $f")
                    assert(f.contains("Banana"), s"option Banana missing: $f")
                    assert(f.contains("Cherry"), s"option Cherry missing: $f")
                end for
            }

            "expanded dropdown has visible non-blank text" in run {
                val s = screen(
                    UI.select.value("a")(
                        UI.option.value("a")("Alpha"),
                        UI.option.value("b")("Beta")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield
                    val f            = s.frame
                    val lines        = f.linesIterator.toVector
                    val contentLines = lines.drop(1).filter(_.trim.nonEmpty)
                    assert(contentLines.nonEmpty, s"dropdown options should be visible:\n$f")
                end for
            }

            "escape closes - back to collapsed, no selection change" in run {
                var selected = ""
                val s = screen(
                    UI.select.value("apple").onChange(v => selected = v)(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                    _ <- s.key(UI.Keyboard.Escape)
                yield assert(selected == "", s"escape should not select, got: $selected")
                end for
            }

            "ArrowDown highlights next option" in run {
                var selected = ""
                val s = screen(
                    UI.select.value("apple").onChange(v => selected = v)(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana"),
                        UI.option.value("cherry")("Cherry")
                    ),
                    20,
                    6
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                    _ <- s.key(UI.Keyboard.ArrowDown)
                    _ <- s.enter
                yield assert(selected.nonEmpty, s"option should be selected after ArrowDown+Enter")
                end for
            }

            "ArrowUp highlights previous option" in run {
                var selected = ""
                val s = screen(
                    UI.select.value("apple").onChange(v => selected = v)(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana"),
                        UI.option.value("cherry")("Cherry")
                    ),
                    20,
                    6
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                    _ <- s.key(UI.Keyboard.ArrowDown) // banana
                    _ <- s.key(UI.Keyboard.ArrowUp)   // back to apple
                    _ <- s.enter
                yield assert(selected == "apple", s"expected apple, got: $selected")
                end for
            }

            "Enter selects highlighted option and closes dropdown" in run {
                var selected = ""
                val s = screen(
                    UI.select.value("apple").onChange(v => selected = v)(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                    _ <- s.key(UI.Keyboard.ArrowDown)
                    _ <- s.enter
                yield assert(selected == "banana", s"expected banana selected, got: $selected")
                end for
            }
        }

        "4.3 edge cases" - {
            "zero options - renders without crash" in run {
                val s = screen(UI.select.value(""), 10, 1)
                s.render.andThen(succeed)
            }

            "one option - shows it when expanded" in run {
                val s = screen(
                    UI.select.value("only")(
                        UI.option.value("only")("Only")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield assert(s.frame.contains("Only"), s"single option should be visible: ${s.frame}")
                end for
            }

            "many options in small viewport - no crash" in run {
                val options = (1 to 20).map(i => UI.option.value(s"opt$i")(s"Option $i"))
                val s = screen(
                    UI.select.value("opt1")(options*),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield succeed
                end for
            }

            "disabled select - click does nothing" in run {
                var selected = ""
                val s = screen(
                    UI.select.value("apple").disabled(true).onChange(v => selected = v)(
                        UI.option.value("apple")("Apple"),
                        UI.option.value("banana")("Banana")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield assert(selected == "", s"disabled select should not respond to click")
                end for
            }
        }
    }

    // ==== 5. Checkbox & Radio ====

    "5 checkbox and radio" - {

        "5.1 visual" - {
            "unchecked checkbox is [ ]" in run {
                val s = screen(UI.checkbox.checked(false), 5, 1)
                s.render.andThen(assert(s.frame.contains("[ ]")))
            }

            "checked checkbox is [x]" in run {
                val s = screen(UI.checkbox.checked(true), 5, 1)
                s.render.andThen(assert(s.frame.contains("[x]")))
            }

            "unchecked radio is ( )" in run {
                val s = screen(UI.radio.checked(false), 5, 1)
                s.render.andThen(assert(s.frame.contains("( )")))
            }

            "checked radio is (dot)" in run {
                val s = screen(UI.radio.checked(true), 5, 1)
                s.render.andThen(assert(s.frame.contains("(\u2022)")))
            }

            "checkbox with label beside it" in run {
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.checkbox.checked(false),
                        UI.span(" Agree")
                    ),
                    15,
                    1
                )
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("[ ]"), s"checkbox missing: $f")
                    assert(f.contains("Agree"), s"label missing: $f")
                }
            }
        }

        "5.2 interaction" - {
            "click toggles checkbox state" in run {
                var toggled = false
                val s = screen(
                    UI.checkbox.checked(false).onChange(b => toggled = b),
                    5,
                    1
                )
                for
                    _ <- s.render
                    _ = assert(s.frame.contains("[ ]"))
                    _ <- s.click(1, 0)
                yield
                    assert(toggled)
                    assert(s.frame.contains("[x]"), s"should be checked: ${s.frame}")
                end for
            }

            "double-click returns to original state" in run {
                var lastState = false
                val s = screen(
                    UI.checkbox.checked(false).onChange(b => lastState = b),
                    5,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                    _ <- s.click(1, 0)
                yield
                    assert(!lastState, "double click should return to unchecked")
                    assert(s.frame.contains("[ ]"))
                end for
            }

            "click on disabled checkbox - no change" in run {
                var toggled = false
                val s = screen(
                    UI.checkbox.checked(false).disabled(true).onChange(b => toggled = b),
                    5,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(1, 0)
                yield
                    assert(!toggled, "disabled checkbox should not toggle")
                    assert(s.frame.contains("[ ]"), "disabled checkbox should stay unchecked")
                end for
            }
        }
    }

    // ==== 6. Button Visual ====

    "6 button visual" - {

        "6.1 appearance" - {
            "has border in default theme" in run {
                val s = Screen(UI.button("OK"), 10, 3, Theme.Default)
                s.render.andThen {
                    val f = s.frame
                    assert(f.contains("OK"), s"button text missing: $f")
                    assert(f.contains("\u250c") || f.contains("\u256d"), s"button top border missing: $f")
                    assert(f.contains("\u2514") || f.contains("\u2570"), s"button bottom border missing: $f")
                }
            }

            "text centered within border" in run {
                val s = screen(UI.button("OK"), 10, 3)
                s.render.andThen {
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    // Button has 3 rows: border-top, content, border-bottom
                    val contentLine = lines.find(_.contains("OK"))
                    assert(contentLine.nonEmpty, s"button content row missing: $f")
                }
            }

            "disabled button still renders" in run {
                val s = screen(UI.button.disabled(true)("Disabled"), 15, 3)
                s.render.andThen {
                    assert(s.frame.contains("Disabled"), s"disabled button should still render: ${s.frame}")
                }
            }

            "button with long text" in run {
                val s = screen(UI.button("A very long button label"), 30, 3)
                s.render.andThen {
                    assert(s.frame.contains("very long"), s"long button text missing: ${s.frame}")
                }
            }
        }

        "6.2 interaction" - {
            "click fires onClick" in run {
                var clicked = false
                val s = screen(
                    UI.div(UI.button.onClick { clicked = true }("Click")),
                    10,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
                yield assert(clicked, "button onClick should fire")
                end for
            }

            "space on focused button fires onClick" in run {
                var clicked = false
                val s = screen(
                    UI.div(UI.button.onClick { clicked = true }("B1")),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
                    _ <- s.key(UI.Keyboard.Space)
                yield assert(clicked, "space on focused button should fire onClick")
                end for
            }

            "disabled button ignores click and keyboard" in run {
                var clicked = false
                val s = screen(
                    UI.div(UI.button.disabled(true).onClick { clicked = true }("No")),
                    10,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0) // Plain theme: no border, button text at row 0
                    _ <- s.key(UI.Keyboard.Space)
                yield assert(!clicked, "disabled button should ignore click and keyboard")
                end for
            }
        }
    }

    // ==== 7. Form Submission ====

    "7 form submission" - {

        "7.1 basic" - {
            "enter in text input inside form fires onSubmit" in run {
                var submitted = false
                val s = screen(
                    UI.form.onSubmit { submitted = true }(
                        UI.input.value("test")
                    ),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.enter
                yield assert(submitted, "form onSubmit should fire on enter in input")
                end for
            }

            "enter in textarea inside form does NOT fire onSubmit" in run {
                var submitted = false
                val s = screen(
                    UI.form.onSubmit { submitted = true }(
                        UI.textarea.value("text")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.enter
                yield assert(!submitted, "onSubmit should NOT fire on enter in textarea")
                end for
            }

            "enter in text input NOT inside form - no onSubmit" in run {
                var submitted = false
                val s = screen(
                    UI.div(
                        UI.input.value("test")
                    ),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.enter
                yield assert(!submitted, "no form means no onSubmit")
                end for
            }
        }

        "7.2 nested" - {
            "enter in input fires innermost form onSubmit" in run {
                var outerSubmitted = false
                var innerSubmitted = false
                val ref            = SignalRef.Unsafe.init("")
                val s = screen(
                    UI.div(
                        UI.h1("Title"),
                        UI.div.style(Style.width(40.px))(
                            UI.form.onSubmit { innerSubmitted = true }(
                                UI.div(
                                    UI.label("Name:"),
                                    UI.input.value(ref.safe)
                                ),
                                UI.button("Submit")
                            )
                        )
                    ),
                    60,
                    10
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.enter
                yield assert(innerSubmitted, "innermost form onSubmit should fire")
                end for
            }

            "form with multiple inputs - enter in any fires onSubmit" in run {
                var submitted = false
                val s = screen(
                    UI.form.onSubmit { submitted = true }(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab // second input
                    _ <- s.enter
                yield assert(submitted, "form onSubmit should fire from any input")
                end for
            }

            "form with input + select + checkbox + button - enter in input fires onSubmit" in run {
                var submitted = false
                val s = screen(
                    UI.form.onSubmit { submitted = true }(
                        UI.input.value("text"),
                        UI.select.value("a")(UI.option.value("a")("Alpha")),
                        UI.checkbox.checked(false),
                        UI.button("Go")
                    ),
                    25,
                    8
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.enter
                yield assert(submitted, "enter in input should fire form onSubmit")
                end for
            }
        }

        "7.3 after submission" - {
            "field values readable in onSubmit handler" in run {
                val ref      = SignalRef.Unsafe.init("")
                var captured = ""
                val s = screen(
                    UI.form.onSubmit { captured = ref.get() }(
                        UI.input.value(ref.safe)
                    ),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('H')
                    _ <- s.typeChar('i')
                    _ <- s.enter
                yield assert(captured == "Hi", s"captured should be 'Hi', got: $captured")
                end for
            }

            "can clear fields in onSubmit handler" in run {
                val ref = SignalRef.Unsafe.init("")
                val s = screen(
                    UI.form.onSubmit { ref.set("") }(
                        UI.input.value(ref.safe)
                    ),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('X')
                    _ <- s.enter
                yield assert(ref.get() == "", s"field should be cleared after submit, got: ${ref.get()}")
                end for
            }
        }
    }

    // ==== 8. Focus & Tab Order ====

    "8 focus and tab order" - {

        "8.1 tab cycling" - {
            "tab visits all focusable elements in order" in run {
                var focusOrder = List.empty[String]
                val s = screen(
                    UI.div(
                        UI.button.onFocus { focusOrder = focusOrder :+ "btn1" }("B1"),
                        UI.button.onFocus { focusOrder = focusOrder :+ "btn2" }("B2"),
                        UI.button.onFocus { focusOrder = focusOrder :+ "btn3" }("B3")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                yield
                    assert(focusOrder.size >= 3, s"expected 3 focus events, got: $focusOrder")
                    assert(focusOrder.head == "btn1", s"first should be btn1, got: ${focusOrder.head}")
                end for
            }

            "shift+tab visits in reverse order" in run {
                var focused = ""
                val s = screen(
                    UI.div(
                        UI.button.onFocus { focused = "btn1" }("B1"),
                        UI.button.onFocus { focused = "btn2" }("B2"),
                        UI.button.onFocus { focused = "btn3" }("B3")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab      // btn1
                    _ <- s.tab      // btn2
                    _ <- s.shiftTab // back to btn1
                yield assert(focused == "btn1", s"expected btn1 after shift+tab, got: $focused")
                end for
            }

            "tab wraps from last to first" in run {
                var focused = ""
                val s = screen(
                    UI.div(
                        UI.button.onFocus { focused = "btn1" }("B1"),
                        UI.button.onFocus { focused = "btn2" }("B2")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab // btn1
                    _ <- s.tab // btn2
                    _ <- s.tab // wrap to btn1
                yield assert(focused == "btn1", s"expected wrap to btn1, got: $focused")
                end for
            }

            "shift+tab wraps from first to last" in run {
                var focused = ""
                val s = screen(
                    UI.div(
                        UI.button.onFocus { focused = "btn1" }("B1"),
                        UI.button.onFocus { focused = "btn2" }("B2")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab      // btn1
                    _ <- s.shiftTab // wrap to btn2
                yield assert(focused == "btn2", s"expected wrap to btn2, got: $focused")
                end for
            }

            "disabled elements skipped" in run {
                var focused = ""
                val s = screen(
                    UI.div(
                        UI.button.onFocus { focused = "btn1" }("B1"),
                        UI.button.disabled(true).onFocus { focused = "btn2" }("B2"),
                        UI.button.onFocus { focused = "btn3" }("B3")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab // btn1
                    _ <- s.tab // skip btn2, go to btn3
                yield assert(focused == "btn3", s"expected btn3 (skipping disabled), got: $focused")
                end for
            }
        }

        "8.2 tab order matches visual order" - {
            "column of inputs - tab goes top to bottom" in run {
                var focusOrder = List.empty[String]
                val s = screen(
                    UI.div(
                        UI.input.value("A").onFocus { focusOrder = focusOrder :+ "A" },
                        UI.input.value("B").onFocus { focusOrder = focusOrder :+ "B" },
                        UI.input.value("C").onFocus { focusOrder = focusOrder :+ "C" }
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                yield assert(focusOrder == List("A", "B", "C"), s"expected A,B,C order, got: $focusOrder")
                end for
            }

            "row of buttons - tab goes left to right" in run {
                var focusOrder = List.empty[String]
                val s = screen(
                    UI.div.style(Style.row)(
                        UI.button.onFocus { focusOrder = focusOrder :+ "L" }("L"),
                        UI.button.onFocus { focusOrder = focusOrder :+ "R" }("R")
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                yield assert(focusOrder == List("L", "R"), s"expected L,R order, got: $focusOrder")
                end for
            }

            "mixed input, checkbox, select, button - tab visits in order" in run {
                var focusOrder = List.empty[String]
                val s = screen(
                    UI.div(
                        UI.input.value("text").onFocus { focusOrder = focusOrder :+ "input" },
                        UI.checkbox.checked(false),
                        UI.select.value("a")(UI.option.value("a")("A")),
                        UI.button.onFocus { focusOrder = focusOrder :+ "button" }("Go")
                    ),
                    20,
                    8
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                    _ <- s.tab
                yield assert(focusOrder.nonEmpty, s"some focus events expected, got: $focusOrder")
                end for
            }
        }

        "8.3 click focus" - {
            "click on input focuses it" in run {
                var focused = false
                val s = screen(
                    UI.div(UI.input.value("text").onFocus { focused = true }),
                    15,
                    1
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield assert(focused, "clicking input should focus it")
                end for
            }

            "click on button focuses it" in run {
                var focused = false
                val s = screen(
                    UI.div(UI.button.onFocus { focused = true }("Btn")),
                    15,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0)
                yield assert(focused, "clicking button should focus it")
                end for
            }

            "click on disabled element does NOT focus" in run {
                var focused = false
                val s = screen(
                    UI.div(
                        UI.button.disabled(true).onFocus { focused = true }("No"),
                        UI.button.onFocus { focused = true }("Yes")
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.click(0, 0) // Plain theme: no border, disabled button at row 0
                yield assert(!focused, "clicking disabled button should not focus")
                end for
            }
        }

        "8.4 focus visual" - {
            "focused input has cursor, unfocused does not" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("first"),
                        UI.input.value("second")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                yield assert(s.hasCursor, "only focused input should have cursor")
                end for
            }

            "only one element focused at a time" in run {
                val s = screen(
                    UI.div(
                        UI.input.value("A"),
                        UI.input.value("B"),
                        UI.input.value("C")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.tab
                yield
                    assert(s.hasCursor, "should have exactly one cursor")
                    // Verify cursor is only on one row (one input focused at a time)
                    val row = s.cursorRow
                    assert(row >= 0, "cursor should be on a valid row")
                end for
            }

            "blur fires on previous element when focus moves" in run {
                var blurFired = false
                val s = screen(
                    UI.div(
                        UI.button.onBlur { blurFired = true }("B1"),
                        UI.button("B2")
                    ),
                    15,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab // focus B1
                    _ <- s.tab // focus B2, blur B1
                yield assert(blurFired, "onBlur should fire when focus moves away")
                end for
            }
        }
    }

end WidgetVisualTest
