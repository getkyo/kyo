package kyo

import kyo.Ack.*
import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.nowarn
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
sealed abstract class Stream[V, -S]:

    /** Returns the effect that produces acknowledgments and emits chunks of values. */
    def emit: Ack < (Emit[Chunk[V]] & S)

    private def continue[S2](f: Int => Ack < (Emit[Chunk[V]] & S & S2))(using Frame): Stream[V, S & S2] =
        Stream(emit.map {
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
        Stream[V2, S & S2](ArrowEffect.handleState(tagV, (), emit)(
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
        Stream[V2, S & S2 & S3](ArrowEffect.handleState(tagV, (), emit)(
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
        Stream[V2, S & S2 & S3](ArrowEffect.handleState(tagV, (), emit)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then
                        Emit.andMap(Chunk.empty[V2])(ack => ((), cont(ack)))
                    else
                        f(input).map(_.emit).map(ack => ((), cont(ack)))
        ))

    private def discard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream(ArrowEffect.handle(tag, emit)(
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
            Stream[V, S](ArrowEffect.handleState(tag, n, emit)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            (0, cont(Stop))
                        else
                            val c   = input.take(state)
                            val nst = state - c.size
                            Emit.andMap(c)(ack => (nst, cont(ack.maxValues(nst))))
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
            Stream[V, S](ArrowEffect.handleState(tag, n, emit)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            Emit.andMap(input)(ack => (0, cont(ack)))
                        else
                            val c = input.dropLeft(state)
                            if c.isEmpty then (state - input.size, cont(Continue()))
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
        Stream[V, S & S2](ArrowEffect.handleState(tag, true, emit)(
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
        Stream[V, S & S2](ArrowEffect.handleState(tag, true, emit)(
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
        Stream[V, S & S2](ArrowEffect.handleState(tag, (), emit)(
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
        Stream[V, S](ArrowEffect.handleState(tag, first, emit)(
            [C] =>
                (input, state, cont) =>
                    val c        = input.changes(state)
                    val newState = if c.isEmpty then state else Maybe(c.last)
                    Emit.andMap(c) { ack =>
                        (newState, cont(ack))
                }
        ))
    end changes

    /** Transforms the stream by regrouping elements into chunks of the specified size.
      *
      * This operation maintains the order of elements while potentially redistributing them into new chunks. Smaller chunks may occur in
      * two cases:
      *   - When there aren't enough remaining elements to form a complete chunk
      *   - When the input stream emits an empty chunk
      *
      * @param chunkSize
      *   The target size for each chunk. Must be positive - negative values will be treated as 1.
      * @return
      *   A new stream with elements regrouped into chunks of the specified size
      */
    def rechunk(chunkSize: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream[V, S]:
            val _chunkSize = chunkSize max 1
            ArrowEffect.handleState(tag, Chunk.empty[V], emit.andThen(Emit(Chunk.empty[V])))(
                [C] =>
                    (input, buffer, cont) =>
                        if input.isEmpty && buffer.nonEmpty then
                            Emit.andMap(buffer)(ack => (Chunk.empty, cont(ack)))
                        else
                            val combined = buffer.concat(input)
                            if combined.size < _chunkSize then
                                (combined, cont(Continue()))
                            else
                                Loop(combined: Chunk[V], Continue(): Ack) { (current, ack) =>
                                    ack match
                                        case Stop => Loop.done((current, cont(Stop)))
                                        case Continue(_) =>
                                            if current.size < _chunkSize then
                                                Loop.done((current, cont(Continue())))
                                            else
                                                Emit.andMap(current.take(_chunkSize)) { nextAck =>
                                                    Loop.continue(current.dropLeft(_chunkSize), nextAck)
                                                }
                                }
                            end if
            )
    end rechunk

    /** Runs the stream and discards all emitted values.
      *
      * @return
      *   A unit effect that runs the stream without collecting results
      */
    def runDiscard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < S =
        ArrowEffect.handle(tag, emit.unit)(
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
        ArrowEffect.handle(tag, emit.unit)(
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
        ArrowEffect.handleState(tag, acc, emit)(
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
        ArrowEffect.handleState(tag, Chunk.empty[Chunk[V]], emit)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(Continue())),
            done = (state, _) => state.flattenChunk
        )

end Stream

object Stream:
    @nowarn("msg=anonymous")
    inline def apply[V, S](inline v: => Ack < (Emit[Chunk[V]] & S)): Stream[V, S] =
        new Stream[V, S]:
            def emit: Ack < (Emit[Chunk[V]] & S) = v

    private val _empty           = Stream(Stop)
    def empty[V]: Stream[V, Any] = _empty.asInstanceOf[Stream[V, Any]]

    /** The default chunk size for streams. */
    inline def DefaultChunkSize: Int = 4096

    /** Creates a stream from a sequence of values.
      *
      * @param v
      *   The effect returning a sequence of values
      * @param chunkSize
      *   The size of chunks to emit (default: 4096). Supplying a negative value will result in a chunk size of 1.
      * @return
      *   A stream of values from the sequence
      */
    def init[V, S](v: => Seq[V] < S, chunkSize: Int = DefaultChunkSize)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream[V, S]:
            v.map { seq =>
                val chunk: Chunk[V] = Chunk.from(seq)
                val _chunkSize      = chunkSize max 1
                Emit.andMap(Chunk.empty[V]) { ack =>
                    Loop(chunk, ack) { (c, ack) =>
                        ack match
                            case Stop =>
                                Loop.done(Stop)
                            case Continue(n) =>
                                if c.isEmpty then Loop.done(Ack.Continue())
                                else
                                    val i = n min _chunkSize
                                    Emit.andMap(c.take(i))(ack => Loop.continue(c.dropLeft(i), ack))
                    }
                }
            }

    /** Creates a stream of integers from start (inclusive) to end (exclusive).
      *
      * @param start
      *   The starting value (inclusive)
      * @param end
      *   The ending value (exclusive)
      * @param step
      *   The step size (default: 1)
      * @param chunkSize
      *   The size of chunks to emit (default: 4096)
      * @return
      *   A stream of integers within the specified range
      */
    def range[S](start: Int, end: Int, step: Int = 1, chunkSize: Int = DefaultChunkSize)(using
        tag: Tag[Emit[Chunk[Int]]],
        frame: Frame
    ): Stream[Int, S] =
        if step == 0 || (start < end && step < 0) || (start > end && step > 0) then empty
        else
            Stream[Int, S]:
                val _chunkSize = chunkSize max 1
                Emit.andMap(Chunk.empty[Int]) { ack =>
                    Loop(start, ack) { (current, ack) =>
                        ack match
                            case Stop =>
                                Loop.done(Stop)
                            case Continue(n) =>
                                val continue =
                                    if step > 0 then current < end
                                    else current > end

                                if !continue then Loop.done(Stop)
                                else
                                    val remaining =
                                        if step > 0 then
                                            ((end - current - 1) / step).abs + 1
                                        else
                                            ((current - end - 1) / step.abs).abs + 1
                                    val size  = (n min _chunkSize) min remaining
                                    val chunk = Chunk.from(Range(current, current + size * step, step))
                                    Emit.andMap(chunk)(ack => Loop.continue(current + step * size, ack))
                                end if
                    }
                }

end Stream
