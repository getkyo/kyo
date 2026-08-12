package kyo.kernel.internal

import kyo.Arrow
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.internal.Handlers.Empty
import kyo.kernel.internal.Handlers.FirstNode
import kyo.kernel.internal.Handlers.Node
import kyo.kernel.internal.Handlers.StateNode
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

class HandlersTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

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

    def askCell(prev: Handlers): Node[Const[Unit], Const[Int], Ask, Int, Any] =
        new Node(loopAsk(1), Arrow[Any], prev)

    def sayCell(prev: Handlers): Node[Const[String], Const[Unit], Say, Unit, Any] =
        new Node(loopSay, Arrow[Any], prev)

    "Empty resolves nothing" in {
        assert((Empty: Handlers).find(Tag[Ask]) eq Empty)
    }

    "a pushed cell is found by its tag" in {
        val cell = askCell(Empty)
        assert((cell: Handlers).find(Tag[Ask]) eq cell)
    }

    "find misses on an unrelated tag" in {
        val cell = askCell(Empty)
        assert((cell: Handlers).find(Tag[Say]) eq Empty)
    }

    "the innermost cell of a tag wins" in {
        val outer = askCell(Empty)
        val inner = askCell(outer)
        assert((inner: Handlers).find(Tag[Ask]) eq inner)
    }

    "cells of distinct tags resolve independently of order" in {
        val ask = askCell(Empty)
        val say = sayCell(ask)
        assert((say: Handlers).find(Tag[Ask]) eq ask)
        assert((say: Handlers).find(Tag[Say]) eq say)
    }

    "a subtype suspension tag resolves the supertype cell" in {
        val cell = askCell(Empty)
        assert((cell: Handlers).find(Tag[AskSub]) eq cell)
    }

    "a supertype suspension tag does not resolve a subtype cell" in {
        val h =
            new Handler.Loop[Const[Unit], Const[Int], AskSub, Int, Any]:
                def tag = Tag[AskSub]
                @targetName("applyInput")
                def apply[X](input: Unit) = Loop.continue(1)
        val cell = new Node(h, Arrow[Any], Empty)
        assert((cell: Handlers).find(Tag[Ask]) eq Empty)
    }

    "prev pops without touching the enclosing cells" in {
        val bottom = askCell(Empty)
        val top    = sayCell(bottom)
        assert(top.prev eq bottom)
        assert(bottom.prev eq Empty)
    }

    "a stateful cell is found by its tag" in {
        val cell = new StateNode(stateAsk, Arrow[Any], 1, Empty)
        assert((cell: Handlers).find(Tag[Ask]) eq cell)
        assert((cell: Handlers).find(Tag[Say]) eq Empty)
    }

    "a first cell is found by its tag" in {
        val h =
            new Handler.First[Const[Unit], Const[Int], Ask, Int, Int, Any, Any]:
                def tag                                                   = Tag[Ask]
                def apply[X](input: Unit, cont: Int => Int < (Ask & Any)) = 1
                @targetName("applyDone")
                def apply(v: Int) = v
        val cell = new FirstNode(h, Arrow[Any], Empty)
        assert((cell: Handlers).find(Tag[Ask]) eq cell)
        assert((cell: Handlers).find(Tag[Say]) eq Empty)
    }

    "withState replaces the state and shares everything else" in {
        val cell = new StateNode(stateAsk, Arrow[Any], 1, Empty)
        val next = cell.withState(2)
        assert(next.state == 2)
        assert(cell.state == 1)
        assert(next.handler eq cell.handler)
        assert(next.exit eq cell.exit)
        assert(next.prev eq cell.prev)
    }

end HandlersTest
