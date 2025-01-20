package kyo.interop

import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import org.reactivestreams.*
import org.reactivestreams.FlowAdapters
import scala.annotation.nowarn

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
        flow.fromPublisher(FlowAdapters.toFlowPublisher(publisher), bufferSize, emitStrategy)

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
        flow.subscribeToStream(stream, FlowAdapters.toFlowSubscriber(subscriber)).map { subscription =>
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
    ): Publisher[T] < (Resource & IO & Ctx) = flow.streamToPublisher(stream).map { publisher =>
        FlowAdapters.toPublisher(publisher)
    }
end reactivestreams
