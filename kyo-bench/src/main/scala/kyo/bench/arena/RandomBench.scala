package kyo.bench.arena

class RandomBench extends ArenaBench.SyncAndFork(()):

    val depth = 10000

    def kyoBench() =
        import kyo.*

        def rand() =
            for
                _ <- Random.nextInt
                _ <- Random.nextLong
                _ <- Random.nextBoolean
                _ <- Random.nextFloat
            yield ()

        def loop(i: Int): Unit < Sync =
            if i > depth then
                ()
            else
                rand().flatMap { _ =>
                    loop(i + 1)
                }
        loop(0)
    end kyoBench

    def catsBench() =
        import cats.effect.*
        import cats.effect.std.Random
        import cats.effect.unsafe.implicits.global

        val random = Random.javaUtilRandom[IO](new java.util.Random).unsafeRunSync()

        def rand() =
            for
                _ <- random.nextInt
                _ <- random.nextLong
                _ <- random.nextBoolean
                _ <- random.nextFloat
            yield ()

        def loop(i: Int): IO[Unit] =
            if i > depth then
                IO.unit
            else
                rand().flatMap { _ =>
                    loop(i + 1)
                }
        loop(0)
    end catsBench

    def zioBench() =
        import zio.*

        def rand() =
            for
                _ <- Random.nextInt
                _ <- Random.nextLong
                _ <- Random.nextBoolean
                _ <- Random.nextFloat
            yield ()

        def loop(i: Int): UIO[Unit] =
            if i > depth then
                ZIO.unit
            else
                rand().flatMap { _ =>
                    loop(i + 1)
                }
        loop(0)
    end zioBench
end RandomBench
