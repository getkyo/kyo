package kyo

import kyo.kernel.Platform

class QueueTest extends Test:

    val access = Access.values.toList

    "bounded" - {
        access.foreach { access =>
            access.toString() - {
                "initWith" in runNotNative {
                    Queue.initWith[Int](2, access) { q =>
                        for
                            b <- q.offer(1)
                            v <- q.poll
                        yield assert(b && v == Maybe(1))
                    }
                }
                "isEmpty" in runNotNative {
                    for
                        q <- Queue.init[Int](2, access)
                        b <- q.empty
                    yield assert(b && q.capacity == 2)
                }
                "offer and poll" in runNotNative {
                    for
                        q <- Queue.init[Int](2, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Maybe(1))
                }
                "peek" in runNotNative {
                    for
                        q <- Queue.init[Int](2, access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Maybe(1))
                }
                "full" in runNotNative {
                    for
                        q <- Queue.init[Int](2, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        b <- q.offer(3)
                    yield assert(!b)
                }
                "full 4" in runNotNative {
                    for
                        q <- Queue.init[Int](4, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        _ <- q.offer(3)
                        _ <- q.offer(4)
                        b <- q.offer(5)
                    yield assert(!b)
                }
                "zero capacity" in runNotNative {
                    for
                        q <- Queue.init[Int](0, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(!b && v.isEmpty)
                }
            }
        }
    }

    "close" - {
        "allowed following ops" in runNotNative {
            for
                q  <- Queue.init[Int](2)
                b  <- q.offer(1)
                c1 <- q.close
                v1 <- Abort.run(q.size)
                v2 <- Abort.run(q.empty)
                v3 <- Abort.run(q.full)
                v4 <- Abort.run(q.offer(2))
                v5 <- Abort.run(q.poll)
                v6 <- Abort.run(q.peek)
                v7 <- Abort.run(q.drain)
                c2 <- q.close
            yield assert(
                b && c1 == Maybe(Seq(1)) &&
                    v1.isFailure &&
                    v2.isFailure &&
                    v3.isFailure &&
                    v4.isFailure &&
                    v5.isFailure &&
                    v6.isFailure &&
                    v7.isFailure &&
                    c2.isEmpty
            )
        }
        "states" in runNotNative {
            for
                q       <- Queue.init[Int](2)
                closed1 <- q.closed
                open1   <- q.open
                _       <- q.offer(1)
                _       <- q.close
                closed2 <- q.closed
                open2   <- q.open
            yield assert(!closed1 && open1 && closed2 && !open2)
        }
    }

    "drain" in runNotNative {
        for
            q <- Queue.init[Int](2)
            _ <- q.offer(1)
            _ <- q.offer(2)
            v <- q.drain
        yield assert(v == Seq(1, 2))
    }

    "drainUpTo" in runNotNative {
        for
            q <- Queue.init[Int](4)
            _ <- Kyo.foreach(1 to 4)(q.offer)
            v <- q.drainUpTo(2)
        yield assert(v == Seq(1, 2))
    }

    "unbounded" - {
        access.foreach { access =>
            access.toString() - {
                "isEmpty" in runNotNative {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        b <- q.empty
                    yield assert(b)
                }
                "offer and poll" in runNotNative {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Maybe(1))
                }
                "peek" in runNotNative {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Maybe(1))
                }
                "add and poll" in runNotNative {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        _ <- q.add(1)
                        v <- q.poll
                    yield assert(v == Maybe(1))
                }
            }
        }
    }

    "dropping" - {
        access.foreach { access =>
            access.toString() in runNotNative {
                for
                    q <- Queue.Unbounded.initDropping[Int](2)
                    _ <- q.add(1)
                    _ <- q.add(2)
                    _ <- q.add(3)
                    a <- q.poll
                    b <- q.poll
                    c <- q.poll
                yield assert(a == Maybe(1) && b == Maybe(2) && c.isEmpty)
            }
        }
    }

    "sliding" - {
        access.foreach { access =>
            access.toString() in runNotNative {
                for
                    q <- Queue.Unbounded.initSliding[Int](2)
                    _ <- q.add(1)
                    _ <- q.add(2)
                    _ <- q.add(3)
                    a <- q.poll
                    b <- q.poll
                    c <- q.poll
                yield assert(a == Maybe(2) && b == Maybe(3) && c.isEmpty)
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        def withQueue[A](f: Queue.Unsafe[Int] => A): A =
            f(Queue.Unsafe.init[Int](2))

        "should offer and poll correctly" in withQueue { testUnsafe =>
            assert(testUnsafe.offer(1).contains(true))
            assert(testUnsafe.poll().contains(Maybe(1)))
        }

        "should peek correctly" in withQueue { testUnsafe =>
            testUnsafe.offer(2)
            assert(testUnsafe.peek().contains(Maybe(2)))
        }

        "should report empty correctly" in withQueue { testUnsafe =>
            assert(testUnsafe.empty().contains(true))
            testUnsafe.offer(3)
            assert(testUnsafe.empty().contains(false))
        }

        "should report size correctly" in withQueue { testUnsafe =>
            assert(testUnsafe.size().contains(0))
            testUnsafe.offer(3)
            assert(testUnsafe.size().contains(1))
        }

        "should drain correctly" in withQueue { testUnsafe =>
            testUnsafe.offer(3)
            testUnsafe.offer(4)
            val drained = testUnsafe.drain()
            assert(drained == Result.succeed(Seq(3, 4)))
            assert(testUnsafe.empty().contains(true))
        }

        "should drainUpTo correctly" in {
            val testUnsafe = Queue.Unsafe.init[Int](6)
            (1 to 6).foreach(testUnsafe.offer)
            val drained3 = testUnsafe.drainUpTo(3)
            assert(drained3 == Result.succeed(Seq(1, 2, 3)))
            val drained5 = testUnsafe.drainUpTo(5)
            assert(drained5 == Result.succeed(Seq(4, 5, 6)))
        }

        "should close correctly" in withQueue { testUnsafe =>
            testUnsafe.offer(5)
            val closed = testUnsafe.close()
            assert(closed == Maybe(Seq(5)))
            assert(testUnsafe.close().isEmpty)
        }
    }

    "concurrency" - {

        val repeats = 100

        "offer and close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Fiber.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(queue.offer(i))))
                )
                closeFiber  <- Fiber.run(latch.await.andThen(queue.close))
                _           <- latch.release
                offered     <- offerFiber.get
                backlog     <- closeFiber.get
                closedQueue <- queue.close
                drained     <- Abort.run(queue.drain)
                isClosed    <- queue.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) == backlog.get.size)
                assert(closedQueue.isEmpty)
                assert(drained.isFailure)
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer and poll" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Fiber.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(queue.offer(i))))
                )
                pollFiber <- Fiber.run(
                    latch.await.andThen(Async.fill(100, 100)(Abort.run(queue.poll)))
                )
                _       <- latch.release
                offered <- offerFiber.get
                polled  <- pollFiber.get
                left    <- queue.size
            yield assert(offered.count(_.contains(true)) == polled.count(_.toMaybe.flatten.isDefined) + left))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer to full queue during close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                _     <- Kyo.foreach(1 to size)(i => queue.offer(i))
                latch <- Latch.init(1)
                offerFiber <- Fiber.run(
                    latch.await.andThen(Async.foreach(1 to 100)(i => Abort.run(queue.offer(i))))
                )
                closeFiber <- Fiber.run(latch.await.andThen(queue.close))
                _          <- latch.release
                offered    <- offerFiber.get
                backlog    <- closeFiber.get
                isClosed   <- queue.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) == backlog.get.size - size)
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent close attempts" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Fiber.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(queue.offer(i))))
                )
                closeFiber <- Fiber.run(
                    latch.await.andThen(Async.fill(100, 100)(queue.close))
                )
                _        <- latch.release
                offered  <- offerFiber.get
                backlog  <- closeFiber.get
                isClosed <- queue.closed
            yield
                assert(backlog.count(_.isDefined) == 1)
                assert(backlog.flatMap(_.toList.flatten).size == offered.count(_.contains(true)))
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer, poll and close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Fiber.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(queue.offer(i))))
                )
                pollFiber <- Fiber.run(
                    latch.await.andThen(Async.fill(100, 100)(Abort.run(queue.poll)))
                )
                closeFiber <- Fiber.run(latch.await.andThen(queue.close))
                _          <- latch.release
                offered    <- offerFiber.get
                polled     <- pollFiber.get
                backlog    <- closeFiber.get
                isClosed   <- queue.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) - polled.count(_.toMaybe.flatten.isDefined) == backlog.get.size)
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }

    if Platform.isJVM then
        "non-power-of-two capacity" - {
            import AllowUnsafe.embrace.danger

            def testCapacity(accessType: Access) =
                List(3, 7, 15, 31).foreach { requestedCapacity =>
                    s"$accessType with requested capacity $requestedCapacity" in pendingUntilFixed {
                        val queue          = Queue.Unsafe.init[Int](requestedCapacity, accessType)
                        val actualCapacity = queue.capacity

                        (1 to requestedCapacity).foreach { i =>
                            assert(queue.offer(i).contains(true), s"Failed to offer item $i")
                        }

                        assert(!queue.offer(requestedCapacity + 1).contains(true), "Should not be able to offer beyond requested capacity")
                        assert(
                            actualCapacity >= requestedCapacity,
                            s"Actual capacity $actualCapacity is less than requested capacity $requestedCapacity"
                        )
                        ()
                    }
                }

            Access.values.foreach { access =>
                access.toString - testCapacity(access)
            }
        }
    end if

    "Kyo computations" - {
        "Sync" in runNotNative {
            for
                queue  <- Queue.init[Int < Sync](2)
                _      <- queue.offer(Sync.defer(42))
                result <- queue.poll.map(_.get)
            yield assert(result == 42)
        }
        "AtomicBoolean" in runNotNative {
            for
                flag   <- AtomicBoolean.init(false)
                queue  <- Queue.init[Int < Sync](2)
                _      <- queue.offer(flag.set(true).andThen(42))
                before <- flag.get
                result <- queue.poll.map(_.get)
                after  <- flag.get
            yield assert(!before && result == 42 && after)
        }
        "Env" in runNotNative {
            for
                queue  <- Queue.init[Int < Env[Int]](2)
                _      <- queue.offer(Env.use[Int](_ + 22))
                result <- Env.run(20)(queue.poll.map(_.get))
            yield assert(result == 42)
        }
    }

    "closeAwaitEmpty" - {
        "returns true when queue is already empty" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                result <- queue.closeAwaitEmpty
                closed <- queue.closed
            yield assert(result && closed)
        }

        "returns true when queue becomes empty after closing" in runNotNative {
            for
                queue   <- Queue.init[Int](10)
                _       <- queue.offer(1)
                _       <- queue.offer(2)
                fiber   <- Fiber.run(queue.closeAwaitEmpty)
                closed1 <- queue.closed
                _       <- queue.poll
                _       <- queue.poll
                result  <- fiber.get
                closed2 <- queue.closed
            yield assert(!closed1 && result && closed2)
        }

        "returns false if queue is already closed" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                _      <- queue.close
                result <- queue.closeAwaitEmpty
            yield assert(!result)
        }

        "unbounded queue" - {
            "returns true when queue is already empty" in runNotNative {
                for
                    queue  <- Queue.Unbounded.init[Int]()
                    result <- queue.closeAwaitEmpty
                yield assert(result)
            }

            "returns true when queue becomes empty after closing" in runNotNative {
                for
                    queue  <- Queue.Unbounded.init[Int]()
                    _      <- queue.add(1)
                    _      <- queue.add(2)
                    fiber  <- Fiber.run(queue.closeAwaitEmpty)
                    _      <- queue.poll
                    _      <- queue.poll
                    result <- fiber.get
                yield assert(result)
            }
        }

        "concurrent polling and waiting" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                _      <- Kyo.foreach(1 to 5)(i => queue.offer(i))
                fiber  <- Fiber.run(queue.closeAwaitEmpty)
                _      <- Async.foreach(1 to 5)(_ => queue.poll)
                result <- fiber.get
            yield assert(result)
        }

        "sliding queue" in runNotNative {
            for
                queue  <- Queue.Unbounded.initSliding[Int](2)
                _      <- queue.add(1)
                _      <- queue.add(2)
                fiber  <- Fiber.run(queue.closeAwaitEmpty)
                _      <- queue.poll
                _      <- queue.poll
                result <- fiber.get
            yield assert(result)
        }

        "dropping queue" in runNotNative {
            for
                queue  <- Queue.Unbounded.initDropping[Int](2)
                _      <- queue.add(1)
                _      <- queue.add(2)
                fiber  <- Fiber.run(queue.closeAwaitEmpty)
                _      <- queue.poll
                _      <- queue.poll
                result <- fiber.get
            yield assert(result)
        }

        "zero capacity queue" in runNotNative {
            for
                queue  <- Queue.init[Int](0)
                result <- queue.closeAwaitEmpty
            yield assert(result)
        }

        "race between closeAwaitEmpty and close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                _     <- Kyo.foreach(1 to (size min 5))(i => queue.offer(i))
                latch <- Latch.init(1)
                closeAwaitEmptyFiber <- Fiber.run(
                    latch.await.andThen(queue.closeAwaitEmpty)
                )
                closeFiber <- Fiber.run(
                    latch.await.andThen(queue.close)
                )
                _        <- latch.release
                _        <- Abort.run(queue.drain)
                result1  <- closeAwaitEmptyFiber.get
                result2  <- closeFiber.get
                isClosed <- queue.closed
            yield
                assert(isClosed)
                assert((result1 && result2.isEmpty) || (!result1 && result2.isDefined))
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "two producers calling closeAwaitEmpty" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)

                producerFiber1 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmpty)
                    )
                )
                producerFiber2 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmpty)
                    )
                )

                consumerFiber <- Fiber.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(untilTrue(queue.poll.map(_.isDefined)))
                    )
                )

                _        <- latch.release
                result1  <- producerFiber1.getResult
                result2  <- producerFiber2.getResult
                isClosed <- queue.closed
                _        <- consumerFiber.getResult
            yield
                assert(isClosed)
                assert(Seq(result1, result2).count(_.contains(true)) == 1)
                assert(Seq(result1, result2).count(r => r.contains(false) || r.isFailure) == 1)
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "producer calling closeAwaitEmpty and another calling close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)

                producerFiber1 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmpty)
                    )
                )
                producerFiber2 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.close)
                    )
                )

                consumerFiber <- Fiber.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(untilTrue(queue.poll.map(_.isDefined)))
                    )
                )

                _        <- latch.release
                result1  <- producerFiber1.getResult
                result2  <- producerFiber2.getResult
                isClosed <- queue.closed
                _        <- consumerFiber.getResult
            yield
                assert(isClosed)
                assert(
                    (result1.isFailure || result1.contains(false)) && !result2.contains(Absent) ||
                        (result1.contains(true)) && result2.contains(Absent)
                )
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }
    }

    "closeAwaitEmptyFiber" - {
        "returns true when queue is already empty" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                result <- queue.closeAwaitEmptyFiber.map(_.get)
                closed <- queue.closed
                open   <- queue.open
            yield assert(result && closed && !open)
        }

        "returns true when queue becomes empty after closing" in runNotNative {
            for
                queue   <- Queue.init[Int](10)
                _       <- queue.offer(1)
                _       <- queue.offer(2)
                fiber   <- queue.closeAwaitEmptyFiber
                closed1 <- queue.closed
                open1   <- queue.open
                _       <- queue.poll
                _       <- queue.poll
                result  <- fiber.get
                closed2 <- queue.closed
                open2   <- queue.open
            yield assert(
                !closed1 &&
                    !open1 &&
                    !open2 &&
                    result &&
                    closed2 &&
                    !open2
            )
        }

        "returns false if queue is already closed" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                _      <- queue.close
                result <- queue.closeAwaitEmptyFiber.map(_.get)
            yield assert(!result)
        }

        "unbounded queue" - {
            "returns true when queue is already empty" in runNotNative {
                for
                    queue  <- Queue.Unbounded.init[Int]()
                    result <- queue.closeAwaitEmptyFiber.map(_.get)
                yield assert(result)
            }

            "returns true when queue becomes empty after closing" in runNotNative {
                for
                    queue  <- Queue.Unbounded.init[Int]()
                    _      <- queue.add(1)
                    _      <- queue.add(2)
                    fiber  <- queue.closeAwaitEmptyFiber
                    _      <- queue.poll
                    _      <- queue.poll
                    result <- fiber.get
                yield assert(result)
            }
        }

        "concurrent polling and waiting" in runNotNative {
            for
                queue  <- Queue.init[Int](10)
                _      <- Kyo.foreach(1 to 5)(i => queue.offer(i))
                fiber  <- queue.closeAwaitEmptyFiber
                _      <- Async.foreach(1 to 5)(_ => queue.poll)
                result <- fiber.get
            yield assert(result)
        }

        "sliding queue" in runNotNative {
            for
                queue  <- Queue.Unbounded.initSliding[Int](2)
                _      <- queue.add(1)
                _      <- queue.add(2)
                fiber  <- queue.closeAwaitEmptyFiber
                _      <- queue.poll
                _      <- queue.poll
                result <- fiber.get
            yield assert(result)
        }

        "dropping queue" in runNotNative {
            for
                queue  <- Queue.Unbounded.initDropping[Int](2)
                _      <- queue.add(1)
                _      <- queue.add(2)
                fiber  <- queue.closeAwaitEmptyFiber
                _      <- queue.poll
                _      <- queue.poll
                result <- fiber.get
            yield assert(result)
        }

        "zero capacity queue" in runNotNative {
            for
                queue  <- Queue.init[Int](0)
                result <- queue.closeAwaitEmptyFiber.map(_.get)
            yield assert(result)
        }

        "race between closeAwaitEmpty and close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                _     <- Kyo.foreach(1 to (size min 5))(i => queue.offer(i))
                latch <- Latch.init(1)
                closeAwaitEmptyFiber <- Fiber.run(
                    latch.await.andThen(queue.closeAwaitEmptyFiber.map(_.get))
                )
                closeFiber <- Fiber.run(
                    latch.await.andThen(queue.close)
                )
                _        <- latch.release
                _        <- Abort.run(queue.drain)
                result1  <- closeAwaitEmptyFiber.get
                result2  <- closeFiber.get
                isClosed <- queue.closed
            yield
                assert(isClosed)
                assert((result1 && result2.isEmpty) || (!result1 && result2.isDefined))
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "two producers calling closeAwaitEmpty" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)

                producerFiber1 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmptyFiber.map(_.get))
                    )
                )
                producerFiber2 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmptyFiber.map(_.get))
                    )
                )

                consumerFiber <- Fiber.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(untilTrue(queue.poll.map(_.isDefined)))
                    )
                )

                _        <- latch.release
                result1  <- producerFiber1.getResult
                result2  <- producerFiber2.getResult
                isClosed <- queue.closed
                _        <- consumerFiber.getResult
            yield
                assert(isClosed)
                assert(Seq(result1, result2).count(_.contains(true)) == 1)
                assert(Seq(result1, result2).count(r => r.contains(false) || r.isFailure) == 1)
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "producer calling closeAwaitEmpty and another calling close" in runNotNative {
            (for
                size  <- Choice.eval(0, 1, 2, 10, 100)
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)

                producerFiber1 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.closeAwaitEmptyFiber.map(_.get))
                    )
                )
                producerFiber2 <- Fiber.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(queue.offer(i)))
                            .andThen(queue.close)
                    )
                )

                consumerFiber <- Fiber.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(untilTrue(queue.poll.map(_.isDefined)))
                    )
                )

                _        <- latch.release
                result1  <- producerFiber1.getResult
                result2  <- producerFiber2.getResult
                isClosed <- queue.closed
                _        <- consumerFiber.getResult
            yield
                assert(isClosed)
                assert(
                    (result1.isFailure || result1.contains(false)) && !result2.contains(Absent) ||
                        (result1.contains(true)) && result2.contains(Absent)
                )
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }
    }

end QueueTest
