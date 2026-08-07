package proto4test

import kyo.Const
import kyo.Tag
import kyo.proto4.*
import kyo.test.Test
import language.implicitConversions

sealed trait Echo extends ArrowEffect[Const[Int], Const[Int]]
sealed trait Get  extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait Fail extends ArrowEffect[Const[String], Const[Nothing]]

class ArrowEffectTest extends Test[Any]:

    def echo(v: Int): Int < Echo =
        ArrowEffect.suspend[Any](Tag[Echo], v)

    def get: Int < Get =
        ArrowEffect.suspend[Any](Tag[Get], ())

    def fail(msg: String): Nothing < Fail =
        ArrowEffect.suspend[Any](using summon[kyo.Frame])[Const[String], Const[Nothing], Fail](Tag[Fail], msg)

    "handleResume answers each operation in place" in {
        val program = echo(1).map(a => echo(a + 10).map(b => a * 100 + b))
        val handled = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in + 1
        )
        assert(handled.eval == 213)
    }

    "handleResume clause can suspend on another effect" in {
        val program = echo(5).map(_ * 2)
        val handled: Int < Get = ArrowEffect.handleResume(Tag[Echo], program.asInstanceOf[Int < (Echo & Get)])(
            [C] => (in) => get.map(_ + in)
        )
        val result = ArrowEffect.handleResume(Tag[Get], handled)(
            [C] => (_) => 100
        )
        assert(result.eval == 210)
    }

    "handleResume is deep across operations in clause results" in {
        var calls   = 0
        val program = echo(1).map(a => echo(a).map(b => a + b))
        val handled = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] =>
                (in) =>
                    calls += 1
                    if calls == 1 then echo(in + 10).asInstanceOf[Int < Echo] else in
        )
        assert(handled.eval == 22)
        assert(calls == 3)
    }

    "handleStop ends the region and skips the prefix" in {
        var afterOp = false
        val program = fail("boom").map { (n: Int) =>
            afterOp = true
            n + 1
        }
        val handled = ArrowEffect.handleStop(Tag[Fail], program.asInstanceOf[Int < Fail])(
            [C] => (msg) => msg.length
        )
        assert(handled.eval == 4)
        assert(!afterOp)
    }

    "handleStop is deep when the clause result suspends again" in {
        var stops               = 0
        val program: Int < Fail = fail("first")
        val handled = ArrowEffect.handleStop(Tag[Fail], program)(
            [C] =>
                (msg) =>
                    stops += 1
                    if msg == "first" then fail("second").asInstanceOf[Int < Fail]
                    else msg.length
        )
        assert(handled.eval == 6)
        assert(stops == 2)
    }

    "handleStop leaves outer maps in place" in {
        val program: Int < Fail = fail("boom")
        val handled             = ArrowEffect.handleStop(Tag[Fail], program)([C] => (msg) => msg.length)
        assert(handled.map(_ * 10).eval == 40)
    }

    "handle aborts by not invoking the continuation" in {
        var afterOp = false
        val program = echo(1).map { n =>
            afterOp = true
            n + 1
        }
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => in + 100
        )
        assert(handled.eval == 101)
        assert(!afterOp)
    }

    "handle resumes multi shot" in {
        val program = echo(10).map(_ + 1)
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] =>
                (in, cont) =>
                    cont(in).map(a => cont(in * 2).map(b => a * 1000 + b))
        )
        assert(handled.eval == 11021)
    }

    "handle is deep through resumed continuations" in {
        var ops     = 0
        val program = echo(1).map(a => echo(a + 1).map(b => echo(b + 1).map(c => a * 100 + b * 10 + c)))
        val handled = ArrowEffect.handle(Tag[Echo], program)(
            [C] =>
                (in, cont) =>
                    ops += 1
                    cont(in)
        )
        assert(handled.eval == 123)
        assert(ops == 3)
    }

    "nested handlers of different effects dispatch to the innermost match" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => a * 10 + b))
        val inner = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => cont(in + 1)
        )
        val outer = ArrowEffect.handle(Tag[Get], inner)(
            [C] => (in, cont) => cont(7)
        )
        assert(outer.eval == 27)
    }

    "nested handlers of the same effect: innermost wins" in {
        val program = echo(1).map(_ + 1)
        val inner = ArrowEffect.handle(Tag[Echo], program)(
            [C] => (in, cont) => cont(in + 10)
        )
        val outer = ArrowEffect.handle(Tag[Echo], inner.asInstanceOf[Int < Echo])(
            [C] => (in, cont) => cont(in + 100)
        )
        assert(outer.eval == 12)
    }

    "an unhandled effect parks and a later handler completes it" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => a + b))
        val partial: Int < Get = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in * 10
        )
        val parked = ArrowEffect.handlePartial(Tag[Get], partial)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val result = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 5
        )
        assert(result.eval == 15)
    }

    "handleFirst handles only the first operation" in {
        val program = echo(1).map(a => echo(a + 1).map(b => a + b))
        val once: Int < Echo = ArrowEffect.handleFirst[Const[Int], Const[Int], Echo, Int, Int, Echo](Tag[Echo], program)(
            [C] => (in, cont) => cont(in * 10).asInstanceOf[Int < Echo]
        )(a => a)
        val rest = ArrowEffect.handleResume(Tag[Echo], once)(
            [C] => (in) => in + 100
        )
        assert(rest.eval == 121)
    }

    "handleFirst runs done when no operation occurs" in {
        var doneRan             = false
        val program: Int < Echo = 5.asInstanceOf[Int < Echo]
        val handled = ArrowEffect.handleFirst(Tag[Echo], program)(
            [C] => (in, cont) => -1
        ) { a =>
            doneRan = true
            a + 1
        }
        assert(handled.eval == 6)
        assert(doneRan)
    }

    "handleFirst done runs when the region completes without an operation after install" in {
        val program: Int < Echo = echo(3)
        val handled = ArrowEffect.handleFirst(Tag[Echo], program)(
            [C] => (in, cont) => cont(in).asInstanceOf[Int < Echo].map(_ + 1000)
        )(a => a)
        assert(handled.asInstanceOf[Int < Any].eval == 1003)
    }

    "a stop shaped effect cannot be resumed by construction" in {
        val program: Int < Fail = fail("nope")
        val handled = ArrowEffect.handle(Tag[Fail], program)(
            [C] => (msg, cont) => msg.length
        )
        assert(handled.eval == 4)
    }

    "handlers travel with parked computations" in {
        val program: Int < (Echo & Get) =
            get.map(a => echo(a + 1).map(b => a + b))
        val handled: Int < Get = ArrowEffect.handleResume(Tag[Echo], program)(
            [C] => (in) => in * 10
        )
        val parked = ArrowEffect.handlePartial(Tag[Get], handled)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val resumed = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 2
        )
        assert(resumed.eval == 32)
    }

    "a handled computation is a reusable value" in {
        var runs = 0
        val handled = ArrowEffect.handleResume(Tag[Echo], echo(1).map(_ + 1))(
            [C] =>
                (in) =>
                    runs += 1
                in
        )
        assert(handled.eval == 2)
        assert(handled.eval == 2)
        assert(runs == 2)
    }

    "defer inside a handled region stays lazy and dispatches" in {
        var log = List.empty[String]
        val program = Effect.defer {
            log :+= "defer"
            echo(1)
        }.map(_ + 1)
        val handled = ArrowEffect.handleResume(Tag[Echo], program.asInstanceOf[Int < Echo])(
            [C] => (in) => in + 10
        )
        assert(log == Nil)
        assert(handled.eval == 12)
        assert(log == List("defer"))
    }

    "handleLoop threads state and applies done with the final state" in {
        val program = echo(1).map(a => echo(2).map(b => echo(3).map(c => a + b + c)))
        val handled = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] => (state, in, cont) => ArrowEffect.Outcome.Continue(state + in, cont(in))
        )((state, a) => (state, a))
        assert(handled.eval == (6, 6))
    }

    "handleLoop ends the region early with Done" in {
        var afterOp = false
        val program = echo(1).map { a =>
            echo(100).map { b =>
                afterOp = true
                a + b
            }
        }
        val handled = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] =>
                (state, in, cont) =>
                    if in >= 100 then ArrowEffect.Outcome.Done(-1)
                    else ArrowEffect.Outcome.Continue(state + in, cont(in))
        )((state, a) => a)
        assert(handled.eval == -1)
        assert(!afterOp)
    }

    "handleLoop forwards effects raised while computing the outcome" in {
        val program: Int < (Echo & Get) = echo(1).map(_ + 1)
        val handled: Int < Get = ArrowEffect.handleLoop(Tag[Echo], 0, program.asInstanceOf[Int < (Echo & Get)])(
            [C] =>
                (state, in, cont) =>
                    get.map(g => ArrowEffect.Outcome.Continue(state + g, cont(in + g)))
        )((state, a) => state * 1000 + a)
        val result = ArrowEffect.handleResume(Tag[Get], handled)(
            [C] => (_) => 7
        )
        assert(result.eval == 7009)
    }

    "handleLoop state survives a park on a foreign effect" in {
        val program: Int < (Echo & Get) =
            echo(1).map(a => get.map(b => echo(2).map(c => a + b + c)))
        val handled: Int < Get = ArrowEffect.handleLoop(Tag[Echo], 0, program)(
            [C] => (state, in, cont) => ArrowEffect.Outcome.Continue(state + in, cont(in))
        )((state, a) => state * 1000 + a)
        val parked = ArrowEffect.handlePartial(Tag[Get], handled)(
            [C] => (in, cont) => kyo.Maybe.Absent
        )
        val result = ArrowEffect.handleResume(Tag[Get], parked)(
            [C] => (_) => 10
        )
        assert(result.eval == 3013)
    }

    "handlePartial handles operations deeply at the drive boundary" in {
        import kyo.Maybe
        val program = echo(1).map(a => echo(a + 1).map(b => a + b))
        val result = ArrowEffect.handlePartial(Tag[Echo], program)(
            [C] => (in, cont) => Maybe(cont(in * 10))
        )
        assert(result.asInstanceOf[Int < Any].eval == 120)
    }

    "handlePartial parks on Absent and the captured continuation resumes" in {
        import kyo.Maybe
        val program       = echo(1).map(a => echo(a + 1).map(b => a + b))
        var captured: Any = null
        var slices        = 0
        def slice(v: Int < Echo): Int < Echo =
            ArrowEffect.handlePartial(Tag[Echo], v)(
                [C] =>
                    (in, cont) =>
                        slices += 1
                        captured = cont
                        Maybe.Absent
            )
        val parked1 = slice(program)
        val k1      = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        val parked2 = slice(k1(10))
        val k2      = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        val done    = k2(100)
        assert(done.asInstanceOf[Int < Any].eval == 110)
        assert(slices == 2)
    }

    "installed delimiters win over the handlePartial clause" in {
        import kyo.Maybe
        var lastResort = 0
        val program    = echo(1).map(a => echo(a + 1).map(b => a + b))
        val handled    = ArrowEffect.handleResume(Tag[Echo], program)([C] => (in) => in * 10)
        val result = ArrowEffect.handlePartial(Tag[Echo], handled.asInstanceOf[Int < Echo])(
            [C] =>
                (in, cont) =>
                    lastResort += 1
                    Maybe(cont(in))
        )
        assert(result.asInstanceOf[Int < Any].eval == 120)
        assert(lastResort == 0)
    }

    "handlePartial resumption re-installs traveling delimiters" in {
        import kyo.Maybe
        val program: Int < (Echo & Get) =
            get.map(a => echo(a + 1).map(b => get.map(c => a + b + c)))
        val bound: Int < Echo = ArrowEffect.handleResume(Tag[Get], program.asInstanceOf[Int < (Get & Echo)])(
            [C] => (_) => 5
        ).asInstanceOf[Int < Echo]
        var captured: Any = null
        val parked = ArrowEffect.handlePartial(Tag[Echo], bound)(
            [C] =>
                (in, cont) =>
                    captured = cont
                    Maybe.Absent
        )
        val k = captured.asInstanceOf[Arrow[Int, Int, Echo]]
        assert(k(100).asInstanceOf[Int < Any].eval == 110)
    }

    "handlePartial polls preemption across dispatches" in {
        import kyo.Maybe
        def program(i: Int): Int < Echo =
            if i == 0 then 0
            else echo(i).map(_ => program(i - 1))
        var polls = 0
        val r = ArrowEffect.handlePartial(
            Tag[Echo],
            program(10000),
            () =>
                polls += 1; polls == 2
            ,
            512
        )(
            [C] => (in, cont) => Maybe(cont(in))
        )
        assert(polls == 2)
        val rest = ArrowEffect.handlePartial(Tag[Echo], r)(
            [C] => (in, cont) => Maybe(cont(in))
        )
        assert(rest.asInstanceOf[Int < Any].eval == 0)
    }

end ArrowEffectTest
