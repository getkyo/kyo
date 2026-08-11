package kyo.kernel

import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class HandlerTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

    "a Loop clause continues at its declared types" in {
        val h       = new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask], [X] => (_: Unit) => Loop.continue(42))
        val outcome = h.clause[Any](())
        (outcome: Any) match
            case c: Loop.Continue[?] => assert(c._1.asInstanceOf[Int < Any].eval == 42)
            case other               => fail(s"expected a continue, got $other")
    }

    "a Loop clause dones with the bare value" in {
        val h       = new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask], [X] => (_: Unit) => Loop.done(-1))
        val outcome = h.clause[Any](())
        (outcome: Any) match
            case c: Loop.Continue[?] => fail(s"expected a done, got $c")
            case done                => assert(done.asInstanceOf[Int] == -1)
    }

    "a LoopState clause continues with the next state and the answer" in {
        val h = new Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any, Int](
            Tag[Ask],
            7,
            [X] => (_: Unit, n: Int) => Loop.continue(n + 1, n)
        )
        val outcome = h.clause[Any]((), h.state)
        (outcome: Any) match
            case c: Loop.Continue2[?, ?] =>
                assert(c._1.asInstanceOf[Int] == 8)
                assert(c._2.asInstanceOf[Int < Any].eval == 7)
            case other =>
                fail(s"expected a continue, got $other")
        end match
    }

    "a Cont clause receives the continuation at its declared types" in {
        val h      = new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask], [X] => (_, cont) => cont(41))
        val result = h.clause[Any]((), o => o + 1)
        assert(result.asInstanceOf[Int < Any].eval == 42)
    }

end HandlerTest
