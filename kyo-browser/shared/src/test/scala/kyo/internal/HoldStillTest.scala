package kyo.internal

import kyo.*

class HoldStillTest extends kyo.BaseBrowserTest:

    private def frame(n: Int): Image = Image.fromBinary(Array(n.toByte))

    "holdStill waits the interval between captures" in {
        val interval = 50.millis
        Clock.withTimeControl { control =>
            for
                times <- AtomicRef.init(Chunk.empty[Duration])
                calls <- AtomicInt.init(0)
                start <- Clock.nowMonotonic
                // Frames 0, 1, 2, then 2 again: the loop converges on the fourth capture, after three waits.
                capture = Clock.nowMonotonic.map(now => times.updateAndGet(_.append(now.minusOrZero(start))))
                    .andThen(calls.getAndIncrement.map(n => frame(math.min(n, 2))))
                fiber <- Fiber.initUnscoped(HoldStill.holdStill(1.hour, interval)(capture))
                // Racing `fiber.get` would interrupt the loop itself when the advance wins, so each round races a promise of its own.
                _ <- Kyo.foreachDiscard(1 to 3) { _ =>
                    for
                        finished <- Promise.init[Unit, Any]
                        _        <- fiber.onComplete(_ => finished.completeUnitDiscard)
                        _        <- Async.race(
                            finished.get,
                            control.awaitPendingSleeper(interval).andThen(control.advance(interval))
                        )
                    yield ()
                }
                result   <- fiber.get
                captured <- times.get
            yield
                assert(captured == Chunk(Duration.Zero, interval, interval * 2, interval * 3))
                assert(HoldStill.frameHash(result) == HoldStill.frameHash(frame(2)))
            end for
        }
    }

end HoldStillTest
