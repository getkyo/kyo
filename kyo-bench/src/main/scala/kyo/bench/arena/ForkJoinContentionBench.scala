package kyo.bench.arena

import org.openjdk.jmh.annotations.*

class ForkJoinContentionBench extends ArenaBench.ForkOnly(()):

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

        Async.repeat(parallism, parallism)(forkJoinAllFibers).unit
    end kyoBenchFiber

    def zioBench() =
        import zio.*

        val forkFiber         = ZIO.unit.forkDaemon
        val forkAllFibers     = ZIO.foreach(range)(_ => forkFiber)
        val forkJoinAllFibers = forkAllFibers.flatMap(fibers => ZIO.foreach(fibers)(_.await).unit)

        ZIO.collectAll(Seq.fill(parallism)(forkJoinAllFibers.forkDaemon)).flatMap(ZIO.foreach(_)(_.join)).unit
    end zioBench

end ForkJoinContentionBench
