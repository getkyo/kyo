package KyoTest

import kyo.*
import kyoTest.KyoTest

class effectsTest extends KyoTest:

    "all effects" - {
        "as" in {
            val effect         = IOs(23)
            val effectAsString = effect.as("hello")
            val handled        = IOs.run(effectAsString)
            assert(handled.pure == "hello")
        }

        "debug" in {
            val effect  = IOs("Hello World").debug
            val handled = IOs.run(effect)
            assert(handled.pure == "Hello World")
        }

        "debug(prefix)" in {
            val effect  = IOs(true).debug("boolean")
            val handled = IOs.run(effect)
            assert(handled.pure == true)
        }

        "discard" in {
            val effect          = IOs(23)
            val effectDiscarded = effect.discard
            val handled         = IOs.run(effectDiscarded)
            assert(handled.pure == ())
        }

        "*>" in {
            val eff1    = IOs("hello")
            val eff2    = IOs("world")
            val zipped  = eff1 *> eff2
            val handled = IOs.run(zipped)
            assert(handled.pure == "world")
        }

        "<*" in {
            val eff1    = IOs("hello")
            val eff2    = IOs("world")
            val zipped  = eff1 <* eff2
            val handled = IOs.run(zipped)
            assert(handled.pure == "hello")
        }

        "<*>" in {
            val eff1    = IOs("hello")
            val eff2    = IOs("world")
            val zipped  = eff1 <*> eff2
            val handled = IOs.run(zipped)
            assert(handled.pure == ("hello", "world"))
        }

        "when" in {
            var state: Boolean = false
            val toggleState = IOs {
                state = !state
            }
            val getState          = IOs(state)
            val effectWhen        = (toggleState *> getState).when(getState)
            val handledEffectWhen = IOs.run(Options.run(effectWhen))
            assert(handledEffectWhen.pure == None)
            state = true
            val handledEffectWhen2 = IOs.run(Options.run(effectWhen))
            assert(handledEffectWhen2.pure == Some(false))
        }

        "unless" in {
            val effect = IOs("value").unless(Envs.get[Boolean])

            def runEffect(b: Boolean) =
                IOs.run {
                    Envs.run(b) {
                        Options.run {
                            effect
                        }
                    }
                }.pure

            assert(runEffect(true) == None)
            assert(runEffect(false) == Some("value"))
        }

        "tap" in {
            val effect: Int < IOs = IOs(42).tap(v => assert(42 == v))
            val handled           = IOs.run(effect)
            assert(handled.pure == 42)
        }
    }
end effectsTest
