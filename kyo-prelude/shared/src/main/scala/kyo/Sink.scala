package kyo

import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.nowarn

/** Processes a stream of type `V`, producing a value of type `A`.
  *
  * `Sink` provides a composable abstraction for processing `Stream` s. A `Sink[V, A, S]` can evaluate a stream of type `Stream[V, S2]`,
  * producing a value of type `A` using effects `S & S2`.
  *
  * @see
  *   [[kyo.Sink.drain]]
  * @see
  *   [[kyo.Stream]] [[kyo.Pipe]]
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
sealed abstract class Sink[-V, +A, -S] extends Serializable:

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
    final def zip[VV <: V, B, S2](other: Sink[VV, B, S2])(using tag: Tag[Poll[Chunk[VV]]], f: Frame): Sink[VV, (A, B), S & S2] =
        Sink:
            Loop((poll: A < (Poll[Chunk[VV]] & S), other.poll)): (pollA, pollB) =>
                ArrowEffect.handleFirst(tag, pollA)(
                    handle = [C] =>
                        (_, contA) =>
                            Poll.andMap[Chunk[VV]]: polledValue =>
                                val nextA = contA(polledValue)
                                ArrowEffect.handleFirst(tag, pollB)(
                                    handle = [C] =>
                                        (_, contB) =>
                                            val nextB = contB(polledValue)
                                            Loop.continue(nextA, nextB)
                                    ,
                                    done = b =>
                                        nextA.map: a =>
                                            Loop.done((a, b))
                            )
                    ,
                    done = a =>
                        ArrowEffect.handleFirst(tag, pollB)(
                            handle = [C] =>
                                (_, contB) =>
                                    Poll.andMap[Chunk[VV]]: polledValue =>
                                        contB(polledValue).map: b =>
                                            Loop.done((a, b)),
                            done = b =>
                                Loop.done((a, b))
                        )
                )

    end zip

    /** Transform a sink to consume a stream of a different element type using a pure mapping function.
      *
      * @param f
      *   Function mapping new stream element type to original stream element type
      * @return
      *   A sink that processes streams of the new element type
      */
    final def contramapPure[VV <: V, V2](f: V2 => VV)(using
        t1: Tag[Poll[Chunk[VV]]],
        t2: Tag[Poll[Chunk[V2]]],
        fr: Frame
    ): Sink[V2, A, S] =
        Sink:
            ArrowEffect.handleLoop(t1, poll)(
                [C] =>
                    (_, cont) =>
                        Poll.andMap[Chunk[V2]]: maybeChunkV2 =>
                            val maybeChunkV = maybeChunkV2.map(_.map(f))
                            Loop.continue(cont(maybeChunkV))
            )

    /** Transform a sink to consume a stream of a different element type using an effectful mapping function.
      *
      * @param f
      *   Effectful function mapping new stream element type to original stream element type
      * @return
      *   A sink that processes streams of the new element type
      */
    final def contramap[VV <: V, V2, S2](f: V2 => VV < S2)(using
        t1: Tag[Poll[Chunk[VV]]],
        t2: Tag[Poll[Chunk[V2]]],
        fr: Frame
    ): Sink[V2, A, S & S2] =
        Sink:
            ArrowEffect.handleLoop[Const[Unit], Const[Maybe[Chunk[VV]]], Poll[Chunk[VV]], A, S, S2 & Poll[Chunk[V2]]](
                t1,
                poll
            )(
                [C] =>
                    (_, cont) =>
                        Poll.andMap[Chunk[V2]]:
                            case Absent =>
                                Loop.continue(cont(Absent))
                            case Present(chunk2) =>
                                Kyo.foreach(chunk2)(f).map: chunk1 =>
                                    Loop.continue(cont(Present(chunk1)))
            )

    /** Transform a sink to consume a stream of a different element type using a pure mapping function that transforms streamed chunks.
      *
      * @param f
      *   Function mapping chunks of new stream element type to chunks of the original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    final def contramapChunkPure[VV <: V, V2](f: Chunk[V2] => Chunk[VV])(using
        t1: Tag[Poll[Chunk[VV]]],
        t2: Tag[Poll[Chunk[V2]]],
        fr: Frame
    ): Sink[V2, A, S] =
        Sink:
            ArrowEffect.handleLoop[Const[Unit], Const[Maybe[Chunk[VV]]], Poll[Chunk[VV]], A, S, Poll[Chunk[V2]]](t1, poll)(
                [C] =>
                    (_, cont) =>
                        Poll.andMap[Chunk[V2]]: maybeChunkV2 =>
                            val maybeChunkV = maybeChunkV2.map(f)
                            Loop.continue(cont(maybeChunkV))
            )

    /** Transform a sink to consume a stream of a different element type using an effectful mapping function that transforms streamed
      * chunks.
      *
      * @param f
      *   Effectful function mapping chunks of new stream element type to chunks of the original stream element type
      * @return
      *   A new sink that processes streams of the new element type
      */
    final def contramapChunk[VV <: V, V2, S2](f: Chunk[V2] => Chunk[VV] < S2)(using
        t1: Tag[Poll[Chunk[VV]]],
        t2: Tag[Poll[Chunk[V2]]],
        fr: Frame
    ): Sink[V2, A, S & S2] =
        Sink:
            ArrowEffect.handleLoop[Const[Unit], Const[Maybe[Chunk[VV]]], Poll[Chunk[VV]], A, S, S2 & Poll[Chunk[V2]]](t1, poll)(
                [C] =>
                    (_, cont) =>
                        Poll.andMap[Chunk[V2]]:
                            case Absent => Loop.continue(cont(Absent))
                            case Present(chunk2) =>
                                f(chunk2).map: chunk1 =>
                                    Loop.continue(cont(Present(chunk1)))
            )

    /** Transform a sink to produce a new output type using a function that transforms the original pipe's result.
      *
      * @param f
      *   Function mapping the original output to a new output value
      * @return
      *   A new sink that processes the same kind of stream to produce a new output type
      */
    final def map[B, S2](f: A => (B < S2))(
        using fr: Frame
    ): Sink[V, B, S & S2] =
        Sink(poll.map((a: A) => f(a)))
    end map

    /** Consumes a stream and produces an output
      *
      * @see
      *   [[kyo.Stream.into]]
      *
      * @param stream
      *   Stream to be processed
      * @return
      *   An effect generating an output value from elements consumed from the stream
      */
    final def drain[VV <: V, S2](stream: Stream[VV, S2])(using
        emitTag: Tag[Emit[Chunk[VV]]],
        pollTag: Tag[Poll[Chunk[VV]]],
        fr: Frame
    ): A < (S & S2) =
        Loop(stream.emit, poll: A < (Poll[Chunk[VV]] & S)) { (emit, poll) =>
            ArrowEffect.handleFirst(pollTag, poll)(
                handle = [C] =>
                    (_, pollCont) =>
                        ArrowEffect.handleFirst(emitTag, emit)(
                            handle = [C2] =>
                                (emitted, emitCont) =>
                                    Loop.continue(emitCont(()), pollCont(Maybe(emitted))),
                            done = _ => Loop.continue(Kyo.unit, pollCont(Absent))
                    ),
                done = a =>
                    Loop.done(a)
            )
        }
