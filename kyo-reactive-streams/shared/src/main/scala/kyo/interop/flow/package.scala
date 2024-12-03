package kyo.interop

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.reactivestreams
import kyo.kernel.Boundary
import org.reactivestreams.FlowAdapters

package object flow:
    inline def fromPublisher[T](
        publisher: Publisher[T],
        bufferSize: Int
    )(
        using
        Frame,
        Tag[T]
    ): Stream[T, Async] < IO = reactivestreams.fromPublisher(FlowAdapters.toPublisher(publisher), bufferSize)

    def subscribeToStream[T, Ctx](
        stream: Stream[T, Ctx],
        subscriber: Subscriber[? >: T]
    )(
        using
        Boundary[Ctx, IO],
        Frame,
        Tag[T]
    ): Subscription < (Resource & IO & Ctx) =
        reactivestreams.subscribeToStream(stream, FlowAdapters.toSubscriber(subscriber)).map { subscription =>
            new Subscription:
                override def request(n: Long): Unit = subscription.request(n)
                override def cancel(): Unit         = subscription.cancel()
        }
end flow
