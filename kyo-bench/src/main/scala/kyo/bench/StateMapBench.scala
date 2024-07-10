package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class StateMapBench extends Bench.SyncAndFork(1000):

    val n = 1000

    def catsBench() =
        import cats.*
        import cats.effect.*
        import cats.mtl.*
        import cats.implicits.*
        import cats.data.*

        def program[F[_]: Monad](using S: Stateful[F, Int]): F[Int] =
            S.get.flatMap { n =>
                if n <= 0
                then n.pure
                else S.set(n - 1).flatMap(_ => program).map(_ + 1)
            }

        program[StateT[IO, Int, *]].run(n).map(_._2)
    end catsBench

    def kyoBench() =
        import kyo.*

        def program: Int < Vars[Int] =
            Vars.use[Int] { n =>
                if n <= 0
                then n
                else Vars.set(n - 1).flatMap(_ => program).map(_ + 1)
            }

        Vars.run(n)(program)
    end kyoBench

    def zioBench() =
        import zio.*

        def program: ZIO[ZState[Int], Nothing, Int] =
            ZIO.getState[Int].flatMap { n =>
                if n <= 0
                then ZIO.succeed(n)
                else ZIO.setState(n - 1).flatMap(_ => program).map(_ + 1)
            }

        ZIO.stateful(n)(program)
    end zioBench
end StateMapBench
