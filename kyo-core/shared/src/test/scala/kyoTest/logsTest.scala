package kyoTest

import kyo._
import kyo.envs._
import kyo.ios._
import kyo.logs._

class logsTest extends KyoTest {

  "log" in run {
    val ex = new Exception
    for {
      _ <- Logs.trace("trace")
      _ <- Logs.debug("debug")
      _ <- Logs.info("info")
      _ <- Logs.warn("warn")
      _ <- Logs.error("error")
      _ <- Logs.trace("trace", ex)
      _ <- Logs.debug("debug", ex)
      _ <- Logs.info("info", ex)
      _ <- Logs.warn("warn", ex)
      _ <- Logs.error("error", ex)
    } yield succeed
  }

  "unsafe" in {
    val ex = new Exception
    Logs.unsafe.trace("trace")
    Logs.unsafe.debug("debug")
    Logs.unsafe.info("info")
    Logs.unsafe.warn("warn")
    Logs.unsafe.error("error")
    Logs.unsafe.trace("trace", ex)
    Logs.unsafe.debug("debug", ex)
    Logs.unsafe.info("info", ex)
    Logs.unsafe.warn("warn", ex)
    Logs.unsafe.error("error", ex)
    succeed
  }
}
