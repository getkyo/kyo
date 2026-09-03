package kyo.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.kernel.ContextEffect
import org.scalatest.freespec.AnyFreeSpec

class ContextTest extends AnyFreeSpec:

    sealed trait TestEffect1 extends ContextEffect[Int]
    sealed trait TestEffect2 extends ContextEffect[String]
    sealed trait Base        extends ContextEffect[Int]
    sealed trait Sub         extends Base

    "empty reads nothing" in {
        assert(Context.empty.get(Tag[TestEffect1]).isEmpty)
        assert(Context.empty.get(Tag[TestEffect2]).isEmpty)
    }

    "bind" - {
        "reads back the bound value" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
        }

        "reads nothing for an unbound tag" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42)
            assert(context.get(Tag[TestEffect2]).isEmpty)
        }

        "a second binding of the same tag shadows the first" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42).bind(Tag[TestEffect1], 24)
            assert(context.get(Tag[TestEffect1]) == Maybe(24))
        }

        "bindings of different tags do not interfere" in {
            val context = Context.empty.bind(Tag[TestEffect1], 42).bind(Tag[TestEffect2], "test")
            assert(context.get(Tag[TestEffect1]) == Maybe(42))
            assert(context.get(Tag[TestEffect2]) == Maybe("test"))
        }
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

        "of the empty context fails" in {
            assert(intercept[Throwable](Context.empty.unbind).getMessage.contains("empty context"))
        }
    }

end ContextTest
