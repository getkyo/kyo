package kyo

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
    inline def mergeAll[V, S](streams: Seq[Stream[V, S]], bufferSize: Int = 1024)(using Frame): Stream[V, S & Async] =
        Stream:
            Resource.run:
                for
                    channel <- Resource.acquireRelease(Channel.init[Option[V]](bufferSize, Access.MultiProducerMultiConsumer))(_.close)
                    _ <- Async.run(Abort.run(
                        Async
                            .foreachDiscard(streams)(
                                _.foreachChunk(v => channel.putBatch(v.map(Some(_))))
                            )
                            .andThen(channel.put(None))
                    ))
                    _ <- channel
                        .streamUntilClosed()
                        .collectWhile(Maybe.fromOption)
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
    inline def mergeAllHalting[V, S](streams: Seq[Stream[V, S]], bufferSize: Int = 1024)(using Frame): Stream[V, S & Async] =
        Stream:
            Resource.run:
                for
                    channel <- Resource.acquireRelease(Channel.init[Option[V]](bufferSize, Access.MultiProducerMultiConsumer))(_.close)
                    _ <- Async.run(Abort.run(
                        Async
                            .foreachDiscard(streams)(
                                _.foreachChunk(v => channel.putBatch(v.map(Some(_))))
                                    .andThen(channel.put(None))
                            )
                    ))
                    _ <- channel
                        .streamUntilClosed()
                        .collectWhile(Maybe.fromOption)
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
    inline def merge[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        Stream.mergeAll(Seq(stream, other))

    /** Merges with another stream. Stream stops as soon as either has have completed.
      *
      * @note
      *   Merges chunks and not individual elements. Rechunk source streams to size 1 to interleave individual elements.
      * @param other
      *   Stream to merge with
      * @param bufferSize
      *   Size of the buffer that source streams will write to and outputs stream will read from
      */
    inline def mergeHalting[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        Stream.mergeAllHalting(Seq(stream, other))

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
    inline def mergeHaltingLeft[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        Stream:
            Resource.run:
                for
                    channel <- Resource.acquireRelease(Channel.init[Option[V]](bufferSize, Access.MultiProducerMultiConsumer))(_.close)
                    _ <- Async.run(
                        Async.gather(
                            stream.foreachChunk(v => channel.putBatch(v.map(Some(_))))
                                .andThen(channel.put(None)),
                            other.foreachChunk(v => channel.putBatch(v.map(Some(_))))
                        ).andThen(channel.put(None))
                    )
                    _ <- channel
                        .streamUntilClosed()
                        .collectWhile(Maybe.fromOption)
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
    inline def mergeHaltingRight[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        other.mergeHaltingLeft(stream)
end extension
