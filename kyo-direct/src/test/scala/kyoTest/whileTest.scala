package kyoTest

import kyo._

import kyo.direct._
import kyo.TestSupport._
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ArrayBuffer

class whileTest extends AnyFreeSpec with Assertions {

  "with atomic" in {
    runLiftTest(3) {
      val i = await(Atomics.initInt(0))
      while (await(i.get) < 3)
        await(i.incrementAndGet)
      await(i.get)
    }
  }
  "double in tuple - strange case" in {
    var i     = 0
    val buff1 = new ArrayBuffer[Int]()
    val buff2 = new ArrayBuffer[Int]()
    def incrementA() = {
      i += 1
      buff1 += i
      i
    }
    def incrementB() = {
      i += 1
      buff2 += i
      i
    }
    val out =
      defer {
        while (i < 3)
          (await(IOs(incrementA())), await(IOs(incrementB())))
        i
      }
    for {
      a <- out
    } yield (
        assert(a == 4 && buff1.toList == List(1, 3) && buff2.toList == List(2, 4))
    )
  }
}
