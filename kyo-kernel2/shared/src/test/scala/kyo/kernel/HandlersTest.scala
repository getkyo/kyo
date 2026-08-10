package kyo.kernel

import kyo.Maybe
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class HandlersTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

    def resumeWith(value: Int): Handler.Resume[Const[Unit], Const[Int], Ask, Any] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = value

    def stopWith(value: Int): Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any] =
        new Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < (Ask & Any) = value

    "empty finds nothing" in {
        assert(Handlers.empty.find(Tag[Ask]).isEmpty)
    }

    "add then find returns the handler" in {
        val h = resumeWith(42)
        assert(Handlers.empty.add(h).find(Tag[Ask]).exists(_ eq h))
    }

    "find misses on an unrelated tag" in {
        val h = resumeWith(42)
        assert(Handlers.empty.add(h).find(Tag[Say]).isEmpty)
    }

    "the innermost handler of a tag wins" in {
        val outer = resumeWith(1)
        val inner = resumeWith(2)
        assert(Handlers.empty.add(outer).add(inner).find(Tag[Ask]).exists(_ eq inner))
    }

    "handlers of distinct tags resolve independently of order" in {
        val ask = resumeWith(42)
        val say = new Handler.Stop[Const[String], Const[Unit], Say, Unit, Any](Tag[Say]):
            def apply[X](input: String): Unit < (Say & Any) = ()
        val handlers = Handlers.empty.add(ask).add(say)
        assert(handlers.find(Tag[Ask]).exists(_ eq ask))
        assert(handlers.find(Tag[Say]).exists(_ eq say))
    }

    "a subtype suspension tag finds the supertype handler" in {
        val h = resumeWith(42)
        assert(Handlers.empty.add(h).find(Tag[AskSub]).exists(_ eq h))
    }

    "a supertype suspension tag does not find a subtype handler" in {
        val h = new Handler.Resume[Const[Unit], Const[Int], AskSub, Any](Tag[AskSub]):
            def apply[X](input: Unit): Int < Any = 1
        assert(Handlers.empty.add(h).find(Tag[Ask]).isEmpty)
    }

    "add returns a new collection and leaves the original unchanged" in {
        val h     = resumeWith(42)
        val empty = Handlers.empty
        val one   = empty.add(h)
        assert(empty.find(Tag[Ask]).isEmpty)
        assert(one.find(Tag[Ask]).exists(_ eq h))
    }

    "a Resume clause is invoked at its declared types" in {
        val h      = resumeWith(42)
        val answer = h[Any](())
        assert(answer.eval == 42)
    }

    "Stop handlers compare by identity" in {
        val a = stopWith(-1)
        val b = stopWith(-1)
        assert(Handlers.empty.add(a).find(Tag[Ask]).exists(_ eq a))
        assert(!Handlers.empty.add(a).find(Tag[Ask]).exists(_ eq b))
    }

    "a Stop clause is invoked at its declared types" in {
        val h = stopWith(-1)
        assert(h[Any](()).asInstanceOf[Int < Any].eval == -1)
    }

    "a Cont clause receives the continuation at its declared types" in {
        val h = new Handler.Cont[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
            def apply[X](input: Unit, cont: Int => Int < (Ask & Any)): Int < (Ask & Any) = cont(41)
        val result = h[Any]((), o => o + 1)
        assert(result.asInstanceOf[Int < Any].eval == 42)
    }

    "a Loop clause threads state at its declared types" in {
        val h = new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any, Int](Tag[Ask]):
            def apply[X](input: Unit, state: Int, cont: Int => Int < (Ask & Any)): (Int, Int < (Ask & Any)) =
                (state + 1, cont(state))
        val (state, result) = h[Any]((), 10, o => o * 2)
        assert(state == 11)
        assert(result.asInstanceOf[Int < Any].eval == 20)
    }

end HandlersTest
