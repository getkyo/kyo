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
  * finalizer machinery this kernel does not carry (backlog R3, `Sync` becomes the bracketing layer, handled last by construction, so
  * releases never ride in continuations and abandonment orphans nothing). Both cases return at the Sync layer when it lands, pinning that
  * its state releases resources even when an inner handler drops a continuation. The catching case is transcribed onto `Handler.recover`,
  * which is this kernel's spelling of the same behavior.
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

    sealed trait AskSub extends Ask
    // the operation is tagged at the subtype and the row names the supertype, the pairing the
    // sub-tag masking pins exercise
    def askSub: Int < Ask = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())
    def runAskSub[A, S](v: A < (AskSub & S))(answer: Int): A < S =
        ArrowEffect.handleCont(Tag[AskSub], v)([C] => (_, cont) => cont(answer))

    sealed trait Log extends ArrowEffect[Const[String], Const[Unit]]
    def logLine(s: String): Unit < Log = ArrowEffect.suspend[Any](Tag[Log], s)
    def runLog[A, S](v: A < (Log & S))(buf: scala.collection.mutable.ListBuffer[String]): A < S =
        ArrowEffect.handleCont(Tag[Log], v)([C] =>
            (input, cont) =>
                buf += input
                cont(()))

    // makes a preemption stop pending from inside a running slice: the jvm and native deliver
    // through the slot's stop sentinel, js and wasm through the slice deadline, and each
    // platform's other call is inert there
    private def requestStop(): Unit =
        kyo.discard(kyo.proto.kernel.internal.Safepoint.stop(Thread.currentThread()))
        kyo.proto.kernel.internal.Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)

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
                    requestStop()
                    kyo.proto.kernel.Effect.defer(ask.map(b => a * 100 + b))
                }
            val out: Int < Any = runAsk(Mask.run[Ask](runAsk(Mask[Ask](body))(1)))(42)
            val parked         = Eval.partial(out)
            assert(eval(parked) == 4242)
        }

        "a slice parked mid-tunnel resumes and both masked effects still route" in {
            val innerBuf = scala.collection.mutable.ListBuffer[String]()
            val outerBuf = scala.collection.mutable.ListBuffer[String]()
            val v: Int < (Ask & Say) =
                ask.map { a =>
                    requestStop()
                    kyo.proto.kernel.Effect.defer(say(s"after $a").map(_ => ask.map(b => a + b)))
                }
            val out: Int < Any =
                runSay(runAsk(Mask.run[Ask & Say](runSay(runAsk(Mask[Ask & Say](v))(1))(innerBuf)))(42))(outerBuf)
            val parked = Eval.partial(out)
            assert(eval(parked) == 84)
            assert(innerBuf.isEmpty)
            assert(outerBuf.toList == List("after 42"))
        }

        "a context binding between the mask and run crosses the tunnel intact" in {
            sealed trait Cfg extends kyo.proto.kernel.ContextEffect[Int]
            def read: Int < Cfg      = kyo.proto.kernel.ContextEffect.suspend(Tag[Cfg])
            val v: Int < (Ask & Cfg) = ask.map(a => read.map(c => a + c))
            // the region binding 10 stands between the mask and run, so the tunneling operation
            // crosses it out and the answer re-enters through it: the read after the tunnel must
            // still see the binding
            val bound = kyo.proto.kernel.ContextEffect.handle(Tag[Cfg], 10)(Mask[Ask](v))
            val out   = Mask.run[Ask](bound)
            assert(eval(runAsk(out)(32)) == 42)
        }

        "an outer handler that ends without resuming stops the masked remainder" in {
            var later = false
            val v: Int < Ask = ask.map { a =>
                later = true
                a + 1
            }
            val out          = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], out)([C] => (_, _) => -1, a => a)
            assert(eval(r) == -1)
            assert(!later)
        }
    }

    "a mask over part of a wider row leaves the other effects live" in {
        val logs     = scala.collection.mutable.ListBuffer[String]()
        val innerBuf = scala.collection.mutable.ListBuffer[String]()
        val outerBuf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say & Log) =
            ask.map(a => logLine("mid").map(_ => say("s").map(_ => ask.map(b => a + b))))
        val masked = Mask[Ask & Say](v)
        // Log is not masked: its operation crosses the mask and is answered here, inside the pipeline
        val logHandled   = runLog(masked)(logs)
        val innerHandled = runSay(runAsk(logHandled)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)
        assert(eval(runSay(runAsk(out)(42))(outerBuf)) == 84)
        assert(logs.toList == List("mid"))
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("s"))
    }

    "a mask over an intersection with only one member occurring" in {
        val innerBuf     = scala.collection.mutable.ListBuffer[String]()
        val outerBuf     = scala.collection.mutable.ListBuffer[String]()
        val v: Int < Ask = ask.map(_ + 1)
        val masked       = Mask[Ask & Say](v)
        val innerHandled = runSay(runAsk(masked)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)
        assert(eval(runSay(runAsk(out)(41))(outerBuf)) == 42)
        assert(innerBuf.isEmpty)
        assert(outerBuf.isEmpty)
    }

    "a mask at the supertype does not capture sub-tagged operations" in {
        // the sub-tagged operation is foreign to the mask, whose tag is not a subtype of the
        // operation's, so it is answered by its own handler standing between the mask and run;
        // had it tunneled, the payload would reach run with no AskSub handler outside and the
        // eval would reject it as unhandled
        val v: Int < Ask = askSub.map(_ + 1)
        val local        = runAskSub(Mask[Ask](v))(41)
        val out          = Mask.run[Ask](local)
        assert(eval(out) == 42)
    }

    "a mask at a subtype effect captures supertype-tagged operations" in {
        // the mask's subtype tag answers the supertype-tagged operation, and the payload carries
        // the operation's own Ask tag, so it lands at the Ask handler outside run. The inner Ask
        // handler answering instead would produce 2
        val v: Int < Ask = ask.map(_ + 1)
        val out          = Mask.run[AskSub](runAsk(Mask[AskSub](v))(1))
        assert(eval(runAsk(out)(42)) == 43)
    }

    "a settled computation passes through mask and run untouched" in {
        val masked = Mask[Ask](42: Int < Ask)
        assert(!masked.isInstanceOf[kyo.proto.kernel.internal.Pending[?, ?]])
        assert(eval(Mask.run[Ask](masked)) == 42)
    }

    "interleaved operations of both masked effects keep program order" in {
        val order = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say("first").map(_ => ask.map(b => say("second").map(_ => a * 10 + b))))
        val out = Mask.run[Ask & Say](Mask[Ask & Say](v))
        val askAnswered: Int < Say = ArrowEffect.handleCont(Tag[Ask], out)(
            [C] =>
                (_, cont) =>
                    order += "ask"
                    cont(7)
            ,
            a => a
        )
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], askAnswered)(
            [C] =>
                (input, cont) =>
                    order += s"say:$input"
                    cont(())
            ,
            a => a
        )
        assert(eval(r) == 77)
        assert(order.toList == List("ask", "say:first", "ask", "say:second"))
    }

    "one mask over an intersection masks both effects and each re-emerges at its own handler" in {
        val innerBuf = scala.collection.mutable.ListBuffer[String]()
        val outerBuf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say(s"got $a").map(_ => ask.map(b => a + b)))
        val masked       = Mask[Ask & Say](v)
        val innerHandled = runSay(runAsk(masked)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)
        // the payloads carry each operation's own tag, so the Ask operations land at the Ask
        // handler and the Say operation at the Say handler, with nothing needing a joint handler
        val r = runSay(runAsk(out)(42))(outerBuf)
        assert(eval(r) == 84)
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("got 42"))
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
