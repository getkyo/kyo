package kyo

import kyo.kernel.ArrowEffect
import kyo.kernel.Effect

class MaskTest extends Test:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    object Ask:
        inline def ask(using Frame): Int < Ask = ArrowEffect.suspend[Int](Tag[Ask], ())
        def run[A, S](v: A < (Ask & S))(answer: Int)(using Frame): A < S =
            ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(answer))
    end Ask

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    object Say:
        inline def say(s: String)(using Frame): Unit < Say = ArrowEffect.suspend[Unit](Tag[Say], s)
        def run[A, S](v: A < (Say & S))(buf: scala.collection.mutable.ListBuffer[String])(using Frame): A < S =
            ArrowEffect.handleCont(Tag[Say], v)([C] =>
                (input, cont) =>
                    buf += input
                    cont(()))
    end Say

    "a masked operation tunnels past an inner handler and is answered outside run" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](Ask.ask)
        val innerHandled: Int < Mask[Ask] = Ask.run(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(Ask.run(unmasked)(42).eval == 42)
    }

    "every operation of the masked effect tunnels" in {
        val masked: Int < Mask[Ask]       = Mask[Ask](Ask.ask.map(a => Ask.ask.map(b => a * 100 + b)))
        val innerHandled: Int < Mask[Ask] = Ask.run(masked)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(Ask.run(unmasked)(42).eval == 4242)
    }

    "an operation outside the mask is answered by the inner handler" in {
        val mixed: Int < (Ask & Mask[Ask]) =
            Ask.ask.map(a => Mask[Ask](Ask.ask).map(b => a * 100 + b))
        val innerHandled: Int < Mask[Ask] = Ask.run(mixed)(1)
        val unmasked: Int < Ask           = Mask.run[Ask](innerHandled)
        assert(Ask.run(unmasked)(42).eval == 142)
    }

    "masking is selective: other effects stay live for local handlers and outer answers flow back in" in {
        val buf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            Ask.ask.map(a => Say.say(s"got $a").map(_ => Ask.ask.map(b => a + b)))
        val masked: Int < (Mask[Ask] & Say) = Mask[Ask](v)
        val sayHandled: Int < Mask[Ask]     = Say.run(masked)(buf)
        val askLocal: Int < Mask[Ask]       = Ask.run(sayHandled)(1)
        val out: Int < Ask                  = Mask.run[Ask](askLocal)
        assert(Ask.run(out)(42).eval == 84)
        assert(buf.toList == List("got 42"))
    }

    "interactions" - {

        "a bracket inside the mask releases after the tunneled answer flows back" in {
            var order = List.empty[String]
            val v: Int < Ask =
                Effect.bracket(Effect.defer {
                    order ::= "acquire"; 0
                })(_ => Effect.defer { order ::= "release" }) { _ =>
                    Ask.ask.map { a =>
                        order ::= s"use $a"; a
                    }
                }
            val out = Mask.run[Ask](Ask.run(Mask[Ask](v))(1))
            assert(Ask.run(out)(42).eval == 42)
            assert(order.reverse == List("acquire", "use 42", "release"))
        }

        "a bracket inside the mask releases when the outer handler discards the continuation" in {
            var order = List.empty[String]
            val v: Int < Ask =
                Effect.bracket(Effect.defer {
                    order ::= "acquire"; 0
                })(_ => Effect.defer { order ::= "release" }) { _ =>
                    Ask.ask
                }
            val unmasked: Int < Ask = Mask.run[Ask](Mask[Ask](v))
            val out: Int < Any = ArrowEffect.handleCont(Tag[Ask], unmasked)(
                [C] => (_, _) => -1,
                a => a
            )
            assert(out.eval == -1)
            assert(order.reverse == List("acquire", "release"))
        }

        "catching inside the mask catches a failure raised after the tunneled answer returns" in {
            val v: Int < Ask =
                Effect.catching(Ask.ask.map(a => (throw new Exception("boom")): Int))(_ => -1)
            val out = Mask.run[Ask](Ask.run(Mask[Ask](v))(1))
            assert(Ask.run(out)(42).eval == -1)
        }

        "a stateful local handler threads its state across tunneled operations" in {
            val v: Int < (Ask & Say) =
                Say.say("a").map(_ => Ask.ask.map(a => Say.say("b").map(_ => a)))
            val counted: (Int, Int) < (Mask[Ask] & Any) = ArrowEffect.handleLoopState(Tag[Say], 0, Mask[Ask](v))(
                [C] => (s, _) => Loop.continue(s + 1, ()),
                (s, a) => (s, a)
            )
            val out = Mask.run[Ask](counted)
            assert(Ask.run(out)(42).eval == (2, 42))
        }

        "a multi-shot outer handler replays the masked region and its local effects" in {
            val buf = scala.collection.mutable.ListBuffer[String]()
            val v: Int < (Ask & Say) =
                Ask.ask.map(a => Say.say(a.toString).map(_ => a))
            val out: Int < Ask = Mask.run[Ask](Say.run(Mask[Ask](v))(buf))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], out)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
                a => a
            )
            assert(r.eval == 102)
            assert(buf.toList == List("1", "2"))
        }

        "deep masked operations are stack safe" in {
            def loop(i: Int, acc: Int): Int < Ask =
                if i == 0 then acc
                else Ask.ask.map(a => loop(i - 1, acc + a))
            val n   = 10000
            val out = Mask.run[Ask](Ask.run(Mask[Ask](loop(n, 0)))(-1))
            assert(Ask.run(out)(1).eval == n)
        }

        "masking the same effect twice behaves as one mask" in {
            val masked: Int < Mask[Ask] = Mask[Ask](Mask[Ask](Ask.ask))
            val out                     = Mask.run[Ask](Ask.run(masked)(1))
            assert(Ask.run(out)(42).eval == 42)
        }
    }

    "masks of different effects stack independently" in {
        val innerBuf = scala.collection.mutable.ListBuffer[String]()
        val outerBuf = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) =
            Ask.ask.map(a => Say.say("crossed").map(_ => a))
        val bothMasked: Int < (Mask[Ask] & Mask[Say]) = Mask[Say](Mask[Ask](v))
        val innerHandled: Int < (Mask[Ask] & Mask[Say]) =
            Say.run(Ask.run(bothMasked)(1))(innerBuf)
        val out = Say.run(Ask.run(Mask.run[Say](Mask.run[Ask](innerHandled)))(42))(outerBuf)
        assert(out.eval == 42)
        assert(innerBuf.isEmpty)
        assert(outerBuf.toList == List("crossed"))
    }
end MaskTest
