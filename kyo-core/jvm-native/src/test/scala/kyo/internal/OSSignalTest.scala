package kyo.internal

import kyo.*

class OsSignalTest extends kyo.test.Test[Any]:

    // One handler per signal is the contract on every platform, so a leaf running concurrently with another that
    // handles the same signal would replace its handler before it raises.
    override def config = super.config.sequential

    private val unixSignals = "SIGUSR1 and SIGUSR2 do not exist on Windows, and OsSignal installs no handler there"

    private def awaitSignal(signal: String)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { delivered =>
            Sync.Unsafe.defer {
                OsSignal.handle(signal, () => delivered.unsafe.completeUnitDiscard())
                OSSignalTestPlatform.send(signal)
            }.andThen(delivered.get)
        }

    "a raised signal reaches its handler" in {
        assume(!Platform.isWindows, unixSignals)
        awaitSignal("USR2").andThen(succeed)
    }

    "installing a handler does not run it, and another signal does not reach it" in {
        assume(!Platform.isWindows, unixSignals)
        for
            runs <- AtomicInt.init(0)
            _    <- Sync.Unsafe.defer(OsSignal.handle("USR1", () => discard(runs.unsafe.incrementAndGet())))
            _    <- awaitSignal("USR2")
            n    <- runs.get
        yield assert(n == 0)
        end for
    }

    "every signal is delivered while other threads allocate" in {
        assume(!Platform.isWindows, unixSignals)
        val rounds = 2000
        for
            stop     <- AtomicBoolean.init(false)
            churners <- Kyo.fill(4)(Fiber.initUnscoped(churn(stop)))
            _        <- Sync.ensure(stop.set(true)) {
                Loop.indexed(i => if i == rounds then Loop.done else awaitSignal("USR2").andThen(Loop.continue))
            }
            churned <- Kyo.foreach(churners)(_.get)
        yield assert(churned.forall(_ > 0))
        end for
    }

    private def churn(stop: AtomicBoolean)(using Frame): Int < Sync =
        Loop(0) { n =>
            stop.get.map { done =>
                if done then Loop.done(n)
                else Loop.continue(n + Chunk.from(Array.fill(64)(new Array[Byte](256))).size / 64)
            }
        }
end OsSignalTest
