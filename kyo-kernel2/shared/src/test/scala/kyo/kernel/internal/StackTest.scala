package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class StackTest extends AnyFreeSpec:

    given Frame = Frame.internal

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

    private val Reach = Safepoint.period() / 2

    def transform(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame                                              = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + 1))

    def askHandler: Handler.HandlerLoop[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.HandlerLoop[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def frame                  = Frame.internal
            def tag                    = Tag[Ask]
            def run[X](input: Unit)    = Loop.continue(1: Int < Any)
            override def apply(a: Int) = a

    def askSubHandler: Handler.HandlerLoop[Const[Unit], Const[Int], AskSub, Int, Int, Any] =
        new Handler.HandlerLoop[Const[Unit], Const[Int], AskSub, Int, Int, Any]:
            def frame                  = Frame.internal
            def tag                    = Tag[AskSub]
            def run[X](input: Unit)    = Loop.continue(1: Int < Any)
            override def apply(a: Int) = a

    def sayHandler: Handler.HandlerLoop[Const[String], Const[Unit], Say, Int, Int, Any] =
        new Handler.HandlerLoop[Const[String], Const[Unit], Say, Int, Int, Any]:
            def frame                  = Frame.internal
            def tag                    = Tag[Say]
            def run[X](input: String)  = Loop.continue((): Unit < Any)
            override def apply(a: Int) = a

    def statefulHandler(init: Int): Handler.HandlerLoopState[Const[Unit], Const[Int], Ask, Int, Int, Any, Int] =
        new Handler.HandlerLoopState[Const[Unit], Const[Int], Ask, Int, Int, Any, Int]:
            def frame                           = Frame.internal
            def tag                             = Tag[Ask]
            def initialState                    = init
            def run[X](state: Int, input: Unit) = Loop.continue(state + 1, 1: Int < Any)
            def apply(state: Int, a: Int)       = a + state
    end statefulHandler

    "starts empty" in {
        val stack = new Stack
        assert(stack.isEmpty)
        assert(stack.size == 0)
    }

    "push and pop" - {
        "are LIFO, index zero innermost" in {
            val stack = new Stack
            val a     = transform
            val b     = askHandler
            stack.push(a)
            stack.push(b)
            assert(stack.size == 2)
            assert(stack.pop() eq b)
            assert(stack.pop() eq a)
            assert(stack.isEmpty)
        }

        "pushing identity is a no-op" in {
            val stack = new Stack
            stack.push(Arrow.id[Int])
            assert(stack.isEmpty)
        }

        "a chain is flattened into one entry per link" in {
            val stack = new Stack
            val a     = transform
            val b     = transform
            stack.push(a.chain(b))
            assert(stack.size == 2)
            assert(stack.pop() eq a)
            assert(stack.pop() eq b)
        }

        "a right-nested chain is flattened fully" in {
            val stack = new Stack
            val a     = transform
            val b     = transform
            val c     = transform
            stack.push(a.chain(b.chain(c)))
            assert(stack.size == 3)
            assert(stack.pop() eq a)
            assert(stack.pop() eq b)
            assert(stack.pop() eq c)
        }

        // a chain never survives as an entry, whichever way it nests. The entries are what dump folds back
        // into an arrow, and a chain among them puts a chain on that arrow's left, where applying it defers
        // instead of running the transform
        "a left-nested chain is flattened fully" in {
            val stack = new Stack
            val a     = transform
            val b     = transform
            val c     = transform
            stack.push(a.chain(b).chain(c))
            assert(stack.size == 3)
            assert(stack.pop() eq a)
            assert(stack.pop() eq b)
            assert(stack.pop() eq c)
        }

        "identity links inside a chain are skipped" in {
            val stack = new Stack
            val a     = transform
            stack.push(new Arrow.Chain(Arrow.id[Int], a))
            assert(stack.size == 1)
            assert(stack.pop() eq a)
        }
    }

    "find" - {
        "locates a handler by its tag" in {
            val stack = new Stack
            stack.push(askHandler)
            assert(stack.find(Tag[Ask]) == 0)
        }

        "misses on an unrelated tag" in {
            val stack = new Stack
            stack.push(askHandler)
            assert(stack.find(Tag[Say]) == -1)
        }

        "returns the innermost matching handler" in {
            val stack = new Stack
            val outer = askHandler
            val inner = askHandler
            stack.push(outer)
            stack.push(transform)
            stack.push(inner)
            assert(stack.find(Tag[Ask]) == 0)
            assert(stack.handler(0) eq inner)
        }

        "skips non-handler entries" in {
            val stack = new Stack
            val h     = askHandler
            stack.push(h)
            stack.push(transform)
            stack.push(transform)
            assert(stack.find(Tag[Ask]) == 2)
            assert(stack.handler(2) eq h)
        }

        // the handler stands below the operation, which is the same direction the row demands: handleCont
        // takes `v: A < (E & S)`, and a contravariant row accepts that only where `E` is under the effect
        // the computation names
        "a subtype handler resolves a supertype suspension tag" in {
            val stack = new Stack
            stack.push(askSubHandler)
            assert(stack.find(Tag[Ask]) == 0)
        }

        "a supertype handler does not resolve a subtype suspension tag" in {
            val stack = new Stack
            stack.push(askHandler)
            assert(stack.find(Tag[AskSub]) == -1)
        }

        "an empty stack misses" in {
            val stack = new Stack
            assert(stack.find(Tag[Ask]) == -1)
        }

        "a popped handler is no longer found" in {
            val stack = new Stack
            stack.push(askHandler)
            stack.pop()
            stack.push(transform)
            assert(stack.find(Tag[Ask]) == -1)
        }
    }

    "state" - {
        "a stateful handler is seeded with its initial state on push" in {
            val stack = new Stack
            stack.push(statefulHandler(7))
            assert(stack.state[Int](0) == Maybe(7))
        }

        "a plain entry carries no state" in {
            val stack = new Stack
            stack.push(transform)
            assert(stack.state[Int](0).isEmpty)
        }

        "putState round-trips at the entry's index" in {
            val stack = new Stack
            stack.push(statefulHandler(0))
            stack.push(transform)
            stack.putState(1, 42)
            assert(stack.state[Int](1) == Maybe(42))
        }

        "pop clears the state of the vacated slot" in {
            val stack = new Stack
            stack.push(statefulHandler(7))
            stack.pop()
            stack.push(transform)
            assert(stack.state[Int](0).isEmpty)
        }
    }

    "truncate" - {
        "drops the innermost entries" in {
            val stack = new Stack
            val outer = transform
            stack.push(outer)
            stack.push(transform)
            stack.push(transform)
            stack.truncate(2)
            assert(stack.size == 1)
            assert(stack.pop() eq outer)
        }

        "of zero keeps everything" in {
            val stack = new Stack
            stack.push(transform)
            stack.truncate(0)
            assert(stack.size == 1)
        }

        "past the bottom stops at empty" in {
            val stack = new Stack
            stack.push(transform)
            stack.truncate(10)
            assert(stack.isEmpty)
        }
    }

    "clear empties the stack" in {
        val stack = new Stack
        stack.push(askHandler)
        stack.push(transform)
        stack.clear()
        assert(stack.isEmpty)
        assert(stack.find(Tag[Ask]) == -1)
    }

    "dump" - {
        "folds the entries below the position, innermost first" in {
            val stack = new Stack
            val log   = List.newBuilder[String]
            def note(name: String)(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
                new Arrow.Transform[Int, Int, Any]:
                    def frame = _frame
                    def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                        v.map { i =>
                            log += name
                            next(i)
                        }
            stack.push(note("outer"))
            stack.push(note("inner"))
            val k = stack.dump[Int, Int, Any](2)
            assert(stack.isEmpty)
            assert(k(0).eval == 0)
            assert(log.result() == List("inner", "outer"))
        }

        "consumes exactly the requested entries" in {
            val stack = new Stack
            val outer = transform
            stack.push(outer)
            stack.push(transform)
            stack.push(transform)
            discard(stack.dump[Int, Int, Any](2))
            assert(stack.size == 1)
            assert(stack.pop() eq outer)
        }

        "of nothing is the identity arrow" in {
            val stack = new Stack
            stack.push(transform)
            assert(stack.dump[Int, Int, Any](0) eq Arrow.id[Int])
            assert(stack.size == 1)
        }

        "composes the captured transforms in order" in {
            val stack = new Stack
            def add(n: Int)(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
                new Arrow.Transform[Int, Int, Any]:
                    def frame                                              = _frame
                    def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + n))
            stack.push(add(100))
            stack.push(add(10))
            stack.push(add(1))
            val k = stack.dump[Int, Int, Any](3)
            assert(k(0).eval == 111)
        }

        "the captured arrow is multi-shot" in {
            val stack = new Stack
            stack.push(transform)
            stack.push(transform)
            val k = stack.dump[Int, Int, Any](2)
            assert(k(0).eval == 2)
            assert(k(0).eval == 2)
            assert(k(10).eval == 12)
        }

        // a handler-free capture is still wrapped, but a wrap is not a way back onto the stack as one entry:
        // pushing it flattens it link by link, so the stack it returns to holds what it held before
        "a handler-free capture is wrapped and pushes back link by link" in {
            val stack = new Stack
            (0 until 5).foreach(_ => stack.push(transform))
            val k     = stack.dump[Int, Int, Any](5)
            val other = new Stack
            other.push(k)
            assert(other.size == 5)
            assert(k(0).eval == 5)
        }

        "a capture holding a handler is left normalized" in {
            val stack = new Stack
            stack.push(transform)
            stack.push(askHandler)
            stack.push(transform)
            stack.push(transform)
            stack.push(transform)
            val k     = stack.dump[Int, Int, Any](5)
            val other = new Stack
            other.push(k)
            assert(other.size == 5)
        }

        "a stateful handler is captured with the state it carried" in {
            val stack = new Stack
            stack.push(statefulHandler(0))
            stack.putState(0, 9)
            val k = stack.dump[Int, Int, Any](1)
            assert(k(1).eval == 10)
        }
    }

    "dump of the tail" - {
        "stops at the innermost handler" in {
            val stack = new Stack
            val h     = askHandler
            stack.push(h)
            stack.push(transform)
            stack.push(transform)
            stack.push(transform)
            discard(stack.dump[Int, Int, Any]())
            assert(stack.size == 1)
            assert(stack.pop() eq h)
        }

        "takes the whole stack when no handler is below" in {
            val stack = new Stack
            stack.push(transform)
            stack.push(transform)
            val k = stack.dump[Int, Int, Any]()
            assert(stack.isEmpty)
            assert(k(0).eval == 2)
        }

        "caps at half the safepoint period" in {
            val stack = new Stack
            (0 until Reach + 44).foreach(_ => stack.push(transform))
            discard(stack.dump[Int, Int, Any]())
            assert(stack.size == 44)
        }

        "leaves a capture that pushes back link by link" in {
            val stack = new Stack
            (0 until 5).foreach(_ => stack.push(transform))
            val k     = stack.dump[Int, Int, Any]()
            val other = new Stack
            other.push(k)
            assert(other.size == 5)
        }
    }

    "growth beyond the initial capacity preserves entries and state" in {
        val stack  = new Stack
        val bottom = transform
        stack.push(bottom)
        stack.push(statefulHandler(5))
        (0 until 100).foreach(_ => stack.push(transform))
        assert(stack.size == 102)
        assert(stack.find(Tag[Ask]) == 100)
        assert(stack.state[Int](100) == Maybe(5))
        stack.truncate(101)
        assert(stack.size == 1)
        assert(stack.pop() eq bottom)
    }

    "the pool" - {
        "hands out a stack and takes it back" in {
            val stack = Stack.borrow()
            stack.push(transform)
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

        "is thread local" in {
            // the Out cell and the ring ownership story assume a stack never migrates threads: a
            // borrow on another thread must never hand out an instance this thread released
            val a = Stack.borrow()
            Stack.release(a)
            @volatile var other: AnyRef = null
            val t = new Thread(() =>
                val b = Stack.borrow()
                other = b
                Stack.release(b)
            )
            t.start()
            t.join(10000)
            assert(other ne null)
            assert(other ne a)
            val again = Stack.borrow()
            assert(again eq a)
            Stack.release(again)
        }
    }

    "finalizers" - {
        def finalizer(ran: () => Unit)(using frame: Frame) =
            new Finalizer[Unit, Any]((_, _) => ran(), (), frame)

        // what a release is told where the extent it belonged to never ended
        val abandoned = Result.panic[Nothing, Any](Finalizer.Abandoned)

        "hold until drained" in {
            val s = Stack.borrow()
            var n = 0
            s.pushFinalizer(finalizer(() => n += 1))
            s.pushFinalizer(finalizer(() => n += 1))
            assert(s.outstanding == 2)
            assert(n == 0)
            s.drainFinalizers(Maybe.empty)
            assert(n == 2)
            assert(s.outstanding == 0)
            Stack.release(s)
        }

        "drain innermost first" in {
            val s     = Stack.borrow()
            var order = List.empty[String]
            s.pushFinalizer(finalizer(() => order :+= "outer"))
            s.pushFinalizer(finalizer(() => order :+= "inner"))
            s.drainFinalizers(Maybe.empty)
            assert(order == List("inner", "outer"))
            Stack.release(s)
        }

        "one that already ran is a no-op at the drain" in {
            val s = Stack.borrow()
            var n = 0
            val f = finalizer(() => n += 1)
            s.pushFinalizer(f)
            f.run(abandoned)
            assert(n == 1)
            s.drainFinalizers(Maybe.empty)
            assert(n == 1)
            Stack.release(s)
        }

        // an eval that brackets one resource after another must not keep an entry per bracket once each has
        // released, or a long eval accumulates dead entries for its whole length
        "a run of released finalizers does not accumulate" in {
            val s = Stack.borrow()
            var i = 0
            while i < 100 do
                val f = finalizer(() => ())
                s.pushFinalizer(f)
                f.run(abandoned)
                assert(s.outstanding == 1)
                i += 1
            end while
            Stack.release(s)
        }

        "an outstanding finalizer is not dropped by a later push" in {
            val s    = Stack.borrow()
            val held = finalizer(() => ())
            s.pushFinalizer(held)
            val done = finalizer(() => ())
            s.pushFinalizer(done)
            done.run(abandoned)
            s.pushFinalizer(finalizer(() => ()))
            // the released one in the middle is gone, the one still owed is not
            assert(s.outstanding == 2)
            Stack.release(s)
        }

        "a release that throws does not stop the rest, and surfaces when the eval is not already failing" in {
            val s   = Stack.borrow()
            var ran = List.empty[String]
            s.pushFinalizer(finalizer(() => ran :+= "outer"))
            s.pushFinalizer(finalizer(() => throw new IllegalStateException("inner")))
            val message =
                try
                    s.drainFinalizers(Maybe.empty)
                    Maybe.empty[String]
                catch case ex: IllegalStateException => Maybe(ex.getMessage)
            assert(message == Maybe("inner"))
            assert(ran == List("outer"))
            Stack.release(s)
        }

        "a release that throws is suppressed onto the eval's own failure" in {
            val s       = Stack.borrow()
            val failure = new UnsupportedOperationException("body")
            s.pushFinalizer(finalizer(() => throw new IllegalStateException("release")))
            s.drainFinalizers(Maybe(failure))
            assert(failure.getSuppressed.toList.map(_.getMessage) == List("release"))
            Stack.release(s)
        }

        "clear forgets them, since the stack is pooled" in {
            val s = Stack.borrow()
            var n = 0
            s.pushFinalizer(finalizer(() => n += 1))
            s.clear()
            assert(s.outstanding == 0)
            s.drainFinalizers(Maybe.empty)
            assert(n == 0)
            Stack.release(s)
        }
    }

end StackTest
