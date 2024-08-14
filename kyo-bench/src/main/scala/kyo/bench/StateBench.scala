package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class StateBench extends Bench.SyncAndFork(0):

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
                else S.set(n - 1).flatMap(_ => program)
            }

        program[StateT[IO, Int, *]].run(n).map(_._2)
    end catsBench

    def kyoBench() =
        import kyo.*

        def program: Int < Var[Int] =
            Var.use[Int] { n =>
                if n <= 0
                then n
                else Var.set(n - 1).flatMap(_ => program)
            }

        Var.run(n)(program)
    end kyoBench

    def zioBench() =
        import zio.*

        def program: ZIO[ZState[Int], Nothing, Int] =
            ZIO.getState[Int].flatMap { n =>
                if n <= 0
                then ZIO.succeed(n)
                else ZIO.setState(n - 1).flatMap(_ => program)
            }

        ZIO.stateful(n)(program)
    end zioBench
end StateBench
