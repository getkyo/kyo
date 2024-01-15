package kyoTest

import kyo.fibers._
import kyo.latches._
import kyo._
import kyo.ios._

class latchesTest extends KyoTest {

  "zero" in run {
    for {
      latch <- Latches.init(0)
      _     <- latch.release
      _     <- latch.await
    } yield succeed
  }

  "countDown + await" in run {
    for {
      latch <- Latches.init(1)
      _     <- latch.release
      _     <- latch.await
    } yield succeed
  }

  "countDown(2) + await" in run {
    for {
      latch <- Latches.init(2)
      _     <- latch.release
      _     <- latch.release
      _     <- latch.await
    } yield succeed
  }

  "countDown + fibers + await" in runJVM {
    for {
      latch <- Latches.init(1)
      _     <- Fibers.init(latch.release)
      _     <- latch.await
    } yield succeed
  }

  "countDown(2) + fibers + await" in runJVM {
    for {
      latch <- Latches.init(2)
      _     <- Fibers.parallel(latch.release, latch.release)
      _     <- latch.await
    } yield succeed
  }

  "contention" in runJVM {
    for {
      latch <- Latches.init(1000)
      _     <- Fibers.parallelFiber(List.fill(1000)(latch.release))
      _     <- latch.await
    } yield succeed
  }
}
