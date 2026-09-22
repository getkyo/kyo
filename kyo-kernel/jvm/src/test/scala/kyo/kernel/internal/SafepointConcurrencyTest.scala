package kyo.kernel.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.Const
import kyo.Loop
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec

class SafepointConcurrencyTest extends AnyFreeSpec:

    private val Period = Safepoint.period()
    private val Slots  = 65536
    private val Homes  = 8192

    def spinUntil(deadlineMs: Long = 10000)(condition: => Boolean): Boolean =
        val deadline = java.lang.System.currentTimeMillis() + deadlineMs
        while !condition && java.lang.System.currentTimeMillis() < deadline do Thread.onSpinWait()
        condition
    end spinUntil

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    private given CanEqual[Safepoint.Slot, Safepoint.Slot] = CanEqual.derived

    "does not allow capturing across threads" in {
        val slot                             = Safepoint.get()
        @volatile var forked: Safepoint.Slot = slot
        val t                                = new Thread(() => forked = Safepoint.get())
        t.start()
        t.join(10000)
        assert(!t.isAlive)
        assert(forked != slot)
    }

    "allows resuming in the same thread" in {
        val slot                    = Safepoint.get()
        var resumed: Safepoint.Slot = slot
        discard {
            (0: Int < Any).map { _ =>
                resumed = Safepoint.get()
                0
            }.eval
        }
        assert(resumed == slot)
        assert(Safepoint.get() == slot)
    }

    "no leak between forked executions" in {
        val slot                             = Safepoint.get()
        @volatile var forked: Safepoint.Slot = slot
        @volatile var result                 = 0
        val t                                = new Thread(() =>
            forked = Safepoint.get()
            result = (1: Int < Any).map(_ + 1).map(_ + 2).eval
        )
        t.start()
        t.join(10000)
        assert(!t.isAlive)
        assert(result == 4)
        assert(forked != slot)
    }

    "no new Safepoint for nested eval calls" in {
        val outer                 = Safepoint.get()
        var inner: Safepoint.Slot = outer
        val result                =
            (0: Int < Any).map { _ =>
                val nested =
                    (21: Int < Any).map { v =>
                        inner = Safepoint.get()
                        v
                    }.eval
                nested * 2
            }.eval
        assert(result == 42)
        assert(inner == outer)
    }

    "a stop request from another thread is visible once and consumed" in {
        @volatile var ready         = false
        @volatile var stopDelivered = false
        @volatile var first         = false
        @volatile var second        = true
        val t                       = new Thread(() =>
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
        val target            = new Thread(() =>
            val slot = Safepoint.get()
            ready = true
            while running do
                if Safepoint.consumeStopped(slot) then discard(consumed.incrementAndGet())
                Thread.onSpinWait()
            if Safepoint.consumeStopped(slot) then discard(consumed.incrementAndGet())
        )
        target.start()
        assert(spinUntil()(ready))
        val stoppers = (1 to 8).map { _ =>
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
        val target            = new Thread(() =>
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
        val deadline = java.lang.System.currentTimeMillis() + 10000
        while !done && java.lang.System.currentTimeMillis() < deadline do
            discard(Safepoint.stop(target))
            Thread.onSpinWait()
        target.join(10000)
        assert(yielded)
    }

    "a stop delivered during a fast answer loop ends the slice" in {
        def countdown(i: Int): Int < Ask =
            if i == 0 then 0 else ask.map(a => countdown(i - a))
        var pendingRuns = 0
        var run         = 0
        while run < 50 do
            @volatile var started = false
            @volatile var pending = false
            val target            = new Thread(() =>
                val region: Int < Any =
                    ArrowEffect.handleLoop(Tag[Ask], countdown(5_000_000))(
                        [C] =>
                            _ =>
                                started = true
                                Loop.continue(1)
                        ,
                        a => a
                    )
                pending = Eval.partial(region).evalNow.isEmpty
            )
            target.start()
            assert(spinUntil()(started))
            discard(Safepoint.stop(target))
            target.join(20000)
            assert(!target.isAlive)
            if pending then pendingRuns += 1
            run += 1
        end while
        assert(pendingRuns > 40)
    }

    "a live thread that never evaluated is not stoppable" in {
        discard(Safepoint.get())
        @volatile var running = true
        @volatile var started = false
        val t                 = new Thread(() =>
            started = true
            while running do Thread.onSpinWait()
        )
        t.start()
        try
            assert(spinUntil()(started))
            assert(t.isAlive())
            assert(!Safepoint.stop(t))
        finally
            running = false
            t.join(10000)
        end try
    }

    "a dead thread is not stoppable" in {
        val t = new Thread(() => discard(Safepoint.get()))
        t.start()
        t.join(10000)
        assert(!Safepoint.stop(t))
    }

    "a displaced thread keeps its slot and its budget after nearby cells free" in {
        val holderCount = Homes * 3
        val ready       = new CountDownLatch(holderCount)
        val release     = new CountDownLatch(1)
        val holders     = (1 to holderCount).map { _ =>
            Thread.ofVirtual().start(() =>
                discard(Safepoint.get())
                ready.countDown()
                discard(release.await(60, TimeUnit.SECONDS))
            )
        }
        try
            assert(ready.await(60, TimeUnit.SECONDS))
            val probeCount  = 8
            val probesReady = new CountDownLatch(probeCount)
            val holdersDead = new CountDownLatch(1)
            val remaining   = new Array[Int](probeCount)
            val consumed    = new Array[Boolean](probeCount)
            val probes      = (0 until probeCount).map { i =>
                Thread.ofVirtual().start(() =>
                    val slot = Safepoint.get()
                    discard(Safepoint.enter(slot))
                    discard(Safepoint.enter(slot))
                    discard(Safepoint.enter(slot))
                    probesReady.countDown()
                    discard(holdersDead.await(60, TimeUnit.SECONDS))
                    consumed(i) = Safepoint.consumeStopped(Safepoint.get())
                    var extra = 0
                    while Safepoint.enter(Safepoint.get()) do extra += 1
                    remaining(i) = extra
                )
            }
            assert(probesReady.await(60, TimeUnit.SECONDS))
            probes.foreach(p => assert(Safepoint.stop(p)))
            release.countDown()
            holders.foreach(_.join(60000))
            holdersDead.countDown()
            probes.foreach(_.join(60000))
            (0 until probeCount).foreach { i =>
                assert(consumed(i))
                assert(remaining(i) == Period - 3)
            }
        finally
            release.countDown()
        end try
    }

    "claims reuse the cells of dead threads" in {
        var i = 0
        while i < Slots do
            val batch = (1 to 4096).map(_ => Thread.ofVirtual().start(() => discard(Safepoint.get())))
            batch.foreach(_.join(30000))
            i += 4096
        end while
        @volatile var entered = false
        val ready             = new CountDownLatch(1)
        val done              = new CountDownLatch(1)
        val v                 = Thread.ofVirtual().start(() =>
            entered = Safepoint.enter(Safepoint.get())
            ready.countDown()
            discard(done.await(30, TimeUnit.SECONDS))
        )
        try
            assert(ready.await(30, TimeUnit.SECONDS))
            assert(entered)
            assert(Safepoint.stop(v))
        finally
            done.countDown()
            v.join(30000)
        end try
    }

    "the overflowed slot ignores budget operations and misses preemption" in {
        val ready   = new CountDownLatch(Slots)
        val release = new CountDownLatch(1)
        val holders = (1 to Slots).map { _ =>
            Thread.ofVirtual().start(() =>
                discard(Safepoint.get())
                ready.countDown()
                discard(release.await(60, TimeUnit.SECONDS))
            )
        }
        try
            assert(ready.await(60, TimeUnit.SECONDS))
            @volatile var enterFirst    = false
            @volatile var enterAfter    = false
            @volatile var stoppedResult = true
            @volatile var evalResult    = -1
            val probeReady              = new CountDownLatch(1)
            val checked                 = new CountDownLatch(1)
            val probe                   = Thread.ofVirtual().start(() =>
                def burn(n: Int): Int < Any =
                    if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
                val slot = Safepoint.get()
                enterFirst = Safepoint.enter(slot)
                Safepoint.exit(slot)
                Safepoint.restore(slot, Safepoint.save(slot))
                enterAfter = Safepoint.enter(slot)
                Safepoint.exit(slot)
                stoppedResult = Safepoint.consumeStopped(slot)
                evalResult = burn(Period * 4).eval
                probeReady.countDown()
                discard(checked.await(60, TimeUnit.SECONDS))
            )
            assert(probeReady.await(60, TimeUnit.SECONDS))
            assert(enterFirst)
            assert(enterAfter)
            assert(!stoppedResult)
            assert(evalResult == 0)
            assert(!Safepoint.stop(probe))
            checked.countDown()
            probe.join(60000)
        finally
            release.countDown()
            holders.foreach(_.join(60000))
        end try
    }

    "threads claim stable slots under concurrent lookups" in {
        val failures = new AtomicInteger
        val threads  = (1 to 32).map { _ =>
            new Thread(() =>
                val slot = Safepoint.get()
                var i    = 0
                while i < 1000 do
                    if !Safepoint.enter(slot) then discard(failures.incrementAndGet())
                    Safepoint.exit(slot)
                    i += 1
                end while
                val saved = Safepoint.save(Safepoint.get())
                Safepoint.restore(slot, saved)
                if !Safepoint.enter(slot) then discard(failures.incrementAndGet())
                Safepoint.exit(slot)
            )
        }
        threads.foreach(_.start())
        threads.foreach(_.join(10000))
        assert(failures.get == 0)
    }

end SafepointConcurrencyTest
