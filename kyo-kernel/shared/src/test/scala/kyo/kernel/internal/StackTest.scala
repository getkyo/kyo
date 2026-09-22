package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ArrayBuffer

class StackTest extends AnyFreeSpec:

    private def as[A](v: Any): A = v.asInstanceOf[A]

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait Env    extends ContextEffect[Int]

    def askHandler: Handler.LoopHandler[Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopHandler[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                         = Tag[Ask]
            def run[X](input: Unit)         = Loop.continue(1)
            def onDone(state: Unit, v: Int) = v

    def askSubHandler: Handler.LoopHandler[Const[Unit], Const[Int], AskSub, Int, Int, Any] =
        new Handler.LoopHandler[Const[Unit], Const[Int], AskSub, Int, Int, Any]:
            def tag                         = Tag[AskSub]
            def run[X](input: Unit)         = Loop.continue(1)
            def onDone(state: Unit, v: Int) = v

    def sayHandler: Handler.LoopHandler[Const[String], Const[Unit], Say, Int, Int, Any] =
        new Handler.LoopHandler[Const[String], Const[Unit], Say, Int, Int, Any]:
            def tag                         = Tag[Say]
            def run[X](input: String)       = Loop.continue(())
            def onDone(state: Unit, v: Int) = v

    def statefulHandler: Handler.LoopStateHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any] =
        new Handler.LoopStateHandler[Int, Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag                             = Tag[Ask]
            def run[X](state: Int, input: Unit) = Loop.continue(state + 1, 1)
            def onDone(state: Int, v: Int)      = v + state

    def envHandler: Handler.ContextHandler[Int, Env, Int, Any] =
        new Handler.ContextHandler[Int, Env, Int, Any]:
            def tag                                            = Tag[Env]
            def derive(outer: Maybe[Int])                      = outer.getOrElse(0)
            def fork(parent: Int)                              = parent
            def join(parent: Int, forked: Int, child: Int)     = parent
            def release(state: Int, failure: Maybe[Throwable]) = ()

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
        stack.oweRelease(0, _ => ())
        assert(stack.owesAny)
        stack.clear()
        assert(stack.isEmpty)
        assert(!stack.owesAny)
        assert(stack.find(Tag[Ask]) == -1)
        assert(stack.takeEvalReleases().isEmpty)
    }

    "dump" - {
        "takes the entries from the index up, outermost first, and leaves the entry below" in {
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

        "moves the dumped entries' releases onto the entry below, innermost first, and none travel in the snapshot" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.oweRelease(1, _ => discard(ran += "say"))
            stack.oweRelease(2, _ => discard(ran += "state"))
            val snapshot = stack.dump(1)
            assert(snapshot.releases(0).isEmpty)
            assert(snapshot.releases(1).isEmpty)
            assert(stack.owesAny)
            stack.takeReleases(0).run(Absent)(_ => ())
            assert(ran == ArrayBuffer("state", "say"))
        }

        "a dumped entry's own releases move too, from an inner dump to the outer holder" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.oweRelease(2, _ => discard(ran += "state"))
            discard(stack.dump(2)) // entry 2's release moves to entry 1
            discard(stack.dump(1)) // entry 1, carrying it, moves to entry 0
            stack.takeReleases(0).run(Absent)(_ => ())
            assert(ran == ArrayBuffer("state"))
        }

        "takeReleases empties the lane it reads" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.oweRelease(1, _ => ())
            discard(stack.dump(1))
            assert(!stack.takeReleases(0).isEmpty)
            assert(stack.takeReleases(0).isEmpty)
        }

        "takePopped reads the lane of the entry just popped" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.oweRelease(1, _ => discard(ran += "say"))
            stack.pop()
            stack.takePopped().run(Absent)(_ => ())
            assert(ran == ArrayBuffer("say"))
        }

        "owe appends to an entry's lane and oweBelow to the one under it" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.owe(1, Stack.Releases(_ => discard(ran += "one")))
            stack.oweBelow(1, Stack.Releases(_ => discard(ran += "zero")))
            stack.takeReleases(1).run(Absent)(_ => ())
            assert(ran == ArrayBuffer("one"))
            stack.takeReleases(0).run(Absent)(_ => ())
            assert(ran == ArrayBuffer("one", "zero"))
        }

        "oweBelow at the bottom lands on the eval's own lane" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.oweBelow(0, Stack.Releases(_ => discard(ran += "eval")))
            assert(stack.owesAny)
            stack.takeEvalReleases().run(Absent)(_ => ())
            assert(ran == ArrayBuffer("eval"))
            assert(stack.takeEvalReleases().isEmpty)
        }

        "run hands a throwing release to onError and still runs the rest" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            val ran  = ArrayBuffer.empty[String]
            val errs = ArrayBuffer.empty[Throwable]
            stack.oweRelease(0, _ => discard(ran += "a"))
            stack.oweRelease(0, _ => throw new RuntimeException("boom"))
            stack.oweRelease(0, _ => discard(ran += "c"))
            stack.takeReleases(0).run(Absent)(errs += _)
            assert(ran == ArrayBuffer("c", "a"))
            assert(errs.length == 1)
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
            assert(snapshot.releases(0).isEmpty)
            assert(snapshot.releases(1).isEmpty)
        }

        "carries each entry's releases with it, unlike a dump" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            val ran = ArrayBuffer.empty[String]
            stack.oweRelease(1, _ => discard(ran += "say"))
            val snapshot = stack.takeAll()
            assert(snapshot.regions == 2)
            assert(snapshot.releases(0).isEmpty)
            snapshot.releases(1).run(Absent)(_ => ())
            assert(ran == ArrayBuffer("say"))
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
            assert(snapshot.releases(0).isEmpty)
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

    "growth beyond the initial capacity preserves entries, state, and releases" in {
        val stack  = new Stack
        val bottom = sayHandler
        stack.push(bottom, (), Arrow.id[Int])
        stack.push(statefulHandler, 5, Arrow.id[Int])
        val ran = ArrayBuffer.empty[String]
        stack.oweRelease(1, _ => discard(ran += "s"))
        discard(stack.dump(1)) // moves entry 1's release onto entry 0
        var i = 0
        while i < 100 do
            stack.push(statefulHandler, i, Arrow.id[Int])
            i += 1
        assert(stack.depth == 101)
        assert(as[Int](stack.state(50)) == 49)
        assert(stack.find(Tag[Ask]) == 100)
        assert(stack.find(Tag[Say]) == 0)
        stack.takeReleases(0).run(Absent)(_ => ())
        assert(ran == ArrayBuffer("s"))
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

        /* Disabled: these test the prior Stack internals (release/oweAll/oweBelow/hide/Gap/all/completing), which this Stack implementation does not expose.
        "clear empties the stack and forgets what it held" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.owe(1, release(log, "r"))
            stack.oweBelow(0, release(log, "e"))
            stack.clear()
            assert(stack.isEmpty)
            assert(stack.find(Tag[Ask]) == -1)
            assert(stack.releases(1) eq null)
            assert(stack.takeEvalReleases() eq null)
            assert(log.isEmpty)
        }

        "an entry starts holding nothing" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            assert(stack.releases(0) eq null)
        }

        "one release is held as itself, a second makes a chunk in order" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val r1    = release(log, "one")
            val r2    = release(log, "two")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.owe(0, r1)
            assert(stack.releases(0) eq r1)
            stack.owe(0, r2)
            assert(all(stack.releases(0)) == List(r1, r2))
        }

        "oweAll appends a whole list after what is held" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val r1    = release(log, "one")
            val r2    = release(log, "two")
            val r3    = release(log, "three")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.owe(0, r1)
            stack.oweAll(0, Chunk(r2, r3))
            assert(all(stack.releases(0)) == List(r1, r2, r3))
            stack.oweAll(0, null)
            assert(all(stack.releases(0)) == List(r1, r2, r3))
        }

        "oweBelow lands on the entry under the index, and on the evaluation's own list at the bottom" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val r1    = release(log, "one")
            val r2    = release(log, "two")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.oweBelow(1, r1)
            stack.oweBelow(0, r2)
            assert(stack.releases(0) eq r1)
            assert(stack.releases(1) eq null)
            assert(stack.takeEvalReleases() eq r2)
            assert(stack.takeEvalReleases() eq null)
        }

        "takeReleases empties the entry it reads" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val r     = release(log, "one")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.owe(0, r)
            assert(stack.takeReleases(0) eq r)
            assert(stack.takeReleases(0) eq null)
        }

        "a popped entry drops what it held" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.owe(0, release(log, "one"))
            stack.pop()
            assert(stack.releases(0) eq null)
            stack.push(sayHandler, (), Arrow.id[Int])
            assert(stack.releases(0) eq null)
        }

        "the pop runs nothing itself" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.owe(0, release(log, "one"))
            stack.pop()
            assert(log.isEmpty)
        }

        "takes the entries from the index up, outermost first, and moves what they held to the entry below" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val a     = askHandler
            val b     = sayHandler
            val c     = statefulHandler
            val kb    = Arrow[Int](_ + 1)
            val kc    = Arrow[Int](_ + 2)
            val rb    = release(log, "b")
            val rc    = release(log, "c")
            stack.push(a, (), Arrow.id[Int])
            stack.push(b, (), kb)
            stack.push(c, 9, kc)
            stack.owe(1, rb)
            stack.owe(2, rc)
            val snapshot = stack.dump(1, false)
            assert(stack.depth == 1)
            assert(stack.handler(0) eq a)
            assert(snapshot.regions == 2)
            assert(snapshot.handler(0) eq b)
            assert(snapshot.continuation(0) eq kb)
            assert(snapshot.releases(0) eq null)
            assert(snapshot.handler(1) eq c)
            assert(as[Int](snapshot.state(1)) == 9)
            assert(snapshot.continuation(1) eq kc)
            assert(snapshot.releases(1) eq null)
            assert(all(stack.releases(0)) == List(rb, rc))
            assert(log.isEmpty)
        }

        "what the entry below already held comes first" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val ra    = release(log, "a")
            val rb    = release(log, "b")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.owe(0, ra)
            stack.owe(1, rb)
            discard(stack.dump(1, false))
            assert(all(stack.releases(0)) == List(ra, rb))
        }

        "a kept dump leaves each region's releases in the snapshot as well" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val rb    = release(log, "b")
            val rc    = release(log, "c")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 9, Arrow.id[Int])
            stack.owe(1, rb)
            stack.owe(2, rc)
            val snapshot = stack.dump(1, true)
            assert(snapshot.releases(0) eq rb)
            assert(snapshot.releases(1) eq rc)
            assert(all(stack.releases(0)) == List(rb, rc))
            assert(log.isEmpty)
        }

        "pushes a gap that find steps over, together with the entries it hides" in {
            val stack = new Stack
            val a     = askHandler
            stack.push(a, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            stack.hide(1, Arrow.id[Int])
            assert(stack.depth == 4)
            assert(stack.handler(3) eq Handler.Gap)
            assert(stack.hidden(3) == 2)
            assert(stack.find(Tag[Ask]) == 0)
            assert(stack.handler(0) eq a)
            assert(stack.find(Tag[Say]) == -1)
        }

        "an entry pushed above the gap is found first" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.hide(0, Arrow.id[Int])
            val above = sayHandler
            stack.push(above, (), Arrow.id[Int])
            assert(stack.find(Tag[Say]) == 3)
            assert(stack.handler(3) eq above)
            assert(stack.find(Tag[Ask]) == -1)
        }

        "popping the gap puts what it hid back in reach" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.hide(0, Arrow.id[Int])
            stack.pop()
            assert(stack.find(Tag[Say]) == 1)
            assert(stack.find(Tag[Ask]) == 0)
        }

        "nested gaps are stepped over in turn" in {
            val stack = new Stack
            val below = askHandler
            stack.push(below, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.push(statefulHandler, 1, Arrow.id[Int])
            stack.hide(1, Arrow.id[Int])
            stack.push(envHandler, 5, Arrow.id[Int])
            val above = statefulHandler
            stack.push(above, 2, Arrow.id[Int])
            assert(stack.find(Tag[Ask]) == 5)
            stack.hide(5, Arrow.id[Int])
            assert(stack.depth == 7)
            assert(stack.hidden(6) == 1)
            assert(stack.find(Tag[Ask]) == 0)
            assert(stack.handler(0) eq below)
            assert(stack.find(Tag[Env]) == 4)
            assert(stack.find(Tag[Say]) == -1)
        }

        "the gap keeps the continuation it was pushed with" in {
            val stack = new Stack
            val k     = Arrow[Int](_ + 1)
            stack.push(askHandler, (), Arrow.id[Int])
            stack.hide(0, k)
            assert(stack.continuation(1) eq k)
        }

        "carries what each entry held with it, and leaves nothing behind" in {
            val log   = ListBuffer[String]()
            val stack = new Stack
            val r     = release(log, "one")
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.owe(0, r)
            val snapshot = stack.takeAll()
            assert(snapshot.regions == 2)
            assert(snapshot.releases(0) eq r)
            assert(snapshot.releases(1) eq null)
            assert(stack.releases(0) eq null)
            assert(log.isEmpty)
        }

        "a gap travels with the entries it hides" in {
            val stack = new Stack
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(sayHandler, (), Arrow.id[Int])
            stack.hide(1, Arrow.id[Int])
            val snapshot = stack.takeAll()
            assert(snapshot.regions == 3)
            assert(snapshot.handler(2) eq Handler.Gap)
            assert(as[Int](snapshot.state(2)) == 1)
        }

        "keeps the bindings in the order the stack holds them" in {
            val stack = new Stack
            val outer = envHandler
            val inner = envHandler
            stack.push(outer, 1, Arrow.id[Int])
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(inner, 2, Arrow.id[Int])
            val snapshot = stack.contextual()
            assert(snapshot.regions == 2)
            assert(snapshot.handler(0) eq outer)
            assert(as[Int](snapshot.state(0)) == 1)
            assert(snapshot.handler(1) eq inner)
            assert(as[Int](snapshot.state(1)) == 2)
        }

        "skips the bindings a gap hides and keeps those above and below it" in {
            val stack = new Stack
            val below = envHandler
            val above = envHandler
            stack.push(below, 5, Arrow.id[Int])
            stack.push(askHandler, (), Arrow.id[Int])
            stack.push(envHandler, 6, Arrow.id[Int])
            stack.hide(1, Arrow.id[Int])
            stack.push(above, 7, Arrow.id[Int])
            val snapshot = stack.contextual()
            assert(snapshot.regions == 2)
            assert(snapshot.handler(0) eq below)
            assert(as[Int](snapshot.state(0)) == 5)
            assert(snapshot.handler(1) eq above)
            assert(as[Int](snapshot.state(1)) == 7)
        }

        "growth beyond the initial capacity preserves entries, state, and what they hold" in {
            val log    = ListBuffer[String]()
            val stack  = new Stack
            val bottom = sayHandler
            val r      = release(log, "one")
            stack.push(bottom, (), Arrow.id[Int])
            stack.owe(0, r)
            var i = 0
            while i < 100 do
                stack.push(statefulHandler, i, Arrow.id[Int])
                i += 1
            assert(stack.depth == 101)
            assert(as[Int](stack.state(50)) == 49)
            assert(stack.find(Tag[Ask]) == 100)
            assert(stack.find(Tag[Say]) == 0)
            assert(stack.releases(0) eq r)
            while stack.depth > 1 do stack.pop()
            assert(stack.handler(0) eq bottom)
            assert(stack.releases(0) eq r)
        }
         */

    }

end StackTest
