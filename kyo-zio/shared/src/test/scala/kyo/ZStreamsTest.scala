package kyo

import kyo.*
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import zio.Cause
import zio.Runtime
import zio.Task
import zio.Unsafe
import zio.ZIO

class ZStreamsTest extends Test:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def runKyo(v: => Assertion < (Abort[Throwable] & Async)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    case object Error extends RuntimeException("error")

    ".get" - {
        "convert correctly" in runKyo {
            val zioStream = zio.stream.ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.take(10).run.map(v => assert(v == Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
        }
        "fail correctly" in runKyo {
            val zioStream =
                zio.stream.ZStream.fromIterable(List.tabulate(5)(identity)) ++
                    zio.stream.ZStream.fail(Error) ++
                    zio.stream.ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            Abort.run(kyoStream.run).map {
                case Result.Failure(Error) => assert(true)
                case _                     => assert(false)
            }
        }
        "convert correctly with parallel zio's stream" in runKyo {
            val zioStream = zio.stream.ZStream
                .fromIterable(List.tabulate(20)(identity))
                .mapZIOParUnordered(4) { v =>
                    zio.Random.nextIntBounded(20)
                        .flatMap(t => zio.ZIO.sleep(zio.Duration.fromMillis(t))) *> ZIO.succeed(v)
                }
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.run.map { v =>
                assert(v.sorted == Chunk.from(Range(0, 20)))
            }
        }
    }

    ".run" - {
        "convert correctly" in runZIO {
            val kyoStream = Stream.unfold(0, 3)(prev => Maybe.Present(prev -> (prev + 1)))
            val zioStream = ZStreams.run(kyoStream)
            zioStream.take(10).runCollect.map(v => assert(v == zio.Chunk.from(Range(0, 10))))
        }
        "fail correctly" in runZIO {
            val kyoStream = Stream.init(1 to 10, 2)
                .concat(Stream.init(Abort.fail(Error)))
                .concat(Stream.unfold(0)(prev => Maybe.Present(prev -> (prev + 1))))
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runDrain.exit.map {
                case zio.Exit.Failure(cause) if cause.contains(zio.Cause.fail(Error)) => assert(true)
                case _                                                                => assert(false)
            }
        }
        "convert correctly with parallel kyo's stream" in runZIO {
            val kyoStream = Stream
                .init(1 to 20, 1)
                .mapParUnordered(4) { v =>
                    Random.nextInt(20).map: sleep =>
                        Async.sleep(sleep.millis).andThen(v)
                }

            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.map(v => assert(v.toSet == (1 to 20).toSet))
        }
        "kyo stream stops when zio stream is interrupted" in runZIO {
            var counter: Long = 0
            val kyoStream = Stream.unfold((), 1) { _ =>
                counter += 1
                Present(counter -> ())
            }
            val zioStream = ZStreams.run(kyoStream)
            for
                _          <- ZIO.sleep(zio.Duration.fromMillis(10)).raceFirst(zioStream.runDrain)
                oldCounter <- ZIO.succeed(counter)
                _          <- ZIO.sleep(zio.Duration.fromMillis(10))
                newCounter <- ZIO.succeed(counter)
            yield assert(oldCounter == newCounter)
            end for
        }
    }

end ZStreamsTest
