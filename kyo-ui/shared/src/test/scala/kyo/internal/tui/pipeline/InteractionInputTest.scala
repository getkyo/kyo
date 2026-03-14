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
                _ <- s.typeChar('H')
                _ <- s.typeChar('i')
            yield
                assert(ref.get() == "Hi")
                assert(s.frame.contains("Hi"))
            end for
        }

        "backspace deletes character" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.backspace
            yield assert(ref.get() == "A")
            end for
        }

        "arrow keys move cursor" in run {
            val ref = SignalRef.Unsafe.init("AB")
            val s   = Screen(UI.input.value(ref.safe), 10, 1)
            for
                _ <- s.render
                _ <- s.arrowLeft     // cursor moves left
                _ <- s.typeChar('X') // insert at cursor
            yield assert(ref.get() == "AXB")
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
