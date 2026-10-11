package kyo

import kyo.kernel.ArrowEffect
import kyo.kernel.Bracket
import scala.annotation.implicitNotFound
import scala.util.NotGiven

object StreamCoreExtensions:
    val defaultAsyncStreamBufferSize = 1024

    /** Every caller must run this inside `Fiber.use` over its producers: the consumer can stop first (a downstream `take` is the ordinary
      * way), and closing the channel does not reach a producer parked inside a source stream's own step, which then stays parked for the
      * life of the program holding what it acquired. `Fiber.use` binds the interrupt at the spawn, so no interrupt lands between the two.
      */
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

    /** The chunk fibers a parallel map has forked and not yet joined, and the channel that hands them to the consumer in order.
      *
      * `live`, not the channel, owns each fiber from its fork until it is joined. The channel cannot: a forked fiber is outside it until its
      * put lands, closing it discards what it holds, and a take hands a fiber to a consumer that can be interrupted before it joins. `close`
      * takes `live` in one step and interrupts all of it, a fiber forked after that is interrupted as it registers, and `use` runs `close`
      * when its region ends however it ends. So every chunk fiber forked inside `use` is either joined or interrupted by the time `use`
      * returns or is abandoned; an interrupted fiber still runs its own finalizers after that.
      */
    final private[kyo] class ChunkFibers[A, S] private (channel: Channel[Fiber[A, S]], live: AtomicRef[Maybe[Set[Fiber[A, S]]]]):

        def publish[S2](fork: Fiber[A, S] < (Sync & S2))(using Frame): Unit < (Async & Abort[Closed] & S2) =
            // The bracket's release is registered as the fork's value arrives, so a publisher abandoned before the registration
            // still interrupts the fiber it forked.
            Bracket(fork)(register) { (fiber, failure) =>
                // Unsafe: the kernel bracket's release is synchronous and takes no effects.
                import AllowUnsafe.embrace.danger
                if failure.isDefined then discard(fiber.unsafe.interrupt())
            }.map(channel.put)

        def take(using Frame): Fiber[A, S] < (Async & Abort[Closed]) =
            channel.take

        /** Whether the map still owned `fiber`. `false` means `close` took it, so its result is the map's own interrupt. */
        def joined(fiber: Fiber[A, S])(using Frame): Boolean < Sync =
            live.getAndUpdate(_.map(_ - fiber)).map(_.isDefined)

        def closeAwaitEmpty(using Frame): Unit < Async =
            channel.closeAwaitEmpty.unit

        def close(using Frame): Unit < Sync =
            channel.closeDiscard.andThen:
                live.getAndSet(Absent).map:
                    case Present(fibers) => Kyo.foreachDiscard(fibers)(_.interrupt)
                    case Absent          => Kyo.unit

        private def register(fiber: Fiber[A, S])(using Frame): Fiber[A, S] < Sync =
            live.getAndUpdate(_.map(_ + fiber)).map:
                case Present(_) => fiber
                case Absent     => fiber.interrupt.andThen(fiber)
    end ChunkFibers

    private[kyo] object ChunkFibers:
        def use[A, S](capacity: Int)[B, S2](f: ChunkFibers[A, S] => B < S2)(using Frame): B < (Sync & S2) =
            AtomicRef.init[Maybe[Set[Fiber[A, S]]]](Present(Set.empty)).map: live =>
                Channel.use[Fiber[A, S]](capacity): channel =>
                    val fibers = new ChunkFibers(channel, live)
                    Sync.ensure(fibers.close)(f(fibers))
    end ChunkFibers

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
        Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[A]]]]]]
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
            Sync.Unsafe.defer(streamStatus.get()).map:
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
                        Sync.Unsafe.defer(streamStatus.set(Present(Result.Success(())))).andThen(hub.put(Result.Success(Absent)))
                    case Result.Failure(e) =>
                        Sync.Unsafe.defer(streamStatus.set(Present(Result.Failure(e)))).andThen(hub.put(Result.Failure(e)))
                    case panic @ Result.Panic(e) => Abort.get(panic)
            })(_.interrupt).unit
    end StreamHubImpl

    private object StreamHubImpl:
        def init[A, E](bufferSize: Int)(
            using
            Tag[A],
            Tag[Emit[Chunk[A]]],
            Tag[Emit[Chunk[Chunk[A]]]],
            Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[A]]]]]],
            Frame
        ): StreamHubImpl[A, E] < (Async & Scope) =
            Sync.Unsafe.defer:
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
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    Fiber.use(Abort.run {
                        Async.foreachDiscard(streams)(
                            _.foreachChunk(c => channel.put(Present(c)))
                        )
                    }.andThen(Abort.run(channel.put(Absent)).unit))(_ => emitMaybeChunksFromChannel(channel))

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
                            case Result.Panic(throwable)                =>
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
                            case error: Result.Error[E] @unchecked      =>
                                Sync.defer:
                                    val lastElements: Chunk[V] = builder.result()
                                    Emit.valueWith(lastElements)(Abort.error(error))

            Stream.unwrap(stream)
        end fromIteratorCatching

        /** Streams a `java.io.InputStream`'s bytes.
          *
          * The stream is registered with the enclosing `Scope` and closed when that scope ends, so a caller never has to pair the
          * read with a manual close. Reads happen in `bufferSize` chunks.
          *
          * @param is
          *   the input stream to consume
          * @param bufferSize
          *   read buffer size, clamped to the range an array can address: `ByteSize.Zero` reads one byte at a time rather than spinning
          *   on a buffer that holds nothing, and anything above `Int.MaxValue` bytes reads through the largest buffer there is
          */
        def fromInputStream(is: java.io.InputStream, bufferSize: ByteSize = 8.kib)(using
            Frame
        ): Stream[Byte, Sync & Scope] =
            streamFromJavaInputStream(is, readBufferCapacity(bufferSize))

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
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    Fiber.use(Abort.run(
                        Async
                            .foreachDiscard(streams)(
                                _.foreachChunk(c => channel.put(Present(c)))
                                    .andThen(Abort.run(channel.put(Absent)))
                            )
                    ))(_ => emitMaybeChunksFromChannel(channel))

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
            Isolate[S, Sync, S],
            ConcreteTag[E],
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
            Isolate[S, Sync, S],
            ConcreteTag[E],
            Tag[Emit[Chunk[V]]],
            Frame
        ): Stream[V, Abort[E] & S & Async] =
            Stream:
                Channel.use[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer): channel =>
                    Fiber.use[E | Closed, Unit, S, S](
                        Async.gather(
                            stream.foreachChunk(c => channel.put(Present(c)))
                                .andThen(channel.put(Absent)),
                            other.foreachChunk(c => channel.put(Present(c)))
                        ).andThen(channel.put(Absent))
                    )(_ => emitMaybeChunksFromChannel(channel))

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
            sct: ConcreteTag[E],
            t: Tag[Emit[Chunk[V]]],
            f: Frame
        ): Stream[V, Abort[E] & S & Async] =
            other.mergeHaltingLeft(stream)(using i, sct, t, f)

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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            given CanEqual[Boolean | Chunk[V2], Boolean | Chunk[V2]] = CanEqual.derived
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Emit from channel of fibers to allow parallel transformations while preserving order
                ChunkFibers.use[Chunk[V2], Async & Abort[E | Closed] & S & S2](bufferSize): channelOut =>
                    // Concurrency limiter
                    Meter.useSemaphore(parallel): semaphore =>
                        // Ensure lingering fibers are interrupted
                        val cleanup = channelOut.close

                        // Handle original stream by running transformations in parallel, limiting concurrency
                        // via semaphore
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                input =>
                                    // Fork async generation of chunks (with each transformation limited by semaphore)
                                    // and publish fiber to output channel. Wait for concurrency using semaphore first to
                                    // backpressure handler loop
                                    channelOut.publish(
                                        semaphore.run(Fiber.initUnscoped(Async.foreach(input)(v => semaphore.run(f(v)))))
                                    ).andThen(Loop.continue(()))
                        )

                        // Run stream handler in background, propagating errors to foreground
                        val background =
                            Abort.fold[E | Closed](
                                // When finished, set output channel to close once it's drained
                                onSuccess = _ => channelOut.closeAwaitEmpty,
                                onFail = {
                                    case _: Closed       => cleanup
                                    case e: E @unchecked => cleanup.andThen(Abort.fail(e))
                                },
                                onPanic = e => cleanup.andThen(Abort.panic(e))
                            )(handleEmit)

                        // Emit chunks from fibers published to channelOut.
                        // Use getResult to inspect fiber errors without re-raising through
                        // Abort.run[Closed], which can't distinguish user errors from channel errors.
                        // Store fiber errors and propagate after the background completes.
                        val emitResults =
                            AtomicRef.init[Maybe[Either[Throwable, Any]]](Absent).map: fiberError =>
                                val emit = Loop.forever:
                                    channelOut.take.map: chunkFiber =>
                                        chunkFiber.getResult.map: result =>
                                            channelOut.joined(chunkFiber).map: owned =>
                                                if !owned then Kyo.unit
                                                else
                                                    result match
                                                        case Result.Success(chunk) =>
                                                            if chunk.nonEmpty then Emit.value(chunk) else Kyo.unit
                                                        case Result.Panic(ex) =>
                                                            fiberError.set(Present(Left(ex)))
                                                        case Result.Failure(closed: Closed) =>
                                                            fiberError.set(Present(Left(closed)))
                                                        case Result.Failure(e) =>
                                                            // Not Closed, must be E
                                                            fiberError.set(Present(Right(e)))
                                // Closing here, ahead of the background's interrupt, makes a put in flight fail Closed
                                // rather than be abandoned mid-put.
                                Sync.ensure(cleanup):
                                    Abort.run[Closed](emit).unit
                                .andThen(fiberError)
                        end emitResults

                        // Stream from output channel, running handlers in background
                        Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                            emitResults.map: fiberError =>
                                backgroundFiber.get.unit.andThen:
                                    fiberError.get.map:
                                        case Present(Left(ex)) => Abort.panic(ex)
                                        case Present(Right(e)) => Abort.fail(e.asInstanceOf[E])
                                        case Absent            => ()
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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i, ev, frame)

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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Output channel containing transformed values
                Channel.use[V2](bufferSize): channelOut =>
                    // Channel containing transformation fibers. This is needed to ensure
                    // all transformations get published prior to completion
                    ChunkFibers.use[Unit, Async & Abort[E | Closed] & S & S2](bufferSize): channelPar =>
                        // Concurrency limiter
                        Meter.useSemaphore(parallel): semaphore =>
                            // Ensure lingering fibers are interrupted
                            val cleanup = channelPar.close.andThen(channelOut.closeDiscard)

                            // Handle original stream, asynchronously transforming input and publishing output
                            // using semaphore as rate limiter
                            val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                                handle = [C] =>
                                    input =>
                                        // For each element in input chunk, transform and publish each to channelOut
                                        // concurrently, limited by semaphore. Fork this collective process and publish
                                        // fiber to channelPar in order to ensure completion/interruption. Wait for
                                        // concurrency first using semaphore to backpressure handler loop
                                        channelPar.publish(semaphore.run(Fiber.initUnscoped(
                                            Async.foreachDiscard(input)(v => semaphore.run(f(v).map(channelOut.put(_))))
                                        ))).andThen(Loop.continue(()))
                            ).andThen(channelPar.closeAwaitEmpty)

                            // Drain channelPar, waiting for each fiber to complete before finishing.
                            // Use getResult to inspect fiber errors without re-raising through
                            // Abort.run[Closed]. Store errors for propagation after background.
                            // On error, run cleanup to close channels and interrupt fibers,
                            // unblocking the foreground emitElementsFromChannel loop.
                            AtomicRef.init[Maybe[Either[Throwable, Any]]](Absent).map: fiberError =>
                                def setError(error: Either[Throwable, Any]) =
                                    fiberError.compareAndSet(Absent, Present(error)).andThen(cleanup)

                                val handlePar =
                                    Abort.run[Closed](
                                        Loop.forever:
                                            channelPar.take.map: fiber =>
                                                fiber.getResult.map: result =>
                                                    channelPar.joined(fiber).map: owned =>
                                                        if !owned then ()
                                                        else
                                                            result match
                                                                case Result.Success(_) => ()
                                                                case Result.Panic(ex)  =>
                                                                    setError(Left(ex))
                                                                case Result.Failure(closed: Closed) =>
                                                                    setError(Left(closed))
                                                                case Result.Failure(e) =>
                                                                    setError(Right(e))
                                    ).unit

                                // Run stream handler in background, closing the output channel when finished
                                // and propagating failures
                                val background =
                                    Abort.fold[E | Closed](
                                        onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                        onFail = {
                                            case _: Closed       => cleanup
                                            case e: E @unchecked => cleanup.andThen(Abort.fail(e))
                                        },
                                        onPanic = e => cleanup.andThen(Abort.panic(e))
                                    )(Async.foreachDiscard(Seq(handleEmit, handlePar))(identity).unit)

                                // Emit from channel while running handler in background, then joining handler
                                // to capture any failures from background
                                Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                                    // Interrupting the background reaches only the chunk fiber its handler is joined to,
                                    // so the consumer's exit closes and interrupts the rest.
                                    Sync.ensure(cleanup)(emitElementsFromChannel(channelOut)).andThen:
                                        backgroundFiber.get.unit.andThen:
                                            fiberError.get.map:
                                                case Present(Left(ex)) => Abort.panic(ex)
                                                case Present(Right(e)) => Abort.fail(e.asInstanceOf[E])
                                                case Absent            => ()
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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i, ev, frame)

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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Emit from channel of fibers to allow parallel transformations while preserving order
                ChunkFibers.use[Chunk[V2], Async & Abort[E | Closed] & S & S2](bufferSize): channelOut =>
                    // Concurrency limiter
                    Meter.useSemaphore(parallel): semaphore =>
                        // Ensure lingering fibers are interrupted
                        val cleanup = channelOut.close

                        // Handle original stream by running transformations in parallel, limiting concurrency
                        // via semaphore
                        val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                            handle = [C] =>
                                input =>
                                    // Transform chunk in background, publishing fiber to channelOut. The outer
                                    // `semaphore.run` backpressures the handler loop; the inner one bounds the actual
                                    // transformation work to `parallel`. Without the inner gate the permit would only
                                    // span the fork (which returns immediately), leaving every chunk to transform
                                    // concurrently regardless of `parallel`.
                                    channelOut.publish(semaphore.run(Fiber.initUnscoped(semaphore.run(f(input)))))
                                        .andThen(Loop.continue(()))
                        )

                        // Run stream handler in background, propagating errors to foreground
                        val background =
                            Abort.fold[E | Closed](
                                // When finished, set output channel to close once it's drained
                                onSuccess = _ => channelOut.closeAwaitEmpty,
                                onFail = {
                                    case _: Closed       => cleanup
                                    case e: E @unchecked => cleanup.andThen(Abort.fail(e))
                                },
                                onPanic = e => cleanup.andThen(Abort.panic(e))
                            )(handleEmit)

                        // Emit chunks from fibers published to channelOut.
                        // Use getResult to inspect fiber errors without re-raising through
                        // Abort.run[Closed], which can't distinguish user errors from channel errors.
                        // Store fiber errors and propagate after the background completes.
                        val emitResults =
                            AtomicRef.init[Maybe[Either[Throwable, Any]]](Absent).map: fiberError =>
                                val emit = Loop.forever:
                                    channelOut.take.map: chunkFiber =>
                                        chunkFiber.getResult.map: result =>
                                            channelOut.joined(chunkFiber).map: owned =>
                                                if !owned then Kyo.unit
                                                else
                                                    result match
                                                        case Result.Success(chunk) =>
                                                            if chunk.nonEmpty then Emit.value(chunk) else Kyo.unit
                                                        case Result.Panic(ex) =>
                                                            fiberError.set(Present(Left(ex)))
                                                        case Result.Failure(closed: Closed) =>
                                                            fiberError.set(Present(Left(closed)))
                                                        case Result.Failure(e) =>
                                                            // Not Closed, must be E
                                                            fiberError.set(Present(Right(e)))
                                Sync.ensure(cleanup):
                                    Abort.run[Closed](emit).unit
                                .andThen(fiberError)
                        end emitResults

                        // Stream from output channel, running handlers in background
                        Fiber.use[E, Unit, S & S2, S & S2](background): backgroundFiber =>
                            emitResults.map: fiberError =>
                                backgroundFiber.get.unit.andThen:
                                    fiberError.get.map:
                                        case Present(Left(ex)) => Abort.panic(ex)
                                        case Present(Right(e)) => Abort.fail(e.asInstanceOf[E])
                                        case Absent            => ()
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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkPar(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i, ev, frame)

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunks in parallel. Does not preserve chunk
          * boundaries.
          *
          * @note
          *   Keeps a separate buffer for background fibers, which means that the number of chunks in memory can be up to 2*`bufferSize`
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
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            Stream[V2, S & S2 & Abort[E] & Async]:
                // Output channel containing transformed values
                Channel.use[Chunk[V2]](bufferSize): channelOut =>
                    // Channel containing transformation fibers. This is needed to ensure
                    // all transformations get published prior to completion
                    ChunkFibers.use[Unit, Async & Abort[E | Closed] & S & S2](bufferSize): channelPar =>
                        // Concurrency limiter
                        Meter.useSemaphore(parallel): semaphore =>
                            // Ensure lingering fibers are interrupted
                            val cleanup = channelPar.close.andThen(channelOut.closeDiscard)

                            // Handle original stream, asynchronously transforming input and publishing output
                            // using semaphore as rate limiter
                            val handleEmit = ArrowEffect.handleLoop(t1, stream.emit)(
                                handle = [C] =>
                                    input =>
                                        // Transform chunks and publish to channelOut in background fiber, placing
                                        // fiber in channelPar to ensure completion/interruption. The outer
                                        // `semaphore.run` backpressures the handler loop; the inner one bounds the
                                        // actual transformation work to `parallel`. Without the inner gate the permit
                                        // would only span the fork (which returns immediately), leaving every chunk to
                                        // transform concurrently regardless of `parallel`.
                                        channelPar.publish(semaphore.run(Fiber.initUnscoped(
                                            semaphore.run(f(input).map(chunk => channelOut.put(chunk).unit))
                                        ))).andThen(Loop.continue(()))
                            ).andThen(channelPar.closeAwaitEmpty)

                            // Drain channelPar, waiting for each fiber to complete before finishing.
                            // Use getResult to inspect fiber errors without re-raising through
                            // Abort.run[Closed]. Store errors for propagation after background.
                            // On error, run cleanup to close channels and interrupt fibers,
                            // unblocking the foreground emit loop.
                            AtomicRef.init[Maybe[Either[Throwable, Any]]](Absent).map: fiberError =>
                                def setError(error: Either[Throwable, Any]) =
                                    fiberError.compareAndSet(Absent, Present(error)).andThen(cleanup)

                                val handlePar =
                                    Abort.run[Closed](
                                        Loop.forever:
                                            channelPar.take.map: fiber =>
                                                fiber.getResult.map: result =>
                                                    channelPar.joined(fiber).map: owned =>
                                                        if !owned then ()
                                                        else
                                                            result match
                                                                case Result.Success(_) => ()
                                                                case Result.Panic(ex)  =>
                                                                    setError(Left(ex))
                                                                case Result.Failure(closed: Closed) =>
                                                                    setError(Left(closed))
                                                                case Result.Failure(e) =>
                                                                    setError(Right(e))
                                    ).unit

                                // Run stream handler in background, closing the output channel when finished
                                // and propagating failures
                                val background =
                                    Abort.fold[E | Closed](
                                        onSuccess = _ => channelOut.closeAwaitEmpty.unit,
                                        onFail = {
                                            case _: Closed       => cleanup
                                            case e: E @unchecked => cleanup.andThen(Abort.fail(e))
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
                                    // Interrupting the background reaches only the chunk fiber its handler is joined to,
                                    // so the consumer's exit closes and interrupts the rest.
                                    Sync.ensure(cleanup)(emitResults).andThen:
                                        backgroundFiber.get.unit.andThen:
                                            fiberError.get.map:
                                                case Present(Left(ex)) => Abort.panic(ex)
                                                case Present(Right(e)) => Abort.fail(e.asInstanceOf[E])
                                                case Absent            => ()

        /** Applies effectful transformation of stream chunks asynchronously, mapping chunk in parallel. Does not preserve chunk boundaries.
          *
          * @note
          *   Keeps a separate buffer for background fibers, which means that the number of chunks in memory can be up to 2*`bufferSize`
          *
          * @param f
          *   Asynchronous transformation of stream elements
          */
        def mapChunkParUnordered[V2, S2](f: Chunk[V] => Chunk[V2] < (Abort[E] & Async & S2))(
            using
            t1: Tag[Emit[Chunk[V]]],
            t2: Tag[Emit[Chunk[V2]]],
            t3: Tag[V2],
            i: Isolate[S & S2, Sync, S & S2],
            ev: ConcreteTag[E | Closed],
            frame: Frame
        ): Stream[V2, Abort[E] & Async & S & S2] =
            mapChunkParUnordered(Async.defaultConcurrency, defaultAsyncStreamBufferSize)(f)(using t1, t2, t3, i, ev, frame)

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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
          *   Chunk of streams of length `numStreams` containing the broadcasted streams
          */
        def broadcastN(numStreams: Int, bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
          *   to subsequent runs. To guarantee all runs handle the same elements, use [[broadcastDynamicWith]] or [[broadcastN]].
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
            t5: ConcreteTag[E],
            fr: Frame
        ): StreamHub[V, E] < (Scope & Async & S) =
            Latch.initWith(1): latch =>
                StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                    streamHub.consume(stream).andThen:
                        streamHub
        end broadcastDynamic

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter `fn`. Original stream
          * begins to run the first time any subscribed stream is run.
          *
          * @note
          *   Do not await evaluation of subscribed streams within `fn`.
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
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
            t5: ConcreteTag[E],
            fr: Frame
        ): A < (Scope & Async & S & S1) =
            StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter `fn`. Original stream
          * begins to run the first time any subscribed stream is run.
          *
          * Uses a default buffer size.
          *
          * @note
          *   Do not await evaluation of subscribed streams within `fn`.
          * @return
          *   A resourceful, asynchronous effect producing a stream that can be run multiple times in parallel
          */
        def broadcastDynamicWith[A, S1](fn: StreamHub[V, E] => A < S1)(
            using
            i: Isolate[S, Sync, S],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: Tag[Emit[Chunk[Result.Partial[E, Maybe[Chunk[V]]]]]],
            t5: ConcreteTag[E],
            fr: Frame
        ): A < (Scope & Async & S & S1) =
            StreamHubImpl.init[V, E](defaultAsyncStreamBufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

        /** Collects values that are emitted by the original stream within the duration `maxTime` up to the amount `maxSize` and emits them
          * as a chunk.
          *
          * If no elements are emitted by the original stream within `maxTime`, as soon as any other elements are emitted the result stream
          * will emit them as a group.
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
                Sync.Unsafe.defer {
                    val safeMax = 1 max maxSize
                    val channel = Channel.Unsafe.init[Event](1 max bufferSize).safe

                    // Handle loop collecting emitted values and flushing them until completion
                    val push: Fiber[Unit, Abort[E | Closed] & S] < (Sync & S) =
                        Fiber.initUnscoped:
                            Sync.ensure(Fiber.initUnscoped(using Isolate[Any, Any, Any])(channel.put(Flush))):
                                ArrowEffect.handleLoop(t1, stream.emit)(
                                    handle = [C] =>
                                        chunk =>
                                            channel.put(Data(chunk)).andThen:
                                                Loop.continue(())
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

                    // The push fork stays outside the internal scope: that Scope.run manages only the tick timer, and a
                    // producer forked inside it would scope the source stream's resources to this combinator, not the
                    // caller's ambient scope.
                    push.map: fiber =>
                        (for
                            _ <- tick
                            _ <- Abort.run[Closed](pull)
                            _ <- fiber.get
                        yield ()).handle(Scope.run, Abort.run[Closed], _.unit)
                }
        end groupedWithin

    end extension

    /** Wraps a Java `InputStream` in a `Stream[Byte, Sync & Scope]`.
      *
      * The stream reads the input in chunks of `bufferSize` bytes. The `InputStream` is registered with the enclosing `Scope` and closed
      * when the scope ends (whether by normal completion, `Abort`, or cancellation).
      */
    /** Narrows a read buffer size to the array capacity the read loop allocates.
      *
      * Two ends need a rule. `ByteSize.Zero` would allocate a buffer that reads nothing, which turns the read loop into a spin, so it
      * becomes one byte. A size above `Int.MaxValue` names more bytes than an array can address, so it becomes `Int.MaxValue`: the caller
      * asked for the largest buffer it could name and gets the largest one there is, which is what the `Int`-typed parameter this replaced
      * already did at its own ceiling.
      *
      * Clamping rather than failing keeps the read total, so the effect row of the stream stays what the read itself needs and does not
      * grow an argument-validation failure that no realistic buffer size can reach.
      */
    private[kyo] def readBufferCapacity(bufferSize: ByteSize): Int =
        val bytes = bufferSize.toBytes
        if bytes <= 0L then 1
        else if bytes > Int.MaxValue.toLong then Int.MaxValue
        else bytes.toInt
    end readBufferCapacity

    private[kyo] def streamFromJavaInputStream(is: java.io.InputStream, bufferSize: Int = 8192)(using Frame): Stream[Byte, Sync & Scope] =
        Stream {
            Scope.acquireRelease(is)(_.close()).map { stream =>
                Loop.foreach {
                    Sync.Unsafe.defer {
                        val buf = new Array[Byte](bufferSize)
                        val n   = stream.read(buf)
                        if n < 0 then Loop.done
                        else if n == 0 then Loop.continue // No data yet (JS async buffer empty) — yield and retry
                        else if n == bufferSize then
                            Emit.valueWith(Chunk.fromNoCopy(buf))(Loop.continue)
                        else
                            Emit.valueWith(Chunk.fromNoCopy(java.util.Arrays.copyOf(buf, n)))(Loop.continue)
                        end if
                    }
                }
            }
        }

end StreamCoreExtensions

// Exported by name. A wildcard emits one forwarder per member in an order the compiler does not fix, so two clean builds of identical
// sources produce different artifacts.
export StreamCoreExtensions.StreamHub
export StreamCoreExtensions.broadcast2
export StreamCoreExtensions.broadcast3
export StreamCoreExtensions.broadcast4
export StreamCoreExtensions.broadcast5
export StreamCoreExtensions.broadcastDynamic
export StreamCoreExtensions.broadcastDynamicWith
export StreamCoreExtensions.broadcasted
export StreamCoreExtensions.broadcastN
export StreamCoreExtensions.collectAll
export StreamCoreExtensions.collectAllHalting
export StreamCoreExtensions.defaultAsyncStreamBufferSize
export StreamCoreExtensions.fromInputStream
export StreamCoreExtensions.fromIterator
export StreamCoreExtensions.fromIteratorCatching
export StreamCoreExtensions.groupedWithin
export StreamCoreExtensions.mapChunkPar
export StreamCoreExtensions.mapChunkParUnordered
export StreamCoreExtensions.mapPar
export StreamCoreExtensions.mapParUnordered
export StreamCoreExtensions.merge
export StreamCoreExtensions.mergeHalting
export StreamCoreExtensions.mergeHaltingLeft
export StreamCoreExtensions.mergeHaltingRight
export StreamCoreExtensions.readBufferCapacity
export StreamCoreExtensions.streamFromJavaInputStream
