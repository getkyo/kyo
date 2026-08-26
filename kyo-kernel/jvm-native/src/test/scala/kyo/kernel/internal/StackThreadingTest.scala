package kyo.kernel.internal

/** The borrow-pool ownership test that needs a second real thread; the cross-platform Stack
  * coverage stays in the shared StackTest.
  */
class StackThreadingTest extends kyo.Test:

    "borrow is thread local" in {
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
end StackThreadingTest
