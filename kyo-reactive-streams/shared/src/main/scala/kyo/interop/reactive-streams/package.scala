package kyo.interop

import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import org.reactivestreams.*
import org.reactivestreams.FlowAdapters
import scala.annotation.nowarn

package object reactivestreams:
    def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int,
        emitStrategy: EmitStrategy = EmitStrategy.Eager
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Stream[T, Async] < (Resource & Sync) =
        flow.fromPublisher(FlowAdapters.toFlowPublisher(publisher), bufferSize, emitStrategy)

    @nowarn("msg=anonymous")
    def subscribeToStream[T, S](
        using Isolate.Contextual[S, Sync]
    )(
        stream: Stream[T, S & Sync],
        subscriber: Subscriber[? >: T]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Subscription < (Resource & Sync & S) =
        flow.subscribeToStream(stream, FlowAdapters.toFlowSubscriber(subscriber)).map { subscription =>
            new Subscription:
                override def request(n: Long): Unit = subscription.request(n)
                override def cancel(): Unit         = subscription.cancel()
        }

    def streamToPublisher[T, S](
        using Isolate.Contextual[S, Sync]
    )(
        stream: Stream[T, S & Sync]
    )(
        using
        Frame,
        Tag[Emit[Chunk[T]]],
        Tag[Poll[Chunk[T]]]
    ): Publisher[T] < (Resource & Sync & S) = flow.streamToPublisher(stream).map { publisher =>
        FlowAdapters.toPublisher(publisher)
    }
end reactivestreams
