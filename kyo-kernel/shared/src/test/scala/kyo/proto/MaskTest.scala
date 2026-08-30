package kyo.proto

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Handler
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import org.scalatest.freespec.AnyFreeSpec

/** The kernel's `MaskTest` corpus pointed at this package. The two bracket interaction cases are not transcribed: `Effect.bracket` is
  * finalizer machinery this kernel does not carry (backlog R3, `Sync` becomes the bracketing layer), and the discarded-continuation case is
  * exactly R3's open abandonment lane; both return in this kernel's spelling when that ruling lands. The catching case is transcribed onto
  * `Handler.recover`, which is this kernel's spelling of the same behavior.
  */
class MaskTest extends AnyFreeSpec:
    // the eval's result as a raw value: unnesting delivers a payload as the computation it holds,
    // and an unanswered suspension surfaces through the failing assertion that compares it
    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
    def runAsk[A, S](v: A < (Ask & S))(answer: Int): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(answer))

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)
    def runSay[A, S](v: A < (Say & S))(buf: scala.collection.mutable.ListBuffer[String]): A < S =
        ArrowEffect.handleCont(Tag[Say], v)([C] =>
            (input, cont) =>
                buf += input
                cont(()))

    // stackless and shared: the pins are about control flow, not exception construction
    private object Boom extends RuntimeException("boom", null, false, false)

    "a masked operation tunnels past an inner handler and is answered outside run" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](ask)
        val innerHandled: Int < Mask[Ask] = runAsk(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(eval(runAsk(unmasked)(42)) == 42)
    }

    "every operation of the masked effect tunnels" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](ask.map(a => ask.map(b => a * 100 + b)))
        val innerHandled: Int < Mask[Ask] = runAsk(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(eval(runAsk(unmasked)(42)) == 4242)
    }

    "an operation outside the mask is answered by the inner handler" in {
        val mixed: Int < (Ask & Mask[Ask]) =
            ask.map(a => Mask[Ask](ask).map(b => a * 100 + b))
        val innerHandled: Int < Mask[Ask] = runAsk(mixed)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(eval(runAsk(unmasked)(42)) == 142)
    }

    "masking is selective: other effects stay live for local handlers and outer answers flow back in" in {
        val buf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say(s"got $a").map(_ => ask.map(b => a + b)))
        val masked: Int < (Mask[Ask] & Say) = Mask[Ask](v)
        val sayHandled: Int < Mask[Ask]     = runSay(masked)(buf)
        val askLocal: Int < Mask[Ask]       = runAsk(sayHandled)(1)
        val out: Int < Ask                  = Mask.run[Ask](askLocal)
        assert(eval(runAsk(out)(42)) == 84)
        assert(buf.toList == List("got 42"))
    }

    "interactions" - {

        "a recovering region inside the mask catches a failure raised after the tunneled answer returns" in {
            val recovering = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Ask]:
                def tag                                          = Tag[Say]
                override def recover(state: Unit, ex: Throwable) = Maybe(-1)
                def done(state: Unit, v: Int)                    = v
                def answer[X](input: String, next: Arrow[Unit, Int, Say & Ask]): Int < (Say & Ask) =
                    next((), Arrow.id)
            val body: Int < (Say & Ask) = ask.map(a => (throw Boom): Int)
            val v: Int < Ask            = Kyo.handle[Say, Int, Int, Ask, Unit](body, recovering, ())
            val out                     = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            assert(eval(runAsk(out)(42)) == -1)
        }

        "a stateful local handler threads its state across tunneled operations" in {
            val v: Int < (Ask & Say) =
                say("a").map(_ => ask.map(a => say("b").map(_ => a)))
            val counted: (Int, Int) < (Mask[Ask] & Any) = ArrowEffect.handleLoopState(Tag[Say], 0, Mask[Ask](v))(
                [C] => (s, _) => Loop.continue(s + 1, (): Unit < Any),
                (s, a) => (s, a)
            )
            val out = Mask.run[Ask](counted)
            assert(eval(runAsk(out)(42)) == (2, 42))
        }

        "a multi-shot outer handler replays the masked region and its local effects" in {
            val buf = scala.collection.mutable.ListBuffer[String]()
            val v: Int < (Ask & Say) =
                ask.map(a => say(a.toString).map(_ => a))
            val out: Int < Ask = Mask.run[Ask](runSay(Mask[Ask](v))(buf))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], out)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                a => a
            )
            assert(eval(r) == 102)
            assert(buf.toList == List("1", "2"))
        }

        "deep masked operations are stack safe" in {
            def loop(i: Int, acc: Int): Int < Ask =
                if i == 0 then acc
                else ask.map(a => loop(i - 1, acc + a))
            val n   = 10000
            val out = Mask.run[Ask](runAsk(Mask[Ask](loop(n, 0)))(-1))
            assert(eval(runAsk(out)(1)) == n)
        }

        "masking the same effect twice behaves as one mask" in {
            val masked: Int < Mask[Ask] = Mask[Ask](Mask[Ask](ask))
            val out                     = Mask.run[Ask](runAsk(masked)(1))
            assert(eval(runAsk(out)(42)) == 42)
        }

        "a slice parked mid-tunnel resumes and the mask still routes" in {
            val body: Int < Ask =
                ask.map { a =>
                    kyo.discard(kyo.proto.kernel.internal.Safepoint.stop(Thread.currentThread()))
                    kyo.proto.kernel.internal.Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
                    kyo.proto.kernel.Effect.defer(ask.map(b => a * 100 + b))
                }
            val out: Int < Any = runAsk(Mask.run[Ask](runAsk(Mask[Ask](body))(1)))(42)
            val parked         = Eval.partial(out)
            assert(eval(parked) == 4242)
        }
    }

    "masks of different effects stack independently" in {
        val innerBuf = scala.collection.mutable.ListBuffer[String]()
        val outerBuf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say("crossed").map(_ => a))
        val bothMasked: Int < (Mask[Ask] & Mask[Say]) = Mask[Say](Mask[Ask](v))
        val innerHandled: Int < (Mask[Ask] & Mask[Say]) =
            runSay(runAsk(bothMasked)(1))(innerBuf)
        val out = runSay(runAsk(Mask.run[Say](Mask.run[Ask](innerHandled)))(42))(outerBuf)
        assert(eval(out) == 42)
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("crossed"))
    }
end MaskTest
