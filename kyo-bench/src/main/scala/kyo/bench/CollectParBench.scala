package kyo.bench

class CollectParBench extends Bench.ForkOnly(Seq.fill(1000)(1)):

    val count     = 1000
    val kyoTasks  = List.fill(count)(kyo.IOs(1))
    val kyo2Tasks = List.fill(count)(kyo2.IO(1))
    val catsTasks = List.fill(count)(cats.effect.IO(1))
    val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

    override def kyoBenchFiber() =
        import kyo.*

        Fibers.parallel(kyoTasks)
    end kyoBenchFiber

    override def kyoBenchFiber2() =
        import kyo2.*

        val x = Async.parallel(kyo2Tasks)
        x
    end kyoBenchFiber2

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
