package kyo.kernel2

import kyo.Maybe
import kyo.Tag
import kyo.test.Test
import language.implicitConversions

sealed trait InternalAsk extends ArrowEffect[Const[Unit], Const[Int]]

class PendingInternalTest extends Test[Any]:

    "evalNow returns the value of a completed computation" in {
        assert((42: Int < Any).evalNow == Maybe(42))
    }

    "evalNow is absent for a suspended computation" in {
        val v = ArrowEffect.suspend[Any](Tag[InternalAsk], ())
        assert(v.evalNow == Maybe.empty)
    }

    "evalNow unwraps a lifted computation value" in {
        val inner: Int < Any          = 1
        val nested: (Int < Any) < Any = Kyo.lift(inner)
        assert(nested.evalNow.map(_.eval) == Maybe(1))
    }

    "unsafeGet returns the completed value" in {
        assert((5: Int < Any).unsafeGet == 5)
        val nested: (Int < Any) < Any = Kyo.lift(1: Int < Any)
        assert(nested.unsafeGet.eval == 1)
    }
end PendingInternalTest
