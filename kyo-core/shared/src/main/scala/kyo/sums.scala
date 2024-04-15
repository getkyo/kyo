package kyo

import kyo.core.*

object Sums:
    private object sums extends Sums[Any]
    def apply[V]: Sums[V] = sums.asInstanceOf[Sums[V]]

class Sums[V] extends Effect[Sums[V]]:
    opaque type Command[T] = V

    def add(v: V)(using Tag[Sums[V]]): Unit < Sums[V] =
        this.suspend[Unit](v)

    def run[T: Flat, S](v: T < (Sums[V] & S))(
        using Tag[Sums[V]]
    ): (Chunk[V], T) < S =
        this.handle(handler)(Chunks.init, v)

    private val handler =
        new ResultHandler[Chunk[V], Const[V], Sums[V], [T] =>> (Chunk[V], T), Any]:
            def done[T](st: Chunk[V], v: T) = (st, v)
            def resume[T, U: Flat, S](st: Chunk[V], command: V, k: T => U < (Sums[V] & S)) =
                Resume(st.append(command), k(().asInstanceOf[T]))
end Sums
