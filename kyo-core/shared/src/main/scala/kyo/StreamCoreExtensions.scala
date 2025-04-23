package kyo

import kyo.Async.defaultConcurrency
import kyo.kernel.ArrowEffect

object StreamCoreExtensions:
    val defaultAsyncStreamBufferSize = 1024

    private def emitMaybeChunksFromChannel[V](channel: Channel[Maybe[Chunk[V]]])(using Tag[V], Frame) =
        val emit = Loop(()): _ =>
            channel.take.map:
                case Absent =>
                    Loop.done
                case Present(c) =>
                    Emit.valueWith(c)(Loop.continue)
        Abort.run(emit).unit
    end emitMaybeChunksFromChannel

    private def emitMaybeElementsFromChannel[V](channel: Channel[Maybe[V]])(using Tag[V], Frame) =
        val emit = Loop(()): _ =>
            channel.take.map: v =>
                channel.drain.map: chunk =>
                    val fullChunk      = Chunk(v).concat(chunk)
                    val publishedChunk = fullChunk.collect({ case Present(v) => v })
                    Emit.valueWith(publishedChunk):
                        if publishedChunk.size == fullChunk.size then
                            Loop.continue
                        else Loop.done
        Abort.run(emit).unit
    end emitMaybeElementsFromChannel

    extension (streamObj: Stream.type)
        /** Merges multiple streams asynchronously. Stream stops when all sources streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param streams
          *   Sequence of streams to be merged
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def collectAll[V, E: SafeClassTag, S](
            streams: Seq[Stream[V, Abort[E] & S & Async]],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate.Contextual[S, IO],
            Isolate.Stateful[S, Abort[E] & Async],
            Tag[V],
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.init[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run[E, Unit, S](Abort.run {
                                Async.foreachDiscard(streams)(
                                    _.foreachChunk(c => Abort.run[Closed](channel.put(Present(c))))
                                )
                            }.andThen(Abort.run(channel.put(Absent)).unit))
                            _ <- emitMaybeChunksFromChannel(channel)
                        yield ()

        /** Merges multiple streams asynchronously. Stream stops as soon as any of the source streams complete.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param streams
          *   Sequence of streams to be merged
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def collectAllHalting[V, E: SafeClassTag, S](
            streams: Seq[Stream[V, S & Abort[E] & Async]],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate.Contextual[S, IO],
            Isolate.Stateful[S, Abort[E] & Async],
            Tag[V],
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.init[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(Abort.run(
                                Async
                                    .foreachDiscard(streams)(
                                        _.foreachChunk(c => Abort.run(channel.put(Present(c))))
                                            .andThen(Abort.run(channel.put(Absent)))
                                    )
                            ))
                            _ <- emitMaybeChunksFromChannel(channel)
                        yield ()
    end extension

    extension [V, S, E](stream: Stream[V, S & Abort[E] & Async])
        /** Merges with another stream. Stream stops when both streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def merge[S2](
            other: Stream[V, Abort[E] & S & Async],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate.Contextual[S, IO],
            Isolate.Stateful[S, Abort[E] & Async],
            SafeClassTag[E],
            Tag[V],
            Frame
        ): Stream[V, Abort[E] & S & Async] =
            val streams: Seq[Stream[V, Abort[E] & S & Async]] = Seq(stream, other)
            Stream.collectAll[V, E, S](streams)
        end merge

        /** Merges with another stream. Stream stops as soon as either has have completed.
          *
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def mergeHalting[S2](
            other: Stream[V, Abort[E] & S & Async],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate.Contextual[S, IO],
            Isolate.Stateful[S, Abort[E] & Async],
            SafeClassTag[E],
            Tag[V],
            Frame
        ): Stream[V, Abort[E] & S & S2 & Async] =
            Stream.collectAllHalting[V, E, S](Seq(stream, other))

        /** Merges with another stream. Stream stops when original stream has completed or when both streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def mergeHaltingLeft(
            other: Stream[V, Abort[E] & S & Async],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate.Contextual[S, IO],
            Isolate.Stateful[S, Abort[E] & Async],
            SafeClassTag[E],
            Tag[V],
            Frame
        ): Stream[V, Abort[E] & S & Async] =
            Stream:
                Channel.init[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(
                                Async.gather(
                                    stream.foreachChunk(c => channel.put(Present(c)))
                                        .andThen(channel.put(Absent)),
                                    other.foreachChunk(c => channel.put(Present(c)))
                                ).andThen(channel.put(Absent))
                            )
                            _ <- emitMaybeChunksFromChannel(channel)
                        yield ()

        /** Merges with another stream. Stream stops when other stream has completed or when both streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def mergeHaltingRight(
            other: Stream[V, Abort[E] & S & Async],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Abort[E] & Async],
            sct: SafeClassTag[E],
            t: Tag[V],
            f: Frame
        ): Stream[V, Abort[E] & S & Async] =
            other.mergeHaltingLeft(stream)(using i1, i2, sct, t, f)

        /** Applies effectful transformation of stream elements asynchronously, mapping them in parallel. Preserves chunk boundaries.
          *
          * @param parallel
          *   Maximum number of elements to transform in parallel at a time
          * @param bufferSize
          *   Size of buffer used to mediate
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapPar[V2, S2](parallel: Int, bufferSize: Int = defaultAsyncStreamBufferSize)(f: V => V2 < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            val initialState: (Fiber[E | Closed, Unit], Int) = (Fiber.unit, parallel)
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.init[Maybe[Chunk[V2]]](bufferSize).map { channel =>
                    AtomicInt.init(0).map: parAdjustmentRef =>
                        val background = Async.run:
                            val handledStream = ArrowEffect.handleState(t1, initialState, stream.emit)(
                                handle = [C] =>
                                    (input, state, cont) =>
                                        val (prevEmitFiber, remainingEmitPar) = state

                                        Loop(Fiber.success[E | Closed, Chunk[V2]](Chunk.empty[V2]), input, remainingEmitPar):
                                            (prevChunkFiber, remainingChunk, remainingPar) =>
                                                parAdjustmentRef.getAndSet(0).map: parAdjustment =>
                                                    val adjustedRemainingPar = remainingPar + parAdjustment
                                                    val nextParSection       = remainingChunk.take(adjustedRemainingPar)

                                                    val nextChunkEffect = Async.foreach(nextParSection)(f)

                                                    val newRemainingPar   = adjustedRemainingPar - nextParSection.size
                                                    val newRemainingChunk = remainingChunk.drop(adjustedRemainingPar)

                                                    if newRemainingPar <= 0 && newRemainingChunk.size <= 0 then
                                                        nextChunkEffect.map: nextChunk =>
                                                            prevChunkFiber.get.map: prevChunk =>
                                                                prevEmitFiber.get.andThen:
                                                                    channel.put(Present(prevChunk ++ nextChunk)).andThen:
                                                                        Loop.done(((Fiber.unit, parallel), cont(())))
                                                    else if newRemainingPar <= 0 then
                                                        nextChunkEffect.map: nextChunk =>
                                                            prevChunkFiber.get.map: prevChunk =>
                                                                prevEmitFiber.get.andThen:
                                                                    Loop.continue(
                                                                        Fiber.success(prevChunk ++ nextChunk),
                                                                        newRemainingChunk,
                                                                        parallel
                                                                    )
                                                    else if newRemainingChunk.size <= 0 then
                                                        Async.run {
                                                            nextChunkEffect.map: nextChunk =>
                                                                prevChunkFiber.get.map: prevChunk =>
                                                                    prevEmitFiber.get.andThen:
                                                                        val chunk = prevChunk ++ nextChunk
                                                                        channel.put(Present(chunk)).andThen:
                                                                            parAdjustmentRef.updateAndGet(_ + chunk.size).unit
                                                        }.map: nextFiber =>
                                                            Loop.done(((nextFiber, newRemainingPar), cont(())))
                                                    else
                                                        bug("Illegal state: there is remaining parallel and remaining chunk in mapPar")
                                                    end if
                                ,
                                done = {
                                    case ((lastFiber, _), _) =>
                                        lastFiber.get.andThen:
                                            channel.put(Absent)
                                }
                            )

                            Abort.fold[E | Closed](
                                _ => Abort.run(channel.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channel.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channel.put(Absent)).andThen(Abort.panic(e))
                            )(handledStream)

                        background.map: backgroundFiber =>
                            emitMaybeChunksFromChannel(channel).andThen:
                                backgroundFiber.get.unit
                }
        end mapPar

        /** Applies effectful transformation of stream elements asynchronously, mapping them in parallel. Preserves chunk boundaries.
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapPar[V2, S2](f: V => V2 < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i1, i2, ev, frame)

        /** Applies effectful transformation of stream elements asynchronously, mapping them in parallel. Does not preserve chunk
          * boundaries.
          *
          * @param parallel
          *   Maximum number of elements to transform in parallel at a time
          * @param bufferSize
          *   Size of buffer used to mediate stream. Determines maximum output chunk size.
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapParUnordered[V2, S2](parallel: Int, bufferSize: Int = defaultAsyncStreamBufferSize)(f: V => V2 < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.initWith[Maybe[V]](bufferSize): channelIn =>
                    Channel.initWith[Maybe[V2]](bufferSize): channelOut =>
                        val input = Abort.run(
                            stream.foreach(v => channelIn.put(Present(v)))
                        ).andThen(channelIn.putBatch(Chunk.fill(parallel)(Absent)))
                        val transform = Async.fill(parallel, parallel) {
                            Loop(()): _ =>
                                channelIn.take.map:
                                    case Absent => Loop.done
                                    case Present(v) => f(v).map: v2 =>
                                        channelOut.put(Present(v2)).andThen(Loop.continue)
                        }.andThen(channelOut.put(Absent))

                        val background = Async.run:
                            Abort.fold[E | Closed](
                                _ => Abort.run(channelOut.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(input, transform))

                        background.map: backgroundFiber =>
                            emitMaybeElementsFromChannel(channelOut).andThen:
                                backgroundFiber.get.unit
        end mapParUnordered

        /** Applies effectful transformation of stream elements asynchronously, mapping them in parallel. Does not preserve chunk
          * boundaries.
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapParUnordered[V2, S2](f: V => V2 < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i1, i2, ev, frame)

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunks in parallel. Preserves chunk boundaries.
          *
          * @param parallel
          *   Maximum number of elements to transform in parallel at a time
          * @param bufferSize
          *   Size of buffer used to mediate
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkPar[V2, S2](
            parallel: Int,
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            val initialState: Fiber[E | Closed, Unit] = Fiber.unit
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.init[Maybe[Chunk[V2]]](bufferSize).map: channel =>
                    Signal.initRefWith(parallel): parRef =>
                        val background = Async.run:
                            val handledStream = ArrowEffect.handleState(t1, initialState, stream.emit)(
                                handle = [C] =>
                                    (input, prevFiber, cont) =>
                                        parRef.currentWith: initialPar =>
                                            Loop(initialPar): currentPar =>
                                                if currentPar > 0 then
                                                    parRef.updateAndGet(_ - 1).andThen:
                                                        // java.lang.System.err.println(s"RUNNING ASYNCHRONOUSLY $input")
                                                        Async.run {
                                                            f(input).map: chunk =>
                                                                // java.lang.System.err.println(s"TRANSFORMED $input to $chunk")
                                                                prevFiber.get.andThen:
                                                                    // java.lang.System.err.println(s"PUTTING $chunk")
                                                                    channel.put(Present(chunk)).andThen:
                                                                        parRef.updateAndGet(_ + 1).unit
                                                        }.map: newFiber =>
                                                            Loop.done((newFiber, cont(())))
                                                else
                                                    parRef.nextWith: nextPar =>
                                                        Loop.continue(nextPar)
                                                end if
                                ,
                                done = (finalFiber, _) => finalFiber.get
                            )

                            Abort.fold[E | Closed](
                                _ => Abort.run(channel.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channel.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channel.put(Absent)).andThen(Abort.panic(e))
                            )(handledStream)

                        background.map: backgroundFiber =>
                            emitMaybeChunksFromChannel(channel).andThen:
                                backgroundFiber.get.unit
        end mapChunkPar

        /** Applies effectful transformation of stream elements asynchronously, mapping them in parallel. Preserves chunk boundaries.
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkPar[V2, S2](f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i1, i2, ev, frame)

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunks in parallel. Does not preserve chunk
          * boundaries.
          *
          * @param parallel
          *   Maximum number of elements to transform in parallel at a time
          * @param bufferSize
          *   Size of buffer used to mediate
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkParUnordered[V2, S2](
            parallel: Int,
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.initWith[Maybe[Chunk[V]]](bufferSize): channelIn =>
                    Channel.initWith[Maybe[Chunk[V2]]](bufferSize): channelOut =>
                        val input = Abort.run(
                            stream.foreachChunk(c => channelIn.put(Present(c)))
                        ).andThen(channelIn.putBatch(Chunk.fill(parallel)(Absent)))
                        val transform = Async.fill(parallel, parallel) {
                            Loop(()): _ =>
                                channelIn.take.map:
                                    case Absent => Loop.done
                                    case Present(c) => f(c).map: c2 =>
                                        channelOut.put(Present(c2)).andThen(Loop.continue)
                        }.andThen(channelOut.put(Absent))

                        val background = Async.run:
                            Abort.fold[E | Closed](
                                _ => Abort.run(channelOut.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(input, transform))

                        background.map: backgroundFiber =>
                            emitMaybeChunksFromChannel(channelOut).andThen:
                                backgroundFiber.get.unit

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunk in parallel. Does not preserve chunk boundaries.
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkParUnordered[V2, S2](f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i1: Isolate.Contextual[S & S2, IO],
            i2: Isolate.Stateful[S & S2, Abort[E] & Async],
            ev: SafeClassTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i1, i2, ev, frame)

    end extension
end StreamCoreExtensions

export StreamCoreExtensions.*
