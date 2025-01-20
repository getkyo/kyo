package kyo.bench

class CollectParBench extends Bench.ForkOnly(Seq.fill(1000)(1)):

    val count     = 1000
    val kyoTasks  = List.fill(count)(kyo.IO(1))
    val catsTasks = List.fill(count)(cats.effect.IO(1))
    val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

    override def kyoBenchFiber() =
        import kyo.*

        Async.parallelUnbounded(kyoTasks)
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
