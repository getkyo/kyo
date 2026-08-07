package proto4test

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto4.*
import kyo.test.Test
import language.implicitConversions

sealed trait LoopAsk extends ControlEffect[Const[Unit], Const[Int]]

class LoopTest extends Test[Any]:

    def ask: Int < LoopAsk =
        ControlEffect.suspend[Any](Tag[LoopAsk], ())

    "apply loops one state value to completion" in {
        val r = Loop(10) { i =>
            if i == 0 then Loop.done("end")
            else Loop.continue(i - 1)
        }
        assert(r.eval == "end")
    }

    "apply threads two state values" in {
        val r = Loop(0, 10) { (acc, i) =>
            if i == 0 then Loop.done(acc)
            else Loop.continue(acc + i, i - 1)
        }
        assert(r.eval == 55)
    }

    "apply threads three state values" in {
        val r = Loop(0, 1, 5) { (sum, step, remaining) =>
            if remaining == 0 then Loop.done(sum)
            else Loop.continue(sum + step, step * 2, remaining - 1)
        }
        assert(r.eval == 31)
    }

    "a pure loop of a million iterations is stack safe" in {
        val r = Loop(1000000) { i =>
            if i == 0 then Loop.done(0)
            else Loop.continue(i - 1)
        }
        assert(r.eval == 0)
    }

    "iterations can suspend and resume" in {
        val program = Loop(0, 3) { (acc, i) =>
            if i == 0 then Loop.done(acc)
            else ask.map(a => Loop.continue(acc + a, i - 1))
        }
        val handled = ControlEffect.handleResume(Tag[LoopAsk], program)(
            [C] => (_) => 5
        )
        assert(handled.eval == 15)
    }

    "a suspended loop of many iterations is stack safe" in {
        val program = Loop(0, 100000) { (acc, i) =>
            if i == 0 then Loop.done(acc)
            else ask.map(a => Loop.continue(acc + a, i - 1))
        }
        val handled = ControlEffect.handleResume(Tag[LoopAsk], program)(
            [C] => (_) => 1
        )
        assert(handled.eval == 100000)
    }

    "indexed supplies the iteration index" in {
        val r = Loop.indexed { i =>
            if i == 3 then Loop.done(i * 10)
            else Loop.continue
        }
        assert(r.eval == 30)
    }

    "indexed threads state alongside the index" in {
        val r = Loop.indexed(0) { (i, acc) =>
            if i == 4 then Loop.done(acc)
            else Loop.continue(acc + i)
        }
        assert(r.eval == 6)
    }

    "foreach loops until done" in {
        var count = 0
        val r = Loop.foreach {
            count += 1
            if count == 5 then Loop.done(count)
            else Loop.continue
        }
        assert(r.eval == 5)
        assert(count == 5)
    }

    "repeat runs exactly n times" in {
        var count = 0
        Loop.repeat(5) {
            count += 1
            count
        }.eval
        assert(count == 5)
    }

    "repeat of zero runs nothing" in {
        var count = 0
        Loop.repeat(0) {
            count += 1
            count
        }.eval
        assert(count == 0)
    }

    "whileTrue runs while the condition holds" in {
        var i   = 0
        var sum = 0
        Loop.whileTrue(i < 4) {
            sum += i
            i += 1
            i
        }.eval
        assert(sum == 6)
        assert(i == 4)
    }

    "forever runs until parked by an unhandled operation" in {
        var seen = 0
        val program: Int < LoopAsk = Loop.forever {
            ask.map { a =>
                seen += 1
                a
            }
        }
        var count = 0
        val parked = ControlEffect.handlePartial(Tag[LoopAsk], program)(
            [C] =>
                (input, cont) =>
                    count += 1
                    if count < 3 then Maybe(cont(count))
                    else Maybe.Absent
        )
        assert(count == 3)
        assert(seen == 2)
    }

end LoopTest
