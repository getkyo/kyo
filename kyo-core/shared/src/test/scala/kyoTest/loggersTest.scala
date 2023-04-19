package kyoTest

import kyo._
import kyo.envs._
import kyo.ios._
import kyo.loggers._

class loggersTest extends KyoTest {

  "log" in run {
    val logger = Loggers.init("test")
    for {
      _ <- logger.debug("debug")
      _ <- logger.info("info")
      _ <- logger.warn("warn")
      _ <- logger.error("error")
    } yield succeed
  }
}
