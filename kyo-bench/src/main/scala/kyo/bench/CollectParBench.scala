package kyo.bench

class CollectParBench extends Bench.ForkOnly[Seq[Int]]:

    val count          = 1000
    val expectedResult = List.fill(count)(1)

    val kyoTasks  = List.fill(count)(kyo.IOs(1))
    val catsTasks = List.fill(count)(cats.effect.IO(1))
    val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

    override def kyoBenchFiber() =
        import kyo.*

        Fibers.parallel(kyoTasks)
    end kyoBenchFiber

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*

        catsTasks.parSequence
    end catsBench

    def zioBench() =
        import zio.*

        ZIO.collectAllPar(zioTasks)
    end zioBench
end CollectParBench
