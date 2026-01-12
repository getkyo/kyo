package kyo

import kyo.ChunkBuilder
import kyo.kernel.ArrowEffect
import scala.annotation.implicitNotFound
import scala.util.NotGiven

object StreamCoreExtensions:
    val defaultAsyncStreamBufferSize = 1024

    private def emitMaybeChunksFromChannel[V](channel: Channel[Maybe[Chunk[V]]])(using Tag[Emit[Chunk[V]]], Frame) =
        val emit = Loop.foreach:
            channel.take.map:
                case Absent =>
                    Loop.done
                case Present(c) =>
                    if c.nonEmpty then
                        Emit.valueWith(c)(Loop.continue)
                    else
                        Loop.continue
        Abort.run(emit).unit
    end emitMaybeChunksFromChannel

    private def emitElementsFromChannel[V](channel: Channel[V])(using Tag[Emit[Chunk[V]]], Frame) =
        val emit = Loop.forever:
            channel.take.map: v =>
                Abort.recover[Closed](_ => Chunk.empty)(channel.drain).map: chunk =>
                    val fullChunk = Chunk(v).concat(chunk)
                    Emit.value(fullChunk)
        Abort.run[Closed](emit).unit
    end emitElementsFromChannel

    sealed trait StreamHub[A, E]:
        def subscribe(using Frame): Stream[A, Abort[E] & Async] < (Scope & Async)

    private class StreamHubImpl[A, E](
        hub: Hub[Result.Partial[E, Maybe[Chunk[A]]]],
        streamStatus: AtomicRef.Unsafe[Maybe[Result.Partial[E, Unit]]],
        latch: Latch
    )(
        using
        Tag[Emit[Chunk[A]]],
        Tag[Emit[Chunk[Chunk[A]]]],
        Tag[Abort[E]]
    ) extends StreamHub[A, E]:
        private def emit(listener: Hub.Listener[Result.Partial[E, Maybe[Chunk[A]]]])(using Frame) =
            listener
                .stream(1)
                .collectWhile {
                    case Result.Success(maybeChunk) => maybeChunk
                    case Result.Failure(e)          => Abort.fail(e)
                }
                .mapChunk(v => v.flattenChunk)
                .emit
        end emit

        private def emitWithStatus(listener: Hub.Listener[Result.Partial[E, Maybe[Chunk[A]]]])(using Frame) =
            // Ensure the end-of-stream signal is propagated to new listeners
            Sync.Unsafe(streamStatus.get()).map:
                case Present(Result.Success(_)) =>
                    Abort.run[Closed](hub.put(Result.Success(Absent))).andThen(emit(listener))
                case Present(Result.Failure(e)) =>
                    Abort.run[Closed](hub.put(Result.Failure(e))).andThen(emit(listener))
                case Absent =>
                    emit(listener)

        def subscribe(using Frame): Stream[A, Abort[E] & Async] < (Scope & Async) =
            Abort.runPartial[Closed](hub.listen).map:
                case Result.Success(listener) =>
                    Stream:
                        latch.release.andThen:
                            Abort.run[Closed](hub.empty).map: hubIsEmptyResult =>
                                // If the hub is not empty post subscription, we don't need to check status
                                if hubIsEmptyResult.getOrElse(true) then
                                    emitWithStatus(listener)
                                else emit(listener)

                case Result.Failure(e) => Abort.panic(e)

        def consume[S](stream: Stream[A, Abort[E] & S & Async])(
            using
            i: Isolate[S, Sync, S],
            t1: ConcreteTag[E],
            t2: Tag[Emit[Chunk[A]]],
            fr: Frame
        ): Unit < (Async & S & Scope) =
            Scope.acquireRelease(Fiber.initUnscoped {
                Abort.run[E](
                    Abort.run[Closed](
                        latch.await.andThen(stream.foreachChunk(chunk => hub.put(Result.Success(Present(chunk)))))
                    )
                ).map:
                    case Result.Success(_) =>
                        Sync.Unsafe(streamStatus.set(Present(Result.Success(())))).andThen(hub.put(Result.Success(Absent)))
                    case Result.Failure(e) => Sync.Unsafe(streamStatus.set(Present(Result.Failure(e)))).andThen(hub.put(Result.Failure(e)))
                    case panic @ Result.Panic(e) => Abort.get(panic)
            })(_.interrupt).unit
    end StreamHubImpl

    private object StreamHubImpl:
        def init[A, E](bufferSize: Int)(
            using
            Tag[A],
            Tag[Emit[Chunk[A]]],
            Tag[Emit[Chunk[Chunk[A]]]],
            Tag[Abort[E]],
            Frame
        ): StreamHubImpl[A, E] < (Async & Scope) =
            Sync.Unsafe:
                Latch.initWith(1): latch =>
                    Hub.initWith[Result.Partial[E, Maybe[Chunk[A]]]](bufferSize): hub =>
                        StreamHubImpl(hub, AtomicRef.Unsafe.init(Absent), latch)
    end StreamHubImpl

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
        def collectAll[V, E: ConcreteTag, S](
            streams: Seq[Stream[V, Abort[E] & S & Async]],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate[S, Sync, S],
            Tag[Emit[Chunk[V]]],
            Frame,
            Tag[Abort[E]]
        ): Stream[V, S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    for
                        _ <- Fiber.initUnscoped(Abort.run {
                            Async.foreachDiscard(streams)(
                                _.foreachChunk(c => Abort.run[Closed](channel.put(Present(c))))
                            )
                        }.andThen(Abort.run(channel.put(Absent)).unit))
                        _ <- emitMaybeChunksFromChannel(channel)
                    yield ()

        /** Creates a stream from an iterator.
          *
          * @param v
          *   Iterator to create a stream from
          * @param chunkSize
          *   Size of the chunks that the iterator will produce and the stream will read from
          */
        def fromIterator[V](v: => Iterator[V], chunkSize: Int = Stream.DefaultChunkSize)(using
            Tag[Emit[Chunk[V]]],
            Frame
        ): Stream[V, Sync] =
            val stream: Stream[V, Sync] < Sync = Sync.defer:
                val it      = v
                val size    = chunkSize max 1
                val builder = ChunkBuilder.init[V]

                val pull: Chunk[V] < Sync =
                    Sync.defer:
                        var count = 0
                        while count < size && it.hasNext do
                            builder.addOne(it.next())
                            count += 1

                        builder.result()

                Stream:
                    Loop.foreach:
                        Abort.run(pull).map:
                            case Result.Success(chunk) if chunk.isEmpty => Loop.done
                            case Result.Success(chunk)                  => Emit.valueWith(chunk)(Loop.continue)
                            case Result.Panic(throwable) =>
                                Sync.defer:
                                    val lastElements: Chunk[V] = builder.result()
                                    Emit.valueWith(lastElements)(Abort.panic(throwable))

            Stream.unwrap(stream)
        end fromIterator

        /** Creates a stream from an iterator.
          *
          * @typeParam
          *   E type of Exception to catch
          * @param v
          *   Iterator to create a stream from
          * @param chunkSize
          *   Size of the chunks that the iterator will produce and the stream will read from
          */
        inline def fromIteratorCatching[E <: Throwable](using
            frame: Frame
        )[V](v: => Iterator[V], chunkSize: Int = Stream.DefaultChunkSize)(using
            tag: Tag[Emit[Chunk[V]]],
            ct: ConcreteTag[E],
            @implicitNotFound(
                "Missing *explicit* type param on `fromIteratorCatching[E = SomeException](iterator, chunkSize)`." +
                    "\n - Cannot catch Exceptions as `Failure[E]` using `E = Nothing`, they are turned into `Panic`." +
                    "\n       If you need this behaviour, use `fromIterator(iterator, chunkSize)` directly." +
                    "\n - `fromIteratorCatching[E = Throwable]` catches all Exceptions as `Failure`."
            ) notNothing: NotGiven[E =:= Nothing]
        ): Stream[V, Sync & Abort[E]] =
            val stream: Stream[V, (Sync & Abort[E])] < Sync = Sync.defer:
                val it      = v
                val size    = chunkSize max 1
                val builder = ChunkBuilder.init[V]

                val pull: Chunk[V] < (Sync & Abort[E]) =
                    Sync.defer:
                        Abort.catching[E]:
                            var count = 0
                            while count < size && it.hasNext do
                                builder.addOne(it.next())
                                count += 1

                            builder.result()

                Stream:
                    Loop.foreach:
                        Abort.run(pull).map:
                            case Result.Success(chunk) if chunk.isEmpty => Loop.done
                            case Result.Success(chunk)                  => Emit.valueWith(chunk)(Loop.continue)
                            case error: Result.Error[E] @unchecked =>
                                Sync.defer:
                                    val lastElements: Chunk[V] = builder.result()
                                    Emit.valueWith(lastElements)(Abort.error(error))

            Stream.unwrap(stream)
        end fromIteratorCatching

        /** Merges multiple streams asynchronously. Stream stops as soon as any of the source streams complete.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param streams
          *   Sequence of streams to be merged
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        def collectAllHalting[V, E: ConcreteTag, S](
            streams: Seq[Stream[V, S & Abort[E] & Async]],
            bufferSize: Int = defaultAsyncStreamBufferSize
        )(
            using
            Isolate[S, Sync, S],
            Tag[Emit[Chunk[V]]],
            Tag[Abort[E]],
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    for
                        _ <- Fiber.initUnscoped(Abort.run(
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
            Isolate[S, Sync, S],
            ConcreteTag[E],
            Tag[Emit[Chunk[V]]],
            Frame,
            Tag[Abort[E]]
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
            Isolate[S, Sync, S],
            ConcreteTag[E],
            Tag[Emit[Chunk[V]]],
            Frame,
            Tag[Abort[E]]
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
            Isolate[S, Sync, S],
            Tag[Abort[E | Closed]],
            Tag[Emit[Chunk[V]]],
            ConcreteTag[E],
            Frame
        ): Stream[V, Abort[E] & S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    for
                        _ <- Fiber.initUnscoped(
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
            i: Isolate[S, Sync, S],
            t: Tag[Abort[E | Closed]],
            t2: Tag[Emit[Chunk[V]]],
            t3: ConcreteTag[E],
            f: Frame
        ): Stream[V, Abort[E] & S & Async] =
            other.mergeHaltingLeft(stream)(using i, t, t2, t3, f)

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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            given CanEqual[Boolean | Chunk[V2], Boolean | Chunk[V2]] = CanEqual.derived
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Emit from channel of fibers to allow parallel transformations while preserving order
                Channel.use[Fiber[Chunk[V2], Async & Abort[E | Closed] & S & S2]](bufferSize): channelOut =>
                    // Concurrency limiter
                    Meter.useSemaphore(parallel): semaphore =>
                        // Ensure lingering fibers are interrupted
                        val cleanup = Abort.run[Closed]:
                            Sync.ensure(channelOut.close):
                                Loop.foreach:
                                    channelOut.drain.map: chunk =>
                                        if chunk.isEmpty then Loop.done
                                        else Kyo.foreach(chunk)(_.interrupt).andThen(Loop.continue)

                        // Handle original stream by running transformations in parallel, limiting concurrency
                        // via semaphore
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    // Fork async generation of chunks (with each transformation limited by semaphore)
                                    // and publish fiber to output channel. Wait for concurrency using semaphore first to
                                    // backpressure handler loop
                                    semaphore.run(Fiber.initUnscoped(Async.foreach(input)(v => semaphore.run(f(v))))).map: chunkFiber =>
                                        channelOut.put(chunkFiber).andThen(Loop.continue(cont(())))
                        )

                        // Run stream handler in background, propagating errors to foreground
                        val background =
                            Abort.fold[E | Closed](
                                // When finished, set output channel to close once it's drained
                                onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                onFail = {
                                    case _: Closed => bug("buffer closed unexpectedly")
                                    case e: E @unchecked =>
                                        cleanup.andThen(Abort.fail[E](e))
                                },
                                onPanic = e => cleanup.andThen(Abort.panic(e))
                            )(handleEmit)

                        // Emit chunks from fibers published to channelOut
                        val emitResults =
                            val emit = Loop.forever:
                                channelOut.take.map: chunkFiber =>
                                    chunkFiber.get.map: chunk =>
                                        if chunk.nonEmpty then Emit.value(chunk) else Kyo.unit
                            Abort.run[E | Closed](emit).map:
                                case Result.Failure(_: Closed)       => ()
                                case Result.Failure(e: E @unchecked) => Abort.fail[E](e)
                                case _                               => ()
                        end emitResults

                        // Stream from output channel, running handlers in background
                        Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                            emitResults.andThen:
                                // Join background to propagate errors to foreground
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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, t4, t5, i, ev, frame)

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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Output channel containing transformed values
                Channel.use[V2](bufferSize): channelOut =>
                    // Channel containing transformation fibers. This is needed to ensure
                    // all transformations get published prior to completion
                    Channel.use[Fiber[Unit, Async & Abort[E | Closed] & S & S2]](bufferSize): channelPar =>
                        // Concurrency limiter
                        Meter.useSemaphore(parallel): semaphore =>
                            // Ensure lingering fibers are interrupted
                            val cleanup = Abort.run[Closed]:
                                Sync.ensure(channelPar.close.andThen(channelOut.close)):
                                    Loop.foreach:
                                        channelPar.drain.map: chunk =>
                                            if chunk.isEmpty then Loop.done
                                            else Kyo.foreach(chunk)(_.interrupt).andThen(Loop.continue)

                            // Handle original stream, asynchronously transforming input and publishing output
                            // using semaphore as rate limiter
                            val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                                handle = [C] =>
                                    (input, cont) =>
                                        // For each element in input chunk, transform and publish each to channelOut
                                        // concurrently, limited by semaphore. Fork this collective process and publish
                                        // fiber to channelPar in order to ensure completion/interruption. Wait for
                                        // concurrency first using semaphore to backpressure handler loop
                                        semaphore.run(Fiber.initUnscoped(
                                            Async.foreachDiscard(input)(v => semaphore.run(f(v).map(channelOut.put(_))))
                                        )).map: fiber =>
                                            channelPar.put(fiber).andThen(Loop.continue(cont(())))
                            ).andThen(channelPar.closeAwaitEmpty.unit)

                            // Drain channelPar, waiting for each fiber to complete before finishing. This
                            // ensures background fiber does not complete until all transformations are published
                            val handlePar =
                                Abort.run[Closed](
                                    Loop.forever(channelPar.take.map(_.get))
                                ).unit

                            // Run stream handler in background, closing the output channel when finished
                            // and propagating failures
                            val background =
                                Abort.fold[E | Closed](
                                    onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                    onFail = {
                                        case _: Closed       => bug("buffer closed unexpectedly")
                                        case e: E @unchecked => cleanup.andThen(Abort.fail[E](e))
                                    },
                                    onPanic = e => cleanup.andThen(Abort.panic(e))
                                )(Async.foreachDiscard(Seq(handleEmit, handlePar))(identity).unit)

                            // Emit from channel while running handler in background, then joining handler
                            // to capture any failures from background
                            Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                                emitElementsFromChannel(channelOut).andThen:
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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, t4, t5, i, ev, frame)

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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Emit from channel of fibers to allow parallel transformations while preserving order
                Channel.use[Fiber[Chunk[V2], Async & Abort[E | Closed] & S & S2]](bufferSize): channelOut =>
                    // Concurrency limiter
                    Meter.useSemaphore(parallel): semaphore =>
                        // Ensure lingering fibers are interrupted
                        val cleanup = Abort.run[Closed]:
                            Sync.ensure(channelOut.close):
                                Loop.foreach:
                                    channelOut.drain.map: chunk =>
                                        if chunk.isEmpty then Loop.done
                                        else Kyo.foreach(chunk)(_.interrupt).andThen(Loop.continue)

                        // Handle original stream by running transformations in parallel, limiting concurrency
                        // via semaphore
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                (input, cont) =>
                                    // Transform chunk in background, publishing fiber to channelOut
                                    semaphore.run(Fiber.initUnscoped(f(input))).map: chunkFiber =>
                                        channelOut.put(chunkFiber).andThen(Loop.continue(cont(())))
                        )

                        // Run stream handler in background, propagating errors to foreground
                        val background =
                            Abort.fold[E | Closed](
                                // When finished, set output channel to close once it's drained
                                onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                onFail = {
                                    case _: Closed       => bug("buffer closed unexpectedly")
                                    case e: E @unchecked => cleanup.andThen(Abort.fail[E](e))
                                },
                                onPanic = e => cleanup.andThen(Abort.panic(e))
                            )(handleEmit)

                        // Emit chunks from fibers published to channelOut
                        val emitResults =
                            val emit = Loop.forever:
                                channelOut.take.map: chunkFiber =>
                                    chunkFiber.use: chunk =>
                                        if chunk.nonEmpty then Emit.value(chunk) else Kyo.unit
                            Abort.run[E | Closed](emit).map:
                                case Result.Failure(e: E @unchecked) => Abort.fail[E](e)
                                case _                               => ()
                        end emitResults

                        // Stream from output channel, running handlers in background
                        Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                            emitResults.andThen:
                                // Join background to propagate errors to foreground
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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, t4, t5, i, ev, frame)

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunks in parallel. Does not preserve chunk
          * boundaries.
          *
          * @note
          *   Keeps a separate buffer for background fibers, which means that the number of chunks in memory can be up to 2*[[bufferSize]]
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
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Output channel containing transformed values
                Channel.use[Chunk[V2]](bufferSize): channelOut =>
                    // Channel containing transformation fibers. This is needed to ensure
                    // all transformations get published prior to completion
                    Channel.use[Fiber[Unit, Async & Abort[E | Closed] & S & S2]](bufferSize): channelPar =>
                        // Concurrency limiter
                        Meter.useSemaphore(parallel): semaphore =>
                            // Ensure lingering fibers are interrupted
                            val cleanup = Abort.run[Closed]:
                                Sync.ensure(channelPar.close.andThen(channelOut.close)):
                                    Loop.foreach:
                                        channelPar.drain.map: chunk =>
                                            if chunk.isEmpty then Loop.done
                                            else Kyo.foreach(chunk)(_.interrupt).andThen(Loop.continue)

                            // Handle original stream, asynchronously transforming input and publishing output
                            // using semaphore as rate limiter
                            val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                                handle = [C] =>
                                    (input, cont) =>
                                        // Transform chunks and publish to channelOut in background fiber, placing
                                        // fiber in channelPar to ensure completion/interruption
                                        semaphore.run(Fiber.initUnscoped(
                                            f(input).map: chunk =>
                                                channelOut.put(chunk).unit
                                        )).map: fiber =>
                                            channelPar.put(fiber).andThen(Loop.continue(cont(())))
                            ).andThen(channelPar.closeAwaitEmpty.unit)

                            // Drain channelPar, waiting for each fiber to complete before finishing. This
                            // ensures background fiber does not complete until all transformations are published
                            val handlePar =
                                Abort.run[Closed](
                                    Loop.forever:
                                        channelPar.take.map(_.get)
                                ).unit

                            // Run stream handler in background, closing the output channel when finished
                            // and propagating failures
                            val background =
                                Abort.fold[E | Closed](
                                    onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                    onFail = {
                                        case _: Closed       => bug("buffer closed unexpectedly")
                                        case e: E @unchecked => cleanup.andThen(Abort.fail[E](e))
                                    },
                                    onPanic = e => cleanup.andThen(Abort.panic(e))
                                )(Async.foreachDiscard(Seq(handleEmit, handlePar))(identity))

                            // Emit chunks from channelOut
                            val emitResults =
                                val emit = Loop.forever:
                                    channelOut.take.map: chunk =>
                                        if chunk.nonEmpty then Emit.value(chunk) else Kyo.unit
                                Abort.run(emit).unit
                            end emitResults

                            // Emit from channel while running handler in background, then joining handler
                            // to capture any failures from background
                            Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                                emitResults.andThen:
                                    backgroundFiber.get.unit

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunk in parallel. Does not preserve chunk boundaries.
          *
          * @note
          *   Keeps a separate buffer for background fibers, which means that the number of chunks in memory can be up to 2*[[bufferSize]]
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkParUnordered[V2, S2](f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            t4: Tag[Abort[E | Closed]],
            t5: Tag[Abort[E]],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, t4, t5, i, ev, frame)

        /** Broadcast to two streams that can be evaluated in parallel. Original stream begins to run as soon as either of the original
          * streams does.
          *
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted streams
          * @return
          *   2-tuple of broadcasted streams
          */
        def broadcast2(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): (Stream[V, Abort[E] & Async], Stream[V, Abort[E] & Scope & Async]) < (Scope & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                yield (s1, s2)
            }(using i, t1, t2, t3, t4, t5, fr)

        /** Broadcast to three streams that can be evaluated in parallel.
          */
        def broadcast3(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Scope & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                yield (s1, s2, s3)
            }(using i, t1, t2, t3, t4, t5, fr)

        /** Broadcast to four streams that can be evaluated in parallel.
          */
        def broadcast4(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Scope & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                    s4 <- streamHub.subscribe
                yield (s1, s2, s3, s4)
            }(using i, t1, t2, t3, t4, t5, fr)

        /** Broadcast to five streams that can be evaluated in parallel.
          */
        def broadcast5(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Scope & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                    s4 <- streamHub.subscribe
                    s5 <- streamHub.subscribe
                yield (s1, s2, s3, s4, s5)
            }(using i, t1, t2, t3, t4, t5, fr)

        /** Broadcast to a specified number of streams that can be evaluated in parallel.
          *
          * @param numStreams
          *   Number of streams to broadcast the original stream to
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted streams
          * @return
          *   Chunk of streams of length [[numStreams]] containing the broadcasted streams
          */
        def broadcastN(numStreams: Int, bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): Chunk[Stream[V, Abort[E] & Scope & Async]] < (Scope & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                val builder = Chunk.newBuilder[Stream[V, Abort[E] & Scope & Async]]
                Loop(numStreams): remaining =>
                    if remaining <= 0 then
                        Sync.defer(builder.result()).map(chunk => Loop.done(chunk))
                    else
                        streamHub.subscribe.map: stream =>
                            Sync.defer(builder.addOne(stream)).andThen(Loop.continue(remaining - 1))
            }(using i, t1, t2, t3, t4, t5, fr)

        /** Convert to a reusable stream that can be run multiple times in parallel to consume the same original elements. Original stream
          * begins to run as soon as the broadcasted stream is run for the first time.
          *
          * @note
          *   This method should only be used when it is not necessary for each evaluation of the resulting stream to consume all the
          *   elements of the original stream. Elements handled by all currently running instances of the stream prior to a subsequent runs
          *   will be lost. As soon a single run commences, elements will start being pulled from the original stream and may be lost prior
          *   to subsequent runs. To guarantee all runs handle the same elements, use [[broadcastDynamicWith]] or [[broadcast[N]]].
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted stream
          * @return
          *   A resourceful, asynchronous effect producing a stream that can be run multiple times in parallel
          */
        def broadcasted(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): Stream[V, Abort[E] & Async & Scope] < (Scope & Async & S) =
            broadcastDynamic(bufferSize).map: streamHub =>
                Stream:
                    streamHub.subscribe.map(_.emit)

        /** Construct a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. Original stream begins to
          * run the first time any subscribed stream is run.
          *
          * @note
          *   This method should only be used when it is not necessary for each subscription to consume all the elements of the original
          *   stream. Elements handled by all subscriptions prior to a subsequent subscription [[StreamHub]] will be lost. As soon a single
          *   subscription is constructed and evaluated, elements will be pulled from the original stream, meaning that elements may be lost
          *   between subscriptions. To guarantee all streams include the same elements, use [[broadcastDynamicWith]] or [[broadcastN]].
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted stream
          * @return
          *   A resourceful, asynchronous effect producing a stream that can be run multiple times in parallel
          */
        def broadcastDynamic(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): StreamHub[V, E] < (Scope & Async & S) =
            Latch.initWith(1): latch =>
                StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                    streamHub.consume(stream).andThen:
                        streamHub
        end broadcastDynamic

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter [[fn]]. Original stream
          * begins to run the first time any subscribed stream is run.
          *
          * @note
          *   Do not await evaluation of subscribed streams within [[fn]].
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted stream
          * @return
          *   A resourceful, asynchronous effect producing a stream that can be run multiple times in parallel
          */
        def broadcastDynamicWith[A, S1](bufferSize: Int)(fn: StreamHub[V, E] => A < S1)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Abort[E]],
            t5: ConcreteTag[E],
            fr: Frame
        ): A < (Scope & Async & S & S1) =
            StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter [[fn]]. Original stream
          * begins to run the first time any subscribed stream is run.
          *
          * Uses a default buffer size.
          *
          * @note
          *   Do not await evaluation of subscribed streams within [[fn]].
          * @return
          *   A resourceful, asynchronous effect producing a stream that can be run multiple times in parallel
          */
        def broadcastDynamicWith[A, S1](fn: StreamHub[V, E] => A < S1)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: ConcreteTag[E],
            t: Tag[Abort[E]],
            fr: Frame
        ): A < (Scope & Async & S & S1) =
            StreamHubImpl.init[V, E](defaultAsyncStreamBufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

        /** Collects values that are emitted by the original stream within the duration [[maxTime]] up to the amount [[maxSize]] and emits
          * them as a chunk.
          *
          * If no elements are emitted by the original stream within [[maxTime]], as soon as any other elements are emitted the result
          * stream will emit them as a group.
          *
          * @param maxSize
          *   Maximum number of elements to be collected within a single duration. Values of less than one are ignored and treated as one.
          * @param maxTime
          *   Maximum amount of time to collect and emit elements
          * @return
          *   A new stream that emits collected chunks of elements
          */
        def groupedWithin(maxSize: Int, maxTime: Duration, bufferSize: Int = defaultAsyncStreamBufferSize)(using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[Chunk[V]]]],
            t3: Tag[Abort[E]],
            t4: Tag[Abort[E | Closed]],
            i: Isolate[S, Sync, S],
            ct: ConcreteTag[Closed | E],
            fr: Frame
        ): Stream[Chunk[V], S & Abort[E] & Async] =
            import Event.*
            enum Event derives CanEqual:
                case Data(chunk: Chunk[V])
                case Tick, Flush
            end Event

            Stream[Chunk[V], S & Abort[E] & Async]:
                Sync.Unsafe {
                    val safeMax = 1 max maxSize
                    val channel = Channel.Unsafe.init[Event](1 max bufferSize).safe

                    // Handle loop collecting emitted values and flushing them until completion
                    val push: Fiber[Unit, Abort[E | Closed] & S] < (Sync & S) =
                        Fiber.initUnscoped:
                            Sync.ensure(Fiber.initUnscoped(using Isolate[Any, Any, Any])(channel.put(Flush))):
                                ArrowEffect.handleLoop(t1, stream.emit)(
                                    handle = [C] =>
                                        (chunk, cont) =>
                                            channel.put(Data(chunk)).andThen:
                                                Loop.continue(cont(()))
                                )

                    // Single fiber emitting a tick at constant interval
                    val tick: Fiber[Unit, Abort[Closed]] < (Sync & Scope) =
                        if maxTime == Duration.Infinity then Fiber.unit
                        else
                            Scope.acquireRelease(Clock.repeatWithDelay(maxTime)(channel.put(Tick)))(_.interrupt)

                    // Loop collecting values from the channel and re-emitting them as chunks.
                    // Chunks are emitted when the buffer exceeds the max size or a flush is requested.
                    val pull: Unit < (Abort[Closed] & Async & Emit[Chunk[Chunk[V]]]) =
                        Loop(Chunk.empty[V]): buffer =>
                            channel.take.map:
                                case Data(chunk) =>
                                    val combined = buffer.concat(chunk)
                                    if combined.size >= safeMax then
                                        Emit.valueWith(Chunk(combined.take(safeMax)))(Loop.continue(combined.drop(safeMax)))
                                    else
                                        Loop.continue(combined)
                                    end if
                                case Tick =>
                                    if buffer.nonEmpty then
                                        Emit.valueWith(Chunk(buffer))(Loop.continue(Chunk.empty))
                                    else
                                        Loop.continue(buffer)
                                case Flush =>
                                    if buffer.nonEmpty then
                                        Emit.valueWith(Chunk(buffer))(Loop.done)
                                    else
                                        Loop.done

                    (for
                        _     <- tick
                        fiber <- push
                        _     <- Abort.run[Closed](pull) // ignore Closed channel, join the push fiber to capture any Abort.
                        _     <- fiber.get
                    yield ()).handle(Scope.run, Abort.run[Closed], _.unit)
                }
        end groupedWithin

    end extension

end StreamCoreExtensions

export StreamCoreExtensions.*
