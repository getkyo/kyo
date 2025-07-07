package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

object OverloadedTest:
    enum A:
        case B, C

    var faCall = 0

    def f(a: A): Unit =
        faCall += 1

    var fbCall = 0

    @targetName("fB")
    def f(b: A.B.type): Unit =
        fbCall += 1

end OverloadedTest

class OverloadedTest extends AnyFreeSpec with Assertions:

    import OverloadedTest.*
    "basic" in {
        assert((faCall, fbCall) == (0, 0))

        f(A.B)
        f(A.B: A)

        assert((faCall, fbCall) == (1, 1))

        Overloaded.resolveEach(A.B, A.C, A.B: A)(a => f(a))

        assert((faCall, fbCall) == (3, 2))

    }
end OverloadedTest
