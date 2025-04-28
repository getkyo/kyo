package kyo

import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

/** Represents a transformation of a stream of type `A` to a stream of type `B`, using effect `S`.
  *
  * `Pipe` provides a composable abstraction for transforming `Stream` s. A `Pipe[A, B, S]` can process a stream of type `Stream[A, S2]` to
  * produce a new stream `Stream[B, S & S2]`.
  *
  * @see
  *   [[kyo.Stream]], [[kyo.Sink]]
  *
  * @tparam A
  *   Input stream element type
  * @tparam B
  *   Output stream element type
  * @tparam S
  *   The type of effects associated with the transformation
  */
sealed abstract class Pipe[A, B, -S] extends Serializable:

    /** Returns the underlying effect that polls chunks of type A and emits chunks of type B */
    def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)

    /** Transform a pipe to consume a stream of a different element type using a pure mapping function.
      *
      * @param f
      *   Pure function mapping new stream element type to original stream element type
      * @return
      *   A pipe that accepts streams of the new element type
      */
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

    /** Transform a pipe to consume a stream of a different element type using an effectful mapping function.
      *
      * @param f
      *   Effectful function mapping new stream element type to original stream element type
      * @return
      *   A pipe that accepts streams of the new element type
      */
    def contramap[A1, S1](f: A1 => A < S1)(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        disc: Discriminator,
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

    /** Transform a pipe to consume a stream of a different element type using a pure mapping function that transforms streamed chunks.
      *
      * @param f
      *   Pure function mapping chunks of a new stream element type to chunks of the original stream element type
      * @return
      *   A pipe that accepts streams of the new element type
      */
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

    /** Transform a pipe to consume a stream of a different element type using an effectful mapping function that transforms streamed
      * chunks.
      *
      * @param f
      *   Effectful function mapping chunks of a new stream element type to chunks of the original stream element type
      * @return
      *   A pipe that accepts streams of the new element type
      */
    def contramapChunk[A1, S1](f: Chunk[A1] => Chunk[A] < S1)(
        using
        t1: Tag[Poll[Chunk[A]]],
        t2: Tag[A1],
        disc: Discriminator,
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

    /** Transform a pipe to produce a new output type using a pure function that transforms each streamed element of the original pipe's
      * output stream.
      *
      * @param f
      *   Pure function mapping the streamed elements produced by the original pipe
      * @return
      *   A new pipe with output stream transformed by the mapping function
      */
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

    /** Transform a pipe to produce a new output type using an effectful function that transforms each streamed element of the original
      * pipe's output stream.
      *
      * @param f
      *   Effectful function mapping the streamed elements produced by the original pipe
      * @return
      *   A new pipe with output stream transformed by the mapping function
      */
    def map[B1, S1](f: B => B1 < S1)(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        disc: Discriminator,
        fr: Frame
    ): Pipe[A, B1, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        Kyo.foreach(chunk)(f).map: chunk2 =>
                            Emit.valueWith(chunk2)(Loop.continue(cont(())))
            )

    /** Transform a pipe to produce a new output type using a pure function that transforms each streamed chunk of the original pipe's
      * output stream.
      *
      * @param f
      *   Pure function mapping the streamed chunks produced by the original pipe
      * @return
      *   A new pipe with output stream transformed by the mapping function
      */
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

    /** Transform a pipe to produce a new output type using an effectful function that transforms each streamed chunk of the original pipe's
      * output stream.
      *
      * @param f
      *   Effectful function mapping the streamed chunks produced by the original pipe
      * @return
      *   A new pipe with output stream transformed by the mapping function
      */
    def mapChunk[B1, S1](f: Chunk[B] => Chunk[B1] < S1)(
        using
        t1: Tag[Emit[Chunk[B]]],
        t2: Tag[B1],
        disc: Discriminator,
        fr: Frame
    ): Pipe[A, B1, S & S1] =
        Pipe:
            ArrowEffect.handleLoop(t1, pollEmit)(
                [C] =>
                    (chunk, cont) =>
                        f(chunk).map: chunk2 =>
                            Emit.valueWith(chunk2)(Loop.continue(cont(())))
            )

    /** Join to another pipe producing a new pipe that performs both pipes' transformations in sequence.
      *
      * @param pipe
      *   Pipe to prepend pipe to
      * @return
      *   New pipe that performs both transformations in sequence
      */
    def join[C, S1](pipe: Pipe[B, C, S1])(using Tag[B], Frame): Pipe[A, C, S & S1] =
        Pipe:
            Poll.run(pollEmit)(pipe.pollEmit).unit

    /** Join to a sink producing a new sink that transforms the stream prior to processing it.
      *
      * @param sink
      *   Sink to prepend pipe to
      * @return
      *   New sink that transforms stream prior to processing it
      */
    def join[C, S1](sink: Sink[B, C, S1])(using Tag[B], Frame): Sink[A, C, S & S1] =
        Sink:
            Poll.run(pollEmit)(sink.poll).map(_._2)

    /** Consume a stream to produce a new stream
      *
      * @see
      *   [[kyo.Stream.into]]
      *
      * @param stream
      *   Stream to be transformed
      * @return
      *   A new transformed stream
      */
    def transform[S1](stream: Stream[A, S1])(using Tag[A], Frame): Stream[B, S & S1] =
        Stream:
            Poll.run(stream.emit)(pollEmit).map(_._2)

end Pipe

object Pipe:
    @nowarn("msg=anonymous")
    inline def apply[A, B, S](inline v: => Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)): Pipe[A, B, S] =
        new Pipe[A, B, S]:
            def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S) = v

    /** A pipe that passes through the original stream without transforming it */
    def identity[A](using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(c)(Loop.continue)

    /** A pipe that transforms each element of a stream with a pure function.
      *
      * @param f
      *   Pure function transforming stream elements
      */
    def map[A, B](f: A => B)(
        using
        Tag[A],
        Tag[B],
        NotGiven[B <:< (Any < Nothing)],
        Frame
    ): Pipe[A, B, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(c.map(f))(Loop.continue)

    /** A pipe that transforms each element of a stream with an effectful function.
      *
      * @param f
      *   Effectful function transforming stream elements
      */
    def map[A, B, S](f: A => B < S)(
        using
        Tag[A],
        Tag[B],
        NotGiven[B <:< (Any < Nothing)],
        Discriminator,
        Frame
    ): Pipe[A, B, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Kyo.foreach(c)(f).map: c1 =>
                            Emit.valueWith(c1)(Loop.continue)

    /** A pipe that transforms each chunk of a stream with a pure function.
      *
      * @param f
      *   Pure function transforming stream chunks
      */
    def mapChunk[A, B](f: Chunk[A] => Chunk[B])(using Tag[A], Tag[B], Frame): Pipe[A, B, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(f(c))(Loop.continue)

    /** A pipe that transforms each chunk of a stream with an effectful function.
      *
      * @param f
      *   Effectful function transforming stream chunks
      */
    def mapChunk[A, B, S](f: Chunk[A] => Chunk[B] < S)(using Tag[A], Tag[B], Discriminator, Frame): Pipe[A, B, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        f(c).map: c1 =>
                            Emit.valueWith(c1)(Loop.continue)

    /** A pipe whose output stream completes after at most `n` elements.
      *
      * @param n
      *   Maximum number of elements to stream
      */
    def take[A](n: Int)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop(n): i =>
                if i <= 0 then Loop.done
                else
                    Poll.andMap[Chunk[A]]:
                        case Absent => Loop.done
                        case Present(c) =>
                            val c1 = c.take(i)
                            Emit.valueWith(c1)(Loop.continue(i - c1.size))

    /** A pipe whose output stream skips the first `n` elements of the input stream.
      *
      * @param n
      *   Number of elements to skip
      */
    def drop[A](n: Int)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop(n): i =>
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if i <= 0 then Emit.valueWith(c)(Loop.continue(0))
                        else
                            val c1 = c.drop(i)
                            if c1.isEmpty then Loop.continue(i - c.size)
                            else
                                Emit.valueWith(c1)(Loop.continue(0))

    /** A pipe whose output continues only as long as the provided predicate returns true.
      *
      * @param f
      *   Pure function determining whether to continue output stream based on input stream element
      */
    def takeWhile[A](f: A => Boolean)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue
                        else
                            val c1 = c.takeWhile(f)
                            Emit.valueWith(c1):
                                if c1.size == c.size then Loop.continue else Loop.done

    /** A pipe whose output continues only as long as the provided effectful predicate returns true.
      *
      * @param f
      *   Effectful function determining whether to continue output stream based on input stream element
      */
    def takeWhile[A, S](f: A => Boolean < S)(using Tag[A], Discriminator, Frame): Pipe[A, A, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue
                        else
                            Kyo.takeWhile(c)(f).map: c1 =>
                                Emit.valueWith(c1):
                                    if c1.size == c.size then Loop.continue else Loop.done

    /** A pipe whose output skips input elements as long as the provided predicate returns true.
      *
      * @param f
      *   Pure function determining whether to continue skipping elements
      */
    def dropWhile[A](f: A => Boolean)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop(false): done =>
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue(done)
                        else if done then Emit.valueWith(c)(Loop.continue(done))
                        else
                            val c1 = c.dropWhile(f)
                            if c1.isEmpty then Loop.continue(done)
                            else Emit.valueWith(c1)(Loop.continue(true))

    /** A pipe whose output skips input elements as long as the provided effectful predicate returns true.
      *
      * @param f
      *   Effectful function determining whether to continue skipping elements
      */
    def dropWhile[A, S](f: A => Boolean < S)(using Tag[A], Discriminator, Frame): Pipe[A, A, S] =
        Pipe:
            Loop(false): done =>
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue(done)
                        else if done then Emit.valueWith(c)(Loop.continue(done))
                        else
                            Kyo.dropWhile(c)(f).map: c1 =>
                                if c1.isEmpty then Loop.continue(done)
                                else Emit.valueWith(c1)(Loop.continue(true))

    /** A pipe whose output skips input elements that do not satisfy the provided predicate.
      *
      * @param f
      *   Pure function determining whether to skip streaming element
      */
    def filter[A](f: A => Boolean)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        val c1 = c.filter(f)
                        if c1.isEmpty then Loop.continue
                        else Emit.valueWith(c1)(Loop.continue)

    /** A pipe whose output skips input elements that do not satisfy the provided effectful predicate.
      *
      * @param f
      *   Effectful function determining whether to skip streaming element
      */
    def filter[A, S](f: A => Boolean < S)(using Tag[A], Discriminator, Frame): Pipe[A, A, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Kyo.filter(c)(f).map: c1 =>
                            if c1.isEmpty then Loop.continue
                            else Emit.valueWith(c1)(Loop.continue)

    /** A pipe that filters and transforms an input stream using a pure function.
      *
      * @param f
      *   Pure function converting input elements to optional output elements
      */
    def collect[A, B](f: A => Maybe[B])(using Tag[A], Tag[B], Frame): Pipe[A, B, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        val c1 = c.map(f).collect({ case Present(v) => v })
                        if c1.isEmpty then Loop.continue
                        else Emit.valueWith(c1)(Loop.continue)

    /** A pipe that filters and transforms an input stream using an effectful function.
      *
      * @param f
      *   Effectful function converting input elements to optional output elements
      */
    def collect[A, B, S](f: A => Maybe[B] < S)(using Tag[A], Tag[B], Discriminator, Frame): Pipe[A, B, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Kyo.collect(c)(f).map: c1 =>
                            if c1.isEmpty then Loop.continue
                            else Emit.valueWith(c1)(Loop.continue)

    /** A pipe that transforms an input stream using a pure function, ending the stream when the first absent transformed element is
      * returned.
      *
      * @param f
      *   Pure function converting input elements to optional output elements
      */
    def collectWhile[A, B](f: A => Maybe[B])(using Tag[A], Tag[B], Frame): Pipe[A, B, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue
                        else
                            val c1 = c.map(f).takeWhile(_.isDefined).collect({ case Present(v) => v })
                            if c1.isEmpty then Loop.done
                            else
                                Emit.valueWith(c1):
                                    if c1.size < c.size then Loop.done
                                    else Loop.continue
                            end if

    /** A pipe that transforms an input stream using an effectful function, ending the stream when the first absent transformed element is
      * returned.
      *
      * @param f
      *   Effectful function converting input elements to optional output elements
      */
    def collectWhile[A, B, S](f: A => Maybe[B] < S)(using Tag[A], Tag[B], Discriminator, Frame): Pipe[A, B, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        if c.isEmpty then Loop.continue
                        else
                            Kyo.foreach(c)(f).map: c1 =>
                                val c2 = c1.takeWhile(_.isDefined).collect({ case Present(v) => v })
                                if c2.isEmpty then Loop.done
                                else
                                    Emit.valueWith(c2):
                                        if c2.length < c.length then Loop.done
                                        else Loop.continue
                                end if

    /** A pipe whose output only emits elements that are different from their predecessor.
      */
    def changes[A](using Tag[A], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
        changes(Maybe.empty)

    /** A pipe whose output only emits elements that are different from their predecessor, starting with the given first element.
      *
      * @param first
      *   The initial element to compare against
      */
    def changes[A](first: A)(using Tag[A], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
        changes(Maybe(first))

    /** A pipe whose output only emits elements that are different from their predecessor, starting with the given optional first element.
      *
      * @param first
      *   The optional initial element to compare against
      */
    @targetName("changesMaybe")
    def changes[A](first: Maybe[A])(using Tag[A], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
        Pipe:
            Loop(first): state =>
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        val c1       = c.changes(state)
                        val newState = if c1.isEmpty then state else Maybe(c1.last)
                        Emit.valueWith(c1)(Loop.continue(newState))
    end changes

    /** A pipe that regroups elements from the input stream into chunks of the specified size.
      *
      * This operation maintains the order of elements while potentially redistributing them into new chunks. Smaller chunks may occur in
      * two cases:
      *   - When there aren't enough remaining elements to form a complete chunk
      *   - When the input stream emits an empty chunk
      *
      * @see
      *   [[kyo.Stream.rechunk]]
      *
      * @param chunkSize
      *   The target size for each chunk. Must be positive - negative values will be treated as 1.
      */
    def rechunk[A](chunkSize: Int)(using Tag[A], Frame): Pipe[A, A, Any] =
        Pipe:
            val _chunkSize = chunkSize max 1
            Loop(Chunk.empty[A]): buffer =>
                Poll.andMap[Chunk[A]]:
                    case Absent =>
                        if buffer.isEmpty then Loop.done
                        else Emit.valueWith(buffer)(Loop.done)
                    case Present(c) if c.isEmpty =>
                        Emit.valueWith(buffer)(Loop.continue(Chunk.empty))
                    case Present(c) =>
                        val combined = buffer.concat(c)
                        if combined.size < _chunkSize then
                            Loop.continue(combined)
                        else
                            Loop(combined): current =>
                                if current.size < _chunkSize then
                                    Loop.done(Loop.continue(current))
                                else
                                    Emit.valueWith(current.take(_chunkSize)):
                                        Loop.continue(current.dropLeft(_chunkSize))
                        end if
    end rechunk

end Pipe
