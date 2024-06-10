package kyo

import kyo.core.*
import kyo.internal.Trace

class Sums[V] extends Effect[Const[V], Const[Unit]]

object Sums:

    inline def add[V](inline v: V)(
        using
        inline tag: Tag[Sums[V]],
        inline trace: Trace
    ): Unit < Sums[V] =
        suspend[Any](tag, v)

    class RunDsl[V](ign: Unit) extends AnyVal:
        def apply[T, S](v: T < (Sums[V] & S))(
            using
            tag: Tag[Sums[V]],
            trace: Trace
        ): (Chunk[V], T) < S =
            handle.state(tag, Chunks.init[V], v)(
                handle =
                    [C] =>
                        (input, state, cont) =>
                            (state.append(input), cont(())),
                done =
                    (state, result) => (state, result)
            )
    end RunDsl

    inline def run[V >: Nothing]: RunDsl[V] = RunDsl(())

end Sums
