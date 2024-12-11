package kyo.interop

import kyo.*
import kyo.interop.reactivestreams.*
import kyo.interop.reactivestreams.StreamSubscriber.EmitStrategy
import kyo.kernel.Boundary
import org.reactivestreams.*
import org.reactivestreams.FlowAdapters

package object reactivestreams:
    inline def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int,
        emitStrategy: EmitStrategy = EmitStrategy.Eager
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Stream[T, Async] < IO =
        for
            subscriber <- StreamSubscriber[T](bufferSize, emitStrategy)
            _          <- IO(publisher.subscribe(subscriber))
        yield subscriber.stream

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

    object StreamReactiveStreamsExtensions:
        extension [T, Ctx](stream: Stream[T, Ctx])
            inline def subscribe(
                subscriber: Subscriber[? >: T]
            )(
                using
                Frame,
                Tag[Emit[Chunk[T]]],
                Tag[Poll[Chunk[T]]]
            ): Subscription < (Resource & IO & Ctx) =
                subscribeToStream(stream, subscriber)

            inline def toPublisher(
                using
                Frame,
                Tag[Emit[Chunk[T]]],
                Tag[Poll[Chunk[T]]]
            ): Publisher[T] < (Resource & IO & Ctx) =
                streamToPublisher(stream)
        end extension
    end StreamReactiveStreamsExtensions

    export StreamReactiveStreamsExtensions.*
end reactivestreams
