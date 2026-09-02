package kyo.proto.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.proto.kernel.ContextEffect
import org.scalatest.freespec.AnyFreeSpec

class ContextTest extends AnyFreeSpec:

    sealed trait TestEffect1 extends ContextEffect[Int]
    sealed trait TestEffect2 extends ContextEffect[String]

    "empty" - {
        "contains no tag" in {
            assert(!Context.empty.contains(Tag[TestEffect1]))
            assert(!Context.empty.contains(Tag[TestEffect2]))
        }

        "gets nothing" in {
            assert(Context.empty.get(Tag[TestEffect1]).isEmpty)
        }
    }

    "contains" - {
        "is true for a bound tag" in {
            val context = Context.empty.update(Tag[TestEffect1], 42)
            assert(context.contains(Tag[TestEffect1]))
        }

        "is false for an unbound tag" in {
            val context = Context.empty.update(Tag[TestEffect1], 42)
            assert(!context.contains(Tag[TestEffect2]))
        }
    }

    "get" - {
        "returns the bound value" in {
            val context = Context.empty.update(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
        }

        "is empty for an unbound tag" in {
            val context = Context.empty.update(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect2]).isEmpty)
        }
    }

    "apply returns the bound value" in {
        val context = Context.empty.update(Tag[TestEffect1], 42)
        assert(context(Tag[TestEffect1]) == 42)
    }

    "update" - {
        "adds a new binding" in {
            val context = Context.empty.update(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
        }

        "replaces an existing binding" in {
            val context = Context.empty.update(Tag[TestEffect1], 42).update(Tag[TestEffect1], 24)
            assert(context.get(Tag[TestEffect1]) == Maybe(24))
        }

        "through the erased tag binds the same slot" in {
            val context = Context.empty.updateErased(Tag[TestEffect1], 7)
            assert(context.get(Tag[TestEffect1]) == Maybe(7))
        }
    }

    "remove" - {
        "drops the binding" in {
            val context = Context.empty.update(Tag[TestEffect1], 42).remove(Tag[TestEffect1])
            assert(!context.contains(Tag[TestEffect1]))
            assert(context.get(Tag[TestEffect1]).isEmpty)
        }

        "leaves the other bindings" in {
            val context = Context.empty
                .update(Tag[TestEffect1], 42)
                .update(Tag[TestEffect2], "test")
                .remove(Tag[TestEffect1])
            assert(context.get(Tag[TestEffect2]) == Maybe("test"))
        }

        "of an unbound tag changes nothing" in {
            val context = Context.empty.update(Tag[TestEffect1], 42).remove(Tag[TestEffect2])
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
            assert(!context.contains(Tag[TestEffect2]))
        }
    }

    "multiple effects" in {
        val context = Context.empty
            .update(Tag[TestEffect1], 42)
            .update(Tag[TestEffect2], "test")
        assert(context.get(Tag[TestEffect1]) == Maybe(42))
        assert(context.get(Tag[TestEffect2]) == Maybe("test"))
    }

end ContextTest
