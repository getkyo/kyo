package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

class ParkedSlotTest extends Test:

    import AllowUnsafe.embrace.danger

    private type P = Promise.Unsafe[ReadOutcome, Abort[Closed]]

    private val closed = Closed("slot", Frame.internal, "cancelled")

    private def promise(): P = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

    private def holds(m: Maybe[P], p: P): Boolean = m.exists(_.equals(p))

    private def failedClosed(p: P): Boolean =
        p.poll() match
            case Present(Result.Failure(c)) => c.equals(closed)
            case _                          => false

    "a cancel during a dispatch fails the promise and the re-park refuses it" in {
        val life = HandleLife.init()
        val p    = promise()
        assert(life.read.park(p, closed))
        assert(holds(life.read.beginDispatch(), p))
        life.cancel(closed)
        assert(failedClosed(p))
        assert(!life.read.repark(p, closed))
        assert(life.read.current.isEmpty)
    }

    "a dispatch with no cancel re-parks its promise live" in {
        val life = HandleLife.init()
        val p    = promise()
        assert(life.read.park(p, closed))
        assert(holds(life.read.beginDispatch(), p))
        assert(life.read.repark(p, closed))
        assert(p.poll().isEmpty)
        assert(holds(life.read.current, p))
    }

    "a promise under dispatch is not taken by the completing side" in {
        val life = HandleLife.init()
        val p    = promise()
        assert(life.read.park(p, closed))
        assert(holds(life.read.beginDispatch(), p))
        assert(life.read.take().isEmpty)
        life.read.endDispatch(p)
        assert(life.read.current.isEmpty)
        assert(p.poll().isEmpty)
    }

    "a re-park after a later park replaced the dispatched promise fails it and keeps the later one" in {
        val life  = HandleLife.init()
        val p     = promise()
        val later = promise()
        assert(life.read.park(p, closed))
        assert(holds(life.read.beginDispatch(), p))
        assert(life.read.park(later, closed))
        assert(!life.read.repark(p, closed))
        assert(failedClosed(p))
        assert(holds(life.read.current, later))
        assert(later.poll().isEmpty)
    }

    "a park after the release claim is failed when it returns, even while a sweep on another carrier takes it" in {
        val life  = HandleLife.init()
        val parks = 200000
        assert(life.claimRelease())
        val stop = AtomicBoolean.Unsafe.init(false)
        // The sweep the release's own engine op runs on the reap carrier, racing the parks below.
        Fiber.initUnscoped(Loop.foreach {
            Sync.defer {
                if stop.get() then Loop.done(())
                else
                    life.read.fail(closed)
                    Loop.continue
            }
        }).map { sweeper =>
            Loop(0, 0) { (i, pending) =>
                if i == parks then Loop.done(pending)
                else
                    val p = promise()
                    assert(!life.read.park(p, closed))
                    Loop.continue(i + 1, if p.poll().isEmpty then pending + 1 else pending)
            }.map { pending =>
                stop.set(true)
                sweeper.get.andThen(assert(pending == 0, s"$pending of $parks parks returned with the promise still pending"))
            }
        }
    }

    "a re-park after the release claim fails the promise" in {
        val life = HandleLife.init()
        val p    = promise()
        assert(life.read.park(p, closed))
        assert(holds(life.read.beginDispatch(), p))
        assert(life.claimRelease())
        assert(!life.read.repark(p, closed))
        assert(failedClosed(p))
        assert(life.read.current.isEmpty)
    }

end ParkedSlotTest
