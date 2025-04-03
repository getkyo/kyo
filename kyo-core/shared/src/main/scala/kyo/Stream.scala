package kyo

extension (streamObj: Stream.type)
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
    inline def merge[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        Stream.mergeAll(Seq(stream, other))

    inline def mergeHalting[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        Stream.mergeAllHalting(Seq(stream, other))

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

    inline def mergeHaltingRight[S2](other: Stream[V, S2], bufferSize: Int = 1024): Stream[V, S & S2 & Async] =
        other.mergeHaltingLeft(stream)
end extension
