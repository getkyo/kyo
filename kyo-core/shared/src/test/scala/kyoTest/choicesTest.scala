package kyoTest

import kyo.concurrent.fibers._
import kyo._
import kyo.ios.IOs
import kyo.choices._

class choicesTest extends KyoTest {

  "one" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices.foreach(List(1)).map(_ + 1)),
        List(2)
    )
  }
  "multiple" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices.foreach(List(1, 2, 3)).map(_ + 1)),
        List(2, 3, 4)
    )
  }
  "nested" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices.foreach(List(1, 2, 3)).map(i => Choices.foreach(List(i * 10, i * 100)))),
        List(10, 100, 20, 200, 30, 300)
    )
  }
  "drop" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices.foreach(List(1, 2, 3)).map(i =>
          if (i < 2) Choices.drop
          else Choices.foreach(List(i * 10, i * 100))
        )),
        List(20, 200, 30, 300)
    )
  }
  "filter" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices.foreach(List(1, 2, 3)).map(i =>
          Choices.dropIf(i >= 2).map(_ => Choices.foreach(List(i * 10, i * 100)))
        )),
        List(20, 200, 30, 300)
    )
  }
}
