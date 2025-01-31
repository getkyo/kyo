package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import scala.annotation.nowarn

package object flow:
    inline def fromPublisher[T](
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
    inline def subscribeToStream[T, Ctx](
        stream: Stream[T, Ctx],
        subscriber: Subscriber[? >: T]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Subscription < (Resource & IO & Ctx) =
        StreamSubscription.subscribe(stream, subscriber)

    inline def streamToPublisher[T, Ctx](
        stream: Stream[T, Ctx]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Publisher[T] < (Resource & IO & Ctx) = StreamPublisher[T, Ctx](stream)

end flow
