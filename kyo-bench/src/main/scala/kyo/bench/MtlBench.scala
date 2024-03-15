package kyo.bench

import cats.data.*
import cats.syntax.all.toFoldableOps
import kyo.*
import org.openjdk.jmh.annotations.*
import zio.Chunk
import zio.prelude.fx.Cause
import zio.prelude.fx.ZPure

case class Env(config: String)
case class Event(name: String)
case class State(value: Int)

class MtlBench extends Bench:

    val loops = (1 to 1000).toList

    @Benchmark
    def syncKyo(): Either[Throwable, (Chain[Event], State)] =
        Vars.run(
            Vars.let(State(2))(
                Vars.let(Chain.empty)(
                    Envs[Env].run(Env("config"))(
                        Aborts[Throwable].run(
                            for
                                _      <- testKyo
                                state  <- Vars.get[State]
                                events <- Vars.get[Chain[Event]]
                            yield (events, state)
                        )
                    )
                )
            )
        ).pure

    def testKyo: Unit < (Aborts[Throwable] & Envs[Env] & Vars[State] & Vars[Chain[Event]]) =
        Seqs.traverseUnit(loops)(_ =>
            for
                conf <- Envs[Env].use(_.config)
                _    <- Vars.update[Chain[Event]](_ :+ Event(s"Env = $conf"))
                _    <- Vars.update[State](state => state.copy(value = state.value + 1))
            yield ()
        )

    @Benchmark
    def syncZPure(): (Chunk[Event], Either[Cause[Throwable], (State, Unit)]) =
        testZPure.provideService(Env("config")).runAll(State(2))

    def testZPure: ZPure[Event, State, State, Env, Throwable, Unit] =
        ZPure.foreachDiscard(loops)(_ =>
            for
                conf <- ZPure.serviceWith[Env](_.config)
                _    <- ZPure.log(Event(s"Env = $conf"))
                _    <- ZPure.update[State, State](state => state.copy(value = state.value + 1))
            yield ()
        )

    type F[A] = Either[Throwable, A]

    @Benchmark
    def syncRWST(): Either[Throwable, (Chain[Event], State, Unit)] =
        testRWST.run(Env("config"), State(2))

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
end MtlBench
