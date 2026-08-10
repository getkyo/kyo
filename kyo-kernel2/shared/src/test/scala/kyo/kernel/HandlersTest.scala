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
        val ask      = resumeWith(42)
        val say      = new Handler.Stop[Const[String], Const[Unit], Say](Tag[Say])
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
        val a = new Handler.Stop[Const[Unit], Const[Int], Ask](Tag[Ask])
        val b = new Handler.Stop[Const[Unit], Const[Int], Ask](Tag[Ask])
        assert(Handlers.empty.add(a).find(Tag[Ask]).exists(_ eq a))
        assert(!Handlers.empty.add(a).find(Tag[Ask]).exists(_ eq b))
    }

end HandlersTest
