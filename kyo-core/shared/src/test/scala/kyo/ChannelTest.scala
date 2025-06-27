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
    "offer, put, and take in parallel" in run {
        for
            c     <- Channel.init[Int](2)
            b     <- c.offer(1)
            put   <- Async.run(c.put(2))
            _     <- untilTrue(c.full)
            take1 <- Async.run(c.take)
            take2 <- Async.run(c.take)
            v1    <- take1.get
            _     <- put.get
            v2    <- take1.get
            v3    <- take2.get
        yield assert(b && v1 == 1 && v2 == 1 && v3 == 2)
    }
    "blocking put" in run {
        for
            c  <- Channel.init[Int](2)
            _  <- c.put(1)
            _  <- c.put(2)
            f  <- Async.run(c.put(3))
            _  <- Async.sleep(10.millis)
            d1 <- f.done
            v1 <- c.poll
            _  <- untilTrue(f.done)
            v2 <- c.poll
            v3 <- c.poll
        yield assert(!d1 && v1 == Maybe(1) && v2 == Maybe(2) && v3 == Maybe(3))
    }
    "blocking take" in run {
        for
            c  <- Channel.init[Int](2)
            f  <- Async.run(c.take)
            _  <- Async.sleep(10.millis)
            d1 <- f.done
            _  <- c.put(1)
            _  <- untilTrue(f.done)
            v  <- f.get
        yield assert(!d1 && v == 1)
    }
    "putBatch" - {
        "non-nested" - {
            "should put a batch" in run {
                for
                    c   <- Channel.init[Int](2)
                    _   <- c.putBatch(Chunk(1, 2))
                    res <- c.drain
                yield assert(res == Chunk(1, 2))
            }
            "should put batch incrementally if exceeds channel size" in run {
                for
                    c   <- Channel.init[Int](2)
                    f   <- Async.run(c.putBatch(Chunk(1, 2, 3, 4, 5, 6)))
                    res <- c.takeExactly(6)
                    _   <- Fiber.get(f)
                yield assert(res == Chunk(1, 2, 3, 4, 5, 6))
            }
            "should put empty batch" in run {
                for
                    c       <- Channel.init[Int](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in run {
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
            "should notify waiting takers immediately" in run {
                for
                    c     <- Channel.init[Int](2)
                    take1 <- Async.run(c.take)
                    take2 <- Async.run(c.take)
                    _     <- c.putBatch(Seq(1, 2))
                    v1    <- take1.get
                    v2    <- take2.get
                yield assert(Set(v1, v2) == Set(1, 2))
            }
            "should handle channel at capacity" in run {
                for
                    c     <- Channel.init[Int](2)
                    _     <- c.put(1)
                    _     <- c.put(2)
                    take1 <- Async.run(c.take)
                    fiber <- Async.run(c.putBatch(Seq(3, 4)))
                    v1    <- take1.get
                    done1 <- fiber.done
                    take2 <- Async.run(c.take)
                    v2    <- take2.get
                    _     <- fiber.get
                yield assert(v1 == 1 && v2 == 2 && !done1)
            }
            "should handle empty sequence" in run {
                for
                    c   <- Channel.init[Int](2)
                    res <- c.putBatch(Seq())
                yield assert(true)
            }
            "should fail when channel is closed" in run {
                for
                    c      <- Channel.init[Int](2)
                    _      <- c.close
                    result <- Abort.run(c.putBatch(Seq(1, 2)))
                yield result match
                    case Result.Failure(_: Closed) => assert(true)
                    case other                     => fail(s"Expected Fail(Closed) but got $other")
            }
            "should preserve elements put before closure during partial batch put" in run {
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
            "should put a batch" in run {
                for
                    c   <- Channel.init[Any](2)
                    _   <- c.putBatch(Chunk(Chunk(1), Chunk(2)))
                    res <- c.drain
                yield assert(res == Chunk(Chunk(1), Chunk(2)))
            }
            "should put batch incrementally if exceeds channel size" in run {
                for
                    c   <- Channel.init[Any](2)
                    f   <- Async.run(c.putBatch(Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6))))
                    res <- c.takeExactly(6)
                    _   <- Fiber.get(f)
                yield assert(res == Chunk(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6)))
            }
            "should put empty batch" in run {
                for
                    c       <- Channel.init[Any](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in run {
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
            "should put a batch" in run {
                for
                    c   <- Channel.init[Chunk.Indexed[Int]](2)
                    _   <- c.putBatch(Chunk(Chunk(1).toIndexed, Chunk(2).toIndexed))
                    res <- c.drain
                yield assert(res == Chunk(Chunk(1), Chunk(2)))
            }
            "should put batch incrementally if exceeds channel size" in run {
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
            "should put empty batch" in run {
                for
                    c       <- Channel.init[Chunk.Indexed[Int]](2)
                    _       <- c.putBatch(Chunk.empty)
                    isEmpty <- c.empty
                yield assert(isEmpty)
            }
            "should fail when non-empty and channel is closed" in run {
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
        "should return empty chunk if n <= 0" in run {
            for
                c  <- Channel.init[Int](3)
                _  <- Kyo.foreach(1 to 3)(c.put(_))
                r0 <- c.takeExactly(0)
                rn <- c.takeExactly(-5)
                s  <- c.size
            yield assert(r0 == Chunk.empty && rn == Chunk.empty && s == 3)
        }
        "should take all contents if in n == capacity" in run {
            for
                c <- Channel.init[Int](3)
                _ <- Kyo.foreach(1 to 3)(c.put(_))
                r <- c.takeExactly(3)
                s <- c.size
            yield assert(r == Seq(1, 2, 3) && s == 0)
        }
        "should take all contents and block if in n > capacity" in run {
            for
                c  <- Channel.init[Int](3)
                _  <- Kyo.foreach(1 to 3)(c.put(_))
                f  <- Async.run(c.takeExactly(5))
                _  <- untilTrue(c.size.map(_ == 0))
                fd <- f.done
                _  <- f.interrupt
            yield assert(!fd)
        }
        "should take partial contents if channel capacity > n" in run {
            for
                c <- Channel.init[Int](4)
                _ <- Kyo.foreach(1 to 4)(c.put(_))
                r <- c.takeExactly(2)
                s <- c.size
            yield assert(r == Seq(1, 2) && s == 2)
        }
        "should take incrementally as elements are added to channel" in run {
            for
                c  <- Channel.init[Int](3)
                _  <- Kyo.foreach(1 to 3)(c.put(_))
                f  <- Async.run(c.takeExactly(6))
                _  <- untilTrue(c.empty)
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
            for
                c         <- Channel.init[Int](2)
                _         <- Async.run(c.put(1))
                _         <- Async.run(c.put(2))
                _         <- Async.run(c.put(3))
                _         <- untilTrue(c.pendingPuts.map(_ == 1))
                result    <- c.drain
                finalSize <- c.size
            yield assert(result.sorted == Chunk(1, 2, 3) && finalSize == 0)
            end for
        }
        "should consider pending puts - zero capacity" in run {
            for
                c         <- Channel.init[Int](0)
                _         <- Async.run(c.put(1))
                _         <- Async.run(c.put(2))
                _         <- Async.run(c.put(3))
                _         <- untilTrue(c.pendingPuts.map(_ == 3))
                result    <- c.drain
                finalSize <- c.size
            yield assert(result.sorted == Chunk(1, 2, 3) && finalSize == 0)
            end for
        }
        "race with close" in run {
            verifyRaceDrainWithClose(2, _.drain, _.close)
        }
        "race with closeAwaitEmpty" in run {
            verifyRaceDrainWithClose(2, _.drain, _.closeAwaitEmpty)
        }
        "race with close and zero capacity" in run {
            verifyRaceDrainWithClose(2, _.drain, _.close)
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
            for
                c         <- Channel.init[Int](2)
                _         <- Async.run(c.put(1))
                _         <- Async.run(c.put(2))
                _         <- Async.run(c.put(3))
                _         <- Async.run(c.put(4))
                _         <- untilTrue(c.pendingPuts.map(_ == 2))
                result    <- c.drainUpTo(3)
                finalSize <- c.size
            yield assert(result.size == 3 && finalSize == 1)
        }
        "should consider pending puts - zero capacity" in run {
            for
                c         <- Channel.init[Int](0)
                _         <- Async.run(c.put(1))
                _         <- Async.run(c.put(2))
                _         <- Async.run(c.put(3))
                _         <- Async.run(c.put(4))
                _         <- untilTrue(c.pendingPuts.map(_ == 4))
                result    <- c.drainUpTo(3)
                finalSize <- c.size
            yield assert(result.size == 3 && finalSize == 0)
        }
        "race with close" in run {
            verifyRaceDrainWithClose(2, _.drainUpTo(2), _.close)
        }
        "race with closeAwaitEmpty" in run {
            verifyRaceDrainWithClose(2, _.drainUpTo(2), _.closeAwaitEmpty)
        }
        "race with close and zero capacity" in run {
            verifyRaceDrainWithClose(0, _.drainUpTo(Int.MaxValue), _.close)
        }
    }
    "close" - {
        "empty" in run {
            for
                c <- Channel.init[Int](2)
                r <- c.close
                t <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq()) && t.isFailure)
        }
        "non-empty" in run {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.close
                t <- Abort.run(c.empty)
            yield assert(r == Maybe(Seq(1, 2)) && t.isFailure)
        }
        "pending take" in run {
            for
                c <- Channel.init[Int](2)
                f <- Async.run(c.take)
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.full)
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
        "pending put" in run {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                f <- Async.run(c.put(3))
                r <- c.close
                d <- f.getResult
                e <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq(1, 2)) && d.isFailure && e.isFailure)
        }
        "no buffer w/ pending put" in run {
            for
                c <- Channel.init[Int](0)
                f <- Async.run(c.put(1))
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.poll)
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
        "no buffer w/ pending take" in run {
            for
                c <- Channel.init[Int](0)
                f <- Async.run(c.take)
                r <- c.close
                d <- f.getResult
                t <- Abort.run[Throwable](c.put(1))
            yield assert(r == Maybe(Seq()) && d.isFailure && t.isFailure)
        }
    }
    "no buffer" in run {
        for
            c <- Channel.init[Int](0)
            _ <- Async.run(c.put(1))
            v <- c.take
            f <- c.full
            e <- c.empty
        yield assert(v == 1 && f && e)
    }
    "contention" - {
        "with buffer" in run {
            for
                c  <- Channel.init[Int](10)
                f1 <- Async.run(Async.fill(1000, 1000)(c.put(1)))
                f2 <- Async.run(Async.fill(1000, 1000)(c.take))
                _  <- f1.get
                _  <- f2.get
                b  <- c.empty
            yield assert(b)
        }

        "no buffer" in run {
            for
                c  <- Channel.init[Int](0)
                f1 <- Async.run(Async.fill(1000, 1000)(c.put(1)))
                f2 <- Async.run(Async.fill(1000, 1000)(c.take))
                _  <- f1.get
                _  <- f2.get
                b  <- c.empty
            yield assert(b)
        }
    }

    "Kyo computations" - {
        "Sync" in run {
            for
                channel <- Channel.init[Int < Sync](2)
                _       <- channel.put(Sync(42))
                result  <- channel.take.flatten
            yield assert(result == 42)
        }
        "AtomicBoolean" in run {
            for
                flag    <- AtomicBoolean.init(false)
                channel <- Channel.init[Int < Sync](2)
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
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(channel.offer(i))))
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
                if size == 0 then
                    assert(backlog.get.size == 0)
                else
                    discard(assert(offered.count(_.contains(true)) == backlog.get.size))
                end if
                assert(closedChannel.isEmpty)
                assert(drained.isFailure)
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer and poll" in runNotNative {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(channel.offer(i))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.fill(100, 100)(Abort.run(channel.poll)))
                )
                _           <- latch.release
                offered     <- offerFiber.get
                polled      <- pollFiber.get
                channelSize <- channel.size
            yield assert(offered.count(_.contains(true)) == polled.count(_.toMaybe.flatten.isDefined) + channelSize))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "put and take" in runNotNative {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                putFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(channel.put(i))))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.fill(100, 100)(Abort.run(channel.take)))
                )
                _     <- latch.release
                puts  <- putFiber.get
                takes <- takeFiber.get
            yield assert(puts.count(_.isSuccess) == takes.count(_.isSuccess) && takes.flatMap(_.toMaybe.toList).toSet == (1 to 100).toSet))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer to full channel during close" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                _       <- Kyo.foreach(1 to size)(i => channel.offer(i))
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(channel.offer(i))))
                )
                closeFiber <- Async.run(latch.await.andThen(channel.close))
                _          <- latch.release
                offered    <- offerFiber.get
                backlog    <- closeFiber.get
                isClosed   <- channel.closed
            yield
                assert(backlog.isDefined)
                if size == 0 then
                    assert(backlog.get.size == 0)
                else
                    assert(offered.count(_.contains(true)) == backlog.get.size - size)
                end if
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent close attempts" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 100, 100)(i => Abort.run(channel.offer(i))))
                )
                closeFiber <- Async.run(
                    latch.await.andThen(Async.fill(100, 100)(channel.close))
                )
                _        <- latch.release
                offered  <- offerFiber.get
                backlog  <- closeFiber.get
                isClosed <- channel.closed
            yield
                assert(backlog.count(_.isDefined) == 1)
                if size == 0 then
                    assert(backlog.flatMap(_.toList.flatten).size == 0)
                else
                    assert(backlog.flatMap(_.toList.flatten).size == offered.count(_.contains(true)))
                end if
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "offer, poll, put, take, and close" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)
                offerFiber <- Async.run(
                    latch.await.andThen(Async.foreach(1 to 50, 50)(i => Abort.run(channel.offer(i))))
                )
                pollFiber <- Async.run(
                    latch.await.andThen(Async.fill(50, 50)(Abort.run(channel.poll)))
                )
                putFiber <- Async.run(
                    latch.await.andThen(Async.foreach(51 to 100, 50)(i => Abort.run(channel.put(i))))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.fill(50, 50)(Abort.run(channel.take)))
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
                if size == 0 then
                    assert(backlog.get.size == 0)
                else
                    assert(totalOffered - totalTaken == backlog.get.size)
                end if
                assert(isClosed)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "putBatch and take" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                putFiber <- Async.run(
                    latch.await.andThen(Async.foreach((1 to 60).grouped(3).toSeq, 60)(batch =>
                        channel.putBatch(batch).andThen(batch)
                    ))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.fill(60, 60)(channel.take))
                )
                _         <- latch.release
                putRes    <- putFiber.get
                takeRes   <- takeFiber.get
                finalSize <- channel.size
            yield assert(putRes.flatten.toSet == takeRes.toSet))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "putBatch and takeExactly" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                putFiber <- Async.run(
                    latch.await.andThen(Async.foreach((1 to 60).grouped(3).toSeq, 60)(batch =>
                        channel.putBatch(batch).andThen(batch)
                    ))
                )
                takeFiber <- Async.run(
                    latch.await.andThen(Async.fill(6, 6)(
                        channel.takeExactly(10)
                    ))
                )
                _         <- latch.release
                putRes    <- putFiber.get
                takeRes   <- takeFiber.get
                finalSize <- channel.size
            yield assert(putRes.flatten.toSet == takeRes.flatten.toSet))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
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

        "should stop when channel is closed async" in run {
            val fullStream = Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8)
            for
                c <- Channel.init[Int](3)
                stream = c.streamUntilClosed()
                f <- Async.run(stream.run)
                _ <- Kyo.foreach(fullStream)(c.put).andThen(c.close)
                _ <- Async.run(c.closeAwaitEmpty)
                r <- Fiber.get(f)
            yield assert(r.size <= fullStream.size && r == fullStream.take(r.size))
            end for
        }
    }

    "closeAwaitEmpty" - {
        "returns true when channel is already empty" in run {
            for
                c      <- Channel.init[Int](10)
                result <- c.closeAwaitEmpty
            yield assert(result)
        }

        "returns true when channel becomes empty after closing" in run {
            for
                c       <- Channel.init[Int](10)
                _       <- c.put(1)
                _       <- c.put(2)
                fiber   <- Async.run(c.closeAwaitEmpty)
                closed1 <- c.closed
                _       <- c.take
                closed2 <- c.closed
                _       <- c.take
                result  <- fiber.get
                closed3 <- c.closed
            yield assert(result && !closed1 && !closed2 && closed3)
        }

        "returns false if channel is already closed" in run {
            for
                c      <- Channel.init[Int](10)
                _      <- c.close
                result <- c.closeAwaitEmpty
            yield assert(!result)
        }

        "concurrent taking and waiting" in run {
            for
                c      <- Channel.init[Int](10)
                _      <- Kyo.foreach(1 to 5)(i => c.put(i))
                fiber  <- Async.run(c.closeAwaitEmpty)
                _      <- Async.foreach(1 to 5)(_ => c.take)
                result <- fiber.get
            yield assert(result)
        }

        "zero capacity channel" in run {
            for
                c      <- Channel.init[Int](0)
                result <- c.closeAwaitEmpty
            yield assert(result)
        }

        "should discard new takes" in run {
            for
                c      <- Channel.init[Int](2)
                _      <- c.put(1)
                _      <- c.put(2)
                fiber  <- Async.run(c.closeAwaitEmpty)
                _      <- c.take
                _      <- c.take
                take   <- Abort.run(c.take)
                result <- fiber.get
            yield assert(result && take.isFailure)
        }

        "concurrent closeAwaitEmpty calls" in run {
            for
                c      <- Channel.init[Int](10)
                _      <- c.put(1)
                _      <- c.put(2)
                fiber  <- Async.run(Async.fill(10)(c.closeAwaitEmpty))
                _      <- c.take
                _      <- c.take
                closes <- fiber.get
            yield assert(closes.count(identity) == 1)
        }

        "race between closeAwaitEmpty and close" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                _       <- Kyo.foreach(1 to (size min 5))(i => channel.put(i))
                latch   <- Latch.init(1)
                closeAwaitEmptyFiber <- Async.run(
                    latch.await.andThen(channel.closeAwaitEmpty)
                )
                closeFiber <- Async.run(
                    latch.await.andThen(channel.close)
                )
                _        <- latch.release
                _        <- Abort.run(channel.drain)
                result1  <- closeAwaitEmptyFiber.get
                result2  <- closeFiber.get
                isClosed <- channel.closed
            yield
                assert(isClosed)
                assert((result1 && result2.isEmpty) || (!result1 && result2.isDefined))
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "two producers calling closeAwaitEmpty" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                producerFiber1 <- Async.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(channel.put(i)))
                            .andThen(channel.closeAwaitEmpty)
                    )
                )
                producerFiber2 <- Async.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(channel.put(i)))
                            .andThen(channel.closeAwaitEmpty)
                    )
                )

                consumerFiber <- Async.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(Abort.run(channel.take))
                    )
                )

                _        <- latch.release
                result1  <- producerFiber1.get
                result2  <- producerFiber2.get
                isClosed <- channel.closed

                consumerResults <- consumerFiber.get
            yield
                assert(isClosed)
                assert((!result1 && result2) || (result1 && !result2))
                assert(consumerResults.count(_.isSuccess) <= 50)
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }

        "producer calling closeAwaitEmpty and another calling close" in run {
            (for
                size    <- Choice.eval(0, 1, 2, 10, 100)
                channel <- Channel.init[Int](size)
                latch   <- Latch.init(1)

                producerFiber1 <- Async.run(
                    latch.await.andThen(
                        Async.foreach(1 to 25, 10)(i => Abort.run(channel.put(i)))
                            .andThen(channel.closeAwaitEmpty)
                    )
                )
                producerFiber2 <- Async.run(
                    latch.await.andThen(
                        Async.foreach(26 to 50, 10)(i => Abort.run(channel.put(i)))
                            .andThen(channel.close)
                    )
                )

                consumerFiber <- Async.run(
                    latch.await.andThen(
                        Async.fill(100, 10)(Abort.run(channel.take))
                    )
                )

                _               <- latch.release
                result1         <- producerFiber1.get
                result2         <- producerFiber2.get
                isClosed        <- channel.closed
                consumerResults <- consumerFiber.get
            yield
                assert(isClosed)
                assert((result1 && result2.isEmpty) || (!result1 && result2.isDefined))
                assert(consumerResults.count(_.isSuccess) <= 50)
            )
                .handle(Choice.run, _.unit, Loop.repeat(10))
                .andThen(succeed)
        }
    }

    "pendingPuts and pendingTakes" - {
        "should return 0 for empty channel" in run {
            for
                c     <- Channel.init[Int](2)
                puts  <- c.pendingPuts
                takes <- c.pendingTakes
            yield assert(puts == 0 && takes == 0)
        }

        "should count pending puts when channel is full" in run {
            for
                c     <- Channel.init[Int](2)
                _     <- c.put(1)
                _     <- c.put(2)
                f1    <- Async.run(c.put(3))
                f2    <- Async.run(c.put(4))
                _     <- Async.sleep(10.millis)
                puts  <- c.pendingPuts
                takes <- c.pendingTakes
                _     <- c.take
                _     <- c.take
                _     <- f1.get
                _     <- f2.get
            yield assert(puts == 2 && takes == 0)
        }

        "should count pending takes when channel is empty" in run {
            for
                c     <- Channel.init[Int](2)
                f1    <- Async.run(c.take)
                f2    <- Async.run(c.take)
                _     <- Async.sleep(10.millis)
                puts  <- c.pendingPuts
                takes <- c.pendingTakes
                _     <- c.put(1)
                _     <- c.put(2)
                _     <- f1.get
                _     <- f2.get
            yield assert(puts == 0 && takes == 2)
        }

        "should fail when channel is closed" in run {
            for
                c     <- Channel.init[Int](2)
                _     <- c.close
                puts  <- Abort.run(c.pendingPuts)
                takes <- Abort.run(c.pendingTakes)
            yield assert(puts.isFailure && takes.isFailure)
        }
    }

    private def verifyRaceDrainWithClose(
        capacity: Int,
        drain: Channel[Int] => Any < (Abort[Closed] & Sync),
        close: Channel[Int] => (Any < Async)
    ) =
        for
            c0  <- Channel.init[Int](capacity)
            ref <- AtomicRef.init(c0)
            // Create a fiber that repeatedly puts and item and then checks to see if the channel
            // has been drained. If it has then it closes the channel and creates a new one.
            producer <- Async.run {
                Loop.foreach:
                    for
                        c     <- ref.get
                        _     <- c.put(1)
                        empty <- c.empty
                        _     <-
                            // If it is empty then it could be that the consumer is in the middle of
                            // draining. Attempt to close the channel right before the consumer
                            // checks for more items.
                            if empty then
                                for
                                    c2 <- Channel.init[Int](capacity)
                                    _  <- ref.set(c2)
                                    _  <- close(c)
                                yield ()
                            else Kyo.unit
                            end if
                    yield Loop.continue
            }
            // Create a fiber that repeatedly drains the channel if it is not closed or empty.
            // If it is closed or empty (and is about to be closed) then repeat until the consumer
            // creates a new channel.
            result <- Abort.run {
                Async.fill(100_000, concurrency = 1) {
                    for
                        c             <- ref.get
                        closedOrEmpty <- Abort.recover[Closed](_ => true)(c.empty)
                        _             <- if closedOrEmpty then Kyo.unit else drain(c)
                    yield ()
                }
            }
            _ <- producer.interrupt
        yield assert(result.isSuccess)
        end for
    end verifyRaceDrainWithClose

end ChannelTest
