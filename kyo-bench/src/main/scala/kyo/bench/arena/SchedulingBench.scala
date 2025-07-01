package kyo.bench.arena

class SchedulingBench extends ArenaBench.ForkOnly(1001000):

    val depth = 1000
    val range = List.range(0, depth)

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*

        def fiber(i: Int): IO[Int] =
            IO.cede.flatMap { _ =>
                IO(i).flatMap { j =>
                    IO.cede.flatMap { _ =>
                        if j > depth then
                            IO.cede.flatMap(_ => IO(j))
                        else
                            IO.cede.flatMap(_ => fiber(j + 1))
                    }
                }
            }

        range.traverse(fiber(_).start)
            .flatMap(_.traverse(_.joinWithNever))
            .map(_.sum)
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        def fiber(i: Int): Int < Sync =
            Kyo.unit.flatMap { _ =>
                Sync(i).flatMap { j =>
                    Kyo.unit.flatMap { _ =>
                        if j > depth then
                            Kyo.unit.flatMap(_ => Sync(j))
                        else
                            Kyo.unit.flatMap(_ => fiber(j + 1))
                    }
                }
            }

        Kyo.foreach(range) { i =>
            Async.run(fiber(i))
        }.map { fibers =>
            Kyo.foreach(fibers)(_.get)
        }.map(_.sum)
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        def fiber(i: Int): UIO[Int] =
            ZIO.yieldNow.flatMap { _ =>
                ZIO.succeed(i).flatMap { j =>
                    ZIO.yieldNow.flatMap { _ =>
                        if j > depth then
                            ZIO.yieldNow.flatMap(_ => ZIO.succeed(j))
                        else
                            ZIO.yieldNow.flatMap(_ => fiber(j + 1))
                    }
                }
            }

        ZIO.foreach(range) { n =>
            fiber(n).forkDaemon
        }.flatMap { list =>
            ZIO.foreach(list)(_.join)
        }.map(_.sum)
    end zioBench
end SchedulingBench
