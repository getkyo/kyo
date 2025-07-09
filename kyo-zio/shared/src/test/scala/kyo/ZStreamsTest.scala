package kyo

import kyo.*
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import zio.Cause
import zio.Runtime
import zio.Task
import zio.Unsafe
import zio.ZIO
import zio.stream.ZStream

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
        "infinite" in runKyo {
            val zioStream = ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.take(1024).run.map(v => assert(v == Chunk.range(0, 1024)))
        }
        "stack safety" in runKyo {
            val zioStream = ZStream.repeatZIO(ZIO.succeed(0))
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.take(10_000).discard.andThen(succeed)
        }
        "failing" in runKyo {
            val zioStream = ZStream.fromIterable(List.tabulate(5)(identity)) ++
                ZStream.fail(Error) ++
                ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            Abort.runWith(kyoStream.run) { result =>
                assert(result == Result.fail(Error))
            }
        }
        "parallel + async" in runKyo {
            val zioStream =
                ZStream
                    .fromIterable(List.tabulate(20)(identity))
                    .mapZIOParUnordered(4) { v =>
                        zio.Random.nextIntBounded(20)
                            .flatMap(t => ZIO.sleep(t.millis.toJava)) *> ZIO.succeed(v)
                    }
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.run.map { v =>
                assert(v.sorted == Chunk.range(0, 20))
            }
        }
    }

end ZStreamsTest
