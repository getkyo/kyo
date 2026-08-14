package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class StackTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask    extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait AskSub extends Ask
    sealed trait Say    extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def askRegion: Stack.Entry =
        ArrowEffect.handleLoop(Tag[Ask], ask)([X] => _ => Loop.continue(1)) match
            case kyo: Kyo[?, ?] => kyo
            case v              => fail(s"expected a region node, got $v")

    def arrow: Stack.Entry = Arrow[Int]

    "starts empty" in {
        val stack = new Stack
        assert(stack.size == 0)
    }

    "push and pop are LIFO" in {
        val stack = new Stack
        val a     = arrow
        val b     = askRegion
        stack.push(a)
        stack.push(b)
        assert(stack.size == 2)
        assert(stack.pop() eq b)
        assert(stack.pop() eq a)
        assert(stack.size == 0)
    }

    "apply reads an entry without removing it" in {
        val stack = new Stack
        val a     = arrow
        stack.push(a)
        assert(stack(0) eq a)
        assert(stack.size == 1)
    }

    "a tagged entry is found by its tag" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        assert(stack.find(Tag[Ask].erased, 0) == 0)
    }

    "find misses on an unrelated tag" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        assert(stack.find(Tag[Say].erased, 0) == -1)
    }

    "find returns the innermost matching entry" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        stack.push(arrow)
        stack.push(askRegion, Tag[Ask].erased)
        assert(stack.find(Tag[Ask].erased, 0) == 2)
    }

    "a subtype suspension tag resolves a supertype entry" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        assert(stack.find(Tag[AskSub].erased, 0) == 0)
    }

    "a supertype suspension tag does not resolve a subtype entry" in {
        val stack = new Stack
        stack.push(askRegion, Tag[AskSub].erased)
        assert(stack.find(Tag[Ask].erased, 0) == -1)
    }

    "plain pushes are invisible to find" in {
        val stack = new Stack
        stack.push(arrow)
        stack.push(arrow)
        assert(stack.find(Tag[Ask].erased, 0) == -1)
    }

    "find does not look below the base" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        val base = stack.size
        stack.push(arrow)
        assert(stack.find(Tag[Ask].erased, base) == -1)
        assert(stack.find(Tag[Ask].erased, 0) == 0)
    }

    "pop clears the tag of the vacated slot" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        stack.pop()
        stack.push(arrow)
        assert(stack.find(Tag[Ask].erased, 0) == -1)
    }

    "truncate drops the entries above the mark" in {
        val stack = new Stack
        stack.push(arrow)
        stack.push(askRegion, Tag[Ask].erased)
        stack.push(arrow)
        stack.truncate(1)
        assert(stack.size == 1)
        assert(stack.find(Tag[Ask].erased, 0) == -1)
    }

    "truncate to the current size keeps everything" in {
        val stack = new Stack
        stack.push(askRegion, Tag[Ask].erased)
        stack.truncate(stack.size)
        assert(stack.size == 1)
        assert(stack.find(Tag[Ask].erased, 0) == 0)
    }

    "copyFrom copies the segment above the index in order" in {
        val stack = new Stack
        val a     = arrow
        val b     = askRegion
        val c     = arrow
        stack.push(a)
        stack.push(b)
        stack.push(c)
        val seg = stack.copyFrom(1)
        assert(seg.length == 2)
        assert(seg(0) eq b)
        assert(seg(1) eq c)
        assert(stack.size == 3)
    }

    "growth beyond the initial capacity preserves entries and tags" in {
        val stack   = new Stack
        val entries = (0 until 100).map(_ => arrow)
        entries.foreach(stack.push)
        stack.push(askRegion, Tag[Ask].erased)
        assert(stack.size == 101)
        assert(stack.find(Tag[Ask].erased, 0) == 100)
        (0 until 100).foreach(i => assert(stack(i) eq entries(i)))
    }

    "current returns the same stack on the same thread" in {
        assert(Stack.current() eq Stack.current())
    }

end StackTest
