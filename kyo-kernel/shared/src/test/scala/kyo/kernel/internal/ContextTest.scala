package kyo.kernel.internal

import kyo.*
import kyo.kernel.*

class ContextTest extends Test:

    sealed trait TestEffect1 extends ContextEffect[Int]
    sealed trait TestEffect2 extends ContextEffect[String]
    sealed trait Base        extends ContextEffect[Int]
    sealed trait Sub         extends Base

    "empty" - {
        "should be empty" in {
            // Diverges from main: Context has no isEmpty predicate here; the empty context is the
            // one with no binding to unbind.
            assert(intercept[Throwable](Context.empty.unbind).getMessage.contains("empty context"))
        }

        "should not contain any tags" in {
            assert(Context.empty.get(Tag[TestEffect1]).isEmpty)
            assert(Context.empty.get(Tag[TestEffect2]).isEmpty)
        }
    }

    "contains" - {
        "should return true for contained tags" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]).isDefined)
        }

        "should return false for non-contained tags" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect2]).isEmpty)
        }
    }

    "getOrElse" - {
        "should return value for contained tags" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]).getOrElse(0) == 42)
        }

        "should return default for non-contained tags" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect2]).getOrElse("default") == "default")
        }
    }

    "set" - {
        "should add new values" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
        }

        "should update existing values" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42).bind(Tag[TestEffect1], 24)
            assert(context.get(Tag[TestEffect1]) == Maybe(24))
        }
    }

    "multiple effects" in {
        val context = Context.empty
            .bind(Tag[TestEffect1], 42)
            .bind(Tag[TestEffect2], "test")

        assert(context.get(Tag[TestEffect1]).getOrElse(0) == 42)
        assert(context.get(Tag[TestEffect2]).getOrElse("") == "test")
    }

    "a read at a supertype" - {
        "takes an inner subtype binding over an outer exact one" in {
            val context = Context.empty.bind(Tag[Base], 1).bind(Tag[Sub], 2)
            assert(context.get(Tag[Base]) == Maybe(2))
        }

        "takes an inner exact binding over an outer subtype one" in {
            val context = Context.empty.bind(Tag[Sub], 1).bind(Tag[Base], 2)
            assert(context.get(Tag[Base]) == Maybe(2))
        }

        "at the subtype ignores a supertype binding" in {
            val context = Context.empty.bind(Tag[Base], 1)
            assert(context.get(Tag[Sub]).isEmpty)
        }
    }

    "unbind" - {
        "drops the innermost binding and uncovers the one below" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42).bind(Tag[TestEffect1], 24).unbind
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
            assert(context.unbind.get(Tag[TestEffect1]).isEmpty)
        }

        "drops bindings in entry order" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42).bind(Tag[TestEffect2], "test")
            assert(context.unbind.get(Tag[TestEffect2]).isEmpty)
            assert(context.unbind.get(Tag[TestEffect1]) == Maybe(42))
        }
    }

end ContextTest