end Sink

object Sink:
    @nowarn("msg=anonymous")
    inline def apply[V, A, S](inline v: => A < (Poll[Chunk[V]] & S)): Sink[V, A, S] =
        new Sink[V, A, S]:
            def poll: A < (Poll[Chunk[V]] & S) = v

    private val _empty = Sink(())

    /** A sink that does nothing: returns an empty (Unit) value without ever running the input stream * */
    def empty[V]: Sink[V, Unit, Any] = _empty

    /** Construct a sink that runs a stream of element type `V` without producing any value.
      *
      * @return
      *   A sink that runs a stream without producing a value
      */
    def discard[V](using Tag[Poll[Chunk[V]]], Frame): Sink[V, Unit, Any] =
        Sink:
            Loop.foreach:
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done
                    case Present(_) =>
                        Loop.continue

    /** Construct a sink that accumulates all elements of a stream of type `V` into a chunk.
      *
      * @return
      *   A sink that produces a chunk of all elements emitted by the source stream
      */
    def collect[V](using Tag[Poll[Chunk[V]]], Frame): Sink[V, Chunk[V], Any] =
        Sink:
            Loop(Chunk.empty[V]): currentChunk =>
                Poll.andMap[Chunk[V]]:
                    case Absent => Loop.done(currentChunk)
                    case Present(c) =>
                        Loop.continue(currentChunk.concat(c))
    end collect

    /** Construct a sink that counts streaming elements.
      *
      * @return
      *   A sink that produces the count of all elements emitted by source stream
      */
    def count[V](using Tag[Poll[Chunk[V]]], Frame): Sink[V, Int, Any] =
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
    def foreach[V, S](f: V => Unit < S)(using Tag[Poll[Chunk[V]]], Frame): Sink[V, Unit, S] =
        Sink:
            Loop.foreach:
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
    def foreachChunk[V, S](f: Chunk[V] => Unit < S)(using Tag[Poll[Chunk[V]]], Frame): Sink[V, Unit, S] =
        Sink:
            Loop.foreach:
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
        t2: Tag[Poll[Chunk[V]]],
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
        t2: Tag[Poll[Chunk[V]]],
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

    def last[V](using Tag[Poll[Chunk[V]]], Frame): Sink[V, Maybe[V], Any] =
        Sink:
            Loop(Absent: Maybe[V]): state =>
                Poll.andMap[Chunk[V]]:
                    case Absent     => Loop.done(state)
                    case Present(c) => Loop.continue(c.lastMaybe.orElse(state))
    end last

    /** Combine two sinks, processing source stream in tandem.
      */
    def zip[V, A, B, S](a: Sink[V, A, S], b: Sink[V, B, S])(using Tag[Poll[Chunk[V]]], Frame): Sink[V, (A, B), S] =
        a.zip(b)

    /** Combine three sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S])(using Tag[Poll[Chunk[V]]], Frame): Sink[V, (A, B, C), S] =
        a.zip(zip(b, c)).map:
            case (a, tail) => a *: tail

    /** Combine four sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S], d: Sink[V, D, S])(using
        Tag[Poll[Chunk[V]]],
        Frame
    ): Sink[V, (A, B, C, D), S] =
        a.zip(zip(b, c, d)).map:
            case (a, tail) => a *: tail

    /** Combine five sinks, processing source stream in tandem.
      */
    def zip[V, A, B, C, D, E, S](a: Sink[V, A, S], b: Sink[V, B, S], c: Sink[V, C, S], d: Sink[V, D, S], e: Sink[V, E, S])(using
        Tag[Poll[Chunk[V]]],
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
        Tag[Poll[Chunk[V]]],
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
        Tag[Poll[Chunk[V]]],
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
        Tag[Poll[Chunk[V]]],
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
        Tag[Poll[Chunk[V]]],
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
        Tag[Poll[Chunk[V]]],
        Frame
    ): Sink[V, (A, B, C, D, E, F, G, H, I, J), S] =
        a.zip(zip(b, c, d, e, f, g, h, i, j)).map:
            case (a, tail) => a *: tail

end Sink
