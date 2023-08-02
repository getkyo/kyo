package kyoTest.concurrent

import kyo.concurrent.fibers._
import kyo.concurrent.latches._
import kyo._
import kyo.ios._
import kyoTest.KyoTest

class latchesTest extends KyoTest {

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
      _     <- Fibers.fork(latch.release)
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
}
