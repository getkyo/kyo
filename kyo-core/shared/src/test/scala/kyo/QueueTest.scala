package kyo

import kyo.kernel.Platform

class QueueTest extends Test:

    val access = Access.values.toList

    "bounded" - {
        access.foreach { access =>
            access.toString() - {
                "isEmpty" in run {
                    for
                        q <- Queue.init[Int](2, access)
                        b <- q.empty
                    yield assert(b && q.capacity == 2)
                }
                "offer and poll" in run {
                    for
                        q <- Queue.init[Int](2, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Maybe(1))
                }
                "peek" in run {
                    for
                        q <- Queue.init[Int](2, access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Maybe(1))
                }
                "full" in run {
                    for
                        q <- Queue.init[Int](2, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        b <- q.offer(3)
                    yield assert(!b)
                }
                "full 4" in run {
                    for
                        q <- Queue.init[Int](4, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        _ <- q.offer(3)
                        _ <- q.offer(4)
                        b <- q.offer(5)
                    yield assert(!b)
                }
                "zero capacity" in run {
                    for
                        q <- Queue.init[Int](0, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(!b && v.isEmpty)
                }
            }
        }
    }

    "close" in run {
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
                v1.isFail &&
                v2.isFail &&
                v3.isFail &&
                v4.isFail &&
                v5.isFail &&
                v6.isFail &&
                v7.isFail &&
                c2.isEmpty
        )
    }

    "drain" in run {
        for
            q <- Queue.init[Int](2)
            _ <- q.offer(1)
            _ <- q.offer(2)
            v <- q.drain
        yield assert(v == Seq(1, 2))
    }

    "unbounded" - {
        access.foreach { access =>
            access.toString() - {
                "isEmpty" in run {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        b <- q.empty
                    yield assert(b)
                }
                "offer and poll" in run {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Maybe(1))
                }
                "peek" in run {
                    for
                        q <- Queue.Unbounded.init[Int](access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Maybe(1))
                }
                "add and poll" in run {
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
            access.toString() in run {
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
            access.toString() in run {
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
            assert(drained == Result.success(Seq(3, 4)))
            assert(testUnsafe.empty().contains(true))
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

        "offer and close" in run {
            (for
                size  <- Choice.get(Seq(0, 1, 2, 10, 100))
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(i => Abort.run(queue.offer(i)))))
                )
                closeFiber  <- Async.run(latch.await.andThen(queue.close))
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
                assert(drained.isFail)
                assert(isClosed)
            )
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "offer and poll" in run {
            (for
                size  <- Choice.get(Seq(0, 1, 2, 10, 100))
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(i => Abort.run(queue.offer(i)))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(_ => Abort.run(queue.poll))))
                )
                _       <- latch.release
                offered <- offerFiber.get
                polled  <- pollFiber.get
                left    <- queue.size
            yield assert(offered.count(_.contains(true)) == polled.count(_.toMaybe.flatten.isDefined) + left))
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "offer to full queue during close" in run {
            (for
                size  <- Choice.get(Seq(0, 1, 2, 10, 100))
                queue <- Queue.init[Int](size)
                _     <- Kyo.foreach(1 to size)(i => queue.offer(i))
                latch <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(i => Abort.run(queue.offer(i)))))
                )
                closeFiber <- Async.run(latch.await.andThen(queue.close))
                _          <- latch.release
                offered    <- offerFiber.get
                backlog    <- closeFiber.get
                isClosed   <- queue.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) == backlog.get.size - size)
                assert(isClosed)
            )
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "concurrent close attempts" in run {
            (for
                size  <- Choice.get(Seq(0, 1, 2, 10, 100))
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(i => Abort.run(queue.offer(i)))))
                )
                closeFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(_ => queue.close)))
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
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "offer, poll and close" in run {
            (for
                size  <- Choice.get(Seq(0, 1, 2, 10, 100))
                queue <- Queue.init[Int](size)
                latch <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(i => Abort.run(queue.offer(i)))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.parallel((1 to 100).map(_ => Abort.run(queue.poll))))
                )
                closeFiber <- Async.run(latch.await.andThen(queue.close))
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
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
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
        "IO" in run {
            for
                queue  <- Queue.init[Int < IO](2)
                _      <- queue.offer(IO(42))
                result <- queue.poll.map(_.get)
            yield assert(result == 42)
        }
        "AtomicBoolean" in run {
            for
                flag   <- AtomicBoolean.init(false)
                queue  <- Queue.init[Int < IO](2)
                _      <- queue.offer(flag.set(true).andThen(42))
                before <- flag.get
                result <- queue.poll.map(_.get)
                after  <- flag.get
            yield assert(!before && result == 42 && after)
        }
        "Env" in run {
            for
                queue  <- Queue.init[Int < Env[Int]](2)
                _      <- queue.offer(Env.use[Int](_ + 22))
                result <- Env.run(20)(queue.poll.map(_.get))
            yield assert(result == 42)
        }
    }
end QueueTest
