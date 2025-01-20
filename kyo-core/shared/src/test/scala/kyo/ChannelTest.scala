package kyo

class ChannelTest extends Test:

    "initWith" in run {
        Channel.initWith[Int](10) { c =>
            for
                b <- c.offer(1)
                v <- c.poll
            yield assert(b && v == Maybe(1))
        }
    }

    "offer and poll" in run {
        for
            c <- Channel.init[Int](2)
            b <- c.offer(1)
            v <- c.poll
        yield assert(b && v == Maybe(1))
    }
    "put and take" in run {
        for
            c <- Channel.init[Int](2)
            _ <- c.put(1)
            v <- c.take
        yield assert(v == 1)
    }
    "offer, put, and take" in run {
        for
            c  <- Channel.init[Int](2)
            b  <- c.offer(1)
            _  <- c.put(2)
            v1 <- c.take
            v2 <- c.take
        yield assert(b && v1 == 1 && v2 == 2)
    }
    "offer, put, and poll" in run {
        for
            c  <- Channel.init[Int](2)
            b  <- c.offer(1)
            _  <- c.put(2)
            v1 <- c.poll
            v2 <- c.poll
            b2 <- c.empty
        yield assert(b && v1 == Maybe(1) && v2 == Maybe(2) && b2)
    }
    "offer, put, and take in parallel" in runNotJS {
        for
            c     <- Channel.init[Int](2)
            b     <- c.offer(1)
            put   <- c.putFiber(2)
            f     <- c.full
            take1 <- c.takeFiber
            take2 <- c.takeFiber
            v1    <- take1.get
            _     <- put.get
            v2    <- take1.get
            v3    <- take2.get
        yield assert(b && f && v1 == 1 && v2 == 1 && v3 == 2)
    }
    "blocking put" in runNotJS {
        for
            c  <- Channel.init[Int](2)
            _  <- c.put(1)
            _  <- c.put(2)
            f  <- c.putFiber(3)
            _  <- Async.sleep(10.millis)
            d1 <- f.done
            v1 <- c.poll
            d2 <- f.done
            v2 <- c.poll
            v3 <- c.poll
        yield assert(!d1 && d2 && v1 == Maybe(1) && v2 == Maybe(2) && v3 == Maybe(3))
    }
    "blocking take" in runNotJS {
        for
            c  <- Channel.init[Int](2)
            f  <- c.takeFiber
            _  <- Async.sleep(10.millis)
            d1 <- f.done
            _  <- c.put(1)
            d2 <- f.done
            v  <- f.get
        yield assert(!d1 && d2 && v == 1)
    }
    "putBatch" - {
        "non-nested" - {
            "should put a batch" in runNotJS {
                for
                    c   <- Channel.init[Int](2)
                    _   <- c.putBatch(Chunk(1, 2))
                    res <- c.drain
                yield assert(res == Chunk(1, 2))
            }
            "should put batch incrementally if exceeds channel size" in runNotJS {
                for
                    c   <- Channel.init[Int](2)
                    f   <- Async.run(c.putBatch(Chunk(1, 2, 3, 4, 5, 6)))
                    res <- c.takeExactly(6)
                    _   <- Fiber.get(f)
                yield assert(res == Chunk(1, 2, 3, 4, 5, 6))
            }
            "should put empty batch" in runNotJS {
                for
                    c       <- Channel.init[Int](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in runNotJS {
                val effect =
                    for
                        c <- Channel.init[Int](2)
                        _ <- c.close
                        _ <- c.putBatch(Chunk(1, 2))
                    yield ()
                Abort.run[Closed](effect).map:
                    case Result.Failure(closed: Closed) => assert(true)
                    case other                          => fail(s"$other was not Result.Failure[Closed]")
            }
            "should notify waiting takers immediately" in runNotJS {
                for
                    c     <- Channel.init[Int](2)
                    take1 <- c.takeFiber
                    take2 <- c.takeFiber
                    _     <- c.putBatch(Seq(1, 2))
                    v1    <- take1.get
                    v2    <- take2.get
                yield assert(v1 == 1 && v2 == 2)
            }
            "should handle channel at capacity" in runNotJS {
                for
                    c     <- Channel.init[Int](2)
                    _     <- c.put(1)
                    _     <- c.put(2)
                    take1 <- c.takeFiber
                    fiber <- Async.run(c.putBatch(Seq(3, 4)))
                    v1    <- take1.get
                    done1 <- fiber.done
                    take2 <- c.takeFiber
                    v2    <- take2.get
                    _     <- fiber.get
                yield assert(v1 == 1 && v2 == 2 && !done1)
            }
            "should handle empty sequence" in runNotJS {
                for
                    c   <- Channel.init[Int](2)
                    res <- c.putBatch(Seq())
                yield assert(true)
            }
            "should fail when channel is closed" in runNotJS {
                for
                    c      <- Channel.init[Int](2)
                    _      <- c.close
                    result <- Abort.run(c.putBatch(Seq(1, 2)))
                yield result match
                    case Result.Failure(_: Closed) => assert(true)
                    case other                     => fail(s"Expected Fail(Closed) but got $other")
            }
            "should preserve elements put before closure during partial batch put" in runNotJS {
                for
                    c     <- Channel.init[Int](2)
                    fiber <- Async.run(c.putBatch(Chunk(1, 2, 3, 4, 5)))
                    v1    <- c.take
                    v2    <- c.take
                    _     <- c.close
                    res   <- fiber.getResult
                yield assert(res.isFailure && v1 == 1 && v2 == 2)
            }
        }
        "nested upper bound" - {
            given ch[A]: CanEqual[Chunk[Any], Chunk[A]] = CanEqual.derived
            "should put a batch" in runNotJS {
                for
                    c   <- Channel.init[Any](2)
                    _   <- c.putBatch(Chunk(Chunk(1), Chunk(2)))
                    res <- c.drain
                yield assert(res == Chunk(Chunk(1), Chunk(2)))
            }
            "should put batch incrementally if exceeds channel size" in runNotJS {
                for
                    c   <- Channel.init[Any](2)
                    f   <- Async.run(c.putBatch(Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6))))
                    res <- c.takeExactly(6)
                    _   <- Fiber.get(f)
                yield assert(res == Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6)))
            }
            "should put empty batch" in runNotJS {
                for
                    c       <- Channel.init[Any](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in runNotJS {
                val effect =
                    for
                        c <- Channel.init[Any](2)
                        _ <- c.close
                        _ <- c.putBatch(Chunk(Chunk(1), Chunk(2)))
                    yield ()
                Abort.run[Closed](effect).map:
                    case Result.Failure(closed: Closed) => assert(true)
                    case other                          => fail(s"$other was not Result.Failure[Closed]")
            }
        }
        "nested lower bound" - {
            "should put a batch" in runNotJS {
                for
                    c   <- Channel.init[Chunk.Indexed[Int]](2)
                    _   <- c.putBatch(Chunk(Chunk(1).toIndexed, Chunk(2).toIndexed))
                    res <- c.drain
                yield assert(res == Chunk(Chunk(1), Chunk(2)))
            }
            "should put batch incrementally if exceeds channel size" in runNotJS {
                for
                    c <- Channel.init[Chunk.Indexed[Int]](2)
                    f <- Async.run(c.putBatch(Chunk(
                        Chunk(1).toIndexed,
                        Chunk(2).toIndexed,
                        Chunk(3).toIndexed,
                        Chunk(4).toIndexed,
                        Chunk(5).toIndexed,
                        Chunk(6).toIndexed
                    )))
                    res <- c.takeExactly(6)
                    _   <- Fiber.get(f)
                yield assert(res == Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6)))
            }
            "should put empty batch" in runNotJS {
                for
                    c       <- Channel.init[Chunk.Indexed[Int]](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in runNotJS {
                val effect =
                    for
                        c <- Channel.init[Chunk.Indexed[Int]](2)
                        _ <- c.close
                        _ <- c.putBatch(Chunk(Chunk(1).toIndexed, Chunk(2).toIndexed))
                    yield ()
                Abort.run[Closed](effect).map:
                    case Result.Failure(closed: Closed) => assert(true)
                    case other                          => fail(s"$other was not Result.Failure[Closed]")
            }
        }
    }
    "takeExactly" - {
        "should return empty chunk if n <= 0" in runNotJS {
            for
                c  <- Channel.init[Int](3)
                _  <- Kyo.foreach(1 to 3)(c.put(_))
                r0 <- c.takeExactly(0)
                rn <- c.takeExactly(-5)
                s  <- c.size
            yield assert(r0 == Chunk.empty && rn == Chunk.empty && s == 3)
        }
        "should take all contents if in n == capacity" in runNotJS {
            for
                c <- Channel.init[Int](3)
                _ <- Kyo.foreach(1 to 3)(c.put(_))
                r <- c.takeExactly(3)
                s <- c.size
            yield assert(r == Seq(1, 2, 3) && s == 0)
        }
        "should take all contents and block if in n > capacity" in runNotJS {
            for
                c  <- Channel.init[Int](3)
                _  <- Kyo.foreach(1 to 3)(c.put(_))
                f  <- Async.run(c.takeExactly(5))
                _  <- Loop(())(_ => c.size.map(s => if s == 0 then Loop.done else Loop.continue(())))
                _  <- Async.sleep(10.millis)
                fd <- f.done
                _  <- f.interrupt
            yield assert(!fd)
        }
        "should take partial contents if channel capacity > n" in runNotJS {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put(_))
                r <- c.takeExactly(2)
                s <- c.size
            yield assert(r == Seq(1, 2) && s == 2)
        }
        "should take incrementally as elements are added to channel" in runNotJS {
            for
                c <- Channel.init[Int](3)
                _ <- Kyo.foreach(1 to 3)(c.put(_))
                f <- Async.run(c.takeExactly(6))
                // Wait until channel is empty
                _  <- Loop(false)(v => if v then Loop.done else c.empty.map(Loop.continue(_)))
                fd <- f.done
                _  <- Kyo.foreach(4 to 6)(c.put(_))
                r  <- Fiber.get(f)
                s  <- c.size
            yield assert(!fd && r == Seq(1, 2, 3, 4, 5, 6) && s == 0)
        }
    }
    "drain" - {
        "empty" in run {
            for
                c <- Channel.init[Int](2)
                r <- c.drain
            yield assert(r == Seq())
        }
        "non-empty" in run {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.drain
            yield assert(r == Seq(1, 2))
        }
        "should consider pending puts" in run {
            import AllowUnsafe.embrace.danger
            IO.Unsafe.evalOrThrow {
                for
                    c         <- Channel.init[Int](2)
                    _         <- c.putFiber(1)
                    _         <- c.putFiber(2)
                    _         <- c.putFiber(3)
                    result    <- c.drain
                    finalSize <- c.size
                yield assert(result == Chunk(1, 2, 3) && finalSize == 0)
            }
        }
        "should consider pending puts - zero capacity" in pendingUntilFixed {
            import AllowUnsafe.embrace.danger
            IO.Unsafe.evalOrThrow {
                for
                    c         <- Channel.init[Int](0)
                    _         <- c.putFiber(1)
                    _         <- c.putFiber(2)
                    _         <- c.putFiber(3)
                    result    <- c.drain
                    finalSize <- c.size
                yield assert(result == Chunk(1, 2, 3) && finalSize == 0)
            }
            ()
        }
    }
    "drainUpTo" - {
        "zero or negative" in run {
            for
                c  <- Channel.init[Int](2)
                r0 <- c.drainUpTo(0)
                rn <- c.drainUpTo(-5)
                s  <- c.size
            yield assert(r0 == Chunk.empty && rn == Chunk.empty && s == 0)
        }
        "empty" in run {
            for
                c <- Channel.init[Int](2)
                r <- c.drainUpTo(2)
                s <- c.size
            yield assert(r == Chunk.empty && s == 0)
        }
        "non-empty channel drain up to the channel contents" in run {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.drainUpTo(2)
                s <- c.size
            yield assert(r == Seq(1, 2) && s == 0)
        }
        "non-empty channel drain up to more than is in the channel" in run {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.drainUpTo(4)
                s <- c.size
            yield assert(r == Seq(1, 2) && s == 0)
        }
        "non-empty channel drain up to less than is in the channel" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put(_))
                r <- c.drainUpTo(2)
                s <- c.size
            yield assert(r == Seq(1, 2) && s == 2)
        }
        "should consider pending puts" in run {
            import AllowUnsafe.embrace.danger
            IO.Unsafe.evalOrThrow {
                for
                    c         <- Channel.init[Int](2)
                    _         <- c.putFiber(1)
                    _         <- c.putFiber(2)
                    _         <- c.putFiber(3)
                    _         <- c.putFiber(4)
                    result    <- c.drainUpTo(3)
                    finalSize <- c.size
                yield assert(result == Chunk(1, 2, 3) && finalSize == 1)
            }
        }
        "should consider pending puts - zero capacity" in pendingUntilFixed {
            import AllowUnsafe.embrace.danger
            IO.Unsafe.evalOrThrow {
                for
                    c         <- Channel.init[Int](0)
                    _         <- c.putFiber(1)
                    _         <- c.putFiber(2)
                    _         <- c.putFiber(3)
                    _         <- c.putFiber(4)
                    result    <- c.drainUpTo(3)
                    finalSize <- c.size
                yield assert(result == Chunk(1, 2, 3) && finalSize == 0)
            }
            ()
        }
    }
    "close" - {
        "empty" in runNotJS {
            for
                c <- Channel.init[Int](2)
                r <- c.close
                t <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq()) && t.isFailure)
        }
        "non-empty" in runNotJS {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.close
                t <- Abort.run(c.empty)
            yield assert(r == Maybe(Seq(1, 2)) && t.isFailure)
        }
        "pending take" in runNotJS {
            for
                c <- Channel.init[Int](2)
                f <- c.takeFiber
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.full)
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
        "pending put" in runNotJS {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                f <- c.putFiber(3)
                r <- c.close
                d <- f.getResult
                e <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq(1, 2)) && d.isFailure && e.isFailure)
        }
        "no buffer w/ pending put" in runNotJS {
            for
                c <- Channel.init[Int](0)
                f <- c.putFiber(1)
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.poll)
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
        "no buffer w/ pending take" in runNotJS {
            for
                c <- Channel.init[Int](0)
                f <- c.takeFiber
                r <- c.close
                d <- f.getResult
                t <- Abort.run[Throwable](c.put(1))
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
    }
    "no buffer" in runNotJS {
        for
            c <- Channel.init[Int](0)
            _ <- c.putFiber(1)
            v <- c.take
            f <- c.full
            e <- c.empty
        yield assert(v == 1 && f && e)
    }
    "contention" - {
        "with buffer" in runNotJS {
            for
                c  <- Channel.init[Int](10)
                f1 <- Fiber.parallelUnbounded(List.fill(1000)(c.put(1)))
                f2 <- Fiber.parallelUnbounded(List.fill(1000)(c.take))
                _  <- f1.get
                _  <- f2.get
                b  <- c.empty
            yield assert(b)
        }

        "no buffer" in runNotJS {
            for
                c  <- Channel.init[Int](10)
                f1 <- Fiber.parallelUnbounded(List.fill(1000)(c.put(1)))
                f2 <- Fiber.parallelUnbounded(List.fill(1000)(c.take))
                _  <- f1.get
                _  <- f2.get
                b  <- c.empty
            yield assert(b)
        }
    }

    "Kyo computations" - {
        "IO" in run {
            for
                channel <- Channel.init[Int < IO](2)
                _       <- channel.put(IO(42))
                result  <- channel.take.flatten
            yield assert(result == 42)
        }
        "AtomicBoolean" in run {
            for
                flag    <- AtomicBoolean.init(false)
                channel <- Channel.init[Int < IO](2)
                _       <- channel.put(flag.set(true).andThen(42))
                before  <- flag.get
                result  <- channel.take.flatten
                after   <- flag.get
            yield assert(!before && result == 42 && after)
        }
        "Env" in run {
            for
                channel <- Channel.init[Int < Env[Int]](2)
                _       <- channel.put(Env.use[Int](_ + 22))
                result  <- Env.run(20)(channel.take.flatten)
            yield assert(result == 42)
        }
    }

    "concurrency" - {

        val repeats = 100

        "offer and close" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(i => Abort.run(channel.offer(i)))))
                )
                closeFiber    <- Async.run(latch.await.andThen(channel.close))
                _             <- latch.release
                offered       <- offerFiber.get
                backlog       <- closeFiber.get
                closedChannel <- channel.close
                drained       <- Abort.run(channel.drain)
                isClosed      <- channel.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) == backlog.get.size)
                assert(closedChannel.isEmpty)
                assert(drained.isFailure)
                assert(isClosed)
            )
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer and poll" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(i => Abort.run(channel.offer(i)))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(_ => Abort.run(channel.poll))))
                )
                _           <- latch.release
                offered     <- offerFiber.get
                polled      <- pollFiber.get
                channelSize <- channel.size
            yield assert(offered.count(_.contains(true)) == polled.count(_.toMaybe.flatten.isDefined) + channelSize))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "put and take" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                putFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(i => Abort.run(channel.put(i)))))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(_ => Abort.run(channel.take))))
                )
                _     <- latch.release
                puts  <- putFiber.get
                takes <- takeFiber.get
            yield assert(puts.count(_.isSuccess) == takes.count(_.isSuccess) && takes.flatMap(_.toMaybe.toList).toSet == (1 to 100).toSet))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer to full channel during close" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                _       <- Kyo.foreach(1 to size)(i => channel.offer(i))
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(i => Abort.run(channel.offer(i)))))
                )
                closeFiber <- Async.run(latch.await.andThen(channel.close))
                _          <- latch.release
                offered    <- offerFiber.get
                backlog    <- closeFiber.get
                isClosed   <- channel.closed
            yield
                assert(backlog.isDefined)
                assert(offered.count(_.contains(true)) == backlog.get.size - size)
                assert(isClosed)
            )
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent close attempts" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(i => Abort.run(channel.offer(i)))))
                )
                closeFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 100).map(_ => channel.close)))
                )
                _        <- latch.release
                offered  <- offerFiber.get
                backlog  <- closeFiber.get
                isClosed <- channel.closed
            yield
                assert(backlog.count(_.isDefined) == 1)
                assert(backlog.flatMap(_.toList.flatten).size == offered.count(_.contains(true)))
                assert(isClosed)
            )
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer, poll, put, take, and close" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 50).map(i => Abort.run(channel.offer(i)))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 50).map(_ => Abort.run(channel.poll))))
                )
                putFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((51 to 100).map(i => Abort.run(channel.put(i)))))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 50).map(_ => Abort.run(channel.take))))
                )
                closeFiber <- Async.run(latch.await.andThen(channel.close))
                _          <- latch.release
                offered    <- offerFiber.get
                polled     <- pollFiber.get
                puts       <- putFiber.get
                takes      <- takeFiber.get
                backlog    <- closeFiber.get
                isClosed   <- channel.closed
            yield
                val totalOffered = offered.count(_.contains(true)) + puts.count(_.isSuccess)
                val totalTaken   = polled.count(_.toMaybe.flatten.isDefined) + takes.count(_.isSuccess)
                assert(backlog.isDefined)
                assert(totalOffered - totalTaken == backlog.get.size)
                assert(isClosed)
            )
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "putBatch and take" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                putFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 60).grouped(3).toSeq.map(batch =>
                        channel.putBatch(batch).andThen(batch)
                    )))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 60).map(_ =>
                        channel.take
                    )))
                )
                _         <- latch.release
                putRes    <- putFiber.get
                takeRes   <- takeFiber.get
                finalSize <- channel.size
            yield assert(putRes.flatten.toSet == takeRes.toSet))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "putBatch and takeExactly" in run {
            (for
                size    <- Choice.get(Seq(0, 1, 2, 10, 100))
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                putFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 60).grouped(3).toSeq.map(batch =>
                        channel.putBatch(batch).andThen(batch)
                    )))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.parallelUnbounded((1 to 6).map(_ =>
                        channel.takeExactly(10)
                    )))
                )
                _         <- latch.release
                putRes    <- putFiber.get
                takeRes   <- takeFiber.get
                finalSize <- channel.size
            yield assert(putRes.flatten.toSet == takeRes.flatten.toSet))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

    }

    "stream" - {
        "should stream from channel" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.stream().take(4)
                v <- stream.run
            yield assert(v == Chunk(1, 2, 3, 4))
        }
        "stream with zero or negative maxChunkSize should stop" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                s0 = c.stream(0)
                sn = c.stream(-5)
                r0 <- s0.run
                rn <- sn.run
                s  <- c.size
            yield assert(r0 == Chunk.empty && rn == Chunk.empty && s == 4)
            end for
        }
        "stream with maxChunkSize of 1 should stream in chunks of 1" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.stream(1).mapChunk(Chunk(_)).take(4)
                r <- stream.run
                s <- c.size
            yield assert(r == Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4)) && s == 0)
            end for
        }
        "should stream from channel without specified chunk size" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.stream().mapChunk(ch => Chunk(ch)).take(1)
                v <- stream.run
                s <- c.size
            yield assert(v == Chunk(Chunk(1, 2, 3, 4)) && s == 0)
        }

        "should stream from channel with a specified chunk size" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.stream(2).mapChunk(ch => Chunk(ch)).take(2)
                v <- stream.run
                s <- c.size
            yield assert(v == Chunk(Chunk(1, 2), Chunk(3, 4)) && s == 0)
        }

        "should stream concurrently with ingest, without specified chunk size" in run {
            for
                c  <- Channel.init[Int](4)
                bg <- Async.run(Loop(0)(i => c.put(i).andThen(Loop.continue(i + 1))))
                stream = c.stream().take(20).mapChunk(ch => Chunk(ch))
                v <- stream.run
                _ <- bg.interrupt
            yield assert(v.flattenChunk == Chunk.from(0 until 20))
        }

        "should stream concurrently with ingest, never exceeding specified chunk size" in run {
            for
                c  <- Channel.init[Int](4)
                bg <- Async.run(Loop(0)(i => c.put(i).andThen(Loop.continue(i + 1))))
                stream = c.stream(2).take(20).mapChunk(ch => Chunk(ch))
                v <- stream.run
                _ <- bg.interrupt
            yield assert(v.flattenChunk == Chunk.from(0 until 20) && v.forall(_.size <= 2))
        }

        "should fail when channel is closed" in run {
            for
                c  <- Channel.init[Int](3)
                bg <- Async.run(Kyo.foreach(0 to 8)(c.put).andThen(c.close))
                stream = c.stream().mapChunk(ch => Chunk(ch))
                v <- Abort.run(stream.run)
            yield v match
                case Result.Success(v)         => fail(s"Stream succeeded unexpectedly: ${v}")
                case Result.Failure(_: Closed) => assert(true)
                case Result.Panic(ex)          => fail(s"Stream panicked unexpectedly: ${ex}")
        }

        "should stream concurrently with ingest via putBatch, yielding consistent chunk sizes" in run {
            for
                c  <- Channel.init[Int](9)
                bg <- Async.run(Loop(0)(i => c.putBatch(Chunk(i, i + 1, i + 2)).andThen(Loop.continue(i + 3))))
                stream = c.stream(3).take(15).mapChunk(ch => Chunk(ch))
                res <- stream.run
                _   <- bg.interrupt
            yield
                assert(res.forall(_.size <= 3))
                assert(res.flatten == (0 to 14))
            end for
        }
    }

    "streamUntilClosed" - {
        "should stream from channel" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.streamUntilClosed().take(4)
                v <- stream.run
            yield assert(v == Chunk(1, 2, 3, 4))
        }
        "stream with zero or negative maxChunkSize should stop" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                s0 = c.streamUntilClosed(0)
                sn = c.streamUntilClosed(-5)
                r0 <- s0.run
                rn <- sn.run
                s  <- c.size
            yield assert(r0 == Chunk.empty && rn == Chunk.empty && s == 4)
            end for
        }
        "stream with maxChunkSize of 1 should stream in chunks of 1" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.streamUntilClosed(1).mapChunk(Chunk(_)).take(4)
                r <- stream.run
                s <- c.size
            yield assert(r == Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4)) && s == 0)
            end for
        }
        "should stream from channel without specified chunk size" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.streamUntilClosed().mapChunk(ch => Chunk(ch)).take(1)
                v <- stream.run
                s <- c.size
            yield assert(v == Chunk(Chunk(1, 2, 3, 4)) && s == 0)
        }

        "should stream from channel with a specified chunk size" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put)
                stream = c.streamUntilClosed(2).mapChunk(ch => Chunk(ch)).take(2)
                v <- stream.run
                s <- c.size
            yield assert(v == Chunk(Chunk(1, 2), Chunk(3, 4)) && s == 0)
        }

        "should stream concurrently with ingest, without specified chunk size" in run {
            for
                c  <- Channel.init[Int](4)
                bg <- Async.run(Loop(0)(i => c.put(i).andThen(Loop.continue(i + 1))))
                stream = c.streamUntilClosed().take(20).mapChunk(ch => Chunk(ch))
                v <- stream.run
                _ <- bg.interrupt
            yield assert(v.flattenChunk == Chunk.from(0 until 20))
        }

        "should stream concurrently with ingest, with specified chunk size" in run {
            for
                c  <- Channel.init[Int](4)
                bg <- Async.run(Loop(0)(i => c.put(i).andThen(Loop.continue(i + 1))))
                stream = c.streamUntilClosed(2).take(20).mapChunk(ch => Chunk(ch))
                v <- stream.run
                _ <- bg.interrupt
            yield assert(v.flattenChunk == Chunk.from(0 until 20) && v.forall(_.size <= 2))
        }

        "should stop when channel is closed" in run {
            val fullStream = Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8)
            for
                c <- Channel.init[Int](3)
                stream = c.streamUntilClosed()
                f <- Async.run(stream.run)
                _ <- Kyo.foreach(fullStream)(c.put).andThen(c.close)
                r <- Fiber.get(f)
            yield assert(r.size <= fullStream.size && r == fullStream.take(r.size))
            end for
        }
    }

end ChannelTest
