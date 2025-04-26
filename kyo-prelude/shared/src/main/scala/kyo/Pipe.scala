package kyo

import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

/** Represents a stream of values of type `V` with effects of type `S`.
  *
  * @tparam V
  *   The type of values in the stream
  * @tparam S
  *   The type of effects associated with the stream
  *
  * @param v
  *   The effect that produces acknowledgments and emits chunks of values
  *
  * @see
  *   [[kyo.Stream.map]], [[kyo.Stream.filter]], [[kyo.Stream.flatMap]] for transforming streams
  * @see
  *   [[kyo.Stream.concat]], [[kyo.Stream.take]], [[kyo.Stream.drop]] for stream composition and slicing
  * @see
  *   [[kyo.Stream.run]], [[kyo.Stream.foreach]], [[kyo.Stream.fold]] for consuming streams
  * @see
  *   [[kyo.Emit]] for the underlying push-based emission mechanism
  * @see
  *   [[kyo.Poll]] for pull-based consumption with backpressure
  */
sealed abstract class Pipe[A, B, -S] extends Serializable:

    /** Returns the effect that polls chunks of type A and emits chunks of type B */
    def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)

    def contramap[A1](f: A1 => A)(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        fr: Frame
    ): Pipe[A1, B, S] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (unit, cont) =>
                        Poll.andMap[Chunk[A1]]: maybeChunk =>
                            Loop.continue(cont(maybeChunk.map(_.map(f))))
            )

    def contramap[A1, S1](f: A1 => A < S1)(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        disc: Stream.Dummy,
        fr: Frame
    ): Pipe[A1, B, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (unit, cont) =>
                        Poll.andMap[Chunk[A1]]:
                            case Absent => Loop.continue(cont(Absent))
                            case Present(chunk) =>
                                Kyo.foreach(chunk)(f).map: chunk2 =>
                                    Loop.continue(cont(Present(chunk2)))
            )

    def contramapChunk[A1](f: Chunk[A1] => Chunk[A])(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        fr: Frame
    ): Pipe[A1, B, S] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (unit, cont) =>
                        Poll.andMap[Chunk[A1]]: maybeChunk =>
                            Loop.continue(cont(maybeChunk.map(f)))
            )

    def contramapChunk[A1, S1](f: Chunk[A1] => Chunk[A] < S1)(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        disc: Stream.Dummy,
        fr: Frame
    ): Pipe[A1, B, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (unit, cont) =>
                        Poll.andMap[Chunk[A1]]:
                            case Absent => Loop.continue(cont(Absent))
                            case Present(chunk) =>
                                f(chunk).map: chunk2 =>
                                    Loop.continue(cont(Present(chunk2)))
            )

    def map[B1](f: B => B1)(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        fr: Frame
    ): Pipe[A, B1, S] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        Emit.valueWith(chunk.map(f))(Loop.continue(cont(())))
            )

    def map[B1, S1](f: B => B1 < S1)(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        disc: Stream.Dummy,
        fr: Frame
    ): Pipe[A, B1, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        Kyo.foreach(chunk)(f).map: chunk2 =>
                            Emit.valueWith(chunk2)(Loop.continue(cont(())))
            )

    def mapChunk[B1](f: Chunk[B] => Chunk[B1])(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        fr: Frame
    ): Pipe[A, B1, S] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        Emit.valueWith(f(chunk))(Loop.continue(cont(())))
            )

    def mapChunk[B1, S1](f: Chunk[B] => Chunk[B1] < S1)(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        disc: Stream.Dummy,
        fr: Frame
    ): Pipe[A, B1, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        f(chunk).map: chunk2 =>
                            Emit.valueWith(chunk2)(Loop.continue(cont(())))
            )

    def combine[S1](other: Pipe[A, B, S1])(
        using
        t1: Tag[Poll[Chunk[A]]],
        fr: Frame
    ): Pipe[A, B, S & S1] =
        Pipe:
            Loop(pollEmit, other.pollEmit): (pollEmit1, pollEmit2) =>
                ArrowEffect.handleFirst(t1, pollEmit1)(
                    handle = [C] =>
                        (_, cont1) =>
                            Poll.andMap[Chunk[A]]: maybeChunk =>
                                val next1 = cont1(maybeChunk)
                                ArrowEffect.handleFirst(t1, pollEmit2)(
                                    handle = [C] =>
                                        (_, cont2) =>
                                            val next2 = cont2(maybeChunk)
                                            Loop.continue(next1, next2)
                                    ,
                                    done = _ =>
                                        next1.andThen(Loop.done(()))
                            )
                    ,
                    done = _ =>
                        pollEmit2.andThen(Loop.done(()))
                )

    def consume[S1](stream: Stream[A, S1])(using Tag[A], Frame): Stream[B, S & S1] =
        Stream:
            Poll.run(stream.emit)(pollEmit).map(_._2)

end Pipe

object Pipe:
    @nowarn("msg=anonymous")
    inline def apply[A, B, S](inline v: => Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)): Pipe[A, B, S] =
        new Pipe[A, B, S]:
            def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S) = v

    def identity[A](using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(c)(Loop.continue)

end Pipe
