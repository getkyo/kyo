package kyo

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.*

object StreamReactiveStreamsExtensions:
    extension [T, S](stream: Stream[T, S & Sync])(using Isolate[S, Sync, Any])
        def subscribe(
            subscriber: Subscriber[? >: T]
        )(
            using
            Frame,
            Tag[Emit[Chunk[T]]],
            Tag[Poll[Chunk[T]]]
        ): Subscription < (Scope & Sync & S) =
            subscribeToStream(stream, subscriber)

        def toPublisher(
            using
            Frame,
            Tag[Emit[Chunk[T]]],
            Tag[Poll[Chunk[T]]]
        ): Publisher[T] < (Scope & Sync & S) =
            streamToPublisher(stream)
    end extension
end StreamReactiveStreamsExtensions

export StreamReactiveStreamsExtensions.*
