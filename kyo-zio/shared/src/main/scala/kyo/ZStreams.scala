package kyo

import ZIOs.toExit
import kyo.Result.*
import scala.reflect.ClassTag
import zio.Cause
import zio.Exit
import zio.FiberId
import zio.Runtime
import zio.Scope
import zio.StackTrace
import zio.Trace
import zio.Unsafe
import zio.ZEnvironment
import zio.ZIO
import zio.stream.ZChannel
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
        frame: Frame,
        trace: zio.Trace,
        tag: kyo.Tag[kyo.Emit[kyo.Chunk.Indexed[A]]],
        classTag: ClassTag[A]
    ): Stream[A, Abort[E] & Async] =
        Stream(Sync {
            zio.Unsafe.unsafely { unsafe ?=>
                val scope = Unsafe.unsafely(Scope.unsafe.make)
                Resource.run {
                    Resource.ensure(ex => ZIOs.get(scope.close(ex.fold(Exit.unit)(_.toExit)))).andThen:
                        ZIOs.get(stream.channel.toPullIn(scope)).map: pullIn =>
                            Loop.foreach:
                                ZIOs.get(pullIn).map {
                                    case Right(zioChunk) => Emit.valueWith(Chunk.from(zioChunk.toArray))(Loop.continue)
                                    case _               => Loop.done
                                }
                }
            }
        })
    end get

    /** Interprets a Kyo Stream to ZStream. Note that this method only accepts Abort[E] and Async pending effects. Plase handle any other
      * effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A ZStream that, when run, will drain the Kyo Stream
      */
    def run[A, E](stream: => Stream[A, Abort[E] & Async])(using
        frame: Frame,
        trace: zio.Trace,
        tag: kyo.Tag[kyo.Emit[kyo.Chunk[A]]],
        classTag: ClassTag[A]
    ): ZStream[Any, E, A] =

        def loop(emit: Unit < (Emit[Chunk[A]] & Abort[E] & Async)): ZChannel[Any, Any, Any, Any, E, zio.Chunk[A], Any] =
            ZChannel
                .fromZIO(ZIOs.run(Emit.runFirst(emit)))
                .flatMap: (chunkMaybe, cont) =>
                    chunkMaybe match
                        case Present(chunk) =>
                            ZChannel.write(zio.Chunk.fromArray(chunk.toArray)) *>
                                ZChannel.suspend(loop(cont()))
                        case Absent => ZChannel.unit
        end loop

        val channel = ZChannel.suspend(loop(stream.emit))
        ZStream.fromChannel(channel)
    end run

end ZStreams
