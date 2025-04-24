package kyo

import kyo.Tag
import kyo.kernel.ArrowEffect
import kyo.kernel.internal.WeakFlat
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

/** Processes a stream of type `V`, producing a value of type `A`.
  *
  * `Sink` provides a composable abstraction for processing `Stream` s. A `Sink[V, A, S]` can process a stream of type `Stream[V, S2]` to
  * produce a value of type `A` using effects `S & S2`.
  *
  * @tparam V
  *   The type of values that this sink can process
  * @tparam A
  *   The type of the value generated when processing a stream of `V` s
  * @tparam S
  *   The type of effects used in processing the stream
  *
  * @see
  *   [[kyo.Poll]] for the underlying pull-based consumption mechanism
  */
sealed abstract class Sink[V, A, -S] extends Serializable:

    /** Returns the effect that produces the output value `A` from polling chunks of `V`. */
    def poll: A < (Poll[Chunk[V]] & S)

    /** Combine with another sink. Each element of the stream is processed in tandem by both sinks, producing a tuple of their result
      * values.
      *
      * @note
      *   Departure from the ZIO API, where `zip` composes ZSinks in sequence instead of in parallel
      * @param other
      *   Second sink to combine with
      * @return
      *   A new sink that produces a tuple of the outputs of the source sinks.
      */
    def zip[B, S2](other: Sink[V, B, S2])(using Tag[V], Frame): Sink[V, (A, B), S & S2] =
        Sink:
            Loop((poll, other.poll)): (pollA, pollB) =>
                Poll.andMap[Chunk[V]]: polledValue =>
                    // TODO: use ArrowEffect.handleFirst directly to avoid Either
                    Poll.runFirst(pollA).map:
                        case Left(a) =>
                            pollB.map: b =>
                                Loop.done((a, b))
                        case Right(fnA) =>
                            val nextA = fnA(polledValue)
                            Poll.runFirst(pollB).map:
                                case Left(b) =>
                                    nextA.map: a =>
                                        Loop.done((a, b))
                                case Right(fnB) =>
                                    val nextB = fnB(polledValue)
                                    Loop.continue(nextA, nextB)
    end zip

    /** Transform a sink to consume a stream of a different element type by providing a function to transform elements of the new type to
      * the original element type.
      *
      * @param f
      *   Function mapping new stream element type to original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    def contramap[V2](f: V2 => V)(using
        t1: Tag[Poll[Chunk[V]]],
        t2: Tag[Poll[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        fr: Frame
    ): Sink[V2, A, S] =
        Sink:
            ArrowEffect.handleState(t1, (), poll)(
                [C] =>
                    (_, _, cont) =>
                        Poll.andMap[Chunk[V2]]: maybeChunkV2 =>
                            val maybeChunkV = maybeChunkV2.map(_.map(f))
                            ((), cont(maybeChunkV))
            )

    /** Transform a sink to consume a stream of a different element type by providing an effectful function to transform elements of the new
      * type to the original element type.
      *
      * @param f
      *   Effectful function mapping new stream element type to original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    def contramap[V2, S2](f: V2 => V < S2)(using
        t1: Tag[Poll[Chunk[V]]],
        t2: Tag[Poll[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        d: Sink.Dummy,
        fr: Frame
    ): Sink[V2, A, S & S2] =
        Sink:
            ArrowEffect.handleState[Const[Unit], Const[Maybe[Chunk[V]]], Poll[Chunk[V]], Unit, A, A, S, S, S2 & Poll[Chunk[V2]]](
                t1,
                (),
                poll
            )(
                [C] =>
                    (_, _, cont) =>
                        Poll.andMap[Chunk[V2]]:
                            case Absent =>
                                ((), cont(Absent))
                            case Present(chunk2) =>
                                Kyo.foreach(chunk2)(f).map: chunk1 =>
                                    ((), cont(Present(chunk1)))
            )

    /** Transform a sink to consume a stream of a different element type by providing a function to transform chunks of elements of the new
      * type to chunks of the original element type.
      *
      * @param f
      *   Function mapping chunks of new stream element type to chunks of the original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    def contramapChunk[V2](f: Chunk[V2] => Chunk[V])(using
        t1: Tag[Poll[Chunk[V]]],
        t2: Tag[Poll[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        fr: Frame
    ): Sink[V2, A, S] =
        Sink:
            ArrowEffect.handleState(t1, (), poll)(
                [C] =>
                    (_, _, cont) =>
                        Poll.andMap[Chunk[V2]]: maybeChunkV2 =>
                            val maybeChunkV = maybeChunkV2.map(f)
                            ((), cont(maybeChunkV))
            )

    /** Transform a sink to consume a stream of a different element type by providing an effectful function to transform chunks of elements
      * of the new type to chunks of the original element type.
      *
      * @param f
      *   Effectful function mapping chunks of new stream element type to chunks of the original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    def contramapChunk[V2, S2](f: Chunk[V2] => Chunk[V] < S2)(using
        t1: Tag[Poll[Chunk[V]]],
        t2: Tag[Poll[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        d: Sink.Dummy,
        fr: Frame
    ): Sink[V2, A, S & S2] =
        Sink:
            ArrowEffect.handleState[Const[Unit], Const[Maybe[Chunk[V]]], Poll[Chunk[V]], Unit, A, A, S, S, S2 & Poll[Chunk[V2]]](
                t1,
                (),
                poll
            )(
                [C] =>
                    (_, _, cont) =>
                        Poll.andMap[Chunk[V2]]:
                            case Absent => ((), cont(Absent))
                            case Present(chunk2) =>
                                f(chunk2).map: chunk1 =>
                                    ((), cont(Present(chunk1)))
            )

    /** Transform a sink to produce a new output type by providing a function to transform the original result type.
      *
      * @param f
      *   Function mapping the original output to a new output value
      * @return
      *   A new sink that processes the same kind of stream to produce a new output type
      */
    def map[B, S2](f: A => (B < S2))(
        using fr: Frame
    ): Sink[V, B, S & S2] =
        Sink(poll.map((a: A) => f(a)))
    end map

    /** Process a stream to produce an output
      *
      * @see
      *   [[kyo.Stream.into]]
      *
      * @param stream
      *   Stream to be processed
      * @return
      *   An effect generating an output value from elements consumed from the stream
      */
    def consume[S2](stream: Stream[V, S2])(using Tag[V], Frame): A < (S & S2) =
        Poll.run(stream.emit)(poll).map(_._2)
