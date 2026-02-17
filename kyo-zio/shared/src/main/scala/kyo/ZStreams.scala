package kyo

import ZIOs.toExit
import kyo.Result.*
import scala.reflect.ClassTag
import zio.Cause
import zio.Chunk as ZChunk
import zio.Exit
import zio.FiberId
import zio.Runtime
import zio.Scope as ZScope
import zio.StackTrace
import zio.Trace
import zio.Unsafe
import zio.ZIO
import zio.stream.ZStream

object ZStreams:

    /** Lifts a zio.stream.ZStream into a Kyo's Stream.
      *
      * @param stream
      *   The zio.stream.ZStream to lift
      * @return
      *   A Kyo's Stream that, when run, will execute the zio.stream.ZStream
      */
    def get[E, A](stream: => ZStream[Any, E, A])(using
        Frame,
        Trace,
        Tag[Emit[Chunk[A]]]
    ): Stream[A, Abort[E] & Async] =
        Stream:
            Sync.defer {
                val scope = Unsafe.unsafely(ZScope.unsafe.make)
                Scope.run {
                    Scope.ensure(ex => ZIOs.get(scope.close(ex.fold(Exit.unit)(_.toExit)))).andThen {
                        ZIOs.get(stream.channel.toPullIn(scope)).map: pullIn =>
                            Loop.foreach:
                                ZIOs.get(pullIn).map {
                                    case Right(zioChunk) => Emit.valueWith(Chunk.from(zioChunk))(Loop.continue)
                                    case _               => Loop.done
                                }
                    }
                }
            }
    end get

    /** Interprets a Kyo's to ZIO's ZStream.
      * @param stream
      *   The Kyo stream
      * @return
      *   A zio.ZStream that, when consume, will consume the input stream
      */
    def run[E, A](stream: => Stream[A, Abort[E] & Async])(using
        Frame,
        Trace,
        Tag[Emit[Chunk[A]]],
        ClassTag[A]
    ): ZStream[Any, E, A] =
        type EmitType = Unit < (Emit[Chunk[A]] & Abort[E] & Async)

        def peel(emit: EmitType): ZIO[Any, E, Option[(ZChunk[A], EmitType)]] =
            ZIO.uninterruptibleMask: restore =>
                restore(ZIOs.run(Emit.runFirst(emit))).map: (maybeChunk, contFn) =>
                    maybeChunk
                        .map: chunk =>
                            ZChunk.fromArray(chunk.toArray) -> contFn()
                        .toOption

        ZStream.unfoldChunkZIO(stream.emit)(peel)
    end run

end ZStreams
