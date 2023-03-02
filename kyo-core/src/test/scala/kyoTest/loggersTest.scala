package kyoTest

class loggersTest extends KyoTest {

  "log" in {
    import kyo._
    import kyo.loggers._
    import kyo.ios._
    import kyo.envs._

    val logger = Loggers.make("test")
    val io = for {
      _ <- logger.debug("debug")
      _ <- logger.info("info")
      _ <- logger.warn("warn")
      _ <- logger.error("error")
    } yield ()

    IOs.run(io)
  }
}
