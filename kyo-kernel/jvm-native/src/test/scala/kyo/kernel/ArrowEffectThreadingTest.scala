package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Loop
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class ArrowEffectThreadingTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    "a capture across the fast path replays on another thread" in {
        var kref: Arrow[Unit, Int, Any] = null
        val body: Int < (Ask & Say)     =
            ask.map(a => ask.map(b => say("x").andThen(ask.map(c => a + b + c))))
        val region: Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                [C] => (n, _) => Loop.continue(n + 1, n),
                (n, a) => n * 1000 + a
            )
        val r0 = ArrowEffect.handleCont(Tag[Say], region)(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                    cont(())
            ,
            a => a
        ).eval
        assert(r0 == 3003)
        @volatile var tr = 0
        val t            = new Thread(() => tr = kref(()).eval)
        t.start()
        t.join()
        assert(tr == 3003)
    }

    "a hoarded fast-path continuation replays on another thread after the region finished" in {
        var kref: Arrow[Int, Int, Any] = null
        def loop(i: Int): Int < Ask    =
            if i > 3 then i else ask.map(a => loop(i + a))
        val r0 = ArrowEffect.handleCont(Tag[Ask], loop(0))(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                    cont(1)
            ,
            a => a
        ).eval
        assert(r0 == 4)
        @volatile var tr = 0
        val t            = new Thread(() => tr = kref(1).eval)
        t.start()
        t.join()
        assert(tr == 4)
    }

    "deep state transitions under a suspending clause fit a small stack" in {
        @volatile var result        = -1
        def loop(i: Int): Int < Ask =
            if i == 0 then 0 else ask.map(a => loop(i - a))
        val t = new Thread(
            null,
            () =>
                val handled = ArrowEffect.handleLoopState(Tag[Ask], 0, loop(20000))(
                    [C] => (s, _) => Effect.defer(Loop.continue(s + 1, 1)),
                    (s, a) => s + a
                )
                result =
                    handled.eval
            ,
            "small-stack",
            256 * 1024
        )
        t.start()
        t.join(30000)
        assert(!t.isAlive)
        assert(result == 20000)
    }
end ArrowEffectThreadingTest
