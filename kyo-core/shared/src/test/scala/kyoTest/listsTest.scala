package kyoTest

import kyo.concurrent.fibers._
import kyo._
import kyo.ios.IOs
import kyo.lists._

class listsTest extends KyoTest {

  "one" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists.foreach(List(1)).map(_ + 1)),
        List(2)
    )
  }
  "multiple" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists.foreach(List(1, 2, 3)).map(_ + 1)),
        List(2, 3, 4)
    )
  }
  "nested" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists.foreach(List(1, 2, 3)).map(i => Lists.foreach(List(i * 10, i * 100)))),
        List(10, 100, 20, 200, 30, 300)
    )
  }
  "drop" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists.foreach(List(1, 2, 3)).map(i =>
          if (i < 2) Lists.drop
          else Lists.foreach(List(i * 10, i * 100))
        )),
        List(20, 200, 30, 300)
    )
  }
  "filter" in {
    checkEquals[List[Int], Nothing](
        Lists.run(Lists.foreach(List(1, 2, 3)).map(i =>
          Lists.filter(i >= 2).map(_ => Lists.foreach(List(i * 10, i * 100)))
        )),
        List(20, 200, 30, 300)
    )
  }
}
