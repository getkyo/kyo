package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, S](effect: Unit < (Emit[Chunk[A]] & S))
    /** Convert streaming Emit effect to a [[Stream[A, S]]]
      *
      * @return
      *   Stream representation of original effect
      */
    def emitToStream: Stream[A, S] = Stream(effect)
end extension

extension [A, B, S](effect: B < (Emit[A] & S))
    /** Handle Emit[A], returning all emitted values along with the original effect's result value.
      *
      * @return
      *   A computation with handled Emit yielding a tuple of emitted values along with the original computation's result
      */
    def handleEmit(using Tag[Emit[A]], Frame): (Chunk[A], B) < S = Emit.run[A](effect)

    /** Handle Emit[A], returning only emitted values, discarding the original effect's result
      *
      * @return
      *   A computation with handled Emit yielding emitted values only
      */
    def handleEmitDiscarding(using Tag[Emit[A]], Frame): Chunk[A] < S = Emit.run[A](effect).map(_._1)

    /** Handle Emit[A], executing function [[fn]] on each emitted value
      *
      * @param fn
      *   Function to handle each emitted value
      * @return
      *   A computation with handled Emit
      */
    def foreachEmit[S1](
        fn: A => Unit < S1
    )(
        using
        tag: Tag[Emit[A]],
        f: Frame
    ): B < (S & S1) =
        ArrowEffect.handle(tag, effect):
            [C] =>
                (a, cont) =>
                    fn(a).andThen(cont(()))

    /** Handle Emit[A] by passing emitted values to [[channel]]. Fails with Abort[Closed] on channel closure
      *
      * @param channel
      *   Channel in which to put emitted values
      * @return
      *   Asynchronous computation with handled Emit that can fail with Abort[Closed]
      */
    def emitToChannel(channel: Channel[A])(using Tag[Emit[A]], Frame): B < (S & Async & Abort[Closed]) =
        effect.foreachEmit(a => channel.put(a))

    /** Handle Emit[A], re-emitting in chunks according to [[chunkSize]]
      *
      * @param chunkSize
      *   maximum size of emitted chunks
      *
      * @return
      */
    def emitChunked(
        chunkSize: Int
    )(
        using
        tag: Tag[Emit[A]],
        fr: Frame,
        at: Tag[Emit[Chunk[A]]]
    ): B < (Emit[Chunk[A]] & S) =
        ArrowEffect.handleLoop(tag, Chunk.empty[A], effect)(
            [C] =>
                (v, buffer, cont) =>
                    val b2 = buffer.append(v)
                    if b2.size >= chunkSize then
                        Emit.valueWith(b2):
                            Loop.continue(Chunk.empty, cont(()))
                    else
                        Loop.continue(b2, cont(()))
                    end if
            ,
            (buffer, v) =>
                if buffer.isEmpty then v
                else Emit.valueWith(buffer)(v)
        )

    /** Convert emitting effect to stream, chunking Emitted values in [[chunkSize]], and discarding result.
      *
      * @param chunkSize
      *   size of chunks to stream
      * @return
      *   Stream of emitted values
      */
    def emitChunkedToStreamDiscarding(
        chunkSize: Int
    )(
        using
        NotGiven[B =:= Unit],
        Tag[Emit[Chunk[A]]],
        Tag[Emit[A]],
        Frame
    ): Stream[A, S] =
        effect.emitChunked(chunkSize).emitToStreamDiscarding

    /** Convert an effect that emits values of type [[A]] while computing a result of type [[B]] to an asynchronous stream of the emission
      * type [[A]] along with a separate asynchronous effect that yields the result of the original effect after the stream has been
      * handled.
      *
      * @param chunkSize
      *   Size of chunks to stream
      * @return
      *   Tuple of async stream of type [[A]] and async effect yielding result [[B]]
      */
    def emitChunkedToStreamAndResult(
        using
        Tag[Emit[A]],
        Tag[Emit[Chunk[A]]],
        Frame
    )(chunkSize: Int): (Stream[A, S & Async], B < Async) < Async =
        effect.emitChunked(chunkSize).emitToStreamAndResult
end extension

extension [A, B, S](effect: B < (Emit[Chunk[A]] & S))
    /** Convert emitting effect to stream and discarding result.
      *
      * @return
      *   Stream of emitted values
      */
    def emitToStreamDiscarding(
        using
        NotGiven[B =:= Unit],
        Frame
    ): Stream[A, S] =
        Stream(effect.unit)

    /** Convert an effect that emits chunks of type [[A]] while computing a result of type [[B]] to an asynchronous stream of the emission
      * type [[A]] and a separate asynchronous effect that yields the result of the original effect after the stream has been handled.
      *
      * @return
      *   tuple of async stream of type [[A]] and async effect yielding result [[B]]
      */
    def emitToStreamAndResult(using Frame): (Stream[A, S & Async], B < Async) < Async =
        for
            p <- Promise.init[Nothing, B]
            streamEmit = effect.map: b =>
                p.completeDiscard(Result.succeed(b))
        yield (Stream(streamEmit), p.join)
end extension

extension [A, B, S](effect: Unit < (Emit[A] & S))
    /** Convert emitting effect to stream, chunking Emitted values in [[chunkSize]].
      *
      * @param chunkSize
      *   Size of chunks to stream
      * @return
      *   Stream of emitted values
      */
    def emitChunkedToStream(
        chunkSize: Int
    )(
        using
        Tag[Emit[A]],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Stream[A, S] =
        effect.emitChunked(chunkSize).emitToStream
end extension
