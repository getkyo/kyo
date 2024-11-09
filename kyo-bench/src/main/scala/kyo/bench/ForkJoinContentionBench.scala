package kyo.bench

import org.openjdk.jmh.annotations.*

class ForkJoinContentionBench extends Bench.ForkOnly(()):

    val depth     = 1000
    val parallism = Runtime.getRuntime().availableProcessors()
    val range     = (0 until depth).toList

    def catsBench() =
        import cats.*
        import cats.effect.*
        import cats.implicits.*

        val forkFiber         = IO.unit.start
        val forkAllFibers     = Traverse[List].traverse(range)(_ => forkFiber)
        val forkJoinAllFibers = forkAllFibers.flatMap(fibers => Traverse[List].traverse(fibers)(_.join).void)

        Seq.fill(parallism)(forkJoinAllFibers).parSequence.void
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        val forkFiber         = Async.run(())
        val forkAllFibers     = Kyo.foreach(range)(_ => forkFiber)
        val forkJoinAllFibers = forkAllFibers.flatMap(fibers => Kyo.foreach(fibers)(_.get).unit)

        Async.parallelUnbounded(Seq.fill(parallism)(forkJoinAllFibers)).unit
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        val forkFiber         = ZIO.unit.forkDaemon
        val forkAllFibers     = ZIO.foreach(range)(_ => forkFiber)
        val forkJoinAllFibers = forkAllFibers.flatMap(fibers => ZIO.foreach(fibers)(_.await).unit)

        ZIO.collectAll(Seq.fill(parallism)(forkJoinAllFibers.forkDaemon)).flatMap(ZIO.foreach(_)(_.join)).unit
    end zioBench

    @Benchmark
    def forkOx() =
        import ox.*
        scoped {
            val fibers =
                for (_ <- 0 until parallism) yield fork {
                    val forkAllFibers =
                        for (_ <- range) yield fork(())
                    for fiber <- forkAllFibers do
                        fiber.join()
                }
            fibers.foreach(_.join())
        }
    end forkOx
end ForkJoinContentionBench
