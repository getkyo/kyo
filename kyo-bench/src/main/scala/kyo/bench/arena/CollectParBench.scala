package kyo.bench.arena

class CollectParBench extends ArenaBench.ForkOnly(Seq.fill(1000)(1)):

    val count     = 1000
    val kyoTasks  = List.fill(count)(kyo.Sync(1))
    val catsTasks = List.fill(count)(cats.effect.IO(1))
    val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

    override def kyoBenchFiber() =
        import kyo.*

        Async.collectAll(kyoTasks, count)
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
