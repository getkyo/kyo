package kyo2

export AsyncStream.*
import kyo.Tag
import kyo2.Emit.Ack
import kyo2.Emit.Ack.*
import kyo2.kernel.Boundary

object AsyncStream:

    extension [S, V](stream: Stream[S, V])
        def buffer(size: Int)(
            using
            boundary: Boundary[S, Async],
            tag: Tag[Emit[Chunk[V]]],
            frame: Frame
        ): Stream[Async & S, V] =
            Stream[Async & S, V] {
                for
                    (send, receive) <- Exchange.init[Chunk[V], Ack](size)
                    _               <- Async.run(stream.runAck(send(_)).andThen(send.close))
                yield Loop.transform(()) { _ =>
                    receive(Emit(_)).map {
                        case Stop => receive.close.map(_ => Loop.done(Stop))
                        case _    => Loop.continue
                    }
                }.pipe(Abort.run[Closed](_))
                    .map(_.getOrElse(Stop))
                end for
            }

    extension (s: Stream.type)
        def init[V, S](chunks: Channel[Chunk[V]])(using tag: Tag[Emit[Chunk[V]]]): Stream[S & Async, V] =
            Stream[S & Async, V] {
                Loop.transform(Chunk.empty[V]) { chunk =>
                    Emit.andMap(chunk) {
                        case Stop        => Loop.done(Stop)
                        case Continue(_) => chunks.take.map(Loop.continue)
                    }
                }.pipe(Abort.run[Closed](_))
                    .map(_.getOrElse(Stop))
            }
end AsyncStream
