package kyo

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.*

object StreamReactiveStreamsExtensions:
    extension [T, S](stream: Stream[T, S & IO])(using Isolate.Contextual[S, IO])
        def subscribe(
            subscriber: Subscriber[? >: T]
        )(
            using
            Frame,
            Tag[Emit[Chunk[T]]],
            Tag[Poll[Chunk[T]]]
        ): Subscription < (Resource & IO & S) =
            subscribeToStream(stream, subscriber)

        def toPublisher(
            using
            Frame,
            Tag[Emit[Chunk[T]]],
            Tag[Poll[Chunk[T]]]
        ): Publisher[T] < (Resource & IO & S) =
            streamToPublisher(stream)
    end extension
end StreamReactiveStreamsExtensions

export StreamReactiveStreamsExtensions.*
