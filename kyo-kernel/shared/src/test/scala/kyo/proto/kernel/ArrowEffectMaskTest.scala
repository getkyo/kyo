package kyo.proto.kernel

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.ArrowEffect.Mask
import kyo.proto.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec

class ArrowEffectMaskTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

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

    private def requestStop(): Unit =
        kyo.discard(kyo.proto.kernel.internal.Safepoint.stop(Thread.currentThread()))
        kyo.proto.kernel.internal.Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)

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

        "a bracket inside the mask releases after the tunneled answer flows back" in {
            var order = List.empty[String]
            val v: Int < Ask =
                Effect.bracket(Effect.defer {
                    order ::= "acquire"
                    0
                })((_, _) => order ::= "release") { _ =>
                    ask.map { a =>
                        order ::= s"use $a"
                        a
                    }
                }
            val out = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            assert(eval(runAsk(out)(42)) == 42)
            assert(order.reverse == List("acquire", "use 42", "release"))
        }

        "a bracket inside the mask releases when the outer handler discards the continuation" in {
            var order = List.empty[String]
            val v: Int < Ask =
                Effect.bracket(Effect.defer {
                    order ::= "acquire"
                    0
                })((_, _) => order ::= "release") { _ =>
                    ask
                }
            val unmasked: Int < Ask = Mask.run[Ask](Mask[Ask](v))
            val out: Int < Any = ArrowEffect.handleCont(Tag[Ask], unmasked)(
                [C] => (_, _) => -1,
                a => a
            )
            assert(eval(out) == -1)
            assert(order.reverse == List("acquire", "release"))
        }

        "a recovering region inside the mask catches a failure raised after the tunneled answer returns" in {
            val body: Int < (Say & Ask) = ask.map(a => (throw Boom): Int)
            val v: Int < Ask = ArrowEffect.handleCont[Const[String], Const[Unit], Say, Int, Int, Ask, Any](Tag[Say], body)(
                [C] => (_, cont) => cont(()),
                a => a,
                _ => Maybe(-1)
            )
            val out = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
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

            val bound = kyo.proto.kernel.ContextEffect.handleInheritable(Tag[Cfg], 10)(Mask[Ask](v))
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

        val v: Int < Ask = askSub.map(_ + 1)
        val local        = runAskSub(Mask[Ask](v))(41)
        val out          = Mask.run[Ask](local)

        assert(eval(runAsk(out)(999)) == 42)
    }

    "a mask at a subtype effect captures supertype-tagged operations" in {

        val v: Int < Ask = ask.map(_ + 1)
        val out          = Mask.run[AskSub](runAsk(Mask[AskSub](v))(1))

        assert(eval(runAskSub(runAsk(out)(42))(998)) == 43)
    }

    "a settled computation passes through mask and run untouched" in {
        val masked = Mask[Ask](42: Int < Ask)
        assert(!masked.isInstanceOf[kyo.proto.kernel.internal.Pending[?, ?]])

        assert(eval(runAsk(Mask.run[Ask](masked))(997)) == 42)
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
end ArrowEffectMaskTest
