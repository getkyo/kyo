package kyo.bench.arena

class ForkJoinBench extends ArenaBench.ForkOnly(()):

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

        val forkFiber     = Async.run(())
        val forkAllFibers = Kyo.foreach(range)(_ => forkFiber)

        forkAllFibers.flatMap(fibers => Kyo.foreachDiscard(fibers)(_.get))
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        val forkFiber     = ZIO.unit.forkDaemon
        val forkAllFibers = ZIO.foreach(range)(_ => forkFiber)
        forkAllFibers.flatMap(fibers => ZIO.foreach(fibers)(_.await).unit)
    end zioBench

end ForkJoinBench
