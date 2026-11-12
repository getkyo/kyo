package kyo.internal

import kyo.*
import kyo.kernel.ArrowEffect

class HoldStillTest extends BrowserTest:

    // Observe the sampler's first asynchronous wait before advancing time. Advancing a clock
    // from a competing fiber could let a missing wait pass, depending on which fiber ran first.
    private def advanceFirstWait[A, S](control: Clock.TimeControl, interval: Duration)(
        body: A < (Async & S)
    )(using Frame): A < (Async & S) =
        ArrowEffect.handleFirst(Tag[Async.Join], body)(
            [C] =>
                (input, continue) =>
                    control.advance(interval, Duration.Zero).andThen {
                        ArrowEffect.suspendWith(Tag[Async.Join], input)(result => continue(result))
                    },
            identity
        )

    private val first  = Image.fromBinary(Array[Byte](1, 2, 3))
    private val second = Image.fromBinary(Array[Byte](4, 5, 6))

    "hold-still awaits the sampling interval before comparing frames" in {
        withBrowser {
            Browser.use { tab =>
                Clock.withTimeControl { control =>
                    for
                        times <- AtomicRef.init(Chunk.empty[Duration])
                        image <- advanceFirstWait(control, 5.seconds) {
                            Browser.runOn(tab) {
                                Browser.withConfig(_.captureHoldStillInterval(5.seconds).captureHoldStillTimeout(20.seconds)) {
                                    HoldStill.holdStillFrame {
                                        Clock.nowMonotonic.map(now => times.updateAndGet(_.append(now)).andThen(first))
                                    }
                                }
                            }
                        }
                        samples <- times.get
                    yield
                        assert(samples == Chunk(Duration.Zero, 5.seconds), s"samples=$samples")
                        assert(image == first)
                }
            }
        }
    }

    "hold-still returns the latest frame when the sampling deadline is reached" in {
        withBrowser {
            Browser.use { tab =>
                Clock.withTimeControl { control =>
                    for
                        times <- AtomicRef.init(Chunk.empty[Duration])
                        image <- advanceFirstWait(control, 5.seconds) {
                            Browser.runOn(tab) {
                                Browser.withConfig(_.captureHoldStillInterval(5.seconds).captureHoldStillTimeout(5.seconds)) {
                                    HoldStill.holdStillFrame {
                                        Clock.nowMonotonic.map { now =>
                                            times.updateAndGet(_.append(now)).andThen(if now == Duration.Zero then first else second)
                                        }
                                    }
                                }
                            }
                        }
                        samples <- times.get
                    yield
                        assert(samples == Chunk(Duration.Zero, 5.seconds), s"samples=$samples")
                        assert(image == second)
                }
            }
        }
    }

    "hold-still with a zero deadline returns its first frame without sleeping" in {
        withBrowser {
            Browser.use { tab =>
                Clock.withTimeControl { control =>
                    for
                        calls <- AtomicInt.init(0)
                        image <- advanceFirstWait(control, 5.seconds) {
                            Browser.runOn(tab) {
                                Browser.withConfig(_.captureHoldStillInterval(5.seconds).captureHoldStillTimeout(Duration.Zero)) {
                                    HoldStill.holdStillFrame(calls.incrementAndGet.andThen(first))
                                }
                            }
                        }
                        count <- calls.get
                        time  <- Clock.nowMonotonic
                    yield
                        assert(count == 1)
                        assert(time == Duration.Zero)
                        assert(image == first)
                }
            }
        }
    }

end HoldStillTest
