package kyo.bench

import cats.data.Chain
import kyo.*
import org.openjdk.jmh.annotations.*

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
end MtlBench
