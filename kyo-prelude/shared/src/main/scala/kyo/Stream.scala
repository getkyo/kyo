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
  *   [[kyo.Pipe]] [[kyo.Sink]] for abstracting and composing stream transformation and processing
  * @see
  *   [[kyo.Emit]] for the underlying push-based emission mechanism
  * @see
  *   [[kyo.Poll]] for pull-based consumption with backpressure
  */
sealed abstract class Stream[+V, -S] extends Serializable:

    /** Returns the effect that produces acknowledgments and emits chunks of values. */
    def emit: Unit < (Emit[Chunk[V]] & S)

    /** Concatenates this stream with another stream.
      *
      * @param other
      *   The stream to concatenate with this one
      * @return
      *   A new stream that emits all values from this stream, followed by all values from the other stream
      */
    def concat[VV >: V, S2](other: Stream[VV, S2])(using Frame): Stream[VV, S & S2] =
        Stream(emit.map(_ => other.emit))

    /** Transforms each value in the stream using the given pure function.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream with transformed values
      */
    def mapPure[VV >: V, V2](f: VV => V2)(using
        t1: Tag[Emit[Chunk[VV]]],
        t2: Tag[Emit[Chunk[V2]]],
        ev: NotGiven[V2 <:< (Any < Nothing)],
        fr: Frame
    ): Stream[V2, S] =
        Stream(
            ArrowEffect.handleLoop(t1, emit)(
                [C] =>
                    (input, cont) =>
                        val c = input.map(f)
                        if c.isEmpty then Loop.continue(cont(()))
                        else Emit.valueWith(c)(Loop.continue(cont(())))
            )
        )

    /** Transforms each value in the stream using the given effectful function.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream with transformed values
      */
    def map[VV >: V, V2, S2](f: VV => V2 < S2)(using Tag[Emit[Chunk[VV]]], Tag[Emit[Chunk[V2]]], Frame): Stream[V2, S & S2] =
        mapChunk[VV, V2, S2](c => Kyo.foreach(c)(f))

    /** Transforms each chunk in the stream using the given pure function.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream with transformed chunks
      */
    def mapChunkPure[VV >: V, V2](f: Chunk[VV] => Seq[V2])(
        using
        tagV: Tag[Emit[Chunk[VV]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S] =
        Stream(
            ArrowEffect.handleLoop(tagV, emit)(
                [C] =>
                    (input, cont) =>
                        if input.isEmpty then Loop.continue(cont(()))
                        else
                            val s = f(input)
                            if s.isEmpty then
                                Loop.continue(cont(()))
                            else
                                Emit.valueWith(Chunk.from(s))(Loop.continue(cont(())))
                            end if
                        end if
            )
        )

    /** Transforms each chunk in the stream using the given effectful function.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream with transformed chunks
      */
    def mapChunk[VV >: V, V2, S2](f: Chunk[VV] => Seq[V2] < S2)(
        using
        tagV: Tag[Emit[Chunk[VV]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tagV, emit)(
                [C] =>
                    (input, cont) =>
                        if input.isEmpty then
                            Emit.valueWith(Chunk.empty[V2])(Loop.continue(cont(())))
                        else
                            f(input).map(c => Emit.valueWith(Chunk.from(c))(Loop.continue(cont(()))))
            )
        )

    /** Applies a function to each value in the stream that returns a new stream, and flattens the result.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream that is the result of flattening all the streams produced by f
      */
    def flatMap[VV >: V, S2, V2, S3](f: VV => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[VV]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream(
            ArrowEffect.handleLoop(tagV, emit)(
                [C] =>
                    (input, cont) =>
                        Kyo.foreachDiscard(input)(v => f(v).map(_.emit))
                            .map(unit => Loop.continue(cont(unit)))
            )
        )

    /** Applies a function to each chunk in the stream that returns a new stream, and flattens the result.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream that is the result of flattening all the streams produced by f
      */
    def flatMapChunk[VV >: V, S2, V2, S3](f: Chunk[VV] => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[VV]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream(
            ArrowEffect.handleLoop(tagV, emit)(
                [C] =>
                    (input, cont) =>
                        if input.isEmpty then
                            Emit.valueWith(Chunk.empty[V2])(Loop.continue(cont(())))
                        else
                            f(input).map(_.emit).map(unit => Loop.continue(cont(unit)))
            )
        )

    /** Applies a side-effecting function to each element in the stream without altering them.
      *
      * @param f
      *   The function to apply to each value
      * @return
      *   A new stream runs f while emitting values
      */
    def tap[VV >: V, S1](f: VV => Any < S1)(
        using
        tag: Tag[Emit[Chunk[VV]]],
        frame: Frame
    ): Stream[VV, S & S1] =
        Stream:
            ArrowEffect.handleLoop(tag, emit: Unit < (Emit[Chunk[VV]] & S & S1))(
                [C] =>
                    (input, cont) =>
                        Kyo.foreachDiscard(input)(f).andThen:
                            Emit.valueWith(input)(Loop.continue(cont(())))
            )

    /** Applies a side-effecting function to each chunk in the stream without altering them.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A new stream runs f while emitting chunks
      */
    def tapChunk[VV >: V, S1](f: Chunk[VV] => Any < S1)(
        using
        tag: Tag[Emit[Chunk[VV]]],
        frame: Frame
    ): Stream[VV, S & S1] =
        Stream(
            ArrowEffect.handleLoop(tag, emit: Unit < (Emit[Chunk[VV]] & S & S1))(
                [C] =>
                    (input, cont) =>
                        f(input).andThen:
                            Emit.valueWith(input)(Loop.continue(cont(())))
            )
        )

    /** Takes the first n elements from the stream.
      *
      * @param n
      *   The number of elements to take
      * @return
      *   A new stream containing at most n elements from the original stream
      */
    def take[VV >: V](n: Int)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S] =
        if n <= 0 then Stream.empty
        else
            Stream(
                ArrowEffect.handleLoop(tag, n, emit)(
                    [C] =>
                        (input, state, cont) =>
                            val c   = input.take(state)
                            val nst = state - c.size
                            Emit.valueWith(c)(
                                Loop.continue(nst, if nst <= 0 then Kyo.unit else cont(()))
                        )
                )
            )
        end if
    end take

    /** Drops the first n elements from the stream.
      *
      * @param n
      *   The number of elements to drop
      * @return
      *   A new stream with the first n elements removed
      */
    def drop[VV >: V](n: Int)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S] =
        if n <= 0 then this
        else
            Stream(
                ArrowEffect.handleLoop(tag, n, emit)(
                    [C] =>
                        (input, state, cont) =>
                            if state == 0 then
                                Emit.valueWith(input)(Loop.continue(0, cont(())))
                            else
                                val c = input.dropLeft(state)
                                if c.isEmpty then Loop.continue(state - input.size, cont(()))
                                else Emit.valueWith(c)(Loop.continue(0, cont(())))
                )
            )

    /** Takes elements from the stream while the pure predicate is true.
      *
      * @param f
      *   The pure predicate function
      * @return
      *   A new stream containing elements that satisfy the predicate
      */
    def takeWhilePure[VV >: V](f: VV => Boolean)(using
        tag: Tag[Emit[Chunk[VV]]],
        frame: Frame
    ): Stream[VV, S] =
        Stream(
            ArrowEffect.handleLoop(tag, true, emit)(
                [C] =>
                    (input, state, cont) =>
                        if !state then Loop.continue(false, Kyo.unit)
                        else if input.isEmpty then Loop.continue(state, cont(()))
                        else
                            val c = input.takeWhile(f)
                            Emit.valueWith(c)(Loop.continue(c.size == input.size, cont(())))
            )
        )
    end takeWhilePure

    /** Takes elements from the stream while the effectful predicate is true.
      *
      * @param f
      *   The effectful predicate function
      * @return
      *   A new stream containing elements that satisfy the predicate
      */
    def takeWhile[VV >: V, S2](f: VV => Boolean < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tag, true, emit)(
                [C] =>
                    (input, state, cont) =>
                        if !state then Loop.continue(false, Kyo.unit)
                        else
                            Kyo.takeWhile(input)(f).map { c =>
                                Emit.valueWith(c)(Loop.continue(c.size == input.size, cont(())))
                        }
            )
        )
    end takeWhile

    /** Drops elements from the stream while the predicate is true.
      *
      * @param f
      *   The pure predicate function
      * @return
      *   A new stream with initial elements that satisfy the predicate removed
      */
    def dropWhilePure[VV >: V](f: VV => Boolean)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S] =
        Stream(
            ArrowEffect.handleLoop(tag, true, emit)(
                [C] =>
                    (input, state, cont) =>
                        if state then
                            val chunk = input.dropWhile(f)
                            if chunk.isEmpty then Loop.continue(true, cont(()))
                            else Emit.valueWith(chunk)(Loop.continue(false, cont(())))
                        else
                            Emit.valueWith(input)(Loop.continue(false, cont(())))
            )
        )

    /** Drops elements from the stream while the effectful predicate is true.
      *
      * @param f
      *   The effectful predicate function
      * @return
      *   A new stream with initial elements that satisfy the predicate removed
      */
    def dropWhile[VV >: V, S2](f: VV => Boolean < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tag, true, emit)(
                [C] =>
                    (input, state, cont) =>
                        if state then
                            Kyo.dropWhile(input)(f).map { c =>
                                if c.isEmpty then Loop.continue(true, cont(()))
                                else Emit.valueWith(c)(Loop.continue(false, cont(())))
                            }
                        else
                            Emit.valueWith(input)(Loop.continue(false, cont(())))
            )
        )

    /** Filters the stream to include only elements that satisfy the predicate.
      *
      * @param f
      *   The predicate function
      * @return
      *   A new stream containing only elements that satisfy the predicate
      */
    def filter[VV >: V, S2](f: VV => Boolean < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        Kyo.filter(input)(f).map { c =>
                            if c.isEmpty then Loop.continue(cont(()))
                            else Emit.valueWith(c)(Loop.continue(cont(())))
                    }
            )
        )

    def filterPure[VV >: V](f: VV => Boolean)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        val c = input.filter(f)
                        if c.isEmpty then Loop.continue(cont(()))
                        else Emit.valueWith(c)(Loop.continue(cont(())))
            )
        )

    /** Transform the stream with a partial function, filtering out values for which the partial function is undefined. Combines the
      * functionality of map and filter.
      *
      * @param f
      *   Partial function transforming V to V2
      * @return
      *   A new stream containing transformed elements
      */
    def collect[VV >: V, V2, S2](f: VV => Maybe[V2] < S2)(using
        tag: Tag[Emit[Chunk[VV]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        Kyo.collect(input)(f).map { c =>
                            Emit.valueWith(c)(Loop.continue(cont(())))
                    }
            )
        )

    def collectPure[VV >: V, V2](f: VV => Maybe[V2])(using
        tag: Tag[Emit[Chunk[VV]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        val c = input.map(f).collect({ case Present(v) => v })
                        if c.isEmpty then Loop.continue(cont(()))
                        else Emit.valueWith(c)(Loop.continue(cont(())))
            )
        )

    /** Transform the stream with a partial function, terminating the stream when the first element is encountered for which the partial
      * function is undefined. Combines the functionality of map and takeWhile.
      *
      * @param f
      *   Partial function transforming V to V2
      * @return
      *   A new stream containing transformed elements
      */
    def collectWhile[VV >: V, V2, S2](f: VV => Maybe[V2] < S2)(using
        tag: Tag[Emit[Chunk[VV]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        Kyo.foreach(input)(f)
                            .map(_.takeWhile(_.isDefined).collect({ case Present(v) => v }))
                            .map { c =>
                                if c.isEmpty && c.size != input.size then Loop.done
                                else
                                    Emit.valueWith(c) {
                                        if c.size != input.size then Loop.done
                                        else Loop.continue(cont(()))
                                    }
                        }
            )
        )

    def collectWhilePure[VV >: V, V2](f: VV => Maybe[V2])(using
        tag: Tag[Emit[Chunk[VV]]],
        t2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S] =
        Stream(
            ArrowEffect.handleLoop(tag, emit)(
                [C] =>
                    (input, cont) =>
                        val c = input.map(f).takeWhile(_.isDefined).collect({ case Present(v) => v })
                        if c.isEmpty && c.size != input.size then Loop.done
                        else
                            Emit.valueWith(c):
                                if c.size != input.size then Loop.done
                                else Loop.continue(cont(()))
                        end if
            )
        )

    /** Emits only elements that are different from their predecessor.
      *
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    def changes[VV >: V](using Tag[Emit[Chunk[VV]]], Frame, CanEqual[VV, VV]): Stream[VV, S] =
        changes[VV](Maybe.empty)

    /** Emits only elements that are different from their predecessor, starting with the given first element.
      *
      * @param first
      *   The initial element to compare against
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    def changes[VV >: V](first: VV)(using Tag[Emit[Chunk[VV]]], Frame, CanEqual[VV, VV]): Stream[VV, S] =
        changes[VV](Maybe(first))

    /** Emits only elements that are different from their predecessor, starting with the given optional first element.
      *
      * @param first
      *   The optional initial element to compare against
      * @return
      *   A new stream with consecutive duplicate elements removed
      */
    @targetName("changesMaybe")
    def changes[VV >: V](first: Maybe[VV])(using tag: Tag[Emit[Chunk[VV]]], frame: Frame, ce: CanEqual[VV, VV]): Stream[VV, S] =
        Stream(
            ArrowEffect.handleLoop(tag, first, emit)(
                [C] =>
                    (input, state, cont) =>
                        val c        = input.changes(state)
                        val newState = if c.isEmpty then state else Maybe(c.last)
                        Emit.valueWith(c) {
                            Loop.continue(newState, cont(()))
                    }
            )
        )
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
    def rechunk[VV >: V](chunkSize: Int)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Stream[VV, S] =
        Stream[VV, S]:
            val _chunkSize = chunkSize max 1
            ArrowEffect.handleLoop(tag, Chunk.empty[VV], emit.andThen(Emit.value(Chunk.empty[VV])))(
                [C] =>
                    (input, buffer, cont) =>
                        if input.isEmpty && buffer.nonEmpty then
                            Emit.valueWith(buffer)(Loop.continue(Chunk.empty, cont(())))
                        else
                            val combined = buffer.concat(input)
                            if combined.size < _chunkSize then
                                Loop.continue(combined, cont(()))
                            else
                                Loop(combined: Chunk[VV]) { current =>
                                    if current.size < _chunkSize then
                                        Loop.done(Loop.continue(current, cont(())))
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
    def discard[VV >: V](using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Unit < S =
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
    def foreach[VV >: V, S2](f: VV => Any < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Unit < (S & S2) =
        foreachChunk[VV, S2](c => Kyo.foreachDiscard(c)(f))

    /** Runs the stream and applies the given function to each emitted chunk.
      *
      * @param f
      *   The function to apply to each chunk
      * @return
      *   A unit effect that runs the stream and applies f to each chunk
      */
    def foreachChunk[VV >: V, S2](f: Chunk[VV] => Any < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Unit < (S & S2) =
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
    def foldPure[VV >: V, A](acc: A)(f: (A, VV) => A)(using
        tag: Tag[Emit[Chunk[VV]]],
        frame: Frame
    ): A < S =
        ArrowEffect.handleLoop(tag, acc, emit)(
            handle = [C] =>
                (input, state, cont) =>
                    Loop.continue(input.foldLeft(state)(f), cont(())),
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
    def fold[VV >: V, A, S2](acc: A)(f: (A, VV) => A < S2)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): A < (S & S2) =
        ArrowEffect.handleLoop(tag, acc, emit)(
            handle = [C] =>
                (input, state, cont) =>
                    Kyo.foldLeft(input)(state)(f).map(Loop.continue(_, cont(()))),
            done = (state, _) => state
        )

    /** Runs the stream and collects all emitted values into a single chunk.
      *
      * @return
      *   A chunk containing all values emitted by the stream
      */
    def run[VV >: V](using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Chunk[VV] < S =
        ArrowEffect.handleLoop(tag, Chunk.empty[Chunk[VV]], emit)(
            handle = [C] =>
                (input, state, cont) =>
                    Loop.continue(state.append(input), cont(())),
            done = (state, _) => state.flattenChunk
        )

    /** Split the stream into a chunk that contains the first n elements of the stream, and the rest of the stream as a new stream.
      *
      * @param n
      *   The number of elements to take
      * @return
      *   A tuple containing chunk of the first n elements and the rest of the stream
      */
    def splitAt[VV >: V](n: Int)(using tag: Tag[Emit[Chunk[VV]]], frame: Frame): (Chunk[VV], Stream[VV, S]) < S =
        Loop(emit: Unit < (Emit[Chunk[VV]] & S), Chunk.empty[VV]): (curEmit, curChunk) =>
            Emit.runFirst(curEmit).map:
                case (Present(items), nextEmitFn) =>
                    val nextChunk = curChunk.concat(items)
                    if nextChunk.length < n then
                        Loop.continue(nextEmitFn(), nextChunk)
                    else
                        val (taken, rest) = nextChunk.splitAt(n)
                        val restEmit      = if rest.isEmpty then nextEmitFn() else Emit.valueWith(rest)(nextEmitFn())
                        Loop.done(taken -> Stream(restEmit))
                    end if
                case (_, _) => Loop.done(curChunk -> Stream(Emit.value(Chunk.empty[VV])))
    end splitAt

    /** Transform with a [[Pipe]] of corresponding input streaming element type.
      *
      * @see
      *   [[kyo.Pipe.transform]]
      *
      * @param sink
      *   Pipe to transform stream with
      * @return
      *   A new stream of transformed element type `A`
      */
    def into[VV >: V, A, S2](pipe: Pipe[VV, A, S2])(
        using
        t1: Tag[Emit[Chunk[VV]]],
        t2: Tag[Poll[Chunk[VV]]],
        f: Frame
    ): Stream[A, S & S2] =
        pipe.transform(this)(using t1, t2, f)

    /** Process with a [[Sink]] of corresponding streaming element type.
      *
      * @see
      *   [[kyo.Sink.drain]]
      *
      * @param sink
      *   Sink to process stream with
      * @return
      *   An effect producing a value of sink's output type `A`
      */
    def into[VV >: V, A, S2](sink: Sink[VV, A, S2])(using Tag[Poll[Chunk[VV]]], Tag[Emit[Chunk[VV]]], Frame): A < (S & S2) =
        sink.drain[VV, S](this)
    end into

    /** Applies a transformation to this stream's underlying computation. This provides a convenient way to handle effects included in the
      * stream.
      *
      * For example, to ensure all resources close when the stream is evaluated:
      * ```
      * val original: Stream[Int, Resource & Async] = ???
      * val withCleanup = original.handle(Resource.run)
      * ```
      *
      * While `handle` can be used with any function that processes the underlying effect, its main purpose is to facilitate effect handling
      * and composition of multiple handlers. The multi-parameter versions of `handle` enable chaining transformations in a readable
      * sequential style.
      *
      * ```
      * val original: Stream[Int, Resource & Abort[String] & Var[Int]] = ???
      * val handled: Stream[Int, Any] = original.handle(
      *   Resource.run,
      *   Abort.run[String](_),
      *   Var.run(20),
      * )
      * ```
      *
      * @param f
      *   The transformation function to apply to the underlying effect
      * @return
      *   A new stream based on the handled effect
      */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), V1, S1](
        f: (=> A) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f(emit).unit)
    end handle

    /** Applies two transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f2(f1(emit)).unit)

    /** Applies three transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] =
        Stream(f3(f2(f1(emit))).unit)

    /** Applies four transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f4(f3(f2(f1(emit)))).unit)

    /** Applies five transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f5(f4(f3(f2(f1(emit))))).unit)

    /** Applies six transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, F, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => F,
        f6: (=> F) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f6(f5(f4(f3(f2(f1(emit)))))).unit)

    /** Applies seven transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, F, G, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => F,
        f6: (=> F) => G,
        f7: (=> G) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f7(f6(f5(f4(f3(f2(f1(emit))))))).unit)

    /** Applies eight transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, F, G, H, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => F,
        f6: (=> F) => G,
        f7: (=> G) => H,
        f8: (=> H) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f8(f7(f6(f5(f4(f3(f2(f1(emit)))))))).unit)

    /** Applies nine transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, F, G, H, I, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => F,
        f6: (=> F) => G,
        f7: (=> G) => H,
        f8: (=> H) => I,
        f9: (=> I) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f9(f8(f7(f6(f5(f4(f3(f2(f1(emit))))))))).unit)

    /** Applies ten transformations to this stream's underlying computation. */
    def handle[A >: (Unit < (Emit[Chunk[V]] & S)), B, C, D, E, F, G, H, I, J, V1, S1](
        f1: (=> A) => B,
        f2: (=> B) => C,
        f3: (=> C) => D,
        f4: (=> D) => E,
        f5: (=> E) => F,
        f6: (=> F) => G,
        f7: (=> G) => H,
        f8: (=> H) => I,
        f9: (=> I) => J,
        f10: (=> J) => (Any < (Emit[Chunk[V1]] & S1))
    )(using Frame): Stream[V1, S1] = Stream(f10(f9(f8(f7(f6(f5(f4(f3(f2(f1(emit)))))))))).unit)
end Stream

object Stream:
    @nowarn("msg=anonymous")
    inline def apply[V, S](inline v: => Unit < (Emit[Chunk[V]] & S)): Stream[V, S] =
        new Stream[V, S]:
            def emit: Unit < (Emit[Chunk[V]] & S) = v

    private val _empty = Stream(())

    /** A stream that emits no elements and does nothing * */
    def empty[V]: Stream[V, Any] = _empty

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
            Loop.foreach:
                v.map {
                    case Maybe.Present(seq) => Emit.valueWith(Chunk.from(seq))(Loop.continue)
                    case Maybe.Absent       => Emit.valueWith(Chunk.empty[V])(Loop.done)
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

    /** Creates a stream by repeatedly applying a function to accumulate values.
      *
      * @tparam A
      *   The type of the accumulator
      * @tparam V
      *   The type of values in the resulting stream
      * @tparam S
      *   The type of effects in the stream
      * @param acc
      *   The initial accumulator value
      * @param chunkSize
      *   The target size for each chunk (must be positive)
      * @param f
      *   A function that takes the current accumulator and returns Maybe of next value and accumulator, where `Absent` will signal the end
      *   of the stream.
      * @return
      *   A stream containing the unfolded values
      */
    def unfold[A, V, S](acc: A, chunkSize: Int = DefaultChunkSize)(f: A => (Maybe[(V, A)] < S))(using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame
    ): Stream[V, S] =
        Stream[V, S]:
            val _chunkSize = chunkSize max 1
            Loop(Chunk.empty[V], acc): (curChunk, curAcc) =>
                if curChunk.length == _chunkSize then
                    Emit.valueWith(curChunk)(Loop.continue(Chunk.empty[V], curAcc))
                else
                    f(curAcc).map:
                        case Present(value -> nextAcc) => Loop.continue(curChunk.append(value), nextAcc)
                        case Absent                    => Emit.valueWith(curChunk)(Loop.done(()))

    /** Takes a Stream[V, S] in the context of S2 (i.e. Stream[V, S] < S2) and returns a Stream that fuses together both effect contexts S
      * and S2 into a single Stream[V, S & S2].
      *
      * @param stream
      *   The stream to unwrap
      * @return
      *   A new stream that fuses together both effect contexts S and S2 into a single Stream[V, S & S2]
      */
    inline def unwrap[V, S, S2](stream: Stream[V, S] < S2)(using Frame): Stream[V, S & S2] = Stream(stream.map(_.emit))
end Stream
