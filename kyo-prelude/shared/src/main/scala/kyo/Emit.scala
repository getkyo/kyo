package kyo

import kyo.Tag
import kyo.kernel.*

sealed trait Emit[V] extends Effect[Const[V], Const[Emit.Ack]]

object Emit:

    opaque type Ack = Int
    object Ack:
        given CanEqual[Ack, Ack] = CanEqual.derived

        extension (ack: Ack)
            def maxItems(n: Int): Ack =
                ack match
                    case Stop         => Stop
                    case Continue(n0) => Math.max(n0, n)

        opaque type Continue <: Ack = Int
        object Continue:
            def apply(): Continue              = Int.MaxValue
            def apply(maxItems: Int): Continue = Math.max(0, maxItems)
            def unapply(ack: Ack): Maybe.Ops[Int] =
                if ack < 0 then Maybe.empty
                else Maybe(ack)
        end Continue

        val Stop: Ack = -1
    end Ack

    inline def apply[V](inline value: V)(using inline tag: Tag[Emit[V]], inline frame: Frame): Ack < Emit[V] =
        Effect.suspend[Any](tag, value)

    inline def andMap[V, A, S](inline value: V)(inline f: Ack => A < S)(
        using
        inline tag: Tag[Emit[V]],
        inline frame: Frame
    ): A < (S & Emit[V]) =
        Effect.suspendMap[Any](tag, value)(f(_))

    final class RunOps[V](dummy: Unit) extends AnyVal:
        def apply[A, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): (Chunk[V], A) < S =
            Effect.handle.state(tag, Chunk.empty[V], v)(
                handle = [C] => (input, state, cont) => (state.append(input), cont(Ack.Continue())),
                done = (state, res) => (state, res)
            )
    end RunOps

    inline def run[V >: Nothing]: RunOps[V] = RunOps(())

    final class RunFoldOps[V](dummy: Unit) extends AnyVal:
        def apply[A, S, B, S2](acc: A)(f: (A, V) => A < S)(v: B < (Emit[V] & S2))(
            using
            tag: Tag[Emit[V]],
            frame: Frame
        ): (A, B) < (S & S2) =
            Effect.handle.state(tag, acc, v)(
                handle = [C] =>
                    (input, state, cont) =>
                        f(state, input).map((_, cont(Ack.Continue()))),
                done = (state, res) => (state, res)
            )
    end RunFoldOps

    inline def runFold[V >: Nothing]: RunFoldOps[V] = RunFoldOps(())

    final class RunDiscardOps[V](dummy: Unit) extends AnyVal:
        def apply[A, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): A < S =
            Effect.handle(tag, v)(
                handle = [C] => (input, cont) => cont(Ack.Stop)
            )
    end RunDiscardOps

    inline def runDiscard[V >: Nothing]: RunDiscardOps[V] = RunDiscardOps(())

    final class RunAckOps[V](dummy: Unit) extends AnyVal:
        def apply[A, S, S2](v: A < (Emit[V] & S))(f: V => Ack < S2)(using tag: Tag[Emit[V]], frame: Frame): A < (S & S2) =
            Effect.handle(tag, v)(
                [C] => (input, cont) => f(input).map(cont)
            )
    end RunAckOps

    inline def runAck[V >: Nothing]: RunAckOps[V] = RunAckOps(())

end Emit
