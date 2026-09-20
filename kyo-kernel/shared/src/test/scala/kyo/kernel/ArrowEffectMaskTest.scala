package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.ArrowEffect.Mask
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ListBuffer

class ArrowEffectMaskTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask                                     = ArrowEffect.suspend[Any](Tag[Ask], ())
    def runAsk[A, S](v: A < (Ask & S))(answer: Int): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(answer))

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say                                     = ArrowEffect.suspend[Any](Tag[Say], s)
    def runSay[A, S](v: A < (Say & S))(buf: ListBuffer[String]): A < S =
        ArrowEffect.handleCont(Tag[Say], v)([C] =>
            (input, cont) =>
                buf += input
                cont(())
        )

    sealed trait AskSub extends Ask

    def askSub: Int < Ask                                        = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())
    def runAskSub[A, S](v: A < (AskSub & S))(answer: Int): A < S =
        ArrowEffect.handleCont(Tag[AskSub], v)([C] => (_, cont) => cont(answer))

    sealed trait Log extends ArrowEffect[Const[String], Const[Unit]]
    def logLine(s: String): Unit < Log                                 = ArrowEffect.suspend[Any](Tag[Log], s)
    def runLog[A, S](v: A < (Log & S))(buf: ListBuffer[String]): A < S =
        ArrowEffect.handleCont(Tag[Log], v)([C] =>
            (input, cont) =>
                buf += input
                cont(())
        )

    sealed trait Cfg extends ContextEffect[Int]
    def readCfg: Int < Cfg                                = ContextEffect.suspend(Tag[Cfg])
    def runCfg[A, S](v: A < (Cfg & S))(value: Int): A < S =
        ContextEffect.handleInheritable(Tag[Cfg], value)(v)

    private def requestStop(): Unit =
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)

    private object Boom extends RuntimeException("boom", null, false, false)

    "a masked operation tunnels past an inner handler and is answered outside run" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](ask)
        val innerHandled: Int < Mask[Ask] = runAsk(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(runAsk(unmasked)(42).eval == 42)
    }

    "every operation of the masked effect tunnels" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](ask.map(a => ask.map(b => a * 100 + b)))
        val innerHandled: Int < Mask[Ask] = runAsk(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(runAsk(unmasked)(42).eval == 4242)
    }

    "an operation outside the mask is answered by the inner handler" in {
        val mixed: Int < (Ask & Mask[Ask]) =
            ask.map(a => Mask[Ask](ask).map(b => a * 100 + b))
        val innerHandled: Int < Mask[Ask] = runAsk(mixed)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(runAsk(unmasked)(42).eval == 142)
    }

    "masking is selective: other effects stay live for local handlers and outer answers flow back in" in {
        val buf                  = ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say(s"got $a").map(_ => ask.map(b => a + b)))
        val masked: Int < (Mask[Ask] & Say) = Mask[Ask](v)
        val sayHandled: Int < Mask[Ask]     = runSay(masked)(buf)
        val askLocal: Int < Mask[Ask]       = runAsk(sayHandled)(1)
        val out: Int < Ask                  = Mask.run[Ask](askLocal)
        assert(runAsk(out)(42).eval == 84)
        assert(buf.toList == List("got 42"))
    }

    "interactions" - {

        "a bracket inside the mask releases after the tunneled answer flows back" in {
            var order        = List.empty[String]
            val v: Int < Ask =
                Bracket(Effect.defer {
                    order ::= "acquire"
                    0
                }) { _ =>
                    ask.map { a =>
                        order ::= s"use $a"
                        a
                    }
                }((_, _) => order ::= "release")
            val out = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            assert(runAsk(out)(42).eval == 42)
            assert(order.reverse == List("acquire", "use 42", "release"))
        }

        "a bracket inside the mask releases when the outer handler discards the continuation" in {
            var order        = List.empty[String]
            val v: Int < Ask =
                Bracket(Effect.defer {
                    order ::= "acquire"
                    0
                }) { _ =>
                    ask
                }((_, _) => order ::= "release")
            val unmasked: Int < Ask = Mask.run[Ask](Mask[Ask](v))
            val out: Int < Any      = ArrowEffect.handleCont(Tag[Ask], unmasked)(
                [C] => (_, _) => -1,
                a => a
            )
            assert(out.eval == -1)
            assert(order.reverse == List("acquire", "release"))
        }

        "a recovering region inside the mask catches a failure raised after the tunneled answer returns" in {
            val body: Int < (Say & Ask) = ask.map(a => (throw Boom): Int)
            val v: Int < Ask            = ArrowEffect.handleCont[Const[String], Const[Unit], Say, Int, Int, Ask, Any](Tag[Say], body)(
                [C] => (_, cont) => cont(()),
                a => a,
                _ => Maybe(-1)
            )
            val out = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            assert(runAsk(out)(42).eval == -1)
        }

        "a stateful local handler threads its state across tunneled operations" in {
            val v: Int < (Ask & Say) =
                say("a").map(_ => ask.map(a => say("b").map(_ => a)))
            val counted: (Int, Int) < (Mask[Ask] & Any) = ArrowEffect.handleLoopState(Tag[Say], 0, Mask[Ask](v))(
                [C] => (s, _) => Loop.continue(s + 1, ()),
                (s, a) => (s, a)
            )
            val out = Mask.run[Ask](counted)
            assert(runAsk(out)(42).eval == (2, 42))
        }

        "a multi-shot outer handler replays the masked region and its local effects" in {
            val buf                  = ListBuffer[String]()
            val v: Int < (Ask & Say) =
                ask.map(a => say(a.toString).map(_ => a))
            val out: Int < Ask = Mask.run[Ask](runSay(Mask[Ask](v))(buf))
            val r: Int < Any   = ArrowEffect.handleCont(Tag[Ask], out)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                a => a
            )
            assert(r.eval == 102)
            assert(buf.toList == List("1", "2"))
        }

        "deep masked operations are stack safe" in {
            def loop(i: Int, acc: Int): Int < Ask =
                if i == 0 then acc
                else ask.map(a => loop(i - 1, acc + a))
            val n   = 10000
            val out = Mask.run[Ask](runAsk(Mask[Ask](loop(n, 0)))(-1))
            assert(runAsk(out)(1).eval == n)
        }

        "masking the same effect twice behaves as one mask" in {
            val masked: Int < Mask[Ask] = Mask[Ask](Mask[Ask](ask))
            val out                     = Mask.run[Ask](runAsk(masked)(1))
            assert(runAsk(out)(42).eval == 42)
        }

        "a slice parked mid-tunnel resumes and the mask still routes" in {
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer(ask.map(b => a * 100 + b))
                }
            val out: Int < Any = runAsk(Mask.run[Ask](runAsk(Mask[Ask](body))(1)))(42)
            val parked         = Eval.partial(out)
            assert(parked.eval == 4242)
        }

        "a slice parked mid-tunnel resumes and both masked effects still route" in {
            val innerBuf             = ListBuffer[String]()
            val outerBuf             = ListBuffer[String]()
            val v: Int < (Ask & Say) =
                ask.map { a =>
                    requestStop()
                    Effect.defer(say(s"after $a").map(_ => ask.map(b => a + b)))
                }
            val out: Int < Any =
                runSay(runAsk(Mask.run[Ask & Say](runSay(runAsk(Mask[Ask & Say](v))(1))(innerBuf)))(42))(outerBuf)
            val parked = Eval.partial(out)
            assert(parked.eval == 84)
            assert(innerBuf.isEmpty)
            assert(outerBuf.toList == List("after 42"))
        }

        "a context binding between the mask and run crosses the tunnel intact" in {
            sealed trait Cfg extends ContextEffect[Int]
            def read: Int < Cfg      = ContextEffect.suspend(Tag[Cfg])
            val v: Int < (Ask & Cfg) = ask.map(a => read.map(c => a + c))

            val bound = ContextEffect.handleInheritable(Tag[Cfg], 10)(Mask[Ask](v))
            val out   = Mask.run[Ask](bound)
            assert(runAsk(out)(32).eval == 42)
        }

        "an outer handler that ends without resuming stops the masked remainder" in {
            var later        = false
            val v: Int < Ask = ask.map { a =>
                later = true
                a + 1
            }
            val out          = Mask.run[Ask](runAsk(Mask[Ask](v))(1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], out)([C] => (_, _) => -1, a => a)
            assert(r.eval == -1)
            assert(!later)
        }
    }

    "context effects" - {

        "a masked context read tunnels past an inner binding" in {
            val masked: Int < Mask[Cfg] = Mask[Cfg](readCfg)
            val inner: Int < Mask[Cfg]  = runCfg(masked)(10)
            val out: Int < Cfg          = Mask.run[Cfg](inner)
            assert(runCfg(out)(42).eval == 42)
        }

        "every masked context read tunnels" in {
            val v: Int < Cfg            = readCfg.map(a => readCfg.map(b => a * 100 + b))
            val masked: Int < Mask[Cfg] = Mask[Cfg](v)
            val inner: Int < Mask[Cfg]  = runCfg(masked)(1)
            val out: Int < Cfg          = Mask.run[Cfg](inner)
            assert(runCfg(out)(42).eval == 4242)
        }

        "a read outside the mask is answered by the inner binding" in {
            val v: Int < (Cfg & Mask[Cfg]) = readCfg.map(a => Mask[Cfg](readCfg).map(b => a * 100 + b))
            val inner: Int < Mask[Cfg]     = runCfg(v)(1)
            val out: Int < Cfg             = Mask.run[Cfg](inner)
            assert(runCfg(out)(42).eval == 142)
        }

        "masking a context effect leaves an arrow effect live for its local handler" in {
            val buf                             = ListBuffer[String]()
            val v: Int < (Cfg & Say)            = readCfg.map(a => say(s"got $a").map(_ => a))
            val masked: Int < (Mask[Cfg] & Say) = Mask[Cfg](v)
            val sayHandled: Int < Mask[Cfg]     = runSay(masked)(buf)
            val cfgLocal: Int < Mask[Cfg]       = runCfg(sayHandled)(1)
            val out: Int < Cfg                  = Mask.run[Cfg](cfgLocal)
            assert(runCfg(out)(42).eval == 42)
            assert(buf.toList == List("got 42"))
        }

        "one mask over an arrow and a context effect covers both" in {
            val buf                                 = ListBuffer[String]()
            val v: Int < (Ask & Cfg)                = ask.map(a => readCfg.map(c => a + c))
            val masked: Int < Mask[Ask & Cfg]       = Mask[Ask & Cfg](v)
            val innerHandled: Int < Mask[Ask & Cfg] = runCfg(runAsk(masked)(1))(2)
            val out: Int < (Ask & Cfg)              = Mask.run[Ask & Cfg](innerHandled)
            assert(runCfg(runAsk(out)(40))(2).eval == 42)
            assert(buf.isEmpty)
        }
    }

    "a mask over part of a wider row leaves the other effects live" in {
        val logs                       = ListBuffer[String]()
        val innerBuf                   = ListBuffer[String]()
        val outerBuf                   = ListBuffer[String]()
        val v: Int < (Ask & Say & Log) =
            ask.map(a => logLine("mid").map(_ => say("s").map(_ => ask.map(b => a + b))))
        val masked = Mask[Ask & Say](v)

        val logHandled   = runLog(masked)(logs)
        val innerHandled = runSay(runAsk(logHandled)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)
        assert(runSay(runAsk(out)(42))(outerBuf).eval == 84)
        assert(logs.toList == List("mid"))
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("s"))
    }

    "a mask over an intersection with only one member occurring" in {
        val innerBuf     = ListBuffer[String]()
        val outerBuf     = ListBuffer[String]()
        val v: Int < Ask = ask.map(_ + 1)
        val masked       = Mask[Ask & Say](v)
        val innerHandled = runSay(runAsk(masked)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)
        assert(runSay(runAsk(out)(41))(outerBuf).eval == 42)
        assert(innerBuf.isEmpty)
        assert(outerBuf.isEmpty)
    }

    "a mask at the supertype does not capture sub-tagged operations" in {

        val v: Int < Ask = askSub.map(_ + 1)
        val local        = runAskSub(Mask[Ask](v))(41)
        val out          = Mask.run[Ask](local)

        assert(runAsk(out)(999).eval == 42)
    }

    "a mask at a subtype effect captures supertype-tagged operations" in {

        val v: Int < Ask = ask.map(_ + 1)
        val out          = Mask.run[AskSub](runAsk(Mask[AskSub](v))(1))

        assert(runAskSub(runAsk(out)(42))(998).eval == 43)
    }

    "a settled computation passes through mask and run untouched" in {
        val masked = Mask[Ask](42: Int < Ask)
        assert(!masked.isInstanceOf[Pending[?, ?]])

        assert(runAsk(Mask.run[Ask](masked))(997).eval == 42)
    }

    "interleaved operations of both masked effects keep program order" in {
        val order                = ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say("first").map(_ => ask.map(b => say("second").map(_ => a * 10 + b))))
        val out                    = Mask.run[Ask & Say](Mask[Ask & Say](v))
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
        assert(r.eval == 77)
        assert(order.toList == List("ask", "say:first", "ask", "say:second"))
    }

    "one mask over an intersection masks both effects and each re-emerges at its own handler" in {
        val innerBuf             = ListBuffer[String]()
        val outerBuf             = ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say(s"got $a").map(_ => ask.map(b => a + b)))
        val masked       = Mask[Ask & Say](v)
        val innerHandled = runSay(runAsk(masked)(1))(innerBuf)
        val out          = Mask.run[Ask & Say](innerHandled)

        val r = runSay(runAsk(out)(42))(outerBuf)
        assert(r.eval == 84)
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("got 42"))
    }

    "masks of different effects stack independently" in {
        val innerBuf             = ListBuffer[String]()
        val outerBuf             = ListBuffer[String]()
        val v: Int < (Ask & Say) =
            ask.map(a => say("crossed").map(_ => a))
        val bothMasked: Int < (Mask[Ask] & Mask[Say])   = Mask[Say](Mask[Ask](v))
        val innerHandled: Int < (Mask[Ask] & Mask[Say]) =
            runSay(runAsk(bothMasked)(1))(innerBuf)
        val out = runSay(runAsk(Mask.run[Say](Mask.run[Ask](innerHandled)))(42))(outerBuf)
        assert(out.eval == 42)
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("crossed"))
    }
end ArrowEffectMaskTest
