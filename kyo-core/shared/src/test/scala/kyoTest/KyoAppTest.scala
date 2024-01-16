package kyoTest

import kyo._

import scala.concurrent.duration._

class KyoAppTest extends KyoTest {

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
