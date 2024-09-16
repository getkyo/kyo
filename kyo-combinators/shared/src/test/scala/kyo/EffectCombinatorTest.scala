package kyo

class EffectCombinatorTest extends Test:

    "all effects" - {
        "as" in {
            val effect         = IO(23)
            val effectAsString = effect.as("hello")
            val handled        = IO.run(effectAsString)
            assert(handled.eval == "hello")
        }

        "debug" in {
            val effect  = IO("Hello World").debugValue
            val handled = IO.run(effect)
            assert(handled.eval == "Hello World")
        }

        "debug(prefix)" in {
            val effect  = IO(true).debugTrace
            val handled = IO.run(effect)
            assert(handled.eval == true)
        }

        "discard" in {
            val effect          = IO(23)
            val effectDiscarded = effect.unit
            val handled         = IO.run(effectDiscarded)
            assert(handled.eval == ())
        }

        "*>" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 *> eff2
            val handled = IO.run(zipped)
            assert(handled.eval == "world")
        }

        "<*" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 <* eff2
            val handled = IO.run(zipped)
            assert(handled.eval == "hello")
        }

        "<*>" in {
            val eff1    = IO("hello")
            val eff2    = IO("world")
            val zipped  = eff1 <*> eff2
            val handled = IO.run(zipped)
            assert(handled.eval == ("hello", "world"))
        }

        "when" in {
            var state: Boolean = false
            val toggleState = IO {
                state = !state
            }
            val getState          = IO(state)
            val effectWhen        = (toggleState *> getState).when(getState)
            val handledEffectWhen = IO.run(Abort.run(effectWhen))
            assert(handledEffectWhen.eval == Result.fail(Maybe.Empty))
            state = true
            val handledEffectWhen2 = IO.run(Abort.run(effectWhen))
            assert(handledEffectWhen2.eval == Result.success(false))
        }

        "unless" in {
            val effect = IO("value").unless(Env.get[Boolean])

            def runEffect(b: Boolean) =
                IO.run {
                    Env.run(b) {
                        Abort.run {
                            effect
                        }
                    }
                }.eval

            assert(runEffect(true) == Result.fail(Maybe.Empty))
            assert(runEffect(false) == Result.success("value"))
        }

        "tap" in {
            val effect: Int < IO = IO(42).tap(v => assert(42 == v))
            val handled          = IO.run(effect)
            assert(handled.eval == 42)
        }
    }
end EffectCombinatorTest
