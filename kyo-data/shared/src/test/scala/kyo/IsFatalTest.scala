package kyo

class IsFatalTest extends kyo.test.Test[Any]:

    "fatal" - {
        // Only what says the process itself is finished. `VirtualMachineError`'s four subclasses are named
        // one by one rather than through the parent, so a change to the set has to be made here too.
        "OutOfMemoryError" in assert(IsFatal(new OutOfMemoryError("boom")))
        "StackOverflowError" in assert(IsFatal(new StackOverflowError("boom")))
        "InternalError" in assert(IsFatal(new InternalError("boom")))
        "UnknownError" in assert(IsFatal(new UnknownError("boom")))

        // Another library's non-local return passing through, never ours to absorb.
        "ControlThrowable" in {
            val ex = new scala.util.control.ControlThrowable("boom") {}
            assert(IsFatal(ex))
        }
    }

    "not fatal" - {
        // The two kyo answers differently from `scala.util.control.NonFatal`, and the reason this object
        // exists. A class that fails to link says the program is wrong, not that the JVM is; an interrupt is
        // something kyo delivers itself, by design. Under Scala's answer both reach the path that ends a
        // scheduler worker and loses every release the computation still owed.
        "LinkageError, which Scala calls fatal" in {
            val ex = new LinkageError("boom")
            assert(!IsFatal(ex))
            assert(!scala.util.control.NonFatal(ex), "the premise is that Scala disagrees")
        }

        "InterruptedException, which Scala calls fatal" in {
            val ex = new InterruptedException("boom")
            assert(!IsFatal(ex))
            assert(!scala.util.control.NonFatal(ex), "the premise is that Scala disagrees")
        }

        "an ordinary exception" in assert(!IsFatal(new RuntimeException("boom")))
        "a checked exception" in assert(!IsFatal(new java.io.IOException("boom")))
        "an Error that is not a VirtualMachineError" in assert(!IsFatal(new AssertionError("boom")))
        "a bare Throwable" in assert(!IsFatal(new Throwable("boom")))
    }

end IsFatalTest
