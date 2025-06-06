package kyo

import kyo.Async.defaultConcurrency
import kyo.Result.Success
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

    sealed trait StreamHub[A, E]:
        def subscribe(using Frame): Stream[A, Abort[E] & Async] < (Resource & Async)

    private case class StreamHubImpl[A, E](
        hub: Hub[Result.Partial[E, Maybe[Chunk[A]]]],
        streamStatus: AtomicRef.Unsafe[Maybe[Result.Partial[E, Unit]]],
        latch: Latch
    )(
        using
        Tag[Emit[Chunk[A]]],
        Tag[Emit[Chunk[Chunk[A]]]]
    ) extends StreamHub[A, E]:
        def subscribe(using Frame): Stream[A, Abort[E] & Async] < (Resource & Async) =
            IO.Unsafe:
                streamStatus.get() match
                    case Present(Result.Success(_)) => Stream.empty
                    case Present(Result.Failure(e)) => Stream(Abort.fail(e))
                    case Absent =>
                        Abort.runPartial[Closed](hub.listen).map:
                            case Result.Success(listener) =>
                                Stream:
                                    latch.release.andThen:
                                        listener
                                            .stream(1)
                                            .collectWhile[Chunk[A], Abort[E]] {
                                                case Result.Success(maybeChunk) => maybeChunk
                                                case Result.Failure(e)          => Abort.fail(e)
                                            }
                                            .mapChunk(v => v.flattenChunk)
                                            .emit
                            case Result.Failure(e) => Abort.panic(e)

        def consume[S](stream: Stream[A, Abort[E] & S & Async])(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Abort[E] & Async],
            t1: SafeClassTag[E],
            t2: Tag[Emit[Chunk[A]]],
            fr: Frame
        ): Unit < (Async & S & Resource) =
            Resource.acquireRelease(Async.run {
                Abort.run[E](
                    Abort.run[Closed](
                        stream.foreachChunk(chunk => hub.put(Result.Success(Present(chunk))))
                    )
                ).map:
                    case Result.Success(_) =>
                        IO.Unsafe(streamStatus.set(Present(Result.Success(())))).andThen(hub.put(Result.Success(Absent)))
                    case Result.Failure(e) => IO.Unsafe(streamStatus.set(Present(Result.Failure(e)))).andThen(hub.put(Result.Failure(e)))
                    case panic @ Result.Panic(e) => Abort.get(panic)
            })(_.interrupt).unit
    end StreamHubImpl

    private object StreamHubImpl:
        def init[A, E](bufferSize: Int)(
            using
            Tag[A],
            Tag[Emit[Chunk[A]]],
            Tag[Emit[Chunk[Chunk[A]]]],
            Frame
        ): StreamHubImpl[A, E] < (Async & Resource) =
            IO.Unsafe:
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
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize) { channel =>
                    IO.ensure(channel.close):
                        AtomicInt.init(0).map: parAdjustmentRef =>
                            val background = Async.run:
                                val handledStream = ArrowEffect.handleLoop(t1, initialState, stream.emit)(
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
                                                                prevChunkFiber.use: prevChunk =>
                                                                    prevEmitFiber.get.andThen:
                                                                        channel.put(Present(prevChunk ++ nextChunk)).andThen:
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
                                                                            channel.put(Present(chunk)).andThen:
                                                                                parAdjustmentRef.updateAndGet(_ + chunk.size).unit
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
                                                channel.put(Absent)
                                    }
                                )

                                Abort.fold[E | Closed](
                                    onSuccess = _ => Abort.run(channel.put(Absent)).unit,
                                    onFail = {
                                        case _: Closed       => bug("buffer closed unexpectedly")
                                        case e: E @unchecked => Abort.run(channel.put(Absent)).andThen(Abort.fail(e))
                                    },
                                    onPanic = e => Abort.run(channel.put(Absent)).andThen(Abort.panic(e))
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
                    IO.ensure(channelIn.close):
                        Channel.initWith[Maybe[V2]](bufferSize): channelOut =>
                            IO.ensure(channelOut.close):
                                val input = Abort.run(
                                    stream.foreach(v => channelIn.put(Present(v)))
                                ).andThen(channelIn.putBatch(Chunk.fill(parallel)(Absent)))
                                val transform = Async.fill(parallel, parallel) {
                                    Loop(()): _ =>
                                        channelIn.take.map:
                                            case Absent => Loop.done
                                            case Present(v) =>
                                                f(v).map: v2 =>
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
                Channel.initWith[Maybe[Chunk[V2]]](bufferSize): channel =>
                    IO.ensure(channel.close):
                        Signal.initRefWith(parallel): parRef =>
                            val background = Async.run:
                                val handledStream = ArrowEffect.handleLoop(t1, initialState, stream.emit)(
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
                                                                Loop.done(Loop.continue(newFiber, cont(())))
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
                    IO.ensure(channelIn.close):
                        Channel.initWith[Maybe[Chunk[V2]]](bufferSize): channelOut =>
                            IO.ensure(channelOut.close):
                                val input = Abort.run(
                                    stream.foreachChunk(c => channelIn.put(Present(c)))
                                ).andThen(channelIn.putBatch(Chunk.fill(parallel)(Absent)))
                                val transform = Async.fill(parallel, parallel) {
                                    Loop(()): _ =>
                                        channelIn.take.map:
                                            case Absent => Loop.done
                                            case Present(c) =>
                                                f(c).map: c2 =>
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

        /** Broadcast to two streams that can be evaluated in parallel.
          *
          * @param bufferSize
          *   Size of underlying channel communicating streamed elements to broadcasted streams
          * @return
          *   2-tuple of broadcasted streams
          */
        def broadcast2(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): (Stream[V, Abort[E] & Async], Stream[V, Abort[E] & Resource & Async]) < (Resource & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                yield (s1, s2)
            }(using i1, i2, t1, t2, t3, t4, fr)

        /** Broadcast to three streams that can be evaluated in parallel.
          */
        def broadcast3(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Resource & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                yield (s1, s2, s3)
            }(using i1, i2, t1, t2, t3, t4, fr)

        /** Broadcast to four streams that can be evaluated in parallel.
          */
        def broadcast4(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Resource & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                    s4 <- streamHub.subscribe
                yield (s1, s2, s3, s4)
            }(using i1, i2, t1, t2, t3, t4, fr)

        /** Broadcast to five streams that can be evaluated in parallel.
          */
        def broadcast5(bufferSize: Int = defaultAsyncStreamBufferSize)(
            using
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): (
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async],
            Stream[V, Abort[E] & Async]
        ) < (Resource & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                for
                    s1 <- streamHub.subscribe
                    s2 <- streamHub.subscribe
                    s3 <- streamHub.subscribe
                    s4 <- streamHub.subscribe
                    s5 <- streamHub.subscribe
                yield (s1, s2, s3, s4, s5)
            }(using i1, i2, t1, t2, t3, t4, fr)

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
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): Chunk[Stream[V, Abort[E] & Resource & Async]] < (Resource & Async & S) =
            broadcastDynamicWith(bufferSize) { streamHub =>
                val builder = Chunk.newBuilder[Stream[V, Abort[E] & Resource & Async]]
                Loop(numStreams): remaining =>
                    if remaining <= 0 then
                        IO(builder.result()).map(chunk => Loop.done(chunk))
                    else
                        streamHub.subscribe.map: stream =>
                            IO(builder.addOne(stream)).andThen(Loop.continue(remaining - 1))
            }(using i1, i2, t1, t2, t3, t4, fr)

        /** Convert to a reusable stream that can be run multiple times in parallel to consume the same original elements.
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
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): Stream[V, Abort[E] & Async & Resource] < (Resource & Async & S) =
            broadcastDynamic(bufferSize).map: streamHub =>
                Stream:
                    streamHub.subscribe.map(_.emit)

        /** Construct a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel.
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
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): StreamHub[V, E] < (Resource & Async & S) =
            Latch.initWith(1): latch =>
                StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                    streamHub.consume(stream).andThen:
                        streamHub
        end broadcastDynamic

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter [[fn]].
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
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): A < (Resource & Async & S & S1) =
            StreamHubImpl.init[V, E](bufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

        /** Use a [[StreamHub]] to broadcast copies of the original streams that may be handled in parallel. The original stream will not
          * begin broadcasting to any subscribed streams prior to the completion of the effect produced by parameter [[fn]].
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
            i1: Isolate.Contextual[S, IO],
            i2: Isolate.Stateful[S, Async],
            t1: Tag[V],
            t2: Tag[Emit[Chunk[V]]],
            t3: Tag[Emit[Chunk[Chunk[V]]]],
            t4: SafeClassTag[E],
            fr: Frame
        ): A < (Resource & Async & S & S1) =
            StreamHubImpl.init[V, E](defaultAsyncStreamBufferSize).map: streamHub =>
                fn(streamHub).map: a =>
                    streamHub.consume(stream).andThen(a)

    end extension
end StreamCoreExtensions

export StreamCoreExtensions.*
