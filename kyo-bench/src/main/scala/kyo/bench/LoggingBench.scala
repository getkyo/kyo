package kyo.bench

class LoggingBench extends Bench.SyncAndFork[Unit] {

  val depth = 10000

  def kyoBench() = {
    import kyo._

    def loop(i: Int): Unit < IOs =
      if (i > depth)
        ()
      else
        Logs.error("test").flatMap { _ =>
          loop(i + 1)
        }
    loop(0)
  }

  def catsBench() = {
    import cats.effect._
    import org.typelevel.log4cats.slf4j.Slf4jLogger
    import cats.effect.unsafe.implicits.global

    val logger = Slf4jLogger.create[IO].unsafeRunSync()

    def loop(i: Int): IO[Unit] =
      if (i > depth)
        IO.unit
      else
        logger.error("test").flatMap { _ =>
          loop(i + 1)
        }
    loop(0)
  }

  def zioBench() = {
    import zio._
    import zio.logging.backend.SLF4J

    def loop(i: Int): UIO[Unit] =
      if (i > depth)
        ZIO.unit
      else
        ZIO.logError("test").flatMap { _ =>
          loop(i + 1)
        }
    loop(0).provide(Runtime.removeDefaultLoggers)
  }
}
