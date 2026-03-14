package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionSelectTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    "collapsed state" - {
        "shows selected value" in run {
            val s = screen(
                UI.select.value("apple")(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("apple") || f.contains("Apple"), s"selected value not shown: $f")
            }
        }

        "shows dropdown indicator" in run {
            val s = screen(
                UI.select.value("apple")(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                1
            )
            s.render.andThen {
                val f = s.frame
                assert(f.contains("▼") || f.contains("▾") || f.contains("v"), s"no dropdown indicator: $f")
            }
        }
    }

    "selection" - {
        "click opens dropdown" in run {
            val s = screen(
                UI.select.value("apple")(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield
                val f = s.frame
                // After clicking, options should be visible
                assert(f.contains("Banana"), s"dropdown options not visible after click: $f")
            end for
        }

        "onChange fires when option selected via keyboard" in run {
            var selected = ""
            val s = screen(
                UI.select.value("apple").onChange(v => selected = v)(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)                // focus and open dropdown
                _ <- s.key(UI.Keyboard.ArrowDown) // highlight "Banana"
                _ <- s.enter                      // select highlighted
            yield assert(selected == "banana", s"expected 'banana' selected, got: $selected")
            end for
        }
    }

    "keyboard navigation" - {
        "arrow down navigates options" in run {
            var selected = ""
            val s = screen(
                UI.select.value("apple").onChange(v => selected = v)(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana"),
                    UI.option.value("cherry")("Cherry")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)                // open dropdown
                _ <- s.key(UI.Keyboard.ArrowDown) // navigate down
                _ <- s.enter                      // select
            yield assert(selected.nonEmpty, s"no option selected after keyboard navigation")
            end for
        }
    }

    "escape closes dropdown" - {
        "escape closes without selecting" in run {
            var selected = ""
            val s = screen(
                UI.select.value("apple").onChange(v => selected = v)(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)             // open
                _ <- s.key(UI.Keyboard.Escape) // close without selecting
            yield assert(selected == "", s"escape should not select, got: $selected")
            end for
        }
    }

    "select with no options" - {
        "empty select renders without crash" in run {
            val s = screen(UI.select.value(""), 10, 1)
            s.render.andThen {
                assert(s.frame.length == 10)
            }
        }
    }

    "disabled select" - {
        "ignores click" in run {
            var selected = ""
            val s = screen(
                UI.select.value("apple").disabled(true).onChange(v => selected = v)(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)
            yield assert(selected == "", s"disabled select should not respond to click")
            end for
        }
    }

    "arrow up navigates options" - {
        "arrow up from second option selects first" in run {
            var selected = ""
            val s = screen(
                UI.select.value("apple").onChange(v => selected = v)(
                    UI.option.value("apple")("Apple"),
                    UI.option.value("banana")("Banana"),
                    UI.option.value("cherry")("Cherry")
                ),
                15,
                5
            )
            for
                _ <- s.render
                _ <- s.click(1, 0)                // open dropdown
                _ <- s.key(UI.Keyboard.ArrowDown) // highlight "Banana"
                _ <- s.key(UI.Keyboard.ArrowUp)   // highlight back to "Apple"
                _ <- s.enter                      // select
            yield assert(selected == "apple", s"expected 'apple', got: $selected")
            end for
        }
    }

end InteractionSelectTest
