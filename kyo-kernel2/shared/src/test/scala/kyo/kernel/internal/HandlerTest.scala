package kyo.kernel.internal

import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

class HandlerTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

    "a Loop handler continues at its declared types" in {
        val h =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any]:
                def tag = Tag[Ask]
                @targetName("applyInput")
                def apply[X](input: Unit) = Loop.continue(42)
        val outcome = h[Any](())
        (outcome: Any) match
            case c: Loop.Continue[?] => assert(c._1.asInstanceOf[Int < Any].eval == 42)
            case other               => fail(s"expected a continue, got $other")
    }

    "a Loop handler dones with the bare value" in {
        val h =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any]:
                def tag = Tag[Ask]
                @targetName("applyInput")
                def apply[X](input: Unit) = Loop.done(-1)
        val outcome = h[Any](())
        (outcome: Any) match
            case c: Loop.Continue[?] => fail(s"expected a done, got $c")
            case done                => assert(done.asInstanceOf[Int] == -1)
    }

    "a LoopState handler continues with the next state and the answer" in {
        val h =
            new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                def tag                               = Tag[Ask]
                def state                             = 7
                def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
        val outcome = h[Any]((), h.state)
        (outcome: Any) match
            case c: Loop.Continue2[?, ?] =>
                assert(c._1.asInstanceOf[Int] == 8)
                assert(c._2.asInstanceOf[Int < Any].eval == 7)
            case other =>
                fail(s"expected a continue, got $other")
        end match
    }

    "a LoopState successor carries the new state and the original logic" in {
        val h =
            new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                def tag                               = Tag[Ask]
                def state                             = 7
                def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
        val h2 = h.withState(10)
        assert(h2.state == 10)
        assert(h2.tag =:= h.tag)
        val outcome = h2[Any]((), h2.state)
        (outcome: Any) match
            case c: Loop.Continue2[?, ?] =>
                assert(c._1.asInstanceOf[Int] == 11)
                assert(c._2.asInstanceOf[Int < Any].eval == 10)
            case other =>
                fail(s"expected a continue, got $other")
        end match
    }

    "a successor of a successor still answers with the original logic" in {
        val h =
            new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int]:
                def tag                               = Tag[Ask]
                def state                             = 0
                def apply[X](input: Unit, state: Int) = Loop.continue(state + 1, state)
        val h3 = h.withState(1).withState(2)
        assert(h3.state == 2)
        val outcome = h3[Any]((), h3.state)
        (outcome: Any) match
            case c: Loop.Continue2[?, ?] =>
                assert(c._1.asInstanceOf[Int] == 3)
                assert(c._2.asInstanceOf[Int < Any].eval == 2)
            case other =>
                fail(s"expected a continue, got $other")
        end match
    }

    "a Cont handler receives the continuation at its declared types" in {
        val h =
            new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any]:
                def tag                                                   = Tag[Ask]
                def apply[X](input: Unit, cont: Int => Int < (Ask & Any)) = cont(41)
        val result = h[Any]((), o => o + 1)
        assert(result.asInstanceOf[Int < Any].eval == 42)
    }

end HandlerTest
