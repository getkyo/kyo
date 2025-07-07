package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.targetName

object OverloadedTest:
    enum A:
        case B, C

    var faCall = 0

    def f(a: A) =
        faCall += 1
        println("hello A")

    var fbCall = 0

    @targetName("fB")
    def f(b: A.B.type) =
        fbCall += 1
        println("hello B")
    end f
end OverloadedTest

class OverloadedTest extends AnyFreeSpec with Assertions:

    import OverloadedTest.*

    "hello" in {

        assert((faCall, fbCall) == (0, 0))

        f(A.B)
        f(A.B: A)

        assert((faCall, fbCall) == (1, 1))

        Overloaded.resolveEach(A.B, A.C, A.B: A)(a => f(a))

        assert((faCall, fbCall) == (3, 2))

    }
end OverloadedTest
