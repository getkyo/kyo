package kyo

import kyo.Async.defaultConcurrency
import kyo.kernel.ArrowEffect

object StreamCoreExtensions:
    val defaultAsyncStreamBufferSize = 1024

    private def emitMaybeChunksFromChannel[V](channel: Channel[Maybe[Chunk[V]]])(using Tag[Emit[Chunk[V]]], Frame) =
        val emit = Loop.foreach:
            channel.take.map:
                case Absent =>
                    Loop.done
                case Present(c) =>
                    Emit.valueWith(c)(Loop.continue)
        Abort.run(emit).unit
    end emitMaybeChunksFromChannel

    private def emitMaybeElementsFromChannel[V](channel: Channel[Maybe[V]])(using Tag[Emit[Chunk[V]]], Frame) =
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
            Tag[Emit[Chunk[V]]],
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
            Tag[Emit[Chunk[V]]],
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.initWith[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
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
            Tag[Emit[Chunk[V]]],
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
            Tag[Emit[Chunk[V]]],
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
            Tag[Emit[Chunk[V]]],
            Frame
        ): Stream[V, Abort[E] & S & Async] =
            Stream:
                Channel.initWith[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
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
            t: Tag[Emit[Chunk[V]]],
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
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize) { outputChannel =>
                    Channel.initWith[Unit](parallel): parChannel =>
                        def throttled[A](task: A < (Async & Abort[Closed | E] & S2)) =
                            parChannel.put(()).andThen(task.map(a => parChannel.take.andThen(a)))

                        val background = Async.run:
                            val handledStream = ArrowEffect.handleLoop(t1, initialState, stream.emit)(
                                handle = [C] =>
                                    (input, state, cont) =>
                                        val (prevEmitFiber, initialRemainingPar) = state
                                        Loop(Fiber.success[E | Closed, Chunk[V2]](Chunk.empty[V2]), input, initialRemainingPar):
                                            (prevChunkFiber, remainingChunk, remainingPar) =>
                                                val nextParSection = remainingChunk.take(remainingPar)

                                                val nextChunkEffect = Async.foreach(nextParSection)(v => throttled(f(v)))

                                                val newRemainingPar   = remainingPar - nextParSection.size
                                                val newRemainingChunk = remainingChunk.drop(remainingPar)

                                                if newRemainingPar <= 0 && newRemainingChunk.size <= 0 then
                                                    nextChunkEffect.map: nextChunk =>
                                                        prevChunkFiber.use: prevChunk =>
                                                            prevEmitFiber.get.andThen:
                                                                outputChannel.put(Present(prevChunk ++ nextChunk)).andThen:
                                                                    Loop.done(Loop.continue((Fiber.unit, parallel), cont(())))
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
                                                                    outputChannel.put(Present(chunk))
                                                    }.map: nextFiber =>
                                                        Loop.done(Loop.continue(
                                                            (nextFiber, newRemainingPar),
                                                            cont(())
                                                        ))
                                                else
                                                    bug("Illegal state: there is remaining parallel and remaining chunk in mapPar")
                                                end if
                                ,
                                done = {
                                    case ((lastFiber, _), _) =>
                                        lastFiber.get.andThen:
                                            outputChannel.put(Absent)
                                }
                            )

                            Abort.fold[E | Closed](
                                onSuccess = _ => Abort.run(outputChannel.put(Absent)).unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(outputChannel.put(Absent)).andThen(Abort.fail(e))
                                },
                                onPanic = e => Abort.run(outputChannel.put(Absent)).andThen(Abort.panic(e))
                            )(handledStream)

                        IO.ensure(outputChannel.close):
                            IO.ensure(parChannel.close):
                                background.map: backgroundFiber =>
                                    emitMaybeChunksFromChannel(outputChannel).andThen:
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
                Channel.initWith[Maybe[V2]](bufferSize): channelOut =>
                    Channel.initWith[Unit](parallel): parChannel =>
                        def throttledFork[A](task: Unit < (Async & Abort[Closed | E] & S2)) =
                            parChannel.put(()).andThen(Async.run(task.map(_ => parChannel.take.unit)))

                        val initialFiber: Fiber[E | Closed, Unit] = Fiber.unit

                        val handleEmit = ArrowEffect.handleLoop(t1, initialFiber, stream.emit)(
                            handle = [C] =>
                                (input, prevFiber, cont) =>
                                    Kyo.foldLeft(input)(prevFiber) { (pf, nextValue) =>
                                        throttledFork {
                                            f(nextValue).map(v2 => channelOut.put(Present(v2)))
                                        }.map: fiber =>
                                            Async.run(pf.get.andThen(fiber.get))
                                    }.map: nextFiber =>
                                        Loop.continue(nextFiber, cont(())),
                            done = (finalFiber, _) => finalFiber.get.andThen(channelOut.put(Absent)).unit
                        )

                        val background = Async.run:
                            Abort.fold[E | Closed](
                                _ => Abort.run(channelOut.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(handleEmit)

                        IO.ensure(channelOut.close):
                            IO.ensure(parChannel.close):
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
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize): outputChannel =>
                    Channel.initWith[Fiber[E | Closed, Maybe[Chunk[V2]]]](parallel - 1): stagingChannel =>
                        val initialFiber: Fiber[E | Closed, Unit] = Fiber.unit
                        val background = Async.run:
                            val handledStream = ArrowEffect.handleLoop(t1, initialFiber, stream.emit)(
                                handle = [C] =>
                                    (input, prevFiber, cont) =>
                                        Async.run(f(input).map(Present(_))).map: fiber =>
                                            stagingChannel.put(fiber).andThen:
                                                Async.run(prevFiber.get.andThen(fiber.get).unit).map: nextFiber =>
                                                    Loop.continue(nextFiber, cont(()))
                                ,
                                done = (finalFiber, _) => finalFiber.get.andThen(stagingChannel.put(Fiber.success(Absent)).unit)
                            )

                            val handleStaging = Loop.foreach:
                                stagingChannel.take.map: fiber =>
                                    fiber.use: maybeChunk =>
                                        outputChannel.put(maybeChunk).andThen:
                                            if maybeChunk.isEmpty then Loop.done
                                            else Loop.continue

                            Abort.fold[E | Closed](
                                _ => Abort.run(outputChannel.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(outputChannel.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(outputChannel.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(handledStream, handleStaging))

                        IO.ensure(outputChannel.close):
                            IO.ensure(stagingChannel.close):
                                background.map: backgroundFiber =>
                                    emitMaybeChunksFromChannel(outputChannel).andThen:
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
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize): channelOut =>
                    Channel.initWith[Unit](parallel): parChannel =>
                        def throttledFork[A](task: Unit < (Async & Abort[Closed | E] & S2)) =
                            parChannel.put(()).andThen(Async.run(task.map(_ => parChannel.take.unit)))

                        val initialFiber: Fiber[E | Closed, Unit] = Fiber.unit

                        val handleEmit = ArrowEffect.handleLoop(t1, initialFiber, stream.emit)(
                            handle = [C] =>
                                (input, prevFiber, cont) =>
                                    throttledFork(f(input).map(c2 => channelOut.put(Present(c2)))).map: fiber =>
                                        Async.run(prevFiber.get.andThen(fiber.get)).map: nextFiber =>
                                            Loop.continue(nextFiber, cont(())),
                            done = (finalFiber, _) => finalFiber.get.andThen(channelOut.put(Absent)).unit
                        )

                        val background = Async.run:
                            Abort.fold[E | Closed](
                                _ => Abort.run(channelOut.put(Absent)).unit,
                                {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(handleEmit)

                        IO.ensure(channelOut.close):
                            IO.ensure(parChannel.close):
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
