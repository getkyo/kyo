package kyo.interop

import kyo.*
import kyo.interop.reactivestreams.*
import kyo.kernel.Boundary
import org.reactivestreams.*
import org.reactivestreams.FlowAdapters

package object reactivestreams:
    def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int
    )(
        using
        Frame,
        Tag[T]
    ): Stream[T, Async] < IO =
        for
            subscriber <- StreamSubscriber[T](bufferSize)
            _          <- IO(publisher.subscribe(subscriber))
        yield subscriber.stream

    def subscribeToStream[T, Ctx](
        stream: Stream[T, Ctx],
        subscriber: Subscriber[? >: T]
    )(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame,
        tag: Tag[T]
    ): Subscription < (Resource & IO & Ctx) =
        StreamSubscription.subscribe(stream, subscriber)(using boundary, frame, tag)
end reactivestreams
