package kyo

class HubTest extends Test:
    val repeats = 100

    "initWith" in run {
        Hub.initWith[Int](10) { h =>
            for
                l <- h.listen
                _ <- h.offer(1)
                v <- l.take
            yield assert(v == 1)
        }
    }

    "basic operations" - {

        "empty/full state" in run {
            for
                h  <- Hub.init[Int](2)
                _  <- h.listen(0)
                e1 <- h.empty
                _  <- h.put(1)
                _  <- h.put(2)
                _  <- h.put(3) // held by the fiber
                e2 <- h.empty
                f  <- h.full
                _  <- h.offer(4)
            yield assert(e1 && !e2 && f)
        }

        "offer returns false when full" in run {
            for
                h <- Hub.init[Int](2)
                _ <- h.listen(0)
                _ <- h.put(1)
                _ <- h.put(2)
                _ <- h.put(3) // held by the fiber
                r <- h.offer(4)
            yield assert(!r)
        }

        "backpressure when hub is full" in run {
            for
                h     <- Hub.init[Int](1)
                latch <- Latch.init(1)
                _     <- h.listen(0)
                _     <- h.put(1)
                _     <- h.put(2)
                fiber <- Async.run(h.put(3))
                _     <- Async.sleep(10.millis)
                done  <- fiber.done
                hFull <- h.full
            yield assert(!done && hFull)
        }
    }

    "listeners" - {
        "multiple listeners receive same messages" in run {
            for
                h  <- Hub.init[Int](4)
                l1 <- h.listen
                l2 <- h.listen
                _  <- h.put(1)
                v1 <- l1.take
                v2 <- l2.take
            yield assert(v1 == 1 && v2 == 1)
        }

        "filtered listeners" in run {
            for
                h  <- Hub.init[Int](4)
                l1 <- h.listen(_ % 2 == 0) // even numbers
                l2 <- h.listen(_ % 2 == 1) // odd numbers
                _  <- h.put(1)
                _  <- h.put(2)
                v1 <- l1.take
                v2 <- l2.take
            yield assert(v1 == 2 && v2 == 1)
        }

        "listener buffer size" in run {
            for
                h    <- Hub.init[Int](1)
                l    <- h.listen(2)
                _    <- h.put(1)
                _    <- h.put(2)
                _    <- h.put(3)
                size <- l.size
            yield assert(size == 2)
        }

        "late listeners don't receive past messages" in run {
            for
                h <- Hub.init[Int](4)
                _ <- h.put(1)
                _ <- Async.sleep(10.millis)
                l <- h.listen
                _ <- h.put(2)
                v <- l.take
            yield assert(v == 2)
        }
    }

    "closing" - {
        "close terminates all listeners" in run {
            for
                h  <- Hub.init[Int](4)
                l1 <- h.listen
                l2 <- h.listen
                _  <- h.close
                r1 <- Abort.run(l1.take)
                r2 <- Abort.run(l2.take)
                c1 <- l1.closed
                c2 <- l2.closed
            yield assert(r1.isFailure && r2.isFailure && c1 && c2)
        }

        "close returns buffered messages" in run {
            for
                h <- Hub.init[Int](4)
                _ <- h.put(1)
                _ <- h.put(2)
                r <- h.close
            yield assert(r == Maybe(Seq(1, 2)))
        }

        "operations fail after close" in run {
            for
                h <- Hub.init[Int](4)
                _ <- h.close
                p <- Abort.run(h.put(1))
                o <- Abort.run(h.offer(1))
                l <- Abort.run(h.listen)
            yield assert(p.isFailure && o.isFailure && l.isFailure)
        }
    }

    "streaming" - {
        "stream delivers messages until closed" in run {
            for
                h <- Hub.init[Int](4)
                l <- h.listen
                f <- Async.run(l.stream().take(4).run)
                _ <- h.put(1)
                _ <- h.put(2)
                _ <- h.put(3)
                _ <- h.put(4)
                r <- f.get
            yield assert(r == Chunk(1, 2, 3, 4))
        }

        "streamFailing fails on close" in run {
            for
                h     <- Hub.init[Int](4)
                l     <- h.listen
                fiber <- Async.run(l.streamFailing().run)
                _     <- h.close
                res   <- Abort.run[Closed](fiber.get)
            yield assert(res.isFailure)
        }

        "stream respects chunk size" in run {
            for
                h <- Hub.init[Int](4)
                l <- h.listen
                f <- Async.run(l.stream(2).mapChunk(Chunk(_)).take(2).run)
                _ <- Kyo.foreachDiscard(1 to 4)(h.put)
                r <- f.get
            yield assert(r == Chunk(Chunk(1, 2), Chunk(3, 4)))
        }

        "stream handles rapid publish-consume cycles" in run {
            for
                h <- Hub.init[Int](4)
                l <- h.listen
                f <- Async.run(l.stream().take(1000).run)
                _ <- Kyo.foreachDiscard(1 to 1000)(h.put)
                r <- f.get
            yield assert(r.size == 1000 && r == Chunk.from(1 to 1000))
        }
    }

    "concurrency" - {
        "publishers and subscribers" in run {
            (for
                hub   <- Hub.init[Int](1)
                l1    <- hub.listen
                l2    <- hub.listen
                l3    <- hub.listen
                latch <- Latch.init(1)
                pubFiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded((1 to 10).map(i => Abort.run(hub.put(i))))
                    )
                )
                sub1Fiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded((1 to 10).map(_ => Abort.run(l1.take)))
                    )
                )
                sub2Fiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded((1 to 10).map(_ => Abort.run(l2.take)))
                    )
                )
                sub3Fiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded((1 to 10).map(_ => Abort.run(l3.take)))
                    )
                )
                _      <- latch.release
                pubs   <- pubFiber.get
                subs1  <- sub1Fiber.get
                subs2  <- sub2Fiber.get
                subs3  <- sub3Fiber.get
                closed <- hub.closed
            yield
                assert(!closed)
                assert(pubs.count(_.isSuccess) > 0)
                assert(subs1.count(_.isSuccess) == pubs.count(_.isSuccess))
                assert(subs2.count(_.isSuccess) == pubs.count(_.isSuccess))
                assert(subs3.count(_.isSuccess) == pubs.count(_.isSuccess))
            ).pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent listeners and close" in run {
            (for
                size  <- Choice.get(Seq(1, 2, 10, 100))
                hub   <- Hub.init[Int](size)
                latch <- Latch.init(1)
                listenerFiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded(
                            List.fill(20)(Abort.run(hub.listen))
                        )
                    )
                )
                closeFiber <- Async.run(latch.await.andThen(hub.close))
                _          <- latch.release
                listeners  <- listenerFiber.get
                backlog    <- closeFiber.get
                isClosed   <- hub.closed
            yield assert(isClosed)).pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "message ordering" in run {
            for
                hub   <- Hub.init[Int](1000)
                l1    <- hub.listen
                l2    <- hub.listen
                latch <- Latch.init(1)
                pubFibers <- Async.parallel(4)(
                    (0 until 4).map(n =>
                        Async.run(
                            latch.await.andThen(
                                Kyo.foreachDiscard(
                                    (n * 250) until ((n + 1) * 250)
                                )(hub.put)
                            )
                        )
                    )
                )
                collector1 <- Async.run(
                    latch.await.andThen(
                        l1.stream().take(1000).run
                    )
                )
                collector2 <- Async.run(
                    latch.await.andThen(
                        l2.stream().take(1000).run
                    )
                )
                _    <- latch.release
                _    <- Kyo.foreach(pubFibers)(_.get)
                res1 <- collector1.get
                res2 <- collector2.get
            yield assert(
                res1.size == 1000 &&
                    res2.size == 1000 &&
                    res1.toSet == (0 until 1000).toSet &&
                    res2.toSet == (0 until 1000).toSet &&
                    res1 == res2
            )
        }

        "backpressure with slow consumers" in run {
            for
                hub          <- Hub.init[Int](10)
                latch        <- Latch.init(1)
                slowListener <- hub.listen(0)
                slowConsumer <- Async.run(
                    latch.await.andThen(
                        Kyo.foreach(1 to 10) { _ =>
                            Async.sleep(1.millis).andThen(slowListener.take)
                        }
                    )
                )
                producerFiber <- Async.run(
                    latch.await.andThen(
                        Kyo.foreachDiscard(1 to 10)(hub.put)
                    )
                )
                _         <- latch.release
                stopwatch <- Clock.stopwatch
                result    <- slowConsumer.get
                _         <- producerFiber.get
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed >= 10.millis && result == (1 to 10))
        }

        "concurrent filtered listeners" in run {
            for
                hub   <- Hub.init[Int](100)
                latch <- Latch.init(1)
                listeners <- Async.parallelUnbounded(
                    (0 until 10).map { n =>
                        hub.listen(_ % 10 == n)
                    }
                )
                publisher <- Async.run(
                    latch.await.andThen(
                        Kyo.foreachDiscard(1 to 1000)(hub.put)
                    )
                )
                collectors <- Async.parallelUnbounded(
                    listeners.map(l =>
                        Async.run(
                            latch.await.andThen(
                                l.stream().take(100).run
                            )
                        )
                    )
                )
                _       <- latch.release
                _       <- publisher.get
                results <- Async.parallelUnbounded(collectors.map(_.get))
            yield assert(
                results.forall(_.size == 100) &&
                    results.zipWithIndex.forall { case (nums, idx) =>
                        nums.forall(_ % 10 == idx)
                    }
            )
        }
    }

    "resource management" - {
        "listeners are cleaned up when hub closes" in run {
            for
                h  <- Hub.init[Int](4)
                l1 <- h.listen
                l2 <- h.listen
                _  <- h.close
                c1 <- l1.closed
                c2 <- l2.closed
            yield assert(c1 && c2)
        }

        "listeners can be closed independently" in run {
            for
                h  <- Hub.init[Int](4)
                l1 <- h.listen
                l2 <- h.listen
                _  <- l1.close
                c1 <- l1.closed
                c2 <- l2.closed
                _  <- h.put(1)
                v  <- l2.take
            yield assert(c1 && !c2 && v == 1)
        }

        "resource safety" in run {
            for
                h <- Hub.init[Int](4)
                r <- Resource.run {
                    for
                        l <- h.listen
                        _ <- h.put(1)
                        v <- l.take
                    yield v == 1
                }
                c <- h.closed
            yield assert(r && !c)
        }
    }

    "edge cases" - {
        "zero capacity" in run {
            for
                h <- Hub.init[Int](0)
                l <- h.listen
                f <- h.putFiber(1)
                v <- l.take
                d <- f.done
            yield assert(v == 1 && d)
        }

        "max buffer sizes" in run {
            for
                h <- Hub.init[Int](Int.MaxValue)
                l <- h.listen(Int.MaxValue)
                _ <- h.put(1)
                v <- l.take
            yield assert(v == 1)
        }
    }
end HubTest
