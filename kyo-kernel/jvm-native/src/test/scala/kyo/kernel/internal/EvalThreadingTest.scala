package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.*

/** The captured-continuation-as-value pin that resumes on real threads; the same-thread shots stay
  * in the shared EvalTest.
  */
class EvalThreadingTest extends kyo.Test:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def stateful(body: Int < (Ask & Say)): Int < Say =
        ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    "a captured continuation resumes on another thread" in {
        var stored: Maybe[Arrow[Unit, Int, Say]] = Maybe.empty
        val inner: Int < Say                     = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
            [C] =>
                (_, cont) =>
                    stored = Maybe(cont)
                    -1
            ,
            a => a
        )
        assert(Eval(r) == -1)
        val k             = stored.get
        def resume(): Int = Eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
        val results       = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
        val threads = (1 to 4).map(_ =>
            new Thread(() =>
                results.add(resume()); ()
            )
        )
        threads.foreach(_.start())
        threads.foreach(_.join())
        assert(List.fill(4)(results.poll()) == List(1, 1, 1, 1))
        assert(results.isEmpty)
    }

    // the stop-driven halves of EvalTest's partial-evaluation section: stops are the jvm-native
    // preemption mechanism, self-delivered or from another thread
    "partial evaluation under stops" - {

        "a preemption stop reifies and resumes with handler state" in {
            var clauseRuns = 0
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val counted: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                [C] =>
                    (n, _) =>
                        clauseRuns += 1
                        if n == 10 then discard(Safepoint.stop(Thread.currentThread()))
                        Loop.continue(n + 1, 1)
                ,
                (n, a) => n + a
            )
            val first = Eval.partial(counted)
            assert(first.evalNow == Maybe.Absent)
            // the slice stopped where the stop was lodged, not budget-periods later: the eleventh
            // clause run raises the stop, and at most one more answer may slip through before the
            // park lands
            assert(clauseRuns >= 11 && clauseRuns <= 12, s"clauseRuns=$clauseRuns")
            assert(Eval.partial(first).evalNow == Maybe(100))
            assert(clauseRuns == 100)
        }

        "a stop delivered between slices short-circuits" in {
            val v: Int < Any = answerAsk(41)(ask.map(_ + 1))
            discard(Safepoint.stop(Thread.currentThread()))
            val r = Eval.partial(v)
            assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])
            assert(Eval.partial(r).evalNow == Maybe(42))
        }

        "a cross-thread stop parks a running slice" in {
            // the suspension channel: the answers loop peeks the sentinel at each answer, bails,
            // and the park check sees the same pending stop. A consuming poll would lose the
            // preemption here; the loop never terminates on its own, so parking is the only way
            // the thread ends
            @volatile var started = false
            @volatile var parked  = false
            val t = new Thread(() =>
                def loop(i: Int): Int < Ask =
                    ask.map { a =>
                        started = true
                        loop(i + a - 1)
                    }
                parked = Eval.partial(answerAsk(1)(loop(0))).evalNow.isEmpty
            )
            t.start()
            while !started do ()
            assert(Safepoint.stop(t))
            t.join(20000)
            assert(!t.isAlive)
            assert(parked)
        }

        "a cross-thread stop parks a settled spin with no suspensions" in {
            // the settled channel: observation through the plain home probe, the drain making the
            // next step defer, and the park check taking the still-pending stop. No suspension
            // anywhere; the spin only ends by parking
            @volatile var started = false
            @volatile var parked  = false
            val t = new Thread(() =>
                def loop(i: Int): Int < Any =
                    ((i + 1) & 63: Int < Any).map { v =>
                        started = true
                        loop(v)
                    }
                parked = Eval.partial(loop(0)).evalNow.isEmpty
            )
            t.start()
            while !started do ()
            assert(Safepoint.stop(t))
            t.join(20000)
            assert(!t.isAlive)
            assert(parked)
        }

        "a stop the slice outruns is consumed at the boundary" in {
            // the stop lands after the last poll point, so the slice completes; the boundary
            // consumes the sentinel, and the next slice runs instead of short-circuiting
            val v: Int < Any = (1: Int < Any).map { x =>
                discard(Safepoint.stop(Thread.currentThread()))
                x + 41
            }
            assert(Eval.partial(v).evalNow == Maybe(42))
            val w: Int < Any = answerAsk(20)(ask.map(_ * 2 + 2))
            assert(Eval.partial(w).evalNow == Maybe(42))
        }
    }
end EvalThreadingTest
