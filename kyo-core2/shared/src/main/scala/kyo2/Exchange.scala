package kyo2

import scala.compiletime.*

object Exchange:

    class Send[A, B](in: Channel[A], out: Channel[B]):
        def apply(a: A): B < (Abort[Closed] & Async) =
            in.put(a).andThen(out.take)
        def close: Maybe[Seq[A]] < IO =
            in.close
    end Send

    class Receive[A, B](in: Channel[A], out: Channel[B]):
        def apply[S](f: A => B < S): B < (Abort[Closed] & Async & S) =
            in.take.map(f).map(b => out.put(b).andThen(b))
        def withValue[C, S](f: A => (B, C) < S): C < (Abort[Closed] & Async & S) =
            in.take.map(f).map((b, c) => out.put(b).andThen(c))
        def close: Maybe[Seq[B]] < IO =
            out.close
    end Receive

    def init[A, B](capacity: Int): (Send[A, B], Receive[A, B]) < IO =
        for
            a <- Channel.init[A](capacity)
            b <- Channel.init[B](capacity)
        yield (Send(a, b), Receive(a, b))

end Exchange
