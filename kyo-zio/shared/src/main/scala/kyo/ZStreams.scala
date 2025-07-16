package kyo

import ZIOs.toExit
import kyo.Result.*
import scala.reflect.ClassTag
import zio.Cause
import zio.Exit
import zio.FiberId
import zio.Runtime
import zio.Scope as ZScope
import zio.StackTrace
import zio.Trace
import zio.Unsafe
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

end ZStreams
