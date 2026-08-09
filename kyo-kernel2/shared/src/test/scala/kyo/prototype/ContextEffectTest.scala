package kyo.prototype

import kyo.Tag
import kyo.test.Test

class ContextEffectTest extends Test[Any]:

    sealed trait Cfg extends ContextEffect[Int]

    def read: Int < Cfg = ContextEffect.suspendWith(Tag[Cfg], 0)(identity)

    "reads the default without a handler" in {
        assert(read.asInstanceOf[Int < Any].eval == 0)
    }

    "a handler provides the value for its scope" in {
        val v = ContextEffect.handle(Tag[Cfg], 42)(read.map(_ + 1))
        assert(v.eval == 43)
    }

    "the innermost binding wins" in {
        val v = ContextEffect.handle(Tag[Cfg], 1)(
            ContextEffect.handle(Tag[Cfg], 2)(read).map(inner => read.map(outer => (inner, outer)))
        )
        assert(v.eval == (2, 1))
    }

    "the binding stays in force across steps" in {
        val v = ContextEffect.handle(Tag[Cfg], 7)(
            read.map(a => read.map(b => a + b))
        )
        assert(v.eval == 14)
    }
end ContextEffectTest
