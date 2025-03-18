package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import scala.annotation.nowarn

package object flow:
    def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int,
        emitStrategy: EmitStrategy = EmitStrategy.Eager
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Stream[T, Async] < (Resource & IO) =
        for
            subscriber <- StreamSubscriber[T](bufferSize, emitStrategy)
            _          <- IO(publisher.subscribe(subscriber))
            stream     <- subscriber.stream
        yield stream

    @nowarn("msg=anonymous")
    def subscribeToStream[T, S](
        using Isolate.Contextual[S, IO]
    )(
        stream: Stream[T, S & IO],
        subscriber: Subscriber[? >: T]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Subscription < (Resource & IO & S) =
        StreamSubscription.subscribe(stream, subscriber)

    def streamToPublisher[T, S](
        using Isolate.Contextual[S, IO]
    )(
        stream: Stream[T, S & IO]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Publisher[T] < (Resource & IO & S) = StreamPublisher[T, S](stream)

end flow
