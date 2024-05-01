package kyo.bench

import org.openjdk.jmh.annotations.*

class ForkJoinBench extends Bench.ForkOnly[Unit]:

    val depth = 10000
    val range = (0 until depth).toList

    def catsBench() =
        import cats.*
        import cats.effect.*

        val forkFiber     = IO.unit.start
        val forkAllFibers = Traverse[List].traverse(range)(_ => forkFiber)

        forkAllFibers.flatMap(fibers => Traverse[List].traverse(fibers)(_.join).void)
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        val forkFiber     = Fibers.init(())
        val forkAllFibers = Seqs.map(range)(_ => forkFiber)

        forkAllFibers.flatMap(fibers => Seqs.map(fibers)(_.get).unit)
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        val forkFiber     = ZIO.unit.forkDaemon
        val forkAllFibers = ZIO.foreach(range)(_ => forkFiber)
        forkAllFibers.flatMap(fibers => ZIO.foreach(fibers)(_.await).unit)
    end zioBench

    @Benchmark
    def forkOx() =
        import ox.*
        scoped {
            val forkAllFibers =
                for (_ <- range) yield fork(())
            for fiber <- forkAllFibers do
                fiber.join()
        }
    end forkOx
end ForkJoinBench
