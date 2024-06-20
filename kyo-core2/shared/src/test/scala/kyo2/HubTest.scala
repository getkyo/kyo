package kyo2

class HubTest extends Test:

    "listen/offer/take" in runJVM {
        for
            h <- Hubs.init[Int](2)
            l <- h.listen
            b <- h.offer(1)
            v <- l.take
        yield assert(b && v == 1)
    }
    "listen/listen/offer/take" in runJVM {
        for
            h  <- Hubs.init[Int](2)
            l1 <- h.listen
            l2 <- h.listen
            b  <- h.offer(1)
            v1 <- l1.take
            v2 <- l2.take
        yield assert(b && v1 == 1 && v2 == 1)
    }
    "listen/offer/listen/take/poll" in runJVM {
        for
            h  <- Hubs.init[Int](2)
            l1 <- h.listen
            b  <- h.offer(1)
            _  <- untilTrue(h.isEmpty) // wait transfer
            l2 <- h.listen
            v1 <- l1.take
            v2 <- l2.poll
        yield assert(b && v1 == 1 && v2.isEmpty)
    }
    "listen/offer/take/listen/poll" in runJVM {
        for
            h  <- Hubs.init[Int](2)
            l1 <- h.listen
            b  <- h.offer(1)
            v1 <- l1.take
            l2 <- h.listen
            v2 <- l2.poll
        yield assert(b && v1 == 1 && v2.isEmpty)
    }
    "offer/listen/poll" in runJVM {
        for
            h <- Hubs.init[Int](2)
            b <- h.offer(1)
            _ <- untilTrue(h.isEmpty) // wait transfer
            l <- h.listen
            v <- l.poll
        yield assert(b && v.isEmpty)
    }
    "close hub" in runJVM {
        for
            h  <- Hubs.init[Int](2)
            b  <- h.offer(1)
            _  <- untilTrue(h.isEmpty) // wait transfer
            l  <- h.listen
            c1 <- h.close
            v1 <- Abort.run(h.listen)
            v2 <- h.offer(2)
            v3 <- l.poll
            c2 <- l.close
        yield assert(
            b && c1 == Maybe(Seq()) && v1.isFail && !v2 && v3.isEmpty && c2.isEmpty
        )
    }
    "close listener w/ buffer" in runJVM {
        for
            h  <- Hubs.init[Int](2)
            l1 <- h.listen(2)
            b1 <- h.offer(1)
            _  <- untilTrue(l1.isEmpty.map(!_))
            c1 <- l1.close
            l2 <- h.listen(2)
            b2 <- h.offer(2)
            _  <- untilTrue(l2.isEmpty.map(!_))
            v2 <- l2.poll
            c2 <- l2.close
        yield assert(
            b1 && c1 == Maybe(Seq(1)) && b2 && v2 == Maybe(2) && c2 == Maybe(Seq())
        )
    }
    "offer beyond capacity" in runJVM {
        for
            h  <- Hubs.init[Int](2)
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
    "concurrent listeners taking values" in runJVM {
        for
            h  <- Hubs.init[Int](10)
            l1 <- h.listen
            l2 <- h.listen
            _  <- h.offer(1)
            v1 <- l1.take
            v2 <- l2.take
        yield assert(v1 == 1 && v2 == 1) // Assuming listeners take different values
    }
    "listener removal" in runJVM {
        for
            h <- Hubs.init[Int](2)
            l <- h.listen
            _ <- h.offer(1)
            _ <- untilTrue(h.isEmpty)
            c <- l.close
            _ <- h.offer(2)
            v <- l.poll
        yield assert(c == Maybe(Seq()) && v.isEmpty)
    }
    "hub closure with pending offers" in runJVM {
        for
            h <- Hubs.init[Int](2)
            _ <- h.offer(1)
            _ <- h.close
            v <- h.offer(2)
        yield assert(!v)
    }
    "create listener on empty hub" in runJVM {
        for
            h <- Hubs.init[Int](2)
            l <- h.listen
            v <- l.poll
        yield assert(v.isEmpty)
    }
    "contention" - {
        "writes" in runJVM {
            for
                h  <- Hubs.init[Int](2)
                l  <- h.listen
                _  <- Kyo.seq.fill(100)(Async.run(h.put(1)))
                t  <- Kyo.seq.fill(100)(l.take)
                e1 <- h.isEmpty
                e2 <- l.isEmpty
            yield assert(t == List.fill(100)(1) && e1 && e2)
        }
        "reads + writes" in runJVM {
            for
                h  <- Hubs.init[Int](2)
                l  <- h.listen
                _  <- Kyo.seq.fill(100)(Async.run(h.put(1)))
                t  <- Kyo.seq.fill(100)(Async.run(l.take).map(_.get))
                e1 <- h.isEmpty
                e2 <- l.isEmpty
            yield assert(t == List.fill(100)(1) && e1 && e2)
        }
        "listeners" in runJVM {
            for
                h  <- Hubs.init[Int](2)
                l  <- Kyo.seq.fill(100)(Async.run(h.listen).map(_.get))
                _  <- Async.run(h.put(1))
                t  <- Kyo.seq.map(l)(l => Async.run(l.take).map(_.get))
                e1 <- h.isEmpty
                e2 <- Kyo.seq.map(l)(_.isEmpty)
            yield assert(t == List.fill(100)(1) && e1 && e2 == Seq.fill(100)(true))
        }
    }
end HubTest
