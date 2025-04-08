package kyo

import kyo.kernel.ArrowEffect

object AsyncStreamExtensions:
    val DefaultCollectBufferSize = 1024

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
                Channel.init[Chunk[V]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(Abort.run(
                                Async
                                    .foreachDiscard(streams)(
                                        _.foreachChunk(c => if c.nonEmpty then channel.put(c) else Kyo.unit)
                                    )
                            )
                                .andThen(channel.put(Chunk.empty)))
                            _ <- channel
                                .streamUntilClosed(1)
                                .takeWhile(_.nonEmpty)
                                .mapChunk(_.flattenChunk)
                                .emit
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
                Channel.init[Chunk[V]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(Abort.run(
                                Async
                                    .foreachDiscard(streams)(
                                        _.foreachChunk(c => if c.nonEmpty then channel.put(c) else Kyo.unit)
                                            .andThen(channel.put(Chunk.empty))
                                    )
                            ))
                            _ <- channel
                                .streamUntilClosed(1)
                                .takeWhile(_.nonEmpty)
                                .mapChunk(_.flattenChunk)
                                .emit
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
                Channel.init[Chunk[V]](bufferSize, Access.MultiProducerMultiConsumer).map: channel =>
                    IO.ensure(channel.close):
                        for
                            _ <- Async.run(
                                Async.gather(
                                    stream.foreachChunk(c => if c.nonEmpty then channel.put(c) else Kyo.unit)
                                        .andThen(channel.put(Chunk.empty)),
                                    other.foreachChunk(c => if c.nonEmpty then channel.put(c) else Kyo.unit)
                                ).andThen(channel.put(Chunk.empty))
                            )
                            _ <- channel
                                .streamUntilClosed(1)
                                .takeWhile(_.nonEmpty)
                                .mapChunk(_.flattenChunk)
                                .emit
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
