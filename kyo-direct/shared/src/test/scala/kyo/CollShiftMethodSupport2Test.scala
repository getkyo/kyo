package kyo

@TestVariant("Coll", "List")
class CollShiftMethodSupport2Test extends Test:

    @TestVariant("Seq[X]", "List[X]")
    type Coll[X] = Seq[X]

    @TestVariant("x.toSeq", "x.toList")
    def Coll[X](x: X*): Coll[X] = x.toSeq

    // @TestVariant("seq", "list")
    "seq" - {
        def xs: Coll[Int < Any] = Coll(1, 2, 3, 4)

        def xsValues: Coll[Int] = Coll(1, 2, 3, 4)

        "collectFirst" in run {
            val d: Option[Int] < Any =
                direct:
                    xs.collectFirst:
                        case i => i.now

            d.map(res => assert(res == Option(1)))
        }
    }
    //  @TestVariant("Coll", "List")
end CollShiftMethodSupport2Test
