package kyoTest

import kyo._
import kyo.envs._
import kyo.ios._
import kyo.loggers._

class loggersTest extends KyoTest {

  "log" in run {
    val logger = Loggers.init("test")
    val ex     = new Exception
    for {
      _ <- logger.trace("trace")
      _ <- logger.debug("debug")
      _ <- logger.info("info")
      _ <- logger.warn("warn")
      _ <- logger.error("error")
      _ <- logger.trace("trace", ex)
      _ <- logger.debug("debug", ex)
      _ <- logger.info("info", ex)
      _ <- logger.warn("warn", ex)
      _ <- logger.error("error", ex)
    } yield succeed
  }
}
