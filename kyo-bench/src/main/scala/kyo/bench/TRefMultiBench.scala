package kyo.bench

import java.util.concurrent.locks.LockSupport

class TRefMultiBench(parallelism: Int) extends Bench.ForkOnly(parallelism):

    def this() = this(Runtime.getRuntime().availableProcessors() * 2)

    def catsBench() =
        import cats.effect.*
        import cats.syntax.all.*
        import io.github.timwspence.cats.stm.*

        STM.runtime[IO].flatMap { stm =>
            for
                refs   <- Seq.fill(parallelism)(stm.commit(stm.TVar.of(0))).sequence
                _      <- refs.map(ref => stm.commit(ref.modify(_ + 1))).parSequence_
                result <- stm.commit(refs.traverse(_.get).map(_.sum))
            yield result
        }
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        for
            refs   <- Kyo.fill(parallelism)(TRef.initNow(0))
            _      <- Async.parallelUnbounded(refs.map(ref => STM.run(ref.update(_ + 1))))
            result <- STM.run(Kyo.foreach(refs)(_.get).map(_.sum))
        yield result
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stm.*

        for
            refs   <- ZIO.collectAll(Seq.fill(parallelism)(TRef.make(0).commit))
            _      <- ZIO.collectAllParDiscard(refs.map(_.update(_ + 1).commit))
            result <- STM.collectAll(refs.map(_.get)).map(_.sum).commit
        yield result
        end for
    end zioBench
end TRefMultiBench
