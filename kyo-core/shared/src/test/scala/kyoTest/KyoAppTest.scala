package kyoTest

import kyo.clocks._
import kyo.concurrent.fibers._
import kyo.concurrent.timers._
import kyo.consoles._
import kyo._
import kyo.ios._
import kyo.randoms._
import kyo.resources._
import kyo.apps._

import scala.concurrent.duration._

class AppTest extends KyoTest {

  "args" in {
    var _args: List[String] = Nil
    val app = new App {
      def run = IOs {
        _args = args
      }
    }
    app.main(Array("hello", "world"))
    assert(_args == List("hello", "world"))
  }

  "effects" in run {
    val app = new App {
      def run =
        for {
          _ <- Timers.scheduleAtFixedRate(1.second, 1.second)(())
          _ <- Randoms.nextInt
          _ <- Consoles.println("1")
          _ <- Clocks.now
          _ <- Resources.ensure(())
          _ <- Fibers.init(())
        } yield ()
    }
    app.main(Array())
    succeed
  }
}
