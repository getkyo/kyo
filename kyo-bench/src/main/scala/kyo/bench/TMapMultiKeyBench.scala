package kyo.bench

class TMapMultiKeyBench(parallelism: Int) extends Bench.ForkOnly(parallelism):

    def this() = this(Runtime.getRuntime().availableProcessors() * 2)

    def catsBench() =
        import cats.effect.*
        import cats.syntax.all.*
        import io.github.timwspence.cats.stm.*

        STM.runtime[IO].flatMap { stm =>
            for
                ref <- stm.commit(stm.TVar.of(Map.empty[Int, Int]))
                _ <-
                    (0 until parallelism).map { i =>
                        stm.commit {
                            for
                                map <- ref.get
                                _   <- ref.set(map.updated(i, map.getOrElse(i, 0) + 1))
                            yield ()
                        }
                    }.toList.parSequence_
                results <- stm.commit(ref.get.map(_.values.sum))
            yield results
        }
    end catsBench

    override def kyoBenchFiber() =
        import kyo.*

        for
            map <- TMap.init[Int, Int]
            _ <-
                Async.parallelUnbounded(
                    (0 until parallelism).map { i =>
                        STM.run {
                            for
                                current <- map.get(i)
                                _       <- map.put(i, current.getOrElse(0) + 1)
                            yield ()
                        }
                    }
                )
            results <- STM.run(map.snapshot)
        yield results.values.sum
        end for
    end kyoBenchFiber

    def zioBench() =
        import zio.*
        import zio.stm.*

        for
            map <- TMap.empty[Int, Int].commit
            _ <-
                ZIO.collectAllParDiscard(
                    (0 until parallelism).map { i =>
                        STM.atomically {
                            for
                                current <- map.get(i)
                                _       <- map.put(i, current.getOrElse(0) + 1)
                            yield ()
                        }
                    }
                )
            results <- map.toMap.commit
        yield results.values.sum
        end for
    end zioBench

end TMapMultiKeyBench
