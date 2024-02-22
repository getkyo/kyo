package kyo.bench

class CollectBench extends Bench.SyncAndFork[Seq[Int]]:

    val count = 1000

    val kyoTasks  = List.fill(count)(kyo.IOs(1))
    val catsTasks = List.fill(count)(cats.effect.IO(1))
    val zioTasks  = List.fill(count)(zio.ZIO.succeed(1))

    def kyoBench() =
        import kyo.*

        Seqs.collect(kyoTasks)
    end kyoBench

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*

        catsTasks.sequence
    end catsBench

    def zioBench() =
        import zio.*

        ZIO.collectAll(zioTasks)
    end zioBench
end CollectBench
