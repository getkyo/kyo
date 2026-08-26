package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Tag
import kyo.kernel.internal.Eval

/** The cross-thread halves of the multi-shot capture pins: a captured continuation is a complete
  * value, replayable on a thread that never ran the eval it escaped. The same-thread replays stay
  * in the shared ArrowEffectTest.
  */
class ArrowEffectThreadingTest extends kyo.Test:

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
                [C] => (n, _) => Loop.continue(n + 1, n),
                (n, a) => n * 1000 + a
            )
        val r0 = Eval(ArrowEffect.handleCont(Tag[Say], region)(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                    cont(())
            ,
            a => a
        ))
        assert(r0 == 3003)
        // the capture is a complete value, not a view of the eval it escaped from
        @volatile var tr = 0
        val t            = new Thread(() => tr = Eval(kref(())))
        t.start()
        t.join()
        assert(tr == 3003)
    }

    "a hoarded fast-path continuation replays on another thread after the region finished" in {
        var kref: Arrow[Int, Int, Any] = null
        def loop(i: Int): Int < Ask =
            if i > 3 then i else ask.map(a => loop(i + a))
        val r0 = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
            [C] =>
                (_, cont) =>
                    kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                    cont(1)
            ,
            a => a
        ))
        assert(r0 == 4)
        @volatile var tr = 0
        val t            = new Thread(() => tr = Eval(kref(1)))
        t.start()
        t.join()
        assert(tr == 4)
    }
end ArrowEffectThreadingTest
