package kyoTest

import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios.IOs
import kyo.lists._

class choicesTest extends KyoTest {

  "one" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists(1)(_ + 1)),
        List(2)
    )
  }
  "multiple" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists(1, 2, 3)(_ + 1)),
        List(2, 3, 4)
    )
  }
  "nested" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists(1, 2, 3)(i => Lists(i * 10, i * 100))),
        List(10, 100, 20, 200, 30, 300)
    )
  }
  "drop" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists(1, 2, 3)(i => if (i < 2) Lists.drop else Lists(i * 10, i * 100))),
        List(20, 200, 30, 300)
    )
  }
  "filter" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists(1, 2, 3)(i => Lists.filter(i >= 2)(_ => Lists(i * 10, i * 100)))),
        List(20, 200, 30, 300)
    )
  }
  "with fibers" in {
    val t = Thread.currentThread()
    val io: Int > (Lists | IOs | Fibers) =
      Lists(1, 2, 3) { i =>
        Fibers.fork {
          assert(Thread.currentThread() != t)
          i + 1
        }
      }
    val b: List[Int] > (IOs | Fibers) =
      Lists.run(io)
    val c: List[Int] =
      IOs.lazyRun(Fibers.block(b))
    assert(c == List(2, 3, 4))
  }
}
