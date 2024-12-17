package kyo.bench

class TRefSingleBench(parallelism: Int) extends Bench.ForkOnly(parallelism):

    def this() = this(Runtime.getRuntime().availableProcessors() * 2)

    def catsBench() =
        import cats.effect.*
        import cats.syntax.all.*
        import io.github.timwspence.cats.stm.*

        STM.runtime[IO].flatMap { stm =>
            for
                ref <- stm.commit(stm.TVar.of(0))
                _ <- Seq.fill(parallelism)(
                    stm.commit(ref.modify(_ + 1))
                ).parSequence_
                result <- stm.commit(ref.get)
            yield result
        }
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        for
            ref    <- TRef.initNow(0)
            _      <- Async.parallelUnbounded(Seq.fill(parallelism)(STM.run(ref.update(_ + 1))))
            result <- STM.run(ref.get)
        yield result
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stm.*

        for
            ref    <- TRef.make(0).commit
            _      <- ZIO.collectAllParDiscard(Seq.fill(parallelism)(ref.update(_ + 1).commit))
            result <- ref.get.commit
        yield result
        end for
    end zioBench
end TRefSingleBench
