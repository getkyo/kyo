package kyo

import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

/** Represents a stream of values of type `V` with effects of type `S`.
  *
  * Stream provides a high-level abstraction for working with sequences of values that can be processed and transformed lazily. It
  * encapsulates an effect that, when executed, emits chunks of values, combining the benefits of both push and pull-based streaming models
  * while hiding their complexity.
  *
  * Streams are built with rich transformation capabilities including mapping, filtering, folding, and concatenation. The implementation
  * uses chunked processing for efficiency, enabling optimized operations on batches of data rather than individual elements. The lazy
  * nature of Stream means values are only produced when needed and consumed downstream.
  *
  * Internally, Stream uses the Emit effect to produce chunks of values and can be connected with Poll for controlled consumption. This
  * design provides automatic flow control and backpressure, ensuring producers don't overwhelm consumers. Streams can be run to collect all
  * values or processed incrementally with operations like foreach and fold.
  *
  * The Stream abstraction is particularly valuable for data processing pipelines, event streams, or any scenario requiring transformation
  * of potentially infinite sequences of values.
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
sealed abstract class Stream[V, -S] extends Serializable:

    /** Returns the effect that produces acknowledgments and emits chunks of values. */
    def emit: Unit < (Emit[Chunk[V]] & S)

    /** Concatenates this stream with another stream.
      *
      * @param other
      *   The stream to concatenate with this one
      * @return
      *   A new stream that emits all values from this stream, followed by all values from the other stream
      */
    def concat[S2](other: Stream[V, S2])(using Frame): Stream[V, S & S2] =
        Stream(emit.map(_ => other.emit))

    /** Transforms each value in the stream using the given pure function.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream with transformed values
      */
    def map[V2](f: V => V2)(using
        t1: Tag[Emit[Chunk[V]]],
        t2: Tag[Emit[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        fr: Frame
    ): Stream[V2, S] =
        Stream[V2, S](ArrowEffect.handleState(t1, (), emit)(
            [C] =>
                (input, _, cont) =>
                    val c = input.map(f)
                    if c.isEmpty then ((), cont(()))
                    else Emit.valueWith(c)(((), cont(())))
        ))

    /** Transforms each value in the stream using the given effectful function.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream with transformed values
      */
    def map[V2, S2](f: V => V2 < S2)(using Tag[Emit[Chunk[V]]], Tag[Emit[Chunk[V2]]], Frame): Stream[V2, S & S2] =
        mapChunk(c => Kyo.foreach(c)(f))

    /** Transforms each chunk in the stream using the given pure function.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream with transformed chunks
      */
    def mapChunk[V2](f: Chunk[V] => Seq[V2])(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        discr: Stream.Dummy,
        frame: Frame
    ): Stream[V2, S] =
        Stream[V2, S](ArrowEffect.handleState(tagV, (), emit)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then ((), cont(()))
                    else
                        val s = f(input)
                        if s.isEmpty then ((), cont(()))
                        else
                            Emit.valueWith(Chunk.from(s))(((), cont(())))
                    end if
        ))

    /** Transforms each chunk in the stream using the given effectful function.
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
                        Emit.valueWith(Chunk.empty[V2])(((), cont(())))
                    else
                        f(input).map(c => Emit.valueWith(Chunk.from(c))(((), cont(()))))
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
                    Kyo
                        .foreachDiscard(input)(v => f(v).map(_.emit))
                        .map(unit => ((), cont(unit)))
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
                        Emit.valueWith(Chunk.empty[V2])(((), cont(())))
                    else
                        f(input).map(_.emit).map(unit => ((), cont(unit)))
        ))

    /** Applies a side-effecting function to each element in the stream without altering them.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream runs f while emitting values
      */
    def tap[S1](f: V => Any < S1)(
        using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame
    ): Stream[V, S & S1] =
        Stream:
            ArrowEffect.handleState(tag, (), emit: Unit < (Emit[Chunk[V]] & S & S1)):
                [C] =>
                    (input, _, cont) =>
                        Kyo.foreachDiscard(input)(f).andThen:
                            Emit.valueWith(input)(((), cont(())))

    /** Applies a side-effecting function to each chunk in the stream without altering them.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream runs f while emitting chunks
      */
    def tapChunk[S1](f: Chunk[V] => Any < S1)(
        using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame
    ): Stream[V, S & S1] =
        Stream:
            ArrowEffect.handleState(tag, (), emit: Unit < (Emit[Chunk[V]] & S & S1)):
                [C] =>
                    (input, _, cont) =>
                        f(input).andThen:
                            Emit.valueWith(input)(((), cont(())))

    /** Takes the first n elements from the stream.
      *
      * @param n
      *   The number of elements to take
      * @return
      *   A new stream containing at most n elements from the original stream
      */
    def take(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        if n <= 0 then Stream.empty
        else
            Stream[V, S](ArrowEffect.handleState(tag, n, emit)(
                [C] =>
                    (input, state, cont) =>
                        val c   = input.take(state)
                        val nst = state - c.size
                        Emit.valueWith(c)(
                            (nst, if nst <= 0 then Kyo.unit else cont(()))
                    )
            ))
        end if
    end take

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
                            Emit.valueWith(input)((0, cont(())))
                        else
                            val c = input.dropLeft(state)
                            if c.isEmpty then (state - input.size, cont(()))
                            else Emit.valueWith(c)((0, cont(())))
            ))

    /** Takes elements from the stream while the pure predicate is true.
      *
      * @param f
      *   The pure predicate function
      * @return
      *   A new stream containing elements that satisfy the predicate
      */
    def takeWhile(f: V => Boolean)(using
        tag: Tag[Emit[Chunk[V]]],
        discr: Stream.Dummy,
        frame: Frame
    ): Stream[V, S] =
        Stream[V, S](ArrowEffect.handleState(tag, true, emit)(
            [C] =>
                (input, state, cont) =>
                    if !state then (false, Kyo.lift[Unit, Emit[Chunk[V]] & S](()))
                    else if input.isEmpty then (state, cont(()))
                    else
                        val c = input.takeWhile(f)
                        Emit.valueWith(c)((c.size == input.size, cont(())))
        ))
    end takeWhile

    /** Takes elements from the stream while the effectful predicate is true.
      *
      * @param f
      *   The effectful predicate function
      * @return
      *   A new stream containing elements that satisfy the predicate
      */
    def takeWhile[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](ArrowEffect.handleState(tag, true, emit)(
            [C] =>
                (input, state, cont) =>
                    if !state then (false, Kyo.lift[Unit, Emit[Chunk[V]] & S](()))
                    else
                        Kyo.takeWhile(input)(f).map { c =>
                            Emit.valueWith(c)((c.size == input.size, cont(())))
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
                            if c.isEmpty then (true, cont(()))
                            else Emit.valueWith(c)((false, cont(())))
                        }
                    else
                        Emit.valueWith(input)((false, cont(())))
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
                        if c.isEmpty then ((), cont(()))
                        else Emit.valueWith(c)(((), cont(())))
                }
        ))

    def filter(f: V => Boolean)(using
        tag: Tag[Emit[Chunk[V]]],
        discr: Flat[Boolean],
        frame: Frame
    ): Stream[V, S] =
        Stream[V, S](ArrowEffect.handleState(tag, (), emit)(
            [C] =>
                (input, _, cont) =>
                    val c = input.filter(f)
                    if c.isEmpty then ((), cont(()))
                    else Emit.valueWith(c)(((), cont(())))
        ))

    /** Transform the stream with a partial function, filtering out values for which the partial function is undefined. Combines the
      * functionality of map and filter.
      *
      * @param f
      *   Partial function transforming V to V2
      * @return
      *   A new stream containing transformed elements
      */
    def collect[V2, S2](f: V => Maybe[V2] < S2)(using
        tag: Tag[Emit[Chunk[V]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream[V2, S & S2](ArrowEffect.handleState(tag, (), emit)(
            [C] =>
                (input, _, cont) =>
                    Kyo.collect(input)(f).map: c =>
                        Emit.valueWith(c)(((), cont(())))
        ))

    def collect[V2](f: V => Maybe[V2])(using
        tag: Tag[Emit[Chunk[V]]],
        t2: Tag[Emit[Chunk[V2]]],
        discr: Stream.Dummy,
        frame: Frame
    ): Stream[V2, S] =
        Stream[V2, S](ArrowEffect.handleState(tag, (), emit)(
            [C] =>
                (input, _, cont) =>
                    val c = input.map(f).collect({ case Present(v) => v })
                    if c.isEmpty then ((), cont(()))
                    else Emit.valueWith(c)(((), cont(())))
        ))

    /** Transform the stream with a partial function, terminating the stream when the first element is encountered for which the partial
      * function is undefined. Combines the functionality of map and takeWhile.
      *
      * @param f
      *   Partial function transforming V to V2
      * @return
      *   A new stream containing transformed elements
      */
    def collectWhile[V2, S2](f: V => Maybe[V2] < S2)(using
        tag: Tag[Emit[Chunk[V]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream[V2, S & S2](ArrowEffect.handleState(tag, (), emit)(
            [C] =>
                (input, _, cont) =>
                    Kyo.foreach(input)(f)
                        .map(_.takeWhile(_.isDefined)
                            .collect({ case Present(v) => v }))
                        .map: c =>
                            if c.isEmpty && c.size != input.size then ((), Kyo.unit)
                            else
                                Emit.valueWith(c):
                                    if c.size != input.size then ((), Kyo.unit)
                                    else ((), cont(()))
        ))

    def collectWhile[V2](f: V => Maybe[V2])(using
        tag: Tag[Emit[Chunk[V]]],
        t2: Tag[Emit[Chunk[V2]]],
        discr: Stream.Dummy,
        frame: Frame
    ): Stream[V2, S] =
        Stream[V2, S](ArrowEffect.handleState(tag, (), emit)(
            [C] =>
                (input, _, cont) =>
                    val c = input.map(f).takeWhile(_.isDefined).collect({ case Present(v) => v })
                    if c.isEmpty && c.size != input.size then ((), Kyo.unit)
                    else
                        Emit.valueWith(c):
                            if c.size != input.size then ((), Kyo.unit)
                            else ((), cont(()))
                    end if
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
                    Emit.valueWith(c) {
                        (newState, cont(()))
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
            ArrowEffect.handleState(tag, Chunk.empty[V], emit.andThen(Emit.value(Chunk.empty[V])))(
                [C] =>
                    (input, buffer, cont) =>
                        if input.isEmpty && buffer.nonEmpty then
                            Emit.valueWith(buffer)((Chunk.empty, cont(())))
                        else
                            val combined = buffer.concat(input)
                            if combined.size < _chunkSize then
                                (combined, cont(()))
                            else
                                Loop(combined: Chunk[V]) { current =>
                                    if current.size < _chunkSize then
                                        Loop.done((current, cont(())))
                                    else
                                        Emit.valueWith(current.take(_chunkSize)) {
                                            Loop.continue(current.dropLeft(_chunkSize))
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
    def discard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < S =
        ArrowEffect.handle(tag, emit)(
            [C] => (input, cont) => cont(())
        )

    /** Runs the stream and applies the given function to each emitted value.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A unit effect that runs the stream and applies f to each value
      */
    def foreach[S2](f: V => Any < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        foreachChunk(c => Kyo.foreachDiscard(c)(f))

    /** Runs the stream and applies the given function to each emitted chunk.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A unit effect that runs the stream and applies f to each chunk
      */
    def foreachChunk[S2](f: Chunk[V] => Any < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        ArrowEffect.handle(tag, emit)(
            [C] =>
                (input, cont) =>
                    if !input.isEmpty then
                        f(input).andThen(cont(()))
                    else
                        cont(())
        )

    /** Runs the stream and folds over its values using the given pure function and initial accumulator.
      *
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The folding function
      * @return
      *   The final accumulated value
      */
    def fold[A](acc: A)(f: (A, V) => A)(using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame
    ): A < S =
        ArrowEffect.handleState(tag, acc, emit)(
            handle = [C] =>
                (input, state, cont) =>
                    (input.foldLeft(state)(f), cont(())),
            done = (state, _) => state
        )

    /** Runs the stream and folds over its values using the given effectful function and initial accumulator.
      *
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The folding function
      * @return
      *   The final accumulated value
      */
    def foldKyo[A, S2](acc: A)(f: (A, V) => A < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): A < (S & S2) =
        ArrowEffect.handleState(tag, acc, emit)(
            handle = [C] =>
                (input, state, cont) =>
                    Kyo.foldLeft(input)(state)(f).map((_, cont(()))),
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
                    (state.append(input), cont(())),
            done = (state, _) => state.flattenChunk
        )

    /** Split the stream into a chunk that contains the first n elements of the stream, and the rest of the stream as a new stream.
      *
      * @param n
      *   The number of elements to take
      * @return
      *   A tuple containing chunk of the first n elements and the rest of the stream
      */
    def splitAt(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): (Chunk[V], Stream[V, S]) < S =
        val emptyEmit = Maybe.empty[Unit < (Emit[Chunk[V]] & S)]
        ArrowEffect.handleState(tag, (Chunk.empty[V], emptyEmit), emit)(
            handle = [C] =>
                (input, state, cont) =>
                    val (chunk, _)    = state
                    val appendedChunk = chunk.concat(input)
                    if (appendedChunk.size) < n then
                        (appendedChunk -> emptyEmit, cont(()))
                    else
                        val (taken, rest) = appendedChunk.splitAt(n)
                        val restEmit      = Maybe.Present(Emit.valueWith(rest)(cont(())))
                        (taken -> restEmit, Kyo.unit)
                    end if
            ,
            done = (state, _) =>
                val (chunk, lastEmit) = state
                lastEmit match
                    case Maybe.Present(emit) => (chunk, Stream(emit))
                    case Maybe.Absent        => (chunk, Stream.empty)
                end match
        )
    end splitAt
end Stream

object Stream:
    @nowarn("msg=anonymous")
    inline def apply[V, S](inline v: => Unit < (Emit[Chunk[V]] & S)): Stream[V, S] =
        new Stream[V, S]:
            def emit: Unit < (Emit[Chunk[V]] & S) = v

    private val _empty           = Stream(())
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
                Loop(chunk) { (c) =>
                    if _chunkSize >= c.length then
                        Emit.valueWith(c)(Loop.done)
                    else
                        Emit.valueWith(c.take(_chunkSize))(Loop.continue(c.dropLeft(_chunkSize)))
                }
            }

    /** Creates a stream by repeatedly calling a lazily evaluated function, until the return is absent.
      *
      * @param v
      *   The effect that might return a sequence of values
      * @param chunkSize
      *   The size of chunks to emit (default: 4096). Supplying a negative value will result in a chunk size of 1.
      * @return
      *   A stream of values from the sequence
      */
    def repeatPresent[V, S](v: => Maybe[Seq[V]] < S, chunkSize: Int = DefaultChunkSize)(using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame
    ): Stream[V, S] =
        Stream[V, S]:
            Loop(()) { _ =>
                v.map {
                    case Maybe.Present(seq) => Emit.valueWith(Chunk.from(seq))(Loop.continue)
                    case Maybe.Absent       => Emit.valueWith(Chunk.empty[V])(Loop.done)
                }
            }
        .rechunk(chunkSize)

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
                Emit.valueWith(Chunk.empty[Int]) {
                    Loop(start) { (current) =>
                        val continue =
                            if step > 0 then current < end
                            else current > end
                        if !continue then Loop.done
                        else
                            val remaining =
                                if step > 0 then
                                    ((end - current - 1) / step).abs + 1
                                else
                                    ((current - end - 1) / step.abs).abs + 1
                            val size  = _chunkSize min remaining
                            val chunk = Chunk.from(Range(current, current + size * step, step))
                            Emit.valueWith(chunk)(Loop.continue(current + step * size))
                        end if
                    }
                }

    /** A dummy type that can be used as implicit evidence to help the compiler discriminate between overloaded methods.
      */
    sealed class Dummy extends Serializable
    object Dummy:
        given Dummy = new Dummy {}

end Stream
