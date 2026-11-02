package kyo.lang

import kyo.*
import kyolang.*

/** Closed data definitions and contextual instances over the `Shapes.kyo` fixture: exact exported signatures and behavior. */
class BodyShapeTest extends kyo.test.Test[Any]:

    private def run[A](value: A < Var[Int], initial: Int = 1): A = Var.run(initial)(value).eval

    "O02 final case classes" in {
        Signatures.exactIn[Shapes.Point < Var[Int]]("kyolang.Shapes", "Point", "scaled")
        val point = Shapes.Point(2, 3)
        assert(point.sum == 5)
        assert(point.moved(1) == Shapes.Point(3, 3))
        assert(Shapes.Point(4) == Shapes.Point(4, 0))
        assert(run(point.scaled, 10) == Shapes.Point(20, 30))
        assert(point.copy(y = 7) == Shapes.Point(2, 7))
    }
    "D15, O03, O05 a sealed hierarchy" in {
        Signatures.exact[List[Int] < Var[Int]]("kyolang.Shapes", "areas")
        Signatures.exact[Int < Var[Int]]("kyolang.Shapes", "total")
        val shapes: List[Shapes.Shape] = List(Shapes.Square(2), Shapes.Rectangle(2, 3), Shapes.Dot)
        assert(run(Shapes.areas(shapes), 1) == List(5, 7, 1))
        assert(shapes.map(_.describe) == List("area 4", "area 6", "area 0"))
        assert(run(Shapes.total(Shapes.Square(3))) == 9)
        assert(run(Shapes.total(Shapes.Dot), 8) == 8)
    }
    "O03 enums" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Shapes", "code")
        Signatures.exact[Int]("kyolang.Shapes", "colors")
        assert(run(Shapes.code(Shapes.Color.Red)) == 1)
        assert(run(Shapes.code(Shapes.Color.Custom(40)), 2) == 42)
        assert(Shapes.colors == 1)
        assert(Shapes.Color.fromOrdinal(0) == Shapes.Color.Red)
    }
    "O04 constructor parameters and parent arguments" in {
        Signatures.exact[String]("kyolang.Shapes", "named")
        assert(Shapes.named == "TAG")
    }
    "C02 givens" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Shapes", "summoned")
        assert(run(Shapes.summoned, 1) == 4)
        assert(List(Shapes.Point(3, 3), Shapes.Point(1, 1)).sorted(using Shapes.given_Ordering_Point[Int]) ==
            List(Shapes.Point(1, 1), Shapes.Point(3, 3)))
    }
    "C06 conversions" in {
        Signatures.exact[Int < Var[Int]]("kyolang.Shapes", "converted")
        assert(run(Shapes.converted, 4) == 8)
    }
    "M05 derived instances" in {
        Signatures.exact[Boolean < Var[Int]]("kyolang.Shapes", "equalPairs")
        assert(run(Shapes.equalPairs, 2))
        assert(!run(Shapes.equalPairs, 3))
        val evidence: CanEqual[Shapes.Pair, Shapes.Pair] = summon[CanEqual[Shapes.Pair, Shapes.Pair]]
        assert(evidence != null)
    }
end BodyShapeTest
