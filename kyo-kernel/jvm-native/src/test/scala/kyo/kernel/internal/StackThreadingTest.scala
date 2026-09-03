package kyo.kernel.internal

import org.scalatest.freespec.AnyFreeSpec

class StackThreadingTest extends AnyFreeSpec:

    "borrow is thread local" in {
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
end StackThreadingTest
