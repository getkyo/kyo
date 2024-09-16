package kyo

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
            v1 <- Abort.run[Throwable](q.size)
            v2 <- Abort.run[Throwable](q.empty)
            v3 <- Abort.run[Throwable](q.full)
            v4 <- q.offer(2)
            v5 <- Abort.run[Throwable](q.poll)
            v6 <- Abort.run[Throwable](q.peek)
            v7 <- Abort.run[Throwable](q.drain)
            c2 <- q.close
        yield assert(
            b && c1 == Maybe(Seq(1)) &&
                v1.isFail &&
                v2.isFail &&
                v3.isFail &&
                !v4 &&
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
                        q <- Queue.initUnbounded[Int](access)
                        b <- q.empty
                    yield assert(b)
                }
                "offer and poll" in run {
                    for
                        q <- Queue.initUnbounded[Int](access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Maybe(1))
                }
                "peek" in run {
                    for
                        q <- Queue.initUnbounded[Int](access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Maybe(1))
                }
                "add and poll" in run {
                    for
                        q <- Queue.initUnbounded[Int](access)
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
                    q <- Queue.initDropping[Int](2)
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
                    q <- Queue.initSliding[Int](2)
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
end QueueTest
