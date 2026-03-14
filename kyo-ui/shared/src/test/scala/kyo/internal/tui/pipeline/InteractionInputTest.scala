package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class InteractionInputTest extends Test:

    import AllowUnsafe.embrace.danger

    "text input" - {
        "renders empty value" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            s.render.andThen {
                val f = s.frame
                // Should render without crashing, cursor visible
                assert(f.length == 10)
            }
        }

        "renders initial value" in run {
            val ref = SignalRef.Unsafe.init("hello")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            s.render.andThen(assert(s.frame.contains("hello")))
        }

        "type a character" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0) // focus input
                _ <- s.typeChar('A')
            yield
                assert(ref.get() == "A")
                assert(s.frame.contains("A"))
            end for
        }

        "type multiple characters" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0) // focus input
                _ <- s.typeChar('H')
                _ <- s.typeChar('i')
            yield
                assert(ref.get() == "Hi")
                assert(s.frame.contains("Hi"))
            end for
        }

        "backspace deletes last typed character" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0) // focus input
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.backspace
            yield assert(ref.get() == "A", s"expected 'A', got '${ref.get()}'")
            end for
        }

        "arrow left then type inserts at cursor" in run {
            val ref = SignalRef.Unsafe.init("")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.click(0, 0)   // focus input
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
                _ <- s.arrowLeft     // cursor before B
                _ <- s.typeChar('X') // insert X before B
            yield assert(ref.get() == "AXB", s"expected 'AXB', got '${ref.get()}'")
            end for
        }
    }

    "input with plain string value" - {
        "renders string value without SignalRef" in run {
            val s = Screen(UI.input.value("static"), 10, 1)
            s.render.andThen {
                assert(s.frame.contains("static"), s"static value missing: ${s.frame}")
            }
        }
    }

    "placeholder" - {
        "renders placeholder when empty" in run {
            val s = Screen(UI.input.placeholder("Enter..."), 15, 1)
            s.render.andThen {
                assert(s.frame.contains("Enter"), s"placeholder missing: ${s.frame}")
            }
        }
    }

    "readonly input" - {
        "readonly input shows value but ignores typing" in run {
            val ref = SignalRef.Unsafe.init("fixed")
            val s   = Screen(UI.input.value(ref.safe).readOnly(true), 10, 1)
            for
                _ <- s.render
                _ <- s.typeChar('X')
            yield assert(ref.get() == "fixed", "readonly input should not accept typing")
            end for
        }
    }

    "password" - {
        "masks value" in run {
            val ref = SignalRef.Unsafe.init("secret")
            val s   = Screen(UI.password.value(ref.safe), 10, 1)
            s.render.andThen {
                val f = s.frame
                assert(!f.contains("secret"))
                assert(f.contains("••••••"))
            }
        }
    }

    "disabled input" - {
        "ignores keystrokes" in run {
            val ref = SignalRef.Unsafe.init("original")
            val s   = Screen(UI.input.value(ref.safe).disabled(true), 15, 1)
            for
                _ <- s.render
                _ <- s.typeChar('X')
            yield assert(ref.get() == "original")
            end for
        }
    }

end InteractionInputTest
