package kyo.kernel2

import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.test.Test
import language.implicitConversions

sealed trait LoopAsk extends ArrowEffect[Const[Unit], Const[Int]]

class LoopTest extends Test[Any]:

    "apply" - {
        "with a single iteration" in {
            assert(
                Loop(1)(i => if i < 5 then Loop.continue(i + 1) else Loop.done(i)).eval == 5
            )
        }

        "with multiple iterations" in {
            assert(
                Loop(1)(i => if i < 5 then Loop.continue(i + 1) else Loop.done(i)).eval == 5
            )
        }

        "with no iterations" in {
            assert(
                Loop(5)(i => if i < 5 then Loop.continue(i + 1) else Loop.done(i)).eval == 5
            )
        }

        "accumulate results in a List" in {
            val result = Loop((0, List.empty[Int])) { case (i, acc) =>
                if i < 5 then
                    Loop.continue((i + 1, i :: acc))
                else
                    Loop.done(acc.reverse)
            }
            assert(result.eval == List(0, 1, 2, 3, 4))
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop(1)(i => if i < largeNumber then Loop.continue(i + 1) else Loop.done(i)).eval == largeNumber
            )
        }

        "stack safety with nested loops" in {
            val outerLoopIterations = 1000
            val innerLoopIterations = 1000

            assert(
                Loop(0)(i =>
                    if i < outerLoopIterations then
                        Loop.continue(Loop(0)(j =>
                            if j < innerLoopIterations then Loop.continue(j + 1) else Loop.done(j)
                        ).eval)
                    else
                        Loop.done(i)
                ).eval == outerLoopIterations
            )
        }

        "stack safety with multiple levels of nested loops" in {
            val level1Iterations = 100
            val level2Iterations = 100
            val level3Iterations = 100

            assert(
                Loop(0)(i =>
                    if i < level1Iterations then
                        Loop.continue(
                            Loop(0)(j =>
                                if j < level2Iterations then
                                    Loop.continue(
                                        Loop(0)(k => if k < level3Iterations then Loop.continue(k + 1) else Loop.done(k)).eval
                                    )
                                else
                                    Loop.done(j)
                            ).eval
                        )
                    else
                        Loop.done(i)
                ).eval == level1Iterations
            )
        }

        "suspend at the beginning" in {
            val result = Loop(1)(i =>
                Effect.defer {
                    if i < 5 then Loop.continue(i + 1) else Loop.done(i)
                }
            )
            assert(result.eval == 5)
        }

        "suspend in the middle" in {
            val result = Loop(1)(i =>
                if i < 3 then
                    Effect.defer(Loop.continue(i + 1))
                else if i < 5 then
                    Loop.continue(i + 1)
                else
                    Loop.done(i)
            )
            assert(result.eval == 5)
        }

        "suspend at the end" in {
            val result = Loop(1)(i =>
                if i < 5 then
                    Loop.continue(i + 1)
                else
                    Effect.defer(Loop.done(i))
            )
            assert(result.eval == 5)
        }
    }

    "apply2" - {
        "with a single iteration" in {
            assert(
                Loop(1, 1)((i, j) => if i + j < 5 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loop(1, 1)((i, j) => if i + j < 10 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 10
            )
        }

        "with no iterations" in {
            assert(
                Loop(5, 5)((i, j) => if i + j < 10 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 10
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop(1, 1)((i, j) =>
                    if i + j < largeNumber then Loop.continue(i + 1, j + 1)
                    else Loop.done(i + j)
                ).eval == largeNumber
            )
        }

        "stack safety with nested loops" in {
            val outerLoopIterations = 1000
            val innerLoopIterations = 1000
            assert(
                Loop(0, 0)((i, j) =>
                    if i < outerLoopIterations then
                        Loop.continue(
                            i + 1,
                            Loop(0)(k =>
                                if k < innerLoopIterations then Loop.continue(k + 1)
                                else Loop.done(k)
                            ).eval
                        )
                    else Loop.done(j)
                ).eval == innerLoopIterations
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loop(0, 0)((i, j) =>
                    if i + j < iterations then
                        if i % 2 == 0 then Loop.continue(i + 1, j)
                        else Loop.continue(i, j + 1)
                    else Loop.done(i + j)
                ).eval == iterations
            )
        }

        "suspend at the beginning" in {
            val result = Loop(1, 1)((i, j) =>
                Effect.defer {
                    if i + j < 5 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)
                }
            )
            assert(result.eval == 6)
        }

        "suspend in the middle" in {
            val result = Loop(1, 1)((i, j) =>
                if i + j < 3 then
                    Effect.defer(Loop.continue(i + 1, j + 1))
                else if i + j < 5 then
                    Loop.continue(i + 1, j + 1)
                else
                    Loop.done(i + j)
            )
            assert(result.eval == 6)
        }

        "suspend at the end" in {
            val result = Loop(1, 1)((i, j) =>
                if i + j < 5 then
                    Loop.continue(i + 1, j + 1)
                else
                    Effect.defer(Loop.done(i + j))
            )
            assert(result.eval == 6)
        }
    }

    "apply3" - {
        "with a single iteration" in {
            assert(
                Loop(1, 1, 1)((i, j, k) =>
                    if i + j + k < 5 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loop(1, 1, 1)((i, j, k) =>
                    if i + j + k < 10 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 12
            )
        }

        "with no iterations" in {
            assert(
                Loop(5, 5, 5)((i, j, k) =>
                    if i + j + k < 10 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 15
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop(1, 1, 1)((i, j, k) =>
                    if i + j + k < largeNumber then Loop.continue(i + 1, j + 1, k + 1)
                    else Loop.done(i + j + k)
                ).eval == largeNumber + 2
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loop(0, 0, 0)((i, j, k) =>
                    if i + j + k < iterations then
                        if i % 3 == 0 then Loop.continue(i + 1, j, k)
                        else if i % 3 == 1 then Loop.continue(i, j + 1, k)
                        else Loop.continue(i, j, k + 1)
                    else Loop.done(i + j + k)
                ).eval == iterations
            )
        }

        "suspend at the beginning" in {
            val result = Loop(1, 1, 1)((i, j, k) =>
                Effect.defer {
                    if i + j + k < 5 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                }
            )
            assert(result.eval == 6)
        }

        "suspend in the middle" in {
            val result = Loop(1, 1, 1)((i, j, k) =>
                if i + j + k < 3 then
                    Effect.defer(Loop.continue(i + 1, j + 1, k + 1))
                else if i + j + k < 5 then
                    Loop.continue(i + 1, j + 1, k + 1)
                else
                    Loop.done(i + j + k)
            )
            assert(result.eval == 6)
        }

        "suspend at the end" in {
            val result = Loop(1, 1, 1)((i, j, k) =>
                if i + j + k < 5 then
                    Loop.continue(i + 1, j + 1, k + 1)
                else
                    Effect.defer(Loop.done(i + j + k))
            )
            assert(result.eval == 6)
        }
    }

    "apply4" - {
        "with a single iteration" in {
            assert(
                Loop(1, 1, 1, 1)((i, j, k, l) =>
                    if i + j + k + l < 5 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 8
            )
        }

        "with multiple iterations" in {
            assert(
                Loop(1, 1, 1, 1)((i, j, k, l) =>
                    if i + j + k + l < 10 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 12
            )
        }

        "with no iterations" in {
            assert(
                Loop(5, 5, 5, 5)((i, j, k, l) =>
                    if i + j + k + l < 10 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 20
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop(1, 1, 1, 1)((i, j, k, l) =>
                    if i + j + k + l < largeNumber then Loop.continue(i + 1, j + 1, k + 1, l + 1)
                    else Loop.done(i + j + k + l)
                ).eval == largeNumber
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loop(0, 0, 0, 0)((i, j, k, l) =>
                    if i + j + k + l < iterations then
                        if i % 4 == 0 then Loop.continue(i + 1, j, k, l)
                        else if i % 4 == 1 then Loop.continue(i, j + 1, k, l)
                        else if i % 4 == 2 then Loop.continue(i, j, k + 1, l)
                        else Loop.continue(i, j, k, l + 1)
                    else Loop.done(i + j + k + l)
                ).eval == iterations
            )
        }

        "suspend at the beginning" in {
            val result = Loop(1, 1, 1, 1)((i, j, k, l) =>
                Effect.defer {
                    if i + j + k + l < 5 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                }
            )
            assert(result.eval == 8)
        }

        "suspend in the middle" in {
            val result = Loop(1, 1, 1, 1)((i, j, k, l) =>
                if i + j + k + l < 3 then
                    Effect.defer(Loop.continue(i + 1, j + 1, k + 1, i + 1))
                else if i + j + k + l < 5 then
                    Loop.continue(i + 1, j + 1, k + 1, l + 1)
                else
                    Loop.done(i + j + k + l)
            )
            assert(result.eval == 8)
        }

        "suspend at the end" in {
            val result = Loop(1, 1, 1, 1)((i, j, k, l) =>
                if i + j + k + l < 5 then
                    Loop.continue(i + 1, j + 1, k + 1, l + 1)
                else
                    Effect.defer(Loop.done(i + j + k + l))
            )
            assert(result.eval == 8)
        }
    }

    "indexed without input" - {
        "with a single iteration" in {
            assert(
                Loop.indexed(idx => if idx < 1 then Loop.continue else Loop.done(idx)).eval == 1
            )
        }

        "with multiple iterations" in {
            assert(
                Loop.indexed(idx => if idx < 5 then Loop.continue else Loop.done(idx)).eval == 5
            )
        }

        "with no iterations" in {
            assert(
                Loop.indexed(idx => if idx < 0 then Loop.continue else Loop.done(idx)).eval == 0
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop.indexed(idx => if idx < largeNumber then Loop.continue else Loop.done(idx)).eval == largeNumber
            )
        }

        "suspend" in {
            val result = Loop.indexed(idx =>
                if idx < 5 then
                    Effect.defer(Loop.continue)
                else
                    Effect.defer(Loop.done(idx))
            )
            assert(result.eval == 5)
        }
    }

    "indexed" - {
        "with a single iteration" in {
            assert(
                Loop.indexed(1)((idx, i) => if idx < 5 then Loop.continue(i + 1) else Loop.done(i)).eval == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loop.indexed(1)((idx, i) => if idx < 10 then Loop.continue(i + 1) else Loop.done(i)).eval == 11
            )
        }

        "with no iterations" in {
            assert(
                Loop.indexed(1)((idx, i) => if idx < 0 then Loop.continue(i + 1) else Loop.done(i)).eval == 1
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop.indexed(1)((idx, i) => if idx < largeNumber then Loop.continue(i + 1) else Loop.done(i)).eval == largeNumber + 1
            )
        }

        "suspend" in {
            val result = Loop.indexed(1)((idx, i) =>
                if idx < 5 then
                    Effect.defer(Loop.continue(i + 1))
                else
                    Effect.defer(Loop.done(i))
            )
            assert(result.eval == 6)
        }
    }

    "indexed2" - {
        "with a single iteration" in {
            assert(
                Loop.indexed(1, 1)((idx, i, j) => if idx < 5 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 12
            )
        }

        "with multiple iterations" in {
            assert(
                Loop.indexed(1, 1)((idx, i, j) => if idx < 10 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 22
            )
        }

        "with no iterations" in {
            assert(
                Loop.indexed(1, 1)((idx, i, j) => if idx < 0 then Loop.continue(i + 1, j + 1) else Loop.done(i + j)).eval == 2
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop.indexed(1, 1)((idx, i, j) =>
                    if idx < largeNumber then Loop.continue(i + 1, j + 1) else Loop.done(i + j)
                ).eval == 2 * largeNumber + 2
            )
        }

        "suspend" in {
            val result = Loop.indexed(1, 1)((idx, i, j) =>
                if idx < 5 then
                    Effect.defer(Loop.continue(i + 1, j + 1))
                else
                    Effect.defer(Loop.done(i + j))
            )
            assert(result.eval == 12)
        }
    }

    "indexed3" - {
        "with a single iteration" in {
            assert(
                Loop.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 5 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 18
            )
        }

        "with multiple iterations" in {
            assert(
                Loop.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 10 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 33
            )
        }

        "with no iterations" in {
            assert(
                Loop.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 0 then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 3
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < largeNumber then Loop.continue(i + 1, j + 1, k + 1) else Loop.done(i + j + k)
                ).eval == 3 * largeNumber + 3
            )
        }

        "suspend" in {
            val result = Loop.indexed(1, 1, 1)((idx, i, j, k) =>
                if idx < 5 then
                    Effect.defer(Loop.continue(i + 1, j + 1, k + 1))
                else
                    Effect.defer(Loop.done(i + j + k))
            )
            assert(result.eval == 18)
        }
    }

    "indexed4" - {
        "with a single iteration" in {
            assert(
                Loop.indexed(1, 1, 1, 1)((idx, i, j, k, l) =>
                    if idx < 5 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 24
            )
        }

        "with multiple iterations" in {
            assert(
                Loop.indexed(1, 1, 1, 1)((idx, i, j, k, l) =>
                    if idx < 10 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 44
            )
        }

        "with no iterations" in {
            assert(
                Loop.indexed(1, 1, 1, 1)((idx, i, j, k, l) =>
                    if idx < 0 then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 4
            )
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loop.indexed(1, 1, 1, 1)((idx, i, j, k, l) =>
                    if idx < largeNumber then Loop.continue(i + 1, j + 1, k + 1, l + 1) else Loop.done(i + j + k + l)
                ).eval == 4 * largeNumber + 4
            )
        }

        "suspend" in {
            val result = Loop.indexed(1, 1, 1, 1)((idx, i, j, k, l) =>
                if idx < 5 then
                    Effect.defer(Loop.continue(i + 1, j + 1, k + 1, l + 1))
                else
                    Effect.defer(Loop.done(i + j + k + l))
            )
            assert(result.eval == 24)
        }
    }

    "foreach" - {
        "with a single iteration" in {
            var counter = 0
            Loop.foreach {
                counter += 1
                if counter < 1 then Loop.continue else Loop.done
            }.eval
            assert(counter == 1)
        }

        "with multiple iterations" in {
            var sum = 0
            Loop.foreach {
                sum += 1
                if sum < 10 then Loop.continue else Loop.done
            }.eval
            assert(sum == 10)
        }

        "with no iterations" in {
            var entered = false
            Loop.foreach {
                entered = true
                Loop.done
            }.eval
            assert(entered)
        }

        "stack safety" in {
            var counter     = 0
            val largeNumber = 100000
            Loop.foreach {
                counter += 1
                if counter < largeNumber then Loop.continue else Loop.done
            }.eval
            assert(counter == largeNumber)
        }

        "suspend" in {
            var effect = ""
            val result = Loop.foreach {
                effect += "A"
                if effect.length < 3 then
                    Effect.defer(Loop.continue)
                else
                    Effect.defer(Loop.done)
                end if
            }
            result.eval
            assert(effect == "AAA")
        }

        "returns" in {
            val result = Loop.foreach {
                Effect.defer(Loop.done(1))
            }
            assert(result.eval == 1)
        }
    }

    "repeat" in {
        var count = 0
        val io    = Effect.defer(count += 1)

        Loop.repeat(0)(io).eval
        assert(count == 0)

        count = 0
        Loop.repeat(1)(io).eval
        assert(count == 1)

        count = 0
        Loop.repeat(100)(io).eval
        assert(count == 100)

        count = 0
        Loop.repeat(10000)(io).eval
        assert(count == 10000)
    }

    "whileTrue" - {
        "continues while condition is true" in {
            var counter = 0
            Loop.whileTrue(counter < 5) {
                counter += 1
            }.eval
            assert(counter == 5)
        }

        "does not execute when condition is initially false" in {
            var executed = false
            Loop.whileTrue(false) {
                executed = true
            }.eval
            assert(!executed)
        }

        "with suspended condition" in {
            var counter = 0
            val result = Loop.whileTrue(Effect.defer(counter < 3)) {
                counter += 1
            }
            result.eval
            assert(counter == 3)
        }

        "with suspended body" in {
            var counter = 0
            val result = Loop.whileTrue(counter < 3) {
                Effect.defer(counter += 1)
            }
            result.eval
            assert(counter == 3)
        }

        "stack safety" in {
            var counter     = 0
            val largeNumber = 100000
            Loop.whileTrue(counter < largeNumber) {
                counter += 1
            }.eval
            assert(counter == largeNumber)
        }

        "stack safety with suspended operations" in {
            var counter     = 0
            val largeNumber = 10000
            val result = Loop.whileTrue(Effect.defer(counter < largeNumber)) {
                Effect.defer(counter += 1)
            }
            result.eval
            assert(counter == largeNumber)
        }
    }
    // kernel2-specific coverage beyond the ported suite

    def ask: Int < LoopAsk =
        ArrowEffect.suspend[Any](Tag[LoopAsk], ())

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
        val handled = ArrowEffect.handleResume(Tag[LoopAsk], program)(
            [C] => (_) => 5
        )
        assert(handled.eval == 15)
    }

    "a suspended loop of many iterations is stack safe" in {
        val program = Loop(0, 100000) { (acc, i) =>
            if i == 0 then Loop.done(acc)
            else ask.map(a => Loop.continue(acc + a, i - 1))
        }
        val handled = ArrowEffect.handleResume(Tag[LoopAsk], program)(
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
        val parked = ArrowEffect.handlePartial(Tag[LoopAsk], program, Context.empty)(
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
