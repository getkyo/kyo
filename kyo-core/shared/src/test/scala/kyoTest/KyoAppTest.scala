package kyoTest

import kyo.*
import scala.concurrent.duration.*

class KyoAppTest extends KyoTest:

    "effects" in {
        def run: Int < KyoApp.Effects =
            for
                _ <- Timers.scheduleAtFixedRate(1.second, 1.second)(())
                i <- Randoms.nextInt
                _ <- Consoles.println(s"$i")
                _ <- Clocks.now
                _ <- Resources.ensure(())
                _ <- Fibers.init(())
            yield 1
        assert(KyoApp.trying(Duration.Inf)(run) == scala.util.Success(1))
    }
    "failing effects" in {
        def run: Unit < KyoApp.Effects =
            for
                _ <- Clocks.now
                _ <- Randoms.nextInt
                _ <- Aborts[Throwable].fail(new RuntimeException("FAILED!"))
            yield ()
        assert(KyoApp.trying(Duration.Inf)(run).isFailure)
    }
end KyoAppTest
