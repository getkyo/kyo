package kyo.proto.kernel

import kyo.Const
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec

class ArrowEffectThreadingTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    "a capture across the fast path replays on another thread" in {
        var kref: Arrow[Unit, Int, Any] = null
        val body: Int < (Ask & Say) =
            ask.map(a => ask.map(b => say("x").andThen(ask.map(c => a + b + c))))
        val region: Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                [C] => (n, _) => Loop.continue(n + 1, n: Int < Any),
                (n, a) => n * 1000 + a
            )
        val r0 = eval(ArrowEffect.handleCont(Tag[Say], region)(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                    cont(())
            ,
            a => a
        ))
        assert(r0 == 3003)
        @volatile var tr = 0
        val t            = new Thread(() => tr = eval(kref(())))
        t.start()
        t.join()
        assert(tr == 3003)
    }

    "a hoarded fast-path continuation replays on another thread after the region finished" in {
        var kref: Arrow[Int, Int, Any] = null
        def loop(i: Int): Int < Ask =
            if i > 3 then i else ask.map(a => loop(i + a))
        val r0 = eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                    cont(1)
            ,
            a => a
        ))
        assert(r0 == 4)
        @volatile var tr = 0
        val t            = new Thread(() => tr = eval(kref(1)))
        t.start()
        t.join()
        assert(tr == 4)
    }

    "deep state transitions under a suspending clause fit a small stack" in {
        @volatile var result = -1
        def loop(i: Int): Int < Ask =
            if i == 0 then 0 else ask.map(a => loop(i - a))
        val t = new Thread(
            null,
            () =>
                val handled = ArrowEffect.handleLoopState(Tag[Ask], 0, loop(20000))(
                    [C] => (s, _) => Effect.defer(Loop.continue(s + 1, 1: Int < Any)),
                    (s, a) => s + a
                )
                result = eval(handled)
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
