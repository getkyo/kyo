package kyo.bench.arena

import java.util.concurrent.locks.LockSupport

class BlockingContentionBench extends ArenaBench.ForkOnly(()):

    val concurrency = Runtime.getRuntime().availableProcessors() * 30

    def block(): Unit =
        LockSupport.parkNanos(100000) // 0.1 ms

    override def kyoBenchFiber() =
        import kyo.*

        Async.fill(concurrency, concurrency)(Sync(block())).unit
    end kyoBenchFiber

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*

        Seq.fill(concurrency)(IO.blocking(block())).parSequence.void
    end catsBench

    def zioBench() =
        import zio.*

        ZIO.collectAll(Seq.fill(concurrency)(ZIO.blocking(ZIO.succeed(block())).forkDaemon)).flatMap(ZIO.foreach(_)(_.join)).unit
    end zioBench

end BlockingContentionBench
