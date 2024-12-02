package kyo.bench

class TMapSingleKeyBench(parallelism: Int) extends Bench.ForkOnly(parallelism):

    def this() = this(Runtime.getRuntime().availableProcessors() * 2)

    def catsBench() =
        import cats.effect.*
        import cats.syntax.all.*
        import io.github.timwspence.cats.stm.*

        STM.runtime[IO].flatMap { stm =>
            for
                ref <- stm.commit(stm.TVar.of(Map.empty[Int, Int]))
                _ <- Seq.fill(parallelism)(
                    stm.commit {
                        for
                            map <- ref.get
                            current = map.getOrElse(0, 0)
                            _ <- ref.set(map.updated(0, current + 1))
                        yield ()
                    }
                ).parSequence_
                result <- stm.commit(ref.get.map(_.getOrElse(0, 0)))
            yield result
        }
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        for
            map <- TMap.initNow[Int, Int]()
            _ <- Async.parallelUnbounded(
                Seq.fill(parallelism)(
                    STM.run {
                        for
                            current <- map.get(0)
                            _       <- map.put(0, current.getOrElse(0) + 1)
                        yield ()
                    }
                )
            )
            result <- STM.run(map.get(0))
        yield result.getOrElse(0)
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stm.*

        for
            map <- TMap.empty[Int, Int].commit
            _ <- ZIO.collectAllPar(
                Seq.fill(parallelism)(
                    STM.atomically {
                        for
                            current <- map.get(0)
                            _       <- map.put(0, current.getOrElse(0) + 1)
                        yield ()
                    }
                )
            )
            result <- map.get(0).commit
        yield result.getOrElse(0)
        end for
    end zioBench

end TMapSingleKeyBench
