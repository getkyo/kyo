package kyo.kernel.internal

import kyo.*
import kyo.kernel.*

class ContextTest extends Test:

    sealed trait TestEffect1 extends ContextEffect[Int]
    sealed trait TestEffect2 extends ContextEffect[String]

    "empty" - {
        "should be empty" in {
            assert(Context.empty.isEmpty)
        }

        "should not contain any tags" in {
            assert(!Context.empty.contains(Tag[TestEffect1]))
            assert(!Context.empty.contains(Tag[TestEffect2]))
        }
    }

    "contains" - {
        "should return true for contained tags" in {
            val context = Context.empty.set(Tag[TestEffect1], 42)
            assert(context.contains(Tag[TestEffect1]))
        }

        "should return false for non-contained tags" in {
            val context = Context.empty.set(Tag[TestEffect1], 42)
            assert(!context.contains(Tag[TestEffect2]))
        }
    }

    "getOrElse" - {
        "should return value for contained tags" in {
            val context = Context.empty.set(Tag[TestEffect1], 42)
            assert(context.getOrElse(Tag[TestEffect1], 0) == 42)
        }

        "should return default for non-contained tags" in {
            val context = Context.empty.set(Tag[TestEffect1], 42)
            assert(context.getOrElse(Tag[TestEffect2], "default") == "default")
        }
    }

    "set" - {
        "should add new values" in {
            val context = Context.empty.set(Tag[TestEffect1], 42)
            assert(context.getOrElse(Tag[TestEffect1], 0) == 42)
        }

        "should update existing values" in {
            val context = Context.empty.set(Tag[TestEffect1], 42).set(Tag[TestEffect1], 24)
            assert(context.getOrElse(Tag[TestEffect1], 0) == 24)
        }
    }

    "multiple effects" in {
        val context = Context.empty
            .set(Tag[TestEffect1], 42)
            .set(Tag[TestEffect2], "test")

        assert(context.getOrElse(Tag[TestEffect1], 0) == 42)
        assert(context.getOrElse(Tag[TestEffect2], "") == "test")
    }

    "inherit" - {
        sealed trait NonIsolatedEffect extends ContextEffect[String]
        sealed trait IsolatedEffect    extends ContextEffect[String] with ContextEffect.Isolated

        "should keep non-isolated effects" in {
            val context = Context.empty
                .set(Tag[NonIsolatedEffect], "test")
                .set(Tag[TestEffect1], 42)

            val inherited = context.inherit

            assert(inherited.contains(Tag[NonIsolatedEffect]))
            assert(inherited.contains(Tag[TestEffect1]))
            assert(inherited.getOrElse(Tag[NonIsolatedEffect], "") == "test")
            assert(inherited.getOrElse(Tag[TestEffect1], 0) == 42)
        }

        "should remove isolated effects" in {
            val context = Context.empty
                .set(Tag[IsolatedEffect], 100)
                .set(Tag[TestEffect1], 42)

            val inherited = context.inherit

            assert(!inherited.contains(Tag[IsolatedEffect]))
            assert(inherited.contains(Tag[TestEffect1]))
        }
    }

end ContextTest
