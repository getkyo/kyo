package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.internal.Handlers.Empty
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

class HandlersTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any]:
            def tag = Tag[Ask]
            @targetName("applyInput")
            def apply[X](input: Unit) = Loop.continue(value)

    def loopSay: Handler.Loop[Const[String], Const[Unit], Say, Unit, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Unit, Any]:
            def tag = Tag[Say]
            @targetName("applyInput")
            def apply[X](input: String) = Loop.continue(())

    def stateAsk: Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int] =
        new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
            def tag                               = Tag[Ask]
            def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)

    def firstAsk: Handler.First[Const[Unit], Const[Int], Ask, Int, Int, Any, Any] =
        new Handler.First[Const[Unit], Const[Int], Ask, Int, Int, Any, Any]:
            def tag                                                   = Tag[Ask]
            def apply[X](input: Unit, cont: Int => Int < (Ask & Any)) = 1
            @targetName("applyDone")
            def apply(v: Int) = v

    // a cell is made by entering a region, which is the only way the spine
    // builds one: the erasure is the same cast the evaluator's arms have already
    // performed by the time they push
    def erased(v: Kyo.Handled[?, ?, ?, ?, ?, ?, ?]): Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] =
        v.asInstanceOf[Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]]

    def erasedState(v: Kyo.HandledState[?, ?, ?, ?, ?, ?, ?, ?])
        : Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any] =
        v.asInstanceOf[Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any]]

    def erasedFirst(v: Kyo.HandledFirst[?, ?, ?, ?, ?, ?, ?, ?, ?])
        : Kyo.HandledFirst[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any] =
        v.asInstanceOf[Kyo.HandledFirst[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any]]

    def askNode                           = erased(Kyo.Handled(ask, loopAsk(1), Arrow[Int]))
    def sayNode                           = erased(Kyo.Handled(say("x"), loopSay, Arrow[Unit]))
    def askCell(prev: Handlers): Handlers = prev.push(askNode)
    def sayCell(prev: Handlers): Handlers = prev.push(sayNode)

    "Empty resolves nothing" in {
        assert(Empty.find(Tag[Ask]) eq Empty)
    }

    "a pushed cell is found by its tag" in {
        val cell = askCell(Empty)
        assert(cell.find(Tag[Ask]) eq cell)
    }

    "find misses on an unrelated tag" in {
        val cell = askCell(Empty)
        assert(cell.find(Tag[Say]) eq Empty)
    }

    "the innermost cell of a tag wins" in {
        val outer = askCell(Empty)
        val inner = askCell(outer)
        assert(inner.find(Tag[Ask]) eq inner)
    }

    "cells of distinct tags resolve independently of order" in {
        val ask = askCell(Empty)
        val say = sayCell(ask)
        assert(say.find(Tag[Ask]) eq ask)
        assert(say.find(Tag[Say]) eq say)
    }

    "a subtype suspension tag resolves the supertype cell" in {
        val cell = askCell(Empty)
        assert(cell.find(Tag[AskSub]) eq cell)
    }

    "a supertype suspension tag does not resolve a subtype cell" in {
        val h =
            new Handler.Loop[Const[Unit], Const[Int], AskSub, Int, Any]:
                def tag = Tag[AskSub]
                @targetName("applyInput")
                def apply[X](input: Unit) = Loop.continue(1)
        val cell = Empty.push(erased(Kyo.Handled(ask.asInstanceOf[Int < AskSub], h, Arrow[Int])))
        assert(cell.find(Tag[Ask]) eq Empty)
    }

    "prev pops without touching the enclosing cells" in {
        val bottom = askCell(Empty)
        val top    = sayCell(bottom)
        assert(top.prev eq bottom)
        assert(bottom.prev eq Empty)
    }

    "a stateful cell is found by its tag" in {
        val cell = Empty.push(erasedState(Kyo.HandledState(ask, stateAsk, Arrow[Int], 1)))
        assert(cell.find(Tag[Ask]) eq cell)
        assert(cell.find(Tag[Say]) eq Empty)
    }

    "a first cell is found by its tag" in {
        val cell = Empty.push(erasedFirst(Kyo.HandledFirst(ask, firstAsk, Arrow[Int])))
        assert(cell.find(Tag[Ask]) eq cell)
        assert(cell.find(Tag[Say]) eq Empty)
    }

    "withState replaces the state and shares everything else" in {
        val cell = Empty.push(erasedState(Kyo.HandledState(ask, stateAsk, Arrow[Int], 1)))
        val next = cell.withState(2)
        assert(next.state.asInstanceOf[Int] == 2)
        assert(cell.state.asInstanceOf[Int] == 1)
        assert(next.handler eq cell.handler)
        assert(next.exit eq cell.exit)
        assert(next.prev eq cell.prev)
    }

    "a region without state refuses the state pair" in {
        val cell = askCell(Empty)
        intercept[IllegalStateException](cell.state)
        intercept[IllegalStateException](cell.withState(1))
    }

    "a rebuilt region re-enters as the cell it was built from" in {
        val cell    = askCell(Empty)
        val rebuilt = cell.rebuilt(ask.asInstanceOf[Any < Nothing])
        assert(Empty.push(erased(rebuilt.asInstanceOf[Kyo.Handled[?, ?, ?, ?, ?, ?, ?]])) eq cell)
    }

    "a rebuilt region landing elsewhere enters as a fresh cell" in {
        val cell    = askCell(Empty)
        val other   = sayCell(Empty)
        val rebuilt = cell.rebuilt(ask.asInstanceOf[Any < Nothing])
        val pushed  = other.push(erased(rebuilt.asInstanceOf[Kyo.Handled[?, ?, ?, ?, ?, ?, ?]]))
        assert(pushed ne cell)
        assert(pushed.handler eq cell.handler)
        assert(pushed.prev eq other)
    }

    "replace swaps one cell and copies the cells above it" in {
        val bottom  = Empty.push(erasedState(Kyo.HandledState(ask, stateAsk, Arrow[Int], 1)))
        val top     = sayCell(bottom)
        val updated = bottom.withState(2)
        val spine   = top.replace(bottom, updated)
        assert(spine ne top)
        assert(spine.handler eq top.handler)
        assert(spine.prev eq updated)
        assert(spine.prev.state.asInstanceOf[Int] == 2)
        assert(top.prev eq bottom)
    }

    "replace of the top cell is the updated cell itself" in {
        val cell    = Empty.push(erasedState(Kyo.HandledState(ask, stateAsk, Arrow[Int], 1)))
        val updated = cell.withState(2)
        assert(cell.replace(cell, updated) eq updated)
    }

end HandlersTest
