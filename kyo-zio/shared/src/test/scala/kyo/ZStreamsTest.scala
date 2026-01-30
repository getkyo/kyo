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
        "finite stream" in runKyo {
            val zioStream = ZStream.fromIterable(List(1, 2, 3, 4, 5))
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.run.map { chunk =>
                assert(chunk == Chunk(1, 2, 3, 4, 5))
            }
        }
        "empty stream" in runKyo {
            val zioStream = ZStream.empty
            val kyoStream = ZStreams.get[Nothing, Int](zioStream)
            kyoStream.run.map { chunk =>
                assert(chunk.isEmpty)
            }
        }
        "infinite stream with take" in runKyo {
            val zioStream = ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.take(1024).run.map(v => assert(v == Chunk.range(0, 1024)))
        }
        "stack safety" in runKyo {
            val zioStream = ZStream.repeatZIO(ZIO.succeed(0))
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.take(10_000).discard.andThen(succeed)
        }
        "failing stream" in runKyo {
            val zioStream = ZStream.fromIterable(List.tabulate(5)(identity)) ++
                ZStream.fail(Error) ++
                ZStream.iterate(0)(_ + 1)
            val kyoStream = ZStreams.get(zioStream)
            Abort.runWith(kyoStream.run) { result =>
                assert(result == Result.fail(Error))
            }
        }
        "stream with async effects" in runKyo {
            val zioStream = ZStream.fromIterable(List(1, 2, 3, 4, 5)).mapZIO { v =>
                ZIO.sleep(1.milli.toJava) *> ZIO.succeed(v * 2)
            }
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.run.map { chunk =>
                assert(chunk == Chunk(2, 4, 6, 8, 10))
            }
        }
        "parallel processing" in runKyo {
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
        "interruption propagates to zio stream" in runKyo {
            import java.util.concurrent.atomic.AtomicBoolean

            // State flag that should be set when ZIO stream is interrupted/completed
            val streamFinalized = new AtomicBoolean(false)

            val zioStream = ZStream.unfoldZIO(0) { n =>
                ZIO.sleep(5.millis.toJava) *> ZIO.succeed(Some((n, n + 1)))
            }.ensuring(ZIO.succeed(streamFinalized.set(true)))

            val kyoStream = ZStreams.get(zioStream)

            // Verify initial state is false
            assert(!streamFinalized.get())

            Scope.run {
                Fiber.init(kyoStream.take(5).run).map { fiber =>
                    Async.sleep(15.millis).andThen {
                        Abort.run[Interrupted](fiber.interrupt).map { _ =>
                            Async.sleep(50.millis).andThen {
                                // Verify interruption was received
                                assert(streamFinalized.get())
                            }
                        }
                    }
                }
            }
        }
        "concurrent stream consumption" in runKyo {
            val zioStream = ZStream.fromIterable(List.range(0, 100))
            val kyoStream = ZStreams.get(zioStream)

            Async.zip(
                kyoStream.run,
                kyoStream.run,
                kyoStream.run
            ).map { case (r1, r2, r3) =>
                // Each should get the full stream
                assert(r1 == Chunk.from(List.range(0, 100)))
                assert(r2 == Chunk.from(List.range(0, 100)))
                assert(r3 == Chunk.from(List.range(0, 100)))
            }
        }
        "concurrent kyo streams racing on shared zio stream with mutable state" in runKyo {
            import java.util.concurrent.atomic.AtomicInteger

            // ZIO stream with internal mutable counter that multiple Kyo streams will race on
            val counter = new AtomicInteger(0)
            val zioStream = ZStream.unfoldChunkZIO(()) { _ =>
                ZIO.succeed {
                    val value = counter.getAndIncrement()
                    if value < 100 then
                        Some((zio.Chunk.single(value), ()))
                    else
                        None
                    end if
                }
            }

            val sharedKyoStream = ZStreams.get(zioStream)

            // 4 Kyo streams racing to consume from the same ZIO stream
            Async.zip(
                sharedKyoStream.run,
                sharedKyoStream.run,
                sharedKyoStream.run,
                sharedKyoStream.run
            ).map { case (r1, r2, r3, r4) =>
                // Combine all results
                val allValues    = (r1.toSeq ++ r2.toSeq ++ r3.toSeq ++ r4.toSeq).toList
                val uniqueValues = allValues.distinct.sorted

                // Verify total data is maintained:
                // 1. All values from 0 to 99 should be present exactly once
                assert(uniqueValues == List.range(0, 100))
                // 2. No duplicates - each value consumed by exactly one stream
                assert(allValues.length == uniqueValues.length)
                // 3. Total count should be 100
                assert(allValues.length == 100)
            }
        }
    }

    ".run" - {
        "finite stream" in runZIO {
            val kyoStream = Stream.init(List(1, 2, 3, 4, 5))
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.map { chunk =>
                assert(chunk.toList == List(1, 2, 3, 4, 5))
            }
        }
        "empty stream" in runZIO {
            val kyoStream = Stream.empty[Int]
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.map { chunk =>
                assert(chunk.isEmpty)
            }
        }
        "infinite stream with take" in runZIO {
            val kyoStream = Stream.unfold(0)(n => Maybe((n, n + 1)))
            val zioStream = ZStreams.run(kyoStream)
            zioStream.take(1024).runCollect.map { chunk =>
                assert(chunk.toList == List.range(0, 1024))
            }
        }
        "stack safety" in runZIO {
            val kyoStream = Stream.init(List.fill(10_000)(1))
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCount.map { count =>
                assert(count == 10_000)
            }
        }
        "failing stream" in runZIO {
            val kyoStream: Stream[Int, Abort[RuntimeException] & Async] =
                Stream.init(List(1, 2, 3)).map(v => Abort.get(Right(v))).concat(
                    Stream(Abort.fail(Error).map(_ => Emit.value(Chunk.empty[Int])))
                )
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.either.map { result =>
                assert(result == Left(Error))
            }
        }
        "stream with async effects" in runZIO {
            val kyoStream = Stream.init(List(1, 2, 3, 4, 5)).map { v =>
                Async.sleep(1.milli).andThen(v * 2)
            }
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.map { chunk =>
                assert(chunk.toList == List(2, 4, 6, 8, 10))
            }
        }
        "round trip: get then run" in runZIO {
            val original  = ZStream.fromIterable(List(1, 2, 3, 4, 5))
            val kyoStream = ZStreams.get(original)
            val zioStream = ZStreams.run(kyoStream)
            zioStream.runCollect.map { chunk =>
                assert(chunk.toList == List(1, 2, 3, 4, 5))
            }
        }
        "round trip: run then get" in runKyo {
            val original  = Stream.init(List(1, 2, 3, 4, 5))
            val zioStream = ZStreams.run(original)
            val kyoStream = ZStreams.get(zioStream)
            kyoStream.run.map { chunk =>
                assert(chunk == Chunk(1, 2, 3, 4, 5))
            }
        }
        "parallel processing" in runZIO {
            val kyoStream = Stream.init(List.range(0, 20)).map { v =>
                Async.sleep(1.milli).andThen(v)
            }
            val zioStream = ZStreams.run(kyoStream)
            zioStream.mapZIOParUnordered(4) { v =>
                zio.Random.nextIntBounded(10)
                    .flatMap(t => ZIO.sleep(t.millis.toJava)) *> ZIO.succeed(v * 2)
            }.runCollect.map { chunk =>
                assert(chunk.toList.sorted == List.range(0, 20).map(_ * 2))
            }
        }
        "interruption propagates to kyo stream" in runZIO {
            import java.util.concurrent.atomic.AtomicBoolean

            // State flag that should be set when Kyo stream is interrupted/completed
            val streamFinalized = new AtomicBoolean(false)

            val kyoStream: Stream[Int, Abort[Nothing] & Async] = Stream {
                Scope.run {
                    Scope.ensure {
                        streamFinalized.set(true)
                    }.andThen {
                        Stream.unfold(0) { n =>
                            Async.sleep(5.millis).andThen(Maybe((n, n + 1)))
                        }.emit
                    }
                }
            }

            val zioStream = ZStreams.run(kyoStream)

            for
                fiber <- zioStream.take(5).runCollect.fork
                // Verify initial state is false
                _ = assert(!streamFinalized.get())
                _      <- ZIO.sleep(15.millis.toJava)
                _      <- fiber.interrupt
                result <- fiber.await
                _      <- ZIO.sleep(50.millis.toJava) // Give time for cleanup to propagate
            yield
                // Verify ZIO interruption was received
                assert(result.isInterrupted)
                // Verify Kyo stream received the interruption signal and finalized
                assert(streamFinalized.get())
            end for
        }
        "concurrent stream consumption" in runZIO {
            val kyoStream = Stream.init(List.range(0, 100))
            val zioStream = ZStreams.run(kyoStream)
            for
                fiber1 <- zioStream.runCollect.fork
                fiber2 <- zioStream.runCollect.fork
                fiber3 <- zioStream.runCollect.fork
                r1     <- fiber1.join
                r2     <- fiber2.join
                r3     <- fiber3.join
            yield
                // Each fiber should get the full stream
                assert(r1.toList == List.range(0, 100))
                assert(r2.toList == List.range(0, 100))
                assert(r3.toList == List.range(0, 100))
            end for
        }
        "concurrent zio streams racing on shared kyo stream with mutable state" in runZIO {
            import java.util.concurrent.atomic.AtomicInteger
            import scala.collection.concurrent.TrieMap

            // Kyo stream with internal mutable counter that multiple ZIO streams will race on
            val counter = new AtomicInteger(0)
            val kyoStream = Stream.unfold((), chunkSize = 1) { _ =>
                val value = counter.getAndIncrement()
                if value < 100 then
                    // Small delay to encourage interleaving
                    Async.sleep(1.milli).andThen(Maybe((value, ())))
                else
                    Maybe.empty
                end if
            }

            val sharedZioStream = ZStreams.run(kyoStream)

            // 4 ZIO streams racing to consume from the same Kyo stream
            for
                fiber1 <- sharedZioStream.runCollect.fork
                fiber2 <- sharedZioStream.runCollect.fork
                fiber3 <- sharedZioStream.runCollect.fork
                fiber4 <- sharedZioStream.runCollect.fork
                r1     <- fiber1.join
                r2     <- fiber2.join
                r3     <- fiber3.join
                r4     <- fiber4.join
            yield
                // Combine all results
                val allValues    = (r1 ++ r2 ++ r3 ++ r4).toList
                val uniqueValues = allValues.distinct.sorted

                // Verify total data is maintained:
                // 1. All values from 0 to 99 should be present exactly once
                assert(uniqueValues == List.range(0, 100))
                // 2. No duplicates - each value consumed by exactly one stream
                assert(allValues.length == uniqueValues.length)
                // 3. Total count should be 100
                assert(allValues.length == 100)
            end for
        }
    }

end ZStreamsTest
