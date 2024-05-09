package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

case class Env(config: String)
case class Event(name: String)
case class State(value: Int)

class MtlBench extends Bench[Unit]:

    val loops          = (1 to 1000).toList
    val expectedResult = ()

    @Benchmark
    def syncKyo() =
        import kyo.*
        def testKyo: Unit < (Aborts[Throwable] & Envs[Env] & Vars[State] & Sums[Event]) =
            Seqs.foreach(loops)(_ =>
                for
                    conf <- Envs.use[Env](_.config)
                    _    <- Sums.add(Event(s"Env = $conf"))
                    _    <- Vars.update((state: State) => state.copy(value = state.value + 1))
                yield ()
            )
        Aborts.run[Throwable](
            Vars.run(State(2))(
                Sums.run(
                    Envs.run(Env("config"))(
                        testKyo.andThen(Vars.get[State])
                    )
                )
            )
        ).pure
    end syncKyo

    @Benchmark
    def syncZPure() =
        import zio.prelude.fx.ZPure

        def testZPure: ZPure[Event, State, State, Env, Throwable, Unit] =
            ZPure.foreachDiscard(loops)(_ =>
                for
                    conf <- ZPure.serviceWith[Env](_.config)
                    _    <- ZPure.log(Event(s"Env = $conf"))
                    _    <- ZPure.update[State, State](state => state.copy(value = state.value + 1))
                yield ()
            )

        testZPure.provideService(Env("config")).runAll(State(2))
    end syncZPure

    @Benchmark
    def syncRWST() =
        import cats.data.{State as _, *}
        import cats.syntax.all.toFoldableOps

        type F[A] = Either[Throwable, A]

        def testRWST: IRWST[F, Env, Chain[Event], State, State, Unit] =
            loops.traverse_(_ =>
                for
                    conf <- IndexedReaderWriterStateT.ask[F, Env, Chain[Event], State].map(_.config)
                    _ <- IndexedReaderWriterStateT.tell[F, Env, Chain[Event], State](
                        Chain(Event(s"Env = $conf"))
                    )
                    _ <- IndexedReaderWriterStateT.modify[F, Env, Chain[Event], State, State](state =>
                        state.copy(value = state.value + 1)
                    )
                yield ()
            )
        testRWST.run(Env("config"), State(2))
    end syncRWST

end MtlBench
