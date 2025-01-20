package kyo

class HubTest extends Test:

    "use" in runNotJS {
        Hub.initWith[Int](2) { h =>
            for
                l <- h.listen
                b <- h.offer(1)
                v <- l.take
            yield assert(b && v == 1)
        }
    }

    "listen/offer/take" in runNotJS {
        for
            h <- Hub.init[Int](2)
            l <- h.listen
            b <- h.offer(1)
            v <- l.take
        yield assert(b && v == 1)
    }
    "listen/listen/offer/take" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l1 <- h.listen
            l2 <- h.listen
            b  <- h.offer(1)
            v1 <- l1.take
            v2 <- l2.take
        yield assert(b && v1 == 1 && v2 == 1)
    }
    "listen/offer/listen/take/poll" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l1 <- h.listen
            b  <- h.offer(1)
            _  <- untilTrue(h.empty) // wait transfer
            l2 <- h.listen
            v1 <- l1.take
            v2 <- l2.poll
        yield assert(b && v1 == 1 && v2.isEmpty)
    }
    "listen/offer/take/listen/poll" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l1 <- h.listen
            b  <- h.offer(1)
            v1 <- l1.take
            l2 <- h.listen
            v2 <- l2.poll
        yield assert(b && v1 == 1 && v2.isEmpty)
    }
    "offer/listen/poll" in runNotJS {
        for
            h <- Hub.init[Int](2)
            b <- h.offer(1)
            _ <- untilTrue(h.empty) // wait transfer
            l <- h.listen
            v <- l.poll
        yield assert(b && v.isEmpty)
    }
    "takeExactly" in runNotJS {
        for
            h   <- Hub.init[Int](3)
            l   <- h.listen
            _   <- Kyo.foreachDiscard(Seq(1, 2, 3))(h.put(_))
            res <- l.takeExactly(3)
        yield assert(res == Chunk(1, 2, 3))
    }
    "close hub" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            b  <- h.offer(1)
            _  <- untilTrue(h.empty) // wait transfer
            l  <- h.listen
            c1 <- h.close
            v1 <- Abort.run(h.listen)
            v2 <- Abort.run(h.offer(2))
            v3 <- Abort.run(l.poll)
            v4 <- l.close
        yield assert(
            b && c1 == Maybe(Seq()) && v1.isFail && v2.isFail && v3.isFail && v4.isEmpty
        )
    }
    "close listener w/ buffer" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l1 <- h.listen(2)
            b1 <- h.offer(1)
            _  <- untilTrue(l1.empty.map(!_))
            c1 <- l1.close
            l2 <- h.listen(2)
            b2 <- h.offer(2)
            _  <- untilTrue(l2.empty.map(!_))
            v2 <- l2.poll
            c2 <- l2.close
        yield assert(
            b1 && c1 == Maybe(Seq(1)) && b2 && v2 == Maybe(2) && c2 == Maybe(Seq())
        )
    }
    "offer beyond capacity" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l  <- h.listen
            _  <- h.put(1)
            _  <- h.put(2)
            _  <- h.put(3)
            b  <- h.offer(4)
            v1 <- l.take
            v2 <- l.take
            v3 <- l.take
            v4 <- l.poll
        yield assert(!b && v1 == 1 && v2 == 2 && v3 == 3 && v4.isEmpty)
    }
    "concurrent listeners taking values" in runNotJS {
        for
            h  <- Hub.init[Int](10)
            l1 <- h.listen
            l2 <- h.listen
            _  <- h.offer(1)
            v1 <- l1.take
            v2 <- l2.take
        yield assert(v1 == 1 && v2 == 1) // Assuming listeners take different values
    }
    "listener removal" in runNotJS {
        for
            h  <- Hub.init[Int](2)
            l  <- h.listen
            _  <- h.offer(1)
            _  <- untilTrue(h.empty)
            c  <- l.close
            v1 <- h.offer(2)
            v2 <- Abort.run(l.poll)
        yield assert(c == Maybe(Seq()) && v1 && v2.isFail)
    }
    "hub closure with pending offers" in runNotJS {
        for
            h <- Hub.init[Int](2)
            _ <- h.offer(1)
            _ <- h.close
            v <- Abort.run(h.offer(2))
        yield assert(v.isFail)
    }
    "create listener on empty hub" in runNotJS {
        for
            h <- Hub.init[Int](2)
            l <- h.listen
            v <- l.poll
        yield assert(v.isEmpty)
    }
    "resource" in runNotJS {
        val effect = Resource.run:
            for
                h <- Hub.init[Int](2)
                l <- h.listen
                _ <- h.put(1)
                v <- l.take
            yield (h, l, v)
        effect.map:
            case (h, l, v) =>
                for
                    lClosed <- l.closed
                    hClosed <- h.closed
                yield assert(lClosed && !hClosed && v == 1)
    }
    "contention" - {
        "writes" in runNotJS {
            for
                h  <- Hub.init[Int](2)
                l  <- h.listen
                _  <- Kyo.fill(100)(Async.run(h.put(1)))
                t  <- Kyo.fill(100)(l.take)
                e1 <- h.empty
                e2 <- l.empty
            yield assert(t == List.fill(100)(1) && e1 && e2)
        }
        "reads + writes" in runNotJS {
            for
                h  <- Hub.init[Int](2)
                l  <- h.listen
                _  <- Kyo.fill(100)(Async.run(h.put(1)))
                t  <- Kyo.fill(100)(Async.run(l.take).map(_.get))
                e1 <- h.empty
                e2 <- l.empty
            yield assert(t == List.fill(100)(1) && e1 && e2)
        }
        "listeners" in runNotJS {
            for
                h  <- Hub.init[Int](2)
                l  <- Kyo.fill(100)(Async.run(h.listen).map(_.get))
                _  <- Async.run(h.put(1))
                t  <- Kyo.foreach(l)(l => Async.run(l.take).map(_.get))
                e1 <- h.empty
                e2 <- Kyo.foreach(l)(_.empty)
            yield assert(t == Chunk.fill(100)(1) && e1 && e2 == Chunk.fill(100)(true))
        }
    }
    "stream" - {
        "multiple listeners should stream the same elements" in runNotJS {
            for
                h  <- Hub.init[Int](2)
                l1 <- h.listen
                l2 <- h.listen
                l3 <- h.listen
                f  <- Async.run(Kyo.foreachDiscard(0 until 4)(h.put(_)))
                res <- Async.parallelUnbounded:
                    Seq(l1, l2, l3).map: l =>
                        l.stream().take(4).run
                empty <- h.empty
            yield assert(empty && res.size == 3 && res.forall(_ == Chunk(0, 1, 2, 3)))
        }
        "stream should end on closure" in runNotJS {
            val effect =
                for
                    h  <- Hub.init[Int](4)
                    l  <- h.listen
                    fp <- Async.run(Kyo.foreachDiscard(0 until 4)(h.put(_)))
                    _ <- l.stream().runForeach: v =>
                        Var.update[Chunk[Int]](_.append(v)).map: _ =>
                            if v == 3 then l.close.unit else ()
                    res <- Var.get[Chunk[Int]]
                yield res
            Var.run(Chunk.empty[Int])(effect).map: res =>
                assert(res == Chunk(0, 1, 2, 3))
        }
        "streamFailing should fail on closure" in runNotJS {
            val effect =
                for
                    h  <- Hub.init[Int](4)
                    l  <- h.listen
                    fp <- Async.run(Kyo.foreachDiscard(0 until 4)(h.put(_)))
                    _ <- l.streamFailing().runForeach: v =>
                        if v == 3 then l.close.unit else ()
                yield ()
            Abort.run[Closed](effect).map:
                case Result.Fail(_: Closed) => succeed
                case other                  => fail(s"Stream did not abort with Closed")
        }
    }
end HubTest
