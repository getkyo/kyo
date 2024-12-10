package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.reactivestreams
import kyo.interop.reactivestreams.StreamSubscriber.EmitStrategy
import kyo.kernel.Boundary
import org.reactivestreams.FlowAdapters
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
    ): Stream[T, Async] < IO = reactivestreams.fromPublisher(FlowAdapters.toPublisher(publisher), bufferSize, emitStrategy)

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
        reactivestreams.subscribeToStream(stream, FlowAdapters.toSubscriber(subscriber)).map { subscription =>
            new Subscription:
                override def request(n: Long): Unit = subscription.request(n)
                override def cancel(): Unit         = subscription.cancel()
        }

    inline def streamToPublisher[T, Ctx](
        stream: Stream[T, Ctx]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Publisher[T] < (Resource & IO & Ctx) = reactivestreams.streamToPublisher(stream).map { publisher =>
        FlowAdapters.toFlowPublisher(publisher)
    }

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
end flow
