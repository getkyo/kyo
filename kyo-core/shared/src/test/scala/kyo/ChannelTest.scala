package kyo

class ChannelTest extends Test:

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
    "offer, put, and take in parallel" in runJVM {
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
    "blocking put" in runJVM {
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
    "blocking take" in runJVM {
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
    }
    "close" - {
        "empty" in runJVM {
            for
                c <- Channel.init[Int](2)
                r <- c.close
                t <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq()) && t.isFail)
        }
        "non-empty" in runJVM {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                r <- c.close
                t <- Abort.run(c.empty)
            yield assert(r == Maybe(Seq(1, 2)) && t.isFail)
        }
        "pending take" in runJVM {
            for
                c <- Channel.init[Int](2)
                f <- c.takeFiber
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.full)
            yield assert(r == Maybe(Seq()) && d.isFail && t.isFail)
        }
        "pending put" in runJVM {
            for
                c <- Channel.init[Int](2)
                _ <- c.put(1)
                _ <- c.put(2)
                f <- c.putFiber(3)
                r <- c.close
                d <- f.getResult
                e <- Abort.run(c.offer(1))
            yield assert(r == Maybe(Seq(1, 2)) && d.isFail && e.isFail)
        }
        "no buffer w/ pending put" in runJVM {
            for
                c <- Channel.init[Int](0)
                f <- c.putFiber(1)
                r <- c.close
                d <- f.getResult
                t <- Abort.run(c.poll)
            yield assert(r == Maybe(Seq()) && d.isFail && t.isFail)
        }
        "no buffer w/ pending take" in runJVM {
            for
                c <- Channel.init[Int](0)
                f <- c.takeFiber
                r <- c.close
                d <- f.getResult
                t <- Abort.run[Throwable](c.put(1))
            yield assert(r == Maybe(Seq()) && d.isFail && t.isFail)
        }
    }
    "no buffer" in runJVM {
        for
            c <- Channel.init[Int](0)
            _ <- c.putFiber(1)
            v <- c.take
            f <- c.full
            e <- c.empty
        yield assert(v == 1 && f && e)
    }
    "contention" - {
        "with buffer" in runJVM {
            for
                c  <- Channel.init[Int](10)
                f1 <- Fiber.parallelUnbounded(List.fill(1000)(c.put(1)))
                f2 <- Fiber.parallelUnbounded(List.fill(1000)(c.take))
                _  <- f1.get
                _  <- f2.get
                b  <- c.empty
            yield assert(b)
        }

        "no buffer" in runJVM {
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
                assert(drained.isFail)
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
    }

end ChannelTest
