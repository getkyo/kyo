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
  *   [[kyo.Pipe.transform]]
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
sealed abstract class Pipe[-A, +B, -S] extends Serializable:

    /** Returns the underlying effect that polls chunks of type A and emits chunks of type B */
    def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)

    /** Transform a pipe to consume a stream of a different element type using a pure mapping function.
      *
      * @param f
      *   Pure function mapping new stream element type to original stream element type
      * @return
      *   A pipe that accepts streams of the new element type
      */
    def contramapPure[AA <: A, A1](f: A1 => AA)(
        using
        t1: Tag[Poll[Chunk[AA]]],
        t2: Tag[Poll[Chunk[A1]]],
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
    def contramap[AA <: A, A1, S1](f: A1 => AA < S1)(
        using
        t1: Tag[Poll[Chunk[AA]]],
        t2: Tag[Poll[Chunk[A1]]],
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
    def contramapChunkPure[AA <: A, A1](f: Chunk[A1] => Chunk[AA])(
        using
        t1: Tag[Poll[Chunk[AA]]],
        t2: Tag[Poll[Chunk[A1]]],
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
    def contramapChunk[AA <: A, A1, S1](f: Chunk[A1] => Chunk[AA] < S1)(
        using
        t1: Tag[Poll[Chunk[AA]]],
        t2: Tag[Poll[Chunk[A1]]],
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
    def mapPure[BB >: B, B1](f: BB => B1)(
        using
        t1: Tag[Emit[Chunk[BB]]],
        t2: Tag[Emit[Chunk[B1]]],
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
    def map[BB >: B, B1, S1](f: BB => B1 < S1)(
        using
        t1: Tag[Emit[Chunk[BB]]],
        t2: Tag[Emit[Chunk[B1]]],
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
    def mapChunkPure[BB >: B, B1](f: Chunk[BB] => Chunk[B1])(
        using
        t1: Tag[Emit[Chunk[BB]]],
        t2: Tag[Emit[Chunk[B1]]],
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
    def mapChunk[BB >: B, B1, S1](f: Chunk[BB] => Chunk[B1] < S1)(
        using
        t1: Tag[Emit[Chunk[BB]]],
        t2: Tag[Emit[Chunk[B1]]],
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
    def join[BB >: B, C, S1](pipe: Pipe[BB, C, S1])(using Tag[Emit[Chunk[BB]]], Tag[Poll[Chunk[BB]]], Frame): Pipe[A, C, S & S1] =
        Pipe:
            Poll.runEmit[Chunk[BB]](pollEmit)(pipe.pollEmit).unit

    /** Join to a sink producing a new sink that transforms a stream prior to processing it.
      *
      * @param sink
      *   Sink to prepend pipe to
      * @return
      *   New sink that transforms stream prior to processing it
      */
    def join[BB >: B, C, S1](sink: Sink[BB, C, S1])(using Tag[Emit[Chunk[BB]]], Tag[Poll[Chunk[BB]]], Frame): Sink[A, C, S & S1] =
        Sink:
            Poll.runEmit[Chunk[BB]](pollEmit)(sink.poll).map(_._2)

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
    def transform[AA <: A, S1](stream: Stream[AA, S1])(
        using
        emitTag: Tag[Emit[Chunk[AA]]],
        pollTag: Tag[Poll[Chunk[AA]]],
        fr: Frame
    ): Stream[B, S & S1] =
        Stream:
            Loop(stream.emit, pollEmit: Unit < (Poll[Chunk[AA]] & Emit[Chunk[B]] & S)) { (emit, poll) =>
                ArrowEffect.handleFirst(pollTag, poll)(
                    handle = [C] =>
                        (_, pollCont) =>
                            ArrowEffect.handleFirst(emitTag, emit)(
                                handle = [C2] =>
                                    (emitted, emitCont) =>
                                        Loop.continue(emitCont(()), pollCont(Maybe(emitted))),
                                done = _ => Loop.continue(Kyo.unit, pollCont(Absent))
                        ),
                    done = _ => Loop.done(())
                )
            }

end Pipe

object Pipe:
    @nowarn("msg=anonymous")
    inline def apply[A, B, S](inline v: => Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)): Pipe[A, B, S] =
        new Pipe[A, B, S]:
            def pollEmit: Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S) = v

    private val _empty = Pipe(())

    /** A pipe that ignores the original stream and emits nothing * */
    def empty[A, B]: Pipe[A, B, Any] = _empty

    /** A pipe that passes through the original stream without transforming it */
    def identity[A](using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]], Frame): Pipe[A, A, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(c)(Loop.continue)

    /** A pipe that transforms each element of a stream with a pure function.
      *
      * @see
      *   [[kyo.Stream.map]]
      *
      * @param f
      *   Pure function transforming stream elements
      */
    def mapPure[A](using
        Tag[A]
    )[B](f: A => B)(
        using
        Tag[Poll[Chunk[A]]],
        Tag[Emit[Chunk[B]]],
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
      * @see
      *   [[kyo.Stream.map]]
      *
      * @param f
      *   Effectful function transforming stream elements
      */
    def map[A](using
        Tag[A]
    )[B, S](f: A => B < S)(
        using
        Tag[Poll[Chunk[A]]],
        Tag[Emit[Chunk[B]]],
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
      * @see
      *   [[kyo.Stream.mapChunk]]
      *
      * @param f
      *   Pure function transforming stream chunks
      */
    def mapChunkPure[A](using Tag[Poll[Chunk[A]]])[B](f: Chunk[A] => Chunk[B])(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, Any] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Emit.valueWith(f(c))(Loop.continue)

    /** A pipe that transforms each chunk of a stream with an effectful function.
      *
      * @see
      *   [[kyo.Stream.mapChunk]]
      *
      * @param f
      *   Effectful function transforming stream chunks
      */
    def mapChunk[A](using
        Tag[Poll[Chunk[A]]]
    )[B, S](f: Chunk[A] => Chunk[B] < S)(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        f(c).map: c1 =>
                            Emit.valueWith(c1)(Loop.continue)

    /** A pipe whose output stream completes after at most `n` elements.
      *
      * @see
      *   [[kyo.Stream.take]]
      *
      * @param n
      *   Maximum number of elements to stream
      */
    def take[A](n: Int)(using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]], Frame): Pipe[A, A, Any] =
        if n <= 0 then Pipe.empty
        else
            Pipe:
                Loop(n): i =>
                    Poll.andMap[Chunk[A]]:
                        case Absent => Loop.done
                        case Present(c) =>
                            val c1  = c.take(i)
                            val nst = i - c1.size
                            Emit.valueWith(c1)(if nst > 0 then Loop.continue(nst) else Loop.done)

    /** A pipe whose output stream skips the first `n` elements of the input stream.
      *
      * @see
      *   [[kyo.Stream.drop]]
      *
      * @param n
      *   Number of elements to skip
      */
    def drop[A](n: Int)(using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]], Frame): Pipe[A, A, Any] =
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
      * @see
      *   [[kyo.Stream.takeWhile]]
      *
      * @param f
      *   Pure function determining whether to continue output stream based on input stream element
      */
    def takeWhilePure[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])(f: A => Boolean)(using Frame): Pipe[A, A, Any] =
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
      * @see
      *   [[kyo.Stream.takeWhile]]
      *
      * @param f
      *   Effectful function determining whether to continue output stream based on input stream element
      */
    def takeWhile[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])[S](f: A => Boolean < S)(using Frame): Pipe[A, A, S] =
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
      * @see
      *   [[kyo.Stream.dropWhile]]
      *
      * @param f
      *   Pure function determining whether to continue skipping elements
      */
    def dropWhilePure[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])(f: A => Boolean)(using Frame): Pipe[A, A, Any] =
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
      * @see
      *   [[kyo.Stream.dropWhile]]
      *
      * @param f
      *   Effectful function determining whether to continue skipping elements
      */
    def dropWhile[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])[S](f: A => Boolean < S)(using Frame): Pipe[A, A, S] =
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
      * @see
      *   [[kyo.Stream.filter]]
      *
      * @param f
      *   Pure function determining whether to skip streaming element
      */
    def filterPure[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])(f: A => Boolean)(using Frame): Pipe[A, A, Any] =
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
      * @see
      *   [[kyo.Stream.filter]]
      *
      * @param f
      *   Effectful function determining whether to skip streaming element
      */
    def filter[A](using Tag[Poll[Chunk[A]]], Tag[Emit[Chunk[A]]])[S](f: A => Boolean < S)(using Frame): Pipe[A, A, S] =
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
      * @see
      *   [[kyo.Stream.collect]]
      *
      * @param f
      *   Pure function converting input elements to optional output elements
      */
    def collectPure[A](using Tag[Poll[Chunk[A]]])[B](f: A => Maybe[B])(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, Any] =
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
      * @see
      *   [[kyo.Stream.collect]]
      *
      * @param f
      *   Effectful function converting input elements to optional output elements
      */
    def collect[A](using Tag[Poll[Chunk[A]]])[B, S](f: A => Maybe[B] < S)(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, S] =
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
      * @see
      *   [[kyo.Stream.collectWhile]]
      *
      * @param f
      *   Pure function converting input elements to optional output elements
      */
    def collectWhilePure[A](using Tag[Poll[Chunk[A]]])[B](f: A => Maybe[B])(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, Any] =
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
      * @see
      *   [[kyo.Stream.collectWhile]]
      *
      * @param f
      *   Effectful function converting input elements to optional output elements
      */
    def collectWhile[A](using
        Tag[Poll[Chunk[A]]]
    )[B, S](f: A => Maybe[B] < S)(using Tag[Emit[Chunk[B]]], Frame): Pipe[A, B, S] =
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
    def changes[A](using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
        changes(Maybe.empty)

    /** A pipe whose output only emits elements that are different from their predecessor, starting with the given first element.
      *
      * @see
      *   [[kyo.Stream.changes]]
      *
      * @param first
      *   The initial element to compare against
      */
    def changes[A](first: A)(using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
        changes(Maybe(first))

    /** A pipe whose output only emits elements that are different from their predecessor, starting with the given optional first element.
      *
      * @see
      *   [[kyo.Stream.changes]]
      *
      * @param first
      *   The optional initial element to compare against
      */
    @targetName("changesMaybe")
    def changes[A](first: Maybe[A])(using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]], Frame, CanEqual[A, A]): Pipe[A, A, Any] =
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
    def rechunk[A](chunkSize: Int)(using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]], Frame): Pipe[A, A, Any] =
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
                            Loop(combined) { current =>
                                if current.size < _chunkSize then
                                    Loop.done(current)
                                else
                                    Emit.valueWith(current.take(_chunkSize)):
                                        Loop.continue(current.dropLeft(_chunkSize))
                                end if
                            }.map(result => Loop.continue(result))
                        end if
    end rechunk

    /** A pipe that performs a side-effect on each element without transforming the stream.
      *
      * @see
      *   [[kyo.Stream.tap]]
      *
      * @param f
      *   Side-effecting function to perform on each input element
      */
    def tap[A](using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]])[S](f: A => Any < S)(using Frame): Pipe[A, A, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        Kyo.foreach(c)(f).andThen:
                            Emit.valueWith(c)(Loop.continue)

    /** A pipe that performs a side-effect on each input chunk without transforming the stream.
      *
      * @see
      *   [[kyo.Stream.tapChunk]]
      *
      * @param f
      *   Side-effecting function to perform on each input chunk
      */
    def tapChunk[A](using Tag[Emit[Chunk[A]]], Tag[Poll[Chunk[A]]])[S](f: Chunk[A] => Any < S)(using Frame): Pipe[A, A, S] =
        Pipe:
            Loop.foreach:
                Poll.andMap[Chunk[A]]:
                    case Absent => Loop.done
                    case Present(c) =>
                        f(c).andThen:
                            Emit.valueWith(c)(Loop.continue)

end Pipe
