package kyoTest.concurrent

import kyoTest.KyoTest
import kyo.concurrent.latches._
import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios._

class latchesTest extends KyoTest {

  def run(v: Unit > (IOs | Fibers)) =
    IOs.run(Fibers.block(v))
    Fibers.block(IOs.lazyRun(v))

  "countDown + await" in run {
    for {
      latch <- Latch(1)
      _     <- latch.release
      _     <- latch.await
    } yield ()
  }

  "countDown(2) + await" in run {
    for {
      latch <- Latch(2)
      _     <- latch.release
      _     <- latch.release
      _     <- latch.await
    } yield ()
  }

  "countDown + fibers + await" in run {
    for {
      latch <- Latch(1)
      _     <- Fibers.fork(latch.release)
      _     <- latch.await
    } yield ()
  }

  "countDown(2) + fibers + await" in run {
    for {
      latch <- Latch(2)
      _     <- Fibers.fork(latch.release, latch.release)
      _     <- latch.await
    } yield ()
  }
}
