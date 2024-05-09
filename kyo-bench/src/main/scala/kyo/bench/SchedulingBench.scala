package kyo.bench

class SchedulingBench extends Bench.ForkOnly[Int]:

    val depth          = 1000
    val range          = List.range(0, depth)
    val expectedResult = 1001000

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

        def fiber(i: Int): Int < IOs =
            IOs.unit.flatMap { _ =>
                IOs(i).flatMap { j =>
                    IOs.unit.flatMap { _ =>
                        if j > depth then
                            IOs.unit.flatMap(_ => IOs(j))
                        else
                            IOs.unit.flatMap(_ => fiber(j + 1))
                    }
                }
            }

        Seqs.map(range) { i =>
            Fibers.init(fiber(i))
        }.map { fibers =>
            Seqs.map(fibers)(_.get)
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
