package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

@Variant("Coll", "List")
class CollShiftMethodSupportTest extends AnyFreeSpec with Assertions:

    @Variant("Seq[X]", "List[X]")
    type Coll[X] = Seq[X]

    @Variant("x.toSeq", "x.toList")
    def Coll[X](x: X*): Coll[X] = x.toSeq

    @Variant("seq", "list")
    val testName = "seq"

    testName - {
        def xs: Coll[Int < Any] = Coll(1, 2, 3, 4)

        def xsValues: Coll[Int] = Coll(1, 2, 3, 4)

        "collectFirst" in {
            val d: Option[Int] < Any = direct:
                xs.collectFirst:
                    case i => i.now
            d.map(res => assert(res == Option(1)))
        }
    }
end CollShiftMethodSupportTest
