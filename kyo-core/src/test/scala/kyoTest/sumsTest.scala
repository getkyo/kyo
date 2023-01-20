package kyoTest

import kyo.core._
import kyo.sums._
import kyo.ios._
import scala.util.Success
import scala.util.Try

class sumsTest extends KyoTest {
  "int" in {
    val v: List[Int] > Sums[Int] =
      for {
        _  <- Sums.add(1)
        v1 <- Sums.get[Int]
        _  <- Sums.add(1)
        v2 <- Sums.get[Int]
        _  <- Sums.add(1)
        v3 <- Sums.get[Int]
      } yield List(v1, v2, v3)

    assert(IOs.run(Sums.drop[Int](v)) == List(1, 2, 3))
  }
  "string" in {
    val v: List[String] > Sums[String] =
      for {
        _  <- Sums.add("1")
        v1 <- Sums.get[String]
        _  <- Sums.add("2")
        v2 <- Sums.get[String]
        _  <- Sums.add("3")
        v3 <- Sums.get[String]
      } yield List(v1, v2, v3)
    val res = IOs.run(Sums.drop[String](v))
    assert(res == List("1", "12", "123"))
  }
  "int and string" in {
    val v: (Int, String) > (Sums[Int] | Sums[String]) =
      for {
        _  <- Sums.add(1)
        _  <- Sums.add("1")
        _  <- Sums.add(1)
        _  <- Sums.add("2")
        _  <- Sums.add(1)
        _  <- Sums.add("3")
        v1 <- Sums.get[Int]
        v2 <- Sums.get[String]
      } yield (v1, v2)
    val res: (Int, String) =
      IOs.run(Sums.drop[String](Sums.drop[Int](v)))
    assert(res == (3, "123"))
  }
  "initial value" in {
    val r: Int =
      IOs.run(Sums.drop[Int](Sums.get[Int]))
    assert(r == 0)

    val s: String =
      IOs.run(Sums.drop[String](Sums.get[String]))
    assert(s == "")
  }
}
