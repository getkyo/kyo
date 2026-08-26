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
        // One producer fiber owns the whole consumption, so every resource the stream acquires
        // lives and dies inside a single evaluation's extent; the ZIO scope owns the fiber and
        // interrupts it when the ZStream ends, which runs the stream's own cleanup. A peel-per-step
        // design instead hands the remainder across evaluations, and a resource inside the stream
        // does not survive the peeling evaluation's exit.
        ZStream.unwrapScoped {
            ZIO.acquireRelease(
                ZIOs.run {
                    Channel.initUnscoped[Chunk[A]](4).map { channel =>
                        val produce =
                            // the consumer going away closes the channel: a put failing with Closed is
                            // that signal, not a stream failure. The producer's own extent owns the
                            // handoff: however the loop ends (completion, failure, interrupt), the
                            // scope waits for the consumer to drain what is buffered before closing,
                            // so no delivered tail is discarded, and the release's hard close unblocks
                            // that wait when the consumer leaves early
                            Scope.run {
                                Scope.ensure(channel.closeAwaitEmpty.unit).andThen {
                                    Abort.run[Closed](stream.foreachChunk(channel.put)).unit
                                }
                            }
                        Fiber.initUnscoped(produce).map(fiber => (channel, fiber))
                    }
                }
            ) { (channel, fiber) =>
                ZIOs.run(fiber.interrupt.andThen(channel.close.unit))
            }.map { (channel, fiber) =>
                ZStream.repeatZIOChunkOption {
                    ZIOs.run(Abort.run[Closed](channel.take)).flatMap {
                        case Result.Success(chunk) => ZIO.succeed(ZChunk.fromArray(chunk.toArray))
                        case _                     =>
                            // closed: the producer finished, and its result says how
                            ZIOs.run(fiber.getResult).flatMap {
                                case Result.Success(_) => ZIO.fail(None)
                                case Result.Failure(e) => ZIO.fail(Some(e))
                                case p: Result.Panic   => ZIO.die(p.exception)
                            }
                    }
                }
            }
        }
    end run

end ZStreams
