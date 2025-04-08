package kyo

import kyo.kernel.ArrowEffect

object AsyncStreamExtensions:
    val DefaultCollectBufferSize = 1024

    private def emitMaybeChunksFromChannel[V](channel: Channel[Maybe[Chunk[V]]])(using Tag[V], Frame) =
        val emit = Loop(()): _ =>
            channel.take.map:
                case Absent => Loop.done
                case Present(c) =>
                    Emit.valueWith(c)(Loop.continue(()))
        Abort.run(emit).unit
    end emitMaybeChunksFromChannel

    extension (streamObj: Stream.type)
        /** Merges multiple streams asynchronously. Stream stops when all sources streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @note
          *   Resulting stream does not preserve chunking from sources
          * @param streams
          *   Sequence of streams to be merged
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collectAll[V, S](streams: Seq[Stream[V, S]], bufferSize: Int = DefaultCollectBufferSize)(using
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.init[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(Abort.run(
                                Async
                                    .foreachDiscard(streams)(
                                        _.foreachChunk(c => channel.put(Present(c)))
                                    )
                            )
                                .andThen(channel.put(Absent)))
                            _ <- emitMaybeChunksFromChannel(channel)
                        yield ()

        /** Merges multiple streams asynchronously. Stream stops as soon as any of the source streams complete.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @note
          *   Resulting stream does not preserve chunking from sources
          * @param streams
          *   Sequence of streams to be merged
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collectAllHalting[V, S](streams: Seq[Stream[V, S]], bufferSize: Int = DefaultCollectBufferSize)(using
            Frame
        ): Stream[V, S & Async] =
            Stream:
                Channel.init[Maybe[Chunk[V]]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(Abort.run(
                                Async
                                    .foreachDiscard(streams)(
                                        _.foreachChunk(c => channel.put(Present(c)))
                                            .andThen(channel.put(Absent))
                                    )
                            ))
                            _ <- emitMaybeChunksFromChannel(channel)
                        yield ()
    end extension

    extension [V, S](stream: Stream[V, S])
        /** Merges with another stream. Stream stops when both streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @note
          *   Resulting stream does not preserve chunking from sources
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collect[S2](other: Stream[V, S2], bufferSize: Int = DefaultCollectBufferSize): Stream[V, S & S2 & Async] =
            Stream.collectAll(Seq(stream, other))

        /** Merges with another stream. Stream stops as soon as either has have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collectHalting[S2](other: Stream[V, S2], bufferSize: Int = DefaultCollectBufferSize): Stream[V, S & S2 & Async] =
            Stream.collectAllHalting(Seq(stream, other))

        /** Merges with another stream. Stream stops when original stream has completed or when both streams have completed.
          *
          * @note
          *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
          * @note
          *   Resulting stream does not preserve chunking from sources
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collectHaltingLeft[S2](other: Stream[V, S2], bufferSize: Int = DefaultCollectBufferSize): Stream[V, S & S2 & Async] =
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
          * @note
          *   Resulting stream does not preserve chunking from sources
          * @param other
          *   Stream to merge with
          * @param bufferSize
          *   Size of the buffer that source streams will write to and outputs stream will read from
          */
        inline def collectHaltingRight[S2](other: Stream[V, S2], bufferSize: Int = DefaultCollectBufferSize): Stream[V, S & S2 & Async] =
            other.collectHaltingLeft(stream)
    end extension
end AsyncStreamExtensions

export AsyncStreamExtensions.*
