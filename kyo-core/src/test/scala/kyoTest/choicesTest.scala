package kyoTest

import kyo.core._
import kyo.choices._

class choicesTest extends KyoTest {

  "one" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices(1)(_ + 1)),
        List(2)
    )
  }
  "multiple" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices(1, 2, 3)(_ + 1)),
        List(2, 3, 4)
    )
  }
  "nested" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices(1, 2, 3)(i => Choices(i * 10, i * 100))),
        List(10, 100, 20, 200, 30, 300)
    )
  }
  "drop" in {
    checkEquals[List[Int], Nothing](
        Choices.run(Choices(1, 2, 3)(i => if (i < 2) Choices.drop else Choices(i * 10, i * 100))),
        List(20, 200, 30, 300)
    )
  }
}
