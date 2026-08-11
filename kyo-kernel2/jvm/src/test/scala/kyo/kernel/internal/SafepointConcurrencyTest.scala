package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class SafepointConcurrencyTest extends AnyFreeSpec:

    private val Period = 512

    def spinUntil(deadlineMs: Long = 10000)(condition: => Boolean): Boolean =
        val deadline = System.currentTimeMillis() + deadlineMs
        while !condition && System.currentTimeMillis() < deadline do Thread.onSpinWait()
        condition
    end spinUntil

    "a stop request from another thread is visible once and consumed" in {
        @volatile var ready         = false
        @volatile var stopDelivered = false
        @volatile var first         = false
        @volatile var second        = true
        val t = new Thread(() =>
            val slot = Safepoint.get()
            ready = true
            while !stopDelivered do Thread.onSpinWait()
            first = Safepoint.consumeStopped(slot)
            second = Safepoint.consumeStopped(slot)
        )
        t.start()
        assert(spinUntil()(ready))
        assert(Safepoint.stop(t))
        stopDelivered = true
        t.join(10000)
        assert(first)
        assert(!second)
    }

    "racing stop requests never lose the slot ownership" in {
        val consumed          = new AtomicInteger
        val requests          = new AtomicInteger
        @volatile var ready   = false
        @volatile var running = true
        val target = new Thread(() =>
            val slot = Safepoint.get()
            ready = true
            while running do
                if Safepoint.consumeStopped(slot) then discard(consumed.incrementAndGet())
                Thread.onSpinWait()
            if Safepoint.consumeStopped(slot) then discard(consumed.incrementAndGet())
        )
        target.start()
        assert(spinUntil()(ready))
        val stoppers =
            (1 to 8).map { _ =>
                new Thread(() =>
                    var i = 0
                    while i < 1000 do
                        if Safepoint.stop(target) then discard(requests.incrementAndGet())
                        i += 1
                )
            }
        stoppers.foreach(_.start())
        stoppers.foreach(_.join(10000))
        running = false
        target.join(10000)
        assert(requests.get > 0)
        assert(consumed.get > 0)
        assert(consumed.get <= requests.get)
        assert(!Safepoint.stop(target))
    }

    "an evaluation yields to a stop requested from another thread" in {
        def burn(n: Int): Int < Any =
            if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
        @volatile var ready   = false
        @volatile var yielded = false
        @volatile var done    = false
        val target = new Thread(() =>
            discard(Safepoint.get())
            ready = true
            var attempts = 0
            while !yielded && attempts < 100000 do
                val out = Eval.partial(burn(Period * 16))
                if out.evalNow.isEmpty then yielded = true
                attempts += 1
            end while
            done = true
        )
        target.start()
        assert(spinUntil()(ready))
        val deadline = System.currentTimeMillis() + 10000
        while !done && System.currentTimeMillis() < deadline do
            discard(Safepoint.stop(target))
            Thread.onSpinWait()
        target.join(10000)
        assert(yielded)
    }

    "a dead thread is not stoppable" in {
        val t = new Thread(() => discard(Safepoint.get()))
        t.start()
        t.join(10000)
        assert(!Safepoint.stop(t))
    }

    "threads claim stable slots under concurrent lookups" in {
        val failures = new AtomicInteger
        val threads =
            (1 to 32).map { _ =>
                new Thread(() =>
                    val slot = Safepoint.get()
                    var i    = 0
                    while i < 1000 do
                        if !Safepoint.enter(slot) then discard(failures.incrementAndGet())
                        Safepoint.exit(slot)
                        i += 1
                    end while
                    // the same thread resolves the same budget state through get
                    val saved = Safepoint.save(Safepoint.get())
                    if saved != 0L then discard(failures.incrementAndGet())
                    Safepoint.restore(slot, saved)
                )
            }
        threads.foreach(_.start())
        threads.foreach(_.join(10000))
        assert(failures.get == 0)
    }

end SafepointConcurrencyTest
