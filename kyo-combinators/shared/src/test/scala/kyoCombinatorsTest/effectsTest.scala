package KyoTest

import kyo.*
import kyoTest.KyoTest

class effectsTest extends KyoTest:

    "all effects" - {
        "as" in {
            val effect         = IO(23)
            val effectAsString = effect.as("hello")
            val handled        = IO.run(effectAsString)
            assert(handled.pure == "hello")
        }

        "debug" in {
            val effect  = IO("Hello World").debug
            val handled = IO.run(effect)
            assert(handled.pure == "Hello World")
        }

        "debug(prefix)" in {
            val effect  = IO(true).debug("boolean")
            val handled = IO.run(effect)
            assert(handled.pure == true)
        }

        "discard" in {
            val effect          = IO(23)
            val effectDiscarded = effect.unit
            val handled         = IO.run(effectDiscarded)
            assert(handled.pure == ())
        }

        "*>" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 *> eff2
            val handled = IO.run(zipped)
            assert(handled.pure == "world")
        }

        "<*" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 <* eff2
            val handled = IO.run(zipped)
            assert(handled.pure == "hello")
        }

        "<*>" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 <*> eff2
            val handled = IO.run(zipped)
            assert(handled.pure == ("hello", "world"))
        }

        "when" in {
            var state: Boolean = false
            val toggleState = IO {
                state = !state
            }
            val getState          = IO(state)
            val effectWhen        = (toggleState *> getState).when(getState)
            val handledEffectWhen = IO.run(Options.run(effectWhen))
            assert(handledEffectWhen.pure == None)
            state = true
            val handledEffectWhen2 = IO.run(Options.run(effectWhen))
            assert(handledEffectWhen2.pure == Some(false))
        }

        "unless" in {
            val effect = IO("value").unless(Env.get[Boolean])

            def runEffect(b: Boolean) =
                IO.run {
                    Env.run(b) {
                        Options.run {
                            effect
                        }
                    }
                }.pure

            assert(runEffect(true) == None)
            assert(runEffect(false) == Some("value"))
        }

        "tap" in {
            val effect: Int < IO = IO(42).tap(v => assert(42 == v))
            val handled           = IO.run(effect)
            assert(handled.pure == 42)
        }
    }
end effectsTest
