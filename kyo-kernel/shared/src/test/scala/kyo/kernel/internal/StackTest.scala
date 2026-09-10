package kyo.kernel.internal

import kyo.Arrow
import kyo.Chunk
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import org.scalatest.freespec.AnyFreeSpec

class StackTest extends AnyFreeSpec:

    private def as[A](v: Any): A = v.asInstanceOf[A]

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait Env    extends ContextEffect[Int]

    def askHandler: Handler.LoopHandler[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                       = Tag[Ask]
            def run[X](input: Unit)       = Loop.continue(1)
            def done(state: Unit, v: Int) = v

    def askSubHandler: Handler.LoopHandler[Const[Unit], Const[Int], AskSub, Int, Int, Any] =
        new Handler.LoopHandler[Const[Unit], Const[Int], AskSub, Int, Int, Any]:
            def tag                       = Tag[AskSub]
            def run[X](input: Unit)       = Loop.continue(1)
            def done(state: Unit, v: Int) = v

    def sayHandler: Handler.LoopHandler[Const[String], Const[Unit], Say, Int, Int, Any] =
        new Handler.LoopHandler[Const[String], Const[Unit], Say, Int, Int, Any]:
            def tag                       = Tag[Say]
            def run[X](input: String)     = Loop.continue(())
            def done(state: Unit, v: Int) = v

    def statefulHandler: Handler.LoopStateHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopStateHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                             = Tag[Ask]
            def run[X](state: Int, input: Unit) = Loop.continue(state + 1, 1)
            def done(state: Int, v: Int)        = v + state

    def envHandler: Handler.ContextHandler[Int, Env, Int, Any] =
        new Handler.ContextHandler[Int, Env, Int, Any]:
            def tag                                        = Tag[Env]
            def derive(outer: Maybe[Int])                  = outer.getOrElse(0)
            def fork(parent: Int)                          = parent
            def join(parent: Int, forked: Int, child: Int) = parent

    "starts empty" in {
        val stack = new Stack
        assert(stack.isEmpty)
        assert(stack.depth == 0)
        assert(stack.find(Tag[Ask]) == -1)
    }

    "push and pop" - {
        "are LIFO, the last push at the highest index" in {
            val stack = new Stack
            val a     = askHandler
            val b     = sayHandler
            stack.push(a, (), Arrow.id[Int])
            stack.push(b, (), Arrow.id[Int])
            assert(stack.depth == 2)
            assert(stack.handler(0) eq a)
            assert(stack.handler(1) eq b)
            stack.pop()
            assert(stack.depth == 1)
            assert(stack.handler(0) eq a)
            stack.pop()
            assert(stack.isEmpty)
        }

        "an entry keeps the continuation it was pushed with" in {
            val stack = new Stack
            val k     = Arrow[Int](_ + 1)
            stack.push(askHandler, (), k)
            assert(stack.continuation(0) eq k)
        }

        // A pop that only moves `size` leaves the entry set, and a stack outlives one evaluation: `Stack.release`
        // returns it to a per-thread pool and `clear` reaches only `0 until size`. A slot left set would hold that
        // region's handler, state and cont, and whatever they close over, for as long as the thread lives.
        "a popped entry is dropped, not just skipped" in {
            val stack = new Stack
            val state = new Object
            val k     = Arrow[Int](_ + 1)
            stack.push(askHandler, state, k)
            stack.pop()
            assert(stack.depth == 0)
            assert(stack.handler(0) == null, "the popped slot still holds its handler")
            assert(stack.state(0).asInstanceOf[AnyRef] eq null, "the popped slot still holds its state")
            assert(stack.continuation(0) == null, "the popped slot still holds its continuation")
        }

        // The same, one level down, so a fix that clears more than it should fails here rather than passing.
        "popping the inner entry leaves the outer one intact" in {
            val stack = new Stack
            val kept  = askHandler
            val outer = new Object
            val inner = new Object
            stack.push(kept, outer, Arrow.id[Int])
            stack.push(sayHandler, inner, Arrow.id[Int])
            stack.pop()
            assert(stack.depth == 1)
            assert(stack.handler(1) == null, "the popped slot still holds its handler")
            assert(stack.state(1).asInstanceOf[AnyRef] eq null, "the popped slot still holds its state")
            assert(stack.continuation(1) == null, "the popped slot still holds its continuation")
            assert(stack.handler(0) eq kept, "the entry still in scope was dropped")
            assert(stack.state(0).asInstanceOf[AnyRef] eq outer, "the entry still in scope lost its state")
        }
    }

    "find" - {
        "locates a handler by its tag" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == 0)
        }

        "misses on an unrelated tag" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[Say]) == -1)
        }

        "returns the innermost matching handler" in {
            val stack = new Stack
            val outer = askHandler
            val inner = askHandler
            stack.push(outer, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(inner, (), Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == 2)
            assert(stack.handler(2) eq inner)
        }

        "skips entries of other tags" in {
            val stack = new Stack
            val h     = askHandler
            stack.push(h, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == 0)
            assert(stack.handler(0) eq h)
        }

        "a subtype handler resolves a supertype suspension tag" in {
            val stack = new Stack
            stack.push(askSubHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == 0)
        }

        "a supertype handler does not resolve a subtype suspension tag" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[AskSub]) == -1)
        }

        "a popped handler is no longer found" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.pop()
            stack.push(sayHandler, (), Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == -1)
        }
    }

    "state" - {
        "an entry is pushed with its state" in {
            val stack = new Stack
            stack.push(statefulHandler, 7, Arrow.id[Int])
            assert(as[Int](stack.state(0)) == 7)
        }

        "setState round-trips at the entry's index" in {
            val stack = new Stack
            stack.push(statefulHandler, 0, Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.setState(0, 42)
            assert(as[Int](stack.state(0)) == 42)
            assert(as[Unit](stack.state(1)) == ())
        }

        "a re-pushed slot carries only the new entry's state" in {
            val stack = new Stack
            stack.push(statefulHandler, 7, Arrow.id[Int])
            stack.pop()
            stack.push(sayHandler, (), Arrow.id[Int])
            assert(as[Unit](stack.state(0)) == ())
        }
    }

    "truncate" - {
        "keeps the outermost entries and drops the rest" in {
            val stack = new Stack
            val outer = askHandler
            stack.push(outer, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.truncate(1)
            assert(stack.depth == 1)
            assert(stack.handler(0) eq outer)
            assert(stack.find(Tag[Say]) == -1)
        }

        "to the current depth keeps everything" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.truncate(1)
            assert(stack.depth == 1)
        }

        "to zero empties the stack" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.truncate(0)
            assert(stack.isEmpty)
            assert(stack.find(Tag[Ask]) == -1)
        }
    }

    "clear empties the stack and forgets what it owed" in {
        val stack = new Stack
        stack.push(askHandler, (), Arrow.id[Int])
        stack.push(sayHandler, (), Arrow.id[Int])
        discard(stack.dump(1))
        assert(stack.owesAny)
        stack.clear()
        assert(stack.isEmpty)
        assert(!stack.owesAny)
        assert(stack.find(Tag[Ask]) == -1)
        assert(stack.takeEvalOwed().isEmpty)
    }

    "dump" - {
        "takes the entries from the index up, outermost first, and owes them to the entry below" in {
            val stack = new Stack
            val a     = askHandler
            val b     = sayHandler
            val c     = statefulHandler
            val kb    = Arrow[Int](_ + 1)
            val kc    = Arrow[Int](_ + 2)
            stack.push(a, (), Arrow.id[Int])
            stack.push(b, (), kb)
            stack.push(c, 9, kc)
            val snapshot = stack.dump(1)
            assert(stack.depth == 1)
            assert(stack.handler(0) eq a)
            assert(snapshot.regions == 2)
            assert(snapshot.handler(0) eq b)
            assert(snapshot.continuation(0) eq kb)
            assert(snapshot.handler(1) eq c)
            assert(as[Int](snapshot.state(1)) == 9)
            assert(snapshot.continuation(1) eq kc)
            assert(stack.owesAny)
            val owed = stack.takeOwed(0).toIndexed
            assert(owed.length == 1)
            assert(owed(0).regions == 2)
            assert(owed(0).handler(1) eq c)
        }

        "the vacated slots hold nothing afterwards" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 5, Arrow.id[Int])
            discard(stack.dump(1))
            stack.push(sayHandler, (), Arrow.id[Int])
            assert(as[Unit](stack.state(1)) == ())
            assert(stack.find(Tag[Ask]) == 0)
        }

        "a dumped entry's own debts travel inside the snapshot" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val inner = stack.dump(2)
            val outer = stack.dump(1)
            assert(stack.depth == 1)
            assert(outer.regions == 1)
            val carried = outer.owed(0).toIndexed
            assert(carried.length == 1)
            assert(carried(0).regions == inner.regions)
            assert(carried(0).handler(0) eq inner.handler(0))
            assert(stack.takeOwed(0).toIndexed.length == 1)
        }

        "takeOwed empties the lane it reads" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            discard(stack.dump(1))
            assert(!stack.takeOwed(0).isEmpty)
            assert(stack.takeOwed(0).isEmpty)
        }

        "takePopped reads the lane of the entry just popped" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val snapshot = stack.dump(2)
            stack.pop()
            val popped = stack.takePopped().toIndexed
            assert(popped.length == 1)
            assert(popped(0).handler(0) eq snapshot.handler(0))
        }

        "owe appends to an entry's lane and oweBelow to the one under it" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val first = stack.dump(2)
            stack.push(statefulHandler, 2, Arrow.id[Int])
            val second = stack.dump(2)
            stack.owe(1, Chunk(first))
            stack.oweBelow(1, Chunk(second))
            val atOne  = stack.takeOwed(1).toIndexed
            val atZero = stack.takeOwed(0).toIndexed
            assert(atOne.length == 3)
            assert(as[Int](atOne(2).state(0)) == 1)
            assert(atZero.length == 1)
            assert(as[Int](atZero(0).state(0)) == 2)
        }

        "settle removes the debt a resumed dump left" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val snapshot = stack.dump(1)
            stack.settle(snapshot)
            assert(stack.takeOwed(0).isEmpty)
        }

        "settle removes the matching debt wherever it sits in the lane and leaves the others" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val first = stack.dump(1)
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val second = stack.dump(1)
            stack.settle(first)
            val left = stack.takeOwed(0).toIndexed
            assert(left.length == 1)
            assert(left(0).handler(0) eq second.handler(0))
        }

        "settle finds a debt owed below the top lane" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val snapshot = stack.dump(1)
            stack.push(statefulHandler, 1, Arrow.id[Int])
            stack.settle(snapshot)
            assert(stack.takeOwed(0).isEmpty)
            assert(stack.takeOwed(1).isEmpty)
        }

        "settle finds a debt owed on the eval's own lane" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val snapshot = stack.dump(1)
            stack.oweBelow(0, stack.takeOwed(0))
            stack.settle(snapshot)
            assert(stack.takeEvalOwed().isEmpty)
        }

        "settle of an unrelated snapshot is a no-op" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            discard(stack.dump(1))
            stack.settle(Stack.Snapshot.empty)
            assert(stack.takeOwed(0).toIndexed.length == 1)
        }

        "oweBelow at the bottom lands on the eval's own lane" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val snapshot = stack.dump(1)
            stack.oweBelow(0, Chunk(snapshot))
            assert(stack.owesAny)
            val owed = stack.takeEvalOwed().toIndexed
            assert(owed.length == 1)
            assert(owed(0).handler(0) eq snapshot.handler(0))
            assert(stack.takeEvalOwed().isEmpty)
        }
    }

    "snapshot" - {
        "moves every entry into a snapshot and empties the stack" in {
            val stack = new Stack
            val a     = askHandler
            val b     = statefulHandler
            val kb    = Arrow[Int](_ + 1)
            stack.push(a, (), Arrow.id[Int])
            stack.push(b, 3, kb)
            val snapshot = stack.takeAll()
            assert(stack.isEmpty)
            assert(snapshot.regions == 2)
            assert(snapshot.handler(0) eq a)
            assert(snapshot.handler(1) eq b)
            assert(as[Int](snapshot.state(1)) == 3)
            assert(snapshot.continuation(1) eq kb)
            assert(snapshot.owed(0).isEmpty)
            assert(snapshot.owed(1).isEmpty)
        }

        "carries each entry's debts with it" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val inner    = stack.dump(1)
            val snapshot = stack.takeAll()
            assert(snapshot.regions == 1)
            val carried = snapshot.owed(0).toIndexed
            assert(carried.length == 1)
            assert(carried(0).handler(0) eq inner.handler(0))
        }
    }

    "contextual" - {
        "keeps the context handlers, their state, and nothing else" in {
            val stack = new Stack
            val env   = envHandler
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(env, 5, Arrow[Int](_ + 1))
            stack.push(sayHandler, (), Arrow.id[Int])
            val snapshot = stack.contextual()
            assert(snapshot.regions == 1)
            assert(snapshot.handler(0) eq env)
            assert(as[Int](snapshot.state(0)) == 5)
            assert(snapshot.continuation(0).isInstanceOf[Arrow.Id[?]])
            assert(snapshot.owed(0).isEmpty)
        }

        "leaves the stack untouched" in {
            val stack = new Stack
            stack.push(envHandler, 5, Arrow.id[Int])
            stack.push(askHandler, (), Arrow.id[Int])
            discard(stack.contextual())
            assert(stack.depth == 2)
            assert(stack.find(Tag[Env]) == 0)
        }

        "of a stack without context handlers is empty" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            assert(stack.contextual().isEmpty)
        }
    }

    "growth beyond the initial capacity preserves entries, state, and debts" in {
        val stack  = new Stack
        val bottom = sayHandler
        stack.push(bottom, (), Arrow.id[Int])
        stack.push(statefulHandler, 5, Arrow.id[Int])
        discard(stack.dump(1))
        var i = 0
        while i < 100 do
            stack.push(statefulHandler, i, Arrow.id[Int])
            i += 1
        assert(stack.depth == 101)
        assert(as[Int](stack.state(50)) == 49)
        assert(stack.find(Tag[Ask]) == 100)
        assert(stack.find(Tag[Say]) == 0)
        assert(stack.takeOwed(0).toIndexed.length == 1)
        stack.truncate(1)
        assert(stack.depth == 1)
        assert(stack.handler(0) eq bottom)
    }

    "the pool" - {
        "hands out a stack and takes it back cleared" in {
            val stack = Stack.borrow()
            stack.push(askHandler, (), Arrow.id[Int])
            Stack.release(stack)
            val again = Stack.borrow()
            assert(again eq stack)
            assert(again.isEmpty)
            Stack.release(again)
        }

        "borrows are distinct while held" in {
            val a = Stack.borrow()
            val b = Stack.borrow()
            assert(a ne b)
            Stack.release(a)
            Stack.release(b)
        }
    }

end StackTest
