package kyoTest

import kyo.core._
import kyo.ios._
import kyo.concurrent.timers._
import kyo.randoms._
import kyo.consoles._
import kyo.clocks._
import kyo.scopes._
import kyo.concurrent.fibers._
import scala.concurrent.duration._

class AppTest extends KyoTest {

  "args" in {
    var args: List[String] = Nil
    val app: kyo.App = (a: List[String]) => {
      args = a
      ()
    }
    app.main(Array("hello", "world"))
    assert(args == List("hello", "world"))
  }

  "effects" in {
    val app: kyo.App = (a: List[String]) => {
      for {
        _ <- Timers.scheduleAtFixedRate((), 1.second, 1.second)
        _ <- Randoms.nextInt
        _ <- Consoles.println("1")
        _ <- Clocks.now
        _ <- Scopes.ensure(())
        _ <- Fibers.fork(())
      } yield ()
    }
    app.main(Array())
  }
}
