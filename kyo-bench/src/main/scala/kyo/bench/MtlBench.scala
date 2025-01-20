package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

case class EnvValue(config: String)
case class Event(name: String)
case class State(value: Int)

class MtlBench extends Bench(()):

    val loops = (1 to 1000).toList

    @Benchmark
    def syncKyo() =
        import kyo.*
        def testKyo: Unit < (Abort[Throwable] & Env[EnvValue] & Var[State] & Emit[Event]) =
            Kyo.foreachDiscard(loops)(_ =>
                for
                    conf <- Env.use[EnvValue](_.config)
                    _    <- Emit.value(Event(s"Env = $conf"))
                    _    <- Var.update((state: State) => state.copy(value = state.value + 1))
                yield ()
            )
        Var.run(State(2))(
            Emit.run(
                Env.run(EnvValue("config"))(
                    testKyo.andThen(Var.get[State])
                )
            )
        )
    end syncKyo

    @Benchmark
    def syncZPure() =
        import zio.prelude.fx.ZPure

        def testZPure: ZPure[Event, State, State, EnvValue, Throwable, Unit] =
            ZPure.foreachDiscard(loops)(_ =>
                for
                    conf <- ZPure.serviceWith[EnvValue](_.config)
                    _    <- ZPure.log(Event(s"Env = $conf"))
                    _    <- ZPure.update[State, State](state => state.copy(value = state.value + 1))
                yield ()
            )

        testZPure.provideService(EnvValue("config")).runAll(State(2))
    end syncZPure

    @Benchmark
    def syncRWST() =
        import cats.data.{State as _, *}
        import cats.syntax.all.toFoldableOps

        type F[A] = Either[Throwable, A]

        def testRWST: IRWST[F, EnvValue, Chain[Event], State, State, Unit] =
            loops.traverse_(_ =>
                for
                    conf <- IndexedReaderWriterStateT.ask[F, EnvValue, Chain[Event], State].map(_.config)
                    _ <- IndexedReaderWriterStateT.tell[F, EnvValue, Chain[Event], State](
                        Chain(Event(s"Env = $conf"))
                    )
                    _ <- IndexedReaderWriterStateT.modify[F, EnvValue, Chain[Event], State, State](state =>
                        state.copy(value = state.value + 1)
                    )
                yield ()
            )
        testRWST.run(EnvValue("config"), State(2))
    end syncRWST

end MtlBench
