package kyo.bench.arena

class LoggingBench extends ArenaBench.SyncAndFork(()):

    val depth = 10000

    def kyoBench() =
        import kyo.*

        def loop(i: Int): Unit < Sync =
            if i > depth then
                ()
            else
                Log.error("test").flatMap { _ =>
                    loop(i + 1)
                }
        loop(0)
    end kyoBench

    def catsBench() =
        import cats.effect.*
        import org.typelevel.log4cats.slf4j.Slf4jLogger
        import cats.effect.unsafe.implicits.global

        val logger = Slf4jLogger.create[IO].unsafeRunSync()

        def loop(i: Int): IO[Unit] =
            if i > depth then
                IO.unit
            else
                logger.error("test").flatMap { _ =>
                    loop(i + 1)
                }
        loop(0)
    end catsBench

    def zioBench() =
        import zio.*

        def loop(i: Int): UIO[Unit] =
            if i > depth then
                ZIO.unit
            else
                ZIO.logError("test").flatMap { _ =>
                    loop(i + 1)
                }
        loop(0).provide(Runtime.removeDefaultLoggers)
    end zioBench
end LoggingBench
