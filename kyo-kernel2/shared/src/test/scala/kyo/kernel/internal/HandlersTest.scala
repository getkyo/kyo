package kyo.kernel.internal

import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class HandlersTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask], [X] => (_: Unit) => Loop.continue(value))

    def loopSay: Handler.Loop[Const[String], Const[Unit], Say, Unit, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Unit, Any](Tag[Say], [X] => (_: String) => Loop.continue(()))

    "empty resolves nothing" in {
        assert(Handlers.empty.indexOf(Tag[Ask]) == -1)
    }

    "add then indexOf resolves the handler" in {
        val h  = loopAsk(42)
        val hs = Handlers.empty.add(h)
        val i  = hs.indexOf(Tag[Ask])
        assert(i == 0)
        assert(hs(i) eq h)
    }

    "indexOf misses on an unrelated tag" in {
        assert(Handlers.empty.add(loopAsk(42)).indexOf(Tag[Say]) == -1)
    }

    "the innermost handler of a tag wins" in {
        val outer = loopAsk(1)
        val inner = loopAsk(2)
        val hs    = Handlers.empty.add(outer).add(inner)
        assert(hs(hs.indexOf(Tag[Ask])) eq inner)
    }

    "handlers of distinct tags resolve independently of order" in {
        val ask      = loopAsk(42)
        val say      = loopSay
        val handlers = Handlers.empty.add(ask).add(say)
        assert(handlers(handlers.indexOf(Tag[Ask])) eq ask)
        assert(handlers(handlers.indexOf(Tag[Say])) eq say)
    }

    "a subtype suspension tag resolves the supertype handler" in {
        val h  = loopAsk(42)
        val hs = Handlers.empty.add(h)
        assert(hs(hs.indexOf(Tag[AskSub])) eq h)
    }

    "a supertype suspension tag does not resolve a subtype handler" in {
        val h = new Handler.Loop[Const[Unit], Const[Int], AskSub, Int, Any](Tag[AskSub], [X] => (_: Unit) => Loop.continue(1))
        assert(Handlers.empty.add(h).indexOf(Tag[Ask]) == -1)
    }

    "add returns a new collection and leaves the original unchanged" in {
        val h     = loopAsk(42)
        val empty = Handlers.empty
        val one   = empty.add(h)
        assert(empty.indexOf(Tag[Ask]) == -1)
        assert(one(one.indexOf(Tag[Ask])) eq h)
    }

    "updated replaces at the position and leaves the original unchanged" in {
        val a   = loopAsk(1)
        val b   = loopAsk(2)
        val one = Handlers.empty.add(a)
        val two = one.updated(0, b)
        assert(one(one.indexOf(Tag[Ask])) eq a)
        assert(two(two.indexOf(Tag[Ask])) eq b)
    }

    "take keeps the outer prefix only" in {
        val a  = loopAsk(1)
        val s  = loopSay
        val hs = Handlers.empty.add(a).add(s)
        assert(hs.take(1).size == 1)
        assert(hs.take(1).indexOf(Tag[Ask]) == 0)
        assert(hs.take(1).indexOf(Tag[Say]) == -1)
    }

end HandlersTest
