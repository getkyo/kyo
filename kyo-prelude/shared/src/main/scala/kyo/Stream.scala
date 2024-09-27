package kyo

import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.targetName

/** Represents a stream of values of type `V` with effects of type `S`.
  *
  * A `Stream` is a lazy sequence of values that can be processed and transformed. It encapsulates an effect that, when executed, emits
  * chunks of values.
  *
  * @tparam V
  *   The type of values in the stream
  * @tparam S
  *   The type of effects associated with the stream
  *
  * @param v
  *   The effect that produces acknowledgments and emits chunks of values
  */
final case class Stream[V, -S](v: Ack < (Emit[Chunk[V]] & S)):

    /** Returns the effect that produces acknowledgments and emits chunks of values. */
    def emit: Ack < (Emit[Chunk[V]] & S) = v

    private def continue[S2](f: Int => Ack < (Emit[Chunk[V]] & S & S2))(using Frame): Stream[V, S & S2] =
        Stream(v.map {
            case Stop        => Stop
            case Continue(n) => f(n)
        })

    /** Concatenates this stream with another stream.
      *
      * @param other
      *   The stream to concatenate with this one
      * @return
      *   A new stream that emits all values from this stream, followed by all values from the other stream
      */
    def concat[S2](other: Stream[V, S2])(using Frame): Stream[V, S & S2] =
        continue(_ => other.emit)

    /** Transforms each value in the stream using the given function.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream with transformed values
      */
    def map[V2, S2](f: V => V2 < S2)(using Tag[Emit[Chunk[V]]], Tag[Emit[Chunk[V2]]], Frame): Stream[V2, S & S2] =
        mapChunk(c => Kyo.foreach(c)(f))

    /** Transforms each chunk in the stream using the given function.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream with transformed chunks
      */
    def mapChunk[V2, S2](f: Chunk[V] => Seq[V2] < S2)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream[V2, S & S2](ArrowEffect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then
                        Emit.andMap(Chunk.empty[V2])(ack => ((), cont(ack)))
                    else
                        f(input).map(c => Emit.andMap(Chunk.from(c))(ack => ((), cont(ack))))
        ))

    /** Applies a function to each value in the stream that returns a new stream, and flattens the result.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream that is the result of flattening all the streams produced by f
      */
    def flatMap[S2, V2, S3](f: V => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream[V2, S & S2 & S3](ArrowEffect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    Kyo.foldLeft(input)(Continue(): Ack) { (ack, v) =>
                        ack match
                            case Stop        => Stop
                            case Continue(_) => f(v).map(_.emit)
                    }.map(ack => ((), cont(ack)))
        ))

    /** Applies a function to each chunk in the stream that returns a new stream, and flattens the result.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream that is the result of flattening all the streams produced by f
      */
    def flatMapChunk[S2, V2, S3](f: Chunk[V] => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream[V2, S & S2 & S3](ArrowEffect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then
                        Emit.andMap(Chunk.empty[V2])(ack => ((), cont(ack)))
                    else
                        f(input).map(_.emit).map(ack => ((), cont(ack)))
        ))

    private def discard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream(ArrowEffect.handle(tag, v)(
            [C] => (input, cont) => cont(Stop)
        ))

    /** Takes the first n elements from the stream.
      *
      * @param n
      *   The number of elements to take
      * @return
      *   A new stream containing at most n elements from the original stream
      */
    def take(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        if n <= 0 then discard
        else
            Stream[V, S](ArrowEffect.handle.state(tag, n, v)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            (0, cont(Stop))
                        else
                            val c   = input.take(state)
                            val nst = state - c.size
                            Emit.andMap(c)(ack => (nst, cont(ack.maxItems(nst))))
            ))

    /** Drops the first n elements from the stream.
      *
      * @param n
      *   The number of elements to drop
      * @return
      *   A new stream with the first n elements removed
      */
    def drop(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        if n <= 0 then this
        else
            Stream[V, S](ArrowEffect.handle.state(tag, n, v)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            Emit.andMap(input)(ack => (0, cont(ack)))
                        else
                            val c = input.dropLeft(state)
                            if c.isEmpty then (state - c.size, cont(Continue()))
                            else Emit.andMap(c)(ack => (0, cont(ack)))
            ))

    /** Takes elements from the stream while the predicate is true.
      *
      * @param f
      *   The predicate function
      * @return
      *   A new stream containing elements that satisfy the predicate
      */
    def takeWhile[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](ArrowEffect.handle.state(tag, true, v)(
            [C] =>
                (input, state, cont) =>
                    if !state then (false, cont(Stop))
                    else
                        Kyo.takeWhile(input)(f).map { c =>
                            Emit.andMap(c)(ack => (c.size == input.size, cont(ack)))
                    }
        ))
    end takeWhile

    /** Drops elements from the stream while the predicate is true.
      *
      * @param f
      *   The predicate function
      * @return
      *   A new stream with initial elements that satisfy the predicate removed
      */
    def dropWhile[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](ArrowEffect.handle.state(tag, true, v)(
            [C] =>
                (input, state, cont) =>
                    if state then
                        Kyo.dropWhile(input)(f).map { c =>
                            if c.isEmpty then (true, cont(Continue()))
                            else Emit.andMap(c)(ack => (false, cont(ack)))
                        }
                    else
                        Emit.andMap(input)(ack => (false, cont(ack)))
        ))

    /** Filters the stream to include only elements that satisfy the predicate.
      *
      * @param f
      *   The predicate function
      * @return
      *   A new stream containing only elements that satisfy the predicate
      */
    def filter[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](ArrowEffect.handle.state(tag, (), v)(
            [C] =>
                (input, _, cont) =>
                    Kyo.filter(input)(f).map { c =>
                        if c.isEmpty then ((), cont(Continue()))
                        else Emit.andMap(c)(ack => ((), cont(ack)))
                }
        ))

    /** Emits only elements that are different from their predecessor.
      *
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    def changes(using Tag[Emit[Chunk[V]]], Frame, CanEqual[V, V]): Stream[V, S] =
        changes(Maybe.empty)

    /** Emits only elements that are different from their predecessor, starting with the given first element.
      *
      * @param first
      *   The initial element to compare against
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    def changes(first: V)(using Tag[Emit[Chunk[V]]], Frame, CanEqual[V, V]): Stream[V, S] =
        changes(Maybe(first))

    /** Emits only elements that are different from their predecessor, starting with the given optional first element.
      *
      * @param first
      *   The optional initial element to compare against
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    @targetName("changesMaybe")
    def changes(first: Maybe[V])(using tag: Tag[Emit[Chunk[V]]], frame: Frame, ce: CanEqual[V, V]): Stream[V, S] =
        Stream[V, S](ArrowEffect.handle.state(tag, first, v)(
            [C] =>
                (input, state, cont) =>
                    val c        = input.changes(state)
                    val newState = if c.isEmpty then state else Maybe(c.last)
                    Emit.andMap(c) { ack =>
                        (newState, cont(ack))
                }
        ))
    end changes

    /** Runs the stream and discards all emitted values.
      *
      * @return
      *   A unit effect that runs the stream without collecting results
      */
    def runDiscard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < S =
        ArrowEffect.handle(tag, v.unit)(
            [C] => (input, cont) => cont(Continue())
        )

    /** Runs the stream and applies the given function to each emitted value.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A unit effect that runs the stream and applies f to each value
      */
    def runForeach[S2](f: V => Unit < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        runForeachChunk(c => Kyo.foreachDiscard(c)(f))

    /** Runs the stream and applies the given function to each emitted chunk.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A unit effect that runs the stream and applies f to each chunk
      */
    def runForeachChunk[S2](f: Chunk[V] => Unit < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        ArrowEffect.handle(tag, v.unit)(
            [C] =>
                (input, cont) =>
                    if !input.isEmpty then
                        f(input).andThen(cont(Continue()))
                    else
                        cont(Continue())
        )

    /** Runs the stream and folds over its values using the given function and initial accumulator.
      *
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The folding function
      * @return
      *   The final accumulated value
      */
    def runFold[A, S2](acc: A)(f: (A, V) => A < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): A < (S & S2) =
        ArrowEffect.handle.state(tag, acc, v)(
            handle = [C] =>
                (input, state, cont) =>
                    Kyo.foldLeft(input)(state)(f).map((_, cont(Continue()))),
            done = (state, _) => state
        )

    /** Runs the stream and collects all emitted values into a single chunk.
      *
      * @return
      *   A chunk containing all values emitted by the stream
      */
    def run(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Chunk[V] < S =
        ArrowEffect.handle.state(tag, Chunk.empty[Chunk[V]], v)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(Continue())),
            done = (state, _) => state.flattenChunk
        )

end Stream

object Stream:

    private val _empty           = Stream(Stop)
    def empty[V]: Stream[V, Any] = _empty.asInstanceOf[Stream[V, Any]]

    def init[V, S](seq: Seq[V] < S)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream[V, S](
            seq.map { seq =>
                val chunk: Chunk[V] = Chunk.from(seq)
                Emit.andMap(Chunk.empty[V]) { ack =>
                    Loop(chunk, ack) { (c, ack) =>
                        ack match
                            case Stop =>
                                Loop.done(Stop)
                            case Continue(n) =>
                                if c.isEmpty then Loop.done(Ack.Continue())
                                else Emit.andMap(c.take(n))(ack => Loop.continue(c.dropLeft(n), ack))
                    }
                }
            }
        )

end Stream