end Sink

object Sink:
    @nowarn("msg=anonymous")
    inline def apply[V, A, S](inline v: => A < (Poll[Chunk[V]] & S)): Sink[V, A, S] =
        new Sink[V, A, S]:
            def poll: A < (Poll[Chunk[V]] & S) = v

    /** Construct a sink that runs a stream of element type `V` without producing any value.
      *
      * @return
      *   A sink that runs a stream without producing a value
      */
    def drain[V](using Tag[V], Frame): Sink[V, Unit, Any] =
        Sink:
            Loop(()): _ =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done
                    case Present(_) =>
                        Loop.continue
    end drain

    /** Construct a sink that accumulates all elements of a stream of type `V` into a chunk.
      *
      * @return
      *   A sink that produces a chunk of all elements emitted by the source stream
      */
    def collect[V](using Tag[V], Frame): Sink[V, Chunk[V], Any] =
        Sink:
            Loop(Chunk.empty[V]): currentChunk =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done(currentChunk)
                    case Present(c) =>
                        Loop.continue(currentChunk ++ c)
    end collect

    /** Construct a sink that counts streaming elements.
      *
      * @return
      *   A sink that produces the count of all elements emitted by source stream
      */
    def count[V](using Tag[V], Frame): Sink[V, Int, Any] =
        Sink:
            Loop(0): count =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done(count)
                    case Present(chunk) =>
                        Loop.continue(count + chunk.size)
    end count

    /** Construct a sink that applies an effectful function to each element of a stream, discarding all resulting values
      *
      * @param f
      *   Effectful function with no result to apply to each streaming element
      * @return
      *   A sink that applies an effectful function to each element of the source stream
      */
    def foreach[V, S](f: V => Unit < S)(using Tag[V], Frame): Sink[V, Unit, S] =
        Sink:
            Loop(()): _ =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Kyo.foreachDiscard(c)(f).andThen(Loop.continue)
    end foreach

    /** Construct a sink that applies an effectful function to each chunk of a stream, discarding all resulting values
      *
      * @param f
      *   Effectful function with no result to apply to each streaming element
      * @return
      *   A sink that applies an effectful function to each chunk of the source stream
      */
    def foreachChunk[V, S](f: Chunk[V] => Unit < S)(using Tag[V], Frame): Sink[V, Unit, S] =
        Sink:
            Loop(()): _ =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        f(c).andThen(Loop.continue)
    end foreachChunk

    /** Construct a sink that applies a pure, stateful, aggregating function to streaming elements to generate a final value.
      *
      * @param acc
      *   Initial state ("accumulator") to be updated by each streaming element
      * @param f
      *   Pure aggregating function to update the accumulator based on the accumulator's current value along with the current element
      *   emitted by the source stream
      * @return
      *   A sink that processes a stream by updating an accumulator for each element of the stream
      */
    def fold[A, V](acc: A)(f: (A, V) => A)(using
        t1: Tag[Emit[Chunk[V]]],
        t2: Tag[V],
        frame: Frame
    ): Sink[V, A, Any] =
        Sink:
            Loop(acc): state =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done(state)
                    case Present(c) =>
                        Loop.continue(c.foldLeft(state)(f))
    end fold

    /** Construct a sink that applies an effectful, stateful, aggregating function to streaming elements to generate a final value.
      *
      * @param acc
      *   Initial state ("accumulator") to be updated by each streaming element
      * @param f
      *   Effectful aggregating function to update the accumulator based on the accumulator's current value along with the current element
      *   emitted by the source stream
      * @return
      *   A sink that processes a stream by updating an accumulator for each element of the stream
      */
    def foldKyo[A, V, S](acc: A)(f: (A, V) => A < S)(using
        t1: Tag[Emit[Chunk[V]]],
        t2: Tag[V],
        frame: Frame
    ): Sink[V, A, S] =
        Sink:
            Loop(acc): state =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done(state)
                    case Present(c) =>
                        Kyo.foldLeft(c)(state)(f).map: newState =>
                            Loop.continue(newState)
    end foldKyo

    /** Combine two sinks, processing source stream in tandem.
      */
    def zip[V, A, B, S](a: Sink[V, A, S], b: Sink[V, B, S])(using Tag[V], Frame): Sink[V, (A, B), S] =
        a.zip(b)

    /** Combine three sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S])(using Tag[V], Frame): Sink[V, (A, B, C), S] =
        a.zip(zip(b, c)).map:
            case (a, tail) => a *: tail

    /** Combine four sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S], d: Sink[V, D, S])(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D), S] =
        a.zip(zip(b, c, d)).map:
            case (a, tail) => a *: tail

    /** Combine five sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S], d: Sink[V, D, S], e: Sink[V, E, S])(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E), S] =
        a.zip(zip(b, c, d, e)).map:
            case (a, tail) => a *: tail

    /** Combine six sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, F, S](
        a: Sink[V, A, S],
        b: Sink[V, B, S],
        c: Sink[V, C, S],
        d: Sink[V, D, S],
        e: Sink[V, E, S],
        f: Sink[V, F, S]
    )(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E, F), S] =
        a.zip(zip(b, c, d, e, f)).map:
            case (a, tail) => a *: tail

    /** Combine seven sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, F, G, S](
        a: Sink[V, A, S],
        b: Sink[V, B, S],
        c: Sink[V, C, S],
        d: Sink[V, D, S],
        e: Sink[V, E, S],
        f: Sink[V, F, S],
        g: Sink[V, G, S]
    )(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E, F, G), S] =
        a.zip(zip(b, c, d, e, f, g)).map:
            case (a, tail) => a *: tail

    /** Combine eight sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, F, G, H, S](
        a: Sink[V, A, S],
        b: Sink[V, B, S],
        c: Sink[V, C, S],
        d: Sink[V, D, S],
        e: Sink[V, E, S],
        f: Sink[V, F, S],
        g: Sink[V, G, S],
        h: Sink[V, H, S]
    )(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E, F, G, H), S] =
        a.zip(zip(b, c, d, e, f, g, h)).map:
            case (a, tail) => a *: tail

    /** Combine nine sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, F, G, H, I, S](
        a: Sink[V, A, S],
        b: Sink[V, B, S],
        c: Sink[V, C, S],
        d: Sink[V, D, S],
        e: Sink[V, E, S],
        f: Sink[V, F, S],
        g: Sink[V, G, S],
        h: Sink[V, H, S],
        i: Sink[V, I, S]
    )(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E, F, G, H, I), S] =
        a.zip(zip(b, c, d, e, f, g, h, i)).map:
            case (a, tail) => a *: tail

    /** Combine ten sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, F, G, H, I, J, S](
        a: Sink[V, A, S],
        b: Sink[V, B, S],
        c: Sink[V, C, S],
        d: Sink[V, D, S],
        e: Sink[V, E, S],
        f: Sink[V, F, S],
        g: Sink[V, G, S],
        h: Sink[V, H, S],
        i: Sink[V, I, S],
        j: Sink[V, J, S]
    )(using
        Tag[V],
        Frame
    ): Sink[V, (A, B, C, D, E, F, G, H, I, J), S] =
        a.zip(zip(b, c, d, e, f, g, h, i, j)).map:
            case (a, tail) => a *: tail

    /** A dummy type that can be used as implicit evidence to help the compiler discriminate between overloaded methods.
      */
    sealed class Dummy extends Serializable
    object Dummy:
        given Dummy = new Dummy {}

end Sink
