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

    "deep state transitions under a suspending clause fit a small stack" in {
        // every suspending clause re-enters its region with the state rewrapped onto the handler,
        // and the rewrap must stay one delegation layer over the per-site clause: a layer per
        // transition walks the whole chain on every answer, which is linear stack per operation.
        // The small explicit stack is what makes a chain observable as an overflow at this depth
        @volatile var result = -1
        def loop(i: Int): Int < Ask =
            if i == 0 then 0 else ask.map(a => loop(i - a))
        val t = new Thread(
            null,
            () =>
                val handled = ArrowEffect.handleLoopState(Tag[Ask], 0, loop(20000))(
                    [C] => (s, _) => Effect.defer(Loop.continue(s + 1, 1)),
                    (s, a) => s + a
                )
                result = Eval(handled)
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
