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
            given CanEqual[Boolean | Chunk[V2], Boolean | Chunk[V2]] = CanEqual.derived
            Stream[V2, S & S2 & Abort[E] & Async]:
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize): channelOut =>
                    // Staging channel acts as concurrency limiter: contains fibers that either contain a
                    // chunk to be published, or a signal to continue or end
                    Channel.initWith[Fiber[E | Closed, Boolean | Chunk[V2]]](parallel - 1): stagingChannel =>
                        // Handle original stream by running transformations in parallel, limiting concurrency by
                        // using staging channel as a limiter
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    // Get a chunk of fibers
                                    Kyo.foreach(input) { v =>
                                        // Fork transformation, pass result through stagingChannel merely as rate limiter,
                                        // (signal to continue)
                                        Async.run(f(v)).map: transformationFiber =>
                                            transformationFiber.map(_ => true).map: signalFiber =>
                                                stagingChannel.put(signalFiber).andThen:
                                                    transformationFiber
                                    }.map: fiberChunk =>
                                        // Note that this means one of the concurrency is "slots" is used to assemble chunk
                                        // this is not an expensive operation, however, so should not be a problem
                                        Async.run(Kyo.foreach(fiberChunk)(_.get)).map: chunkFiber =>
                                            stagingChannel.put(chunkFiber).andThen:
                                                Loop.continue(cont(()))
                        ).andThen(stagingChannel.put(Fiber.success(false)))

                        // Handle staged fibers by getting result, continuing or ending based on boolean signal, and publishing
                        // any chunks
                        val handleStaging = Loop.foreach:
                            stagingChannel.take.map: fiber =>
                                fiber.get.map:
                                    case true             => Loop.continue
                                    case false            => channelOut.put(Absent).andThen(Loop.done)
                                    case chunk: Chunk[V2] => channelOut.put(Present(chunk)).andThen(Loop.continue)

                        // Run stream and staging handlers in background, handling failures (end stream)
                        val background = Async.run:
                            Abort.fold[E | Closed](
                                onSuccess = _ => Abort.run(channelOut.put(Absent)).unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                onPanic = e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(handleEmit, handleStaging))

                        // Stream from output channel, running handlers in background
                        IO.ensure(channelOut.close):
                            IO.ensure(stagingChannel.close):
                                background.map: backgroundFiber =>
                                    emitMaybeChunksFromChannel(channelOut).andThen:
                                        backgroundFiber.get.unit
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
                    // Since we don't have to worry about order, the "staging channel" now just holds a signal
                    // determining whether to continue streaming or not
                    Channel.initWith[Fiber[E | Closed, Boolean]](parallel): parChannel =>
                        // Handle transformation effect, with signal to continue streaming
                        def throttledFork(effect: Any < (Async & Abort[Closed | E] & S2)) =
                            Async.run(effect).map: effectFiber =>
                                effectFiber.map(_ => true).map: signalFiber =>
                                    parChannel.put(signalFiber).andThen:
                                        effectFiber

                        // Handle original stream, running asynchronously transforming input and publishing output
                        // using parChannel as rate limiter (and signaling to continue)
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    Kyo.foreach(input) { v =>
                                        throttledFork(f(v).map(res => channelOut.put(Present(res))))
                                    }.andThen(Loop.continue(cont(())))
                        ).andThen(parChannel.put(Fiber.success(false)))

                        // Handle parChannel by checking whether or not to continue
                        val handlePar = Loop.foreach:
                            parChannel.take.map: fiber =>
                                fiber.get.map: continue =>
                                    if continue then Loop.continue
                                    else Loop.done

                        val background = Async.run:
                            Abort.fold[E | Closed](
                                onSuccess = _ => Abort.run(channelOut.put(Absent)).unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                onPanic = e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(handleEmit, handlePar))

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
                    // Staging channel size is one less than parallel because the `handleStaging` loop
                    // will always pull one value out and wait for it to complete
                    Channel.initWith[Fiber[E | Closed, Maybe[Chunk[V2]]]](parallel - 1): stagingChannel =>

                        // Handle original stream by running transformation asynchronously and publishing resulting *fiber*
                        // to the staging channel. Throttling is enforced by the size of the staging channel. Publish final
                        // fiber at the end.
                        val handledStream = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    Async.run(f(input).map(Present(_))).map: fiber =>
                                        stagingChannel.put(fiber).andThen:
                                            Loop.continue(cont(()))
                        ).andThen(stagingChannel.put(Fiber.success(Absent)).unit)

                        // Publish results from staging to output channel
                        val handleStaging = Loop.foreach:
                            stagingChannel.take.map: fiber =>
                                fiber.use: maybeChunk =>
                                    outputChannel.put(maybeChunk).andThen:
                                        if maybeChunk.isEmpty then Loop.done
                                        else Loop.continue

                        // Run stream handler and staging handler in background, handling errors
                        val background = Async.run:
                            Abort.fold[E | Closed](
                                onSuccess = _ => Abort.run(outputChannel.put(Absent)).unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(outputChannel.put(Absent)).andThen(Abort.fail(e))
                                },
                                onPanic = e => Abort.run(outputChannel.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(handledStream, handleStaging))

                        // Stream from output channel with handlers running in background
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
                    // Since we don't have to worry about order, the "staging channel" now just holds a signal
                    // determining whether to continue streaming or not
                    Channel.initWith[Fiber[E | Closed, Boolean]](parallel - 1): parChannel =>
                        // Handle transformation effect, with signal to continue streaming
                        def throttledFork[A](task: Any < (Async & Abort[Closed | E] & S2)) =
                            Async.run(task).map: fiber =>
                                fiber.map(_ => true).map: signalFiber =>
                                    parChannel.put(signalFiber).unit

                        // Handle original stream by running transformation and publishing result to
                        // output stream asynchronously (throttled via parChannel)
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    throttledFork(f(input).map(c2 => channelOut.put(Present(c2)))).andThen:
                                        Loop.continue(cont(()))
                        ).andThen(parChannel.put(Fiber.success(false)))

                        // Handle parChannel by waiting for each fiber to finish, and stopping only when result is false
                        val handlePar = Loop.foreach:
                            parChannel.take.map(_.get).map: continue =>
                                if continue then Loop.continue
                                else Loop.done

                        // Run stream handler and par handler in background, handling errors (ensure stream ends)
                        val background = Async.run:
                            Abort.fold[E | Closed](
                                onSuccess = _ => Abort.run(channelOut.put(Absent)).unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => Abort.run(channelOut.put(Absent)).andThen(Abort.fail(e))
                                },
                                onPanic = e => Abort.run(channelOut.put(Absent)).andThen(Abort.panic(e))
                            )(Async.gather(handleEmit, handlePar))

                        // Stream from output channel with handler running in background
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
