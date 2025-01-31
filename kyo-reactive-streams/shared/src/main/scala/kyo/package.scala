package kyo

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.*

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
