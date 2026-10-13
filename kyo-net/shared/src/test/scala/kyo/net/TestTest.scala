package kyo.net

import kyo.*

class TestTest extends Test:

    import AllowUnsafe.embrace.danger

    "armedThenAdvance" - {

        "fails naming the timer when no sleeper of its duration is armed, whatever else is armed" in {
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val other = clock.unsafe.sleep(100.millis)
                    awaitArmed(tc, "close-flush grace", 200.millis, 50.millis).map { missing =>
                        assert(
                            missing ==
                                Present(
                                    s"the close-flush grace (${200.millis}) was never armed on the controlled clock within ${50.millis}"
                                ),
                            s"observed: $missing"
                        )
                        assert(!other.done(), "the fence advances nothing")
                    }
                }
            }
        }

        "waits for a timer armed after it starts, then fires it" in {
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    Fiber.initUnscoped(armedThenAdvance(tc, "close-flush grace", 200.millis)).map { advancing =>
                        val timer = clock.unsafe.sleep(200.millis)
                        advancing.get.andThen(assert(timer.done(), "the advance must reach a timer armed after the call began"))
                    }
                }
            }
        }

        "advances by the step it is given, leaving the timer armed short of its deadline" in {
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val timer = clock.unsafe.sleep(200.millis)
                    armedThenAdvance(tc, "close-flush grace", 200.millis, by = 199.millis).andThen {
                        assert(!timer.done(), "one millisecond short of the deadline the timer is still armed")
                        armedThenAdvance(tc, "close-flush grace", 200.millis, by = 1.milli).andThen {
                            assert(timer.done(), "the remaining millisecond fires it")
                        }
                    }
                }
            }
        }
    }

end TestTest
