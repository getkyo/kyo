package kyoTest

import kyo.*
import kyo.Access

class queuesTest extends KyoTest:

    val access = List(Access.Mpmc, Access.Mpsc, Access.Spmc, Access.Spsc)

    "bounded" - {
        access.foreach { access =>
            access.toString() - {
                "isEmpty" in run {
                    for
                        q <- Queues.init[Int](2, access)
                        b <- q.isEmpty
                    yield assert(b && q.capacity == 2)
                }
                "offer and poll" in run {
                    for
                        q <- Queues.init[Int](2, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Some(1))
                }
                "peek" in run {
                    for
                        q <- Queues.init[Int](2, access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Some(1))
                }
                "full" in run {
                    for
                        q <- Queues.init[Int](2, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        b <- q.offer(3)
                    yield assert(!b)
                }
                "full 4" in run {
                    for
                        q <- Queues.init[Int](4, access)
                        _ <- q.offer(1)
                        _ <- q.offer(2)
                        _ <- q.offer(3)
                        _ <- q.offer(4)
                        b <- q.offer(5)
                    yield assert(!b)
                }
                "zero capacity" in run {
                    for
                        q <- Queues.init[Int](0, access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(!b && v == None)
                }
            }
        }
    }

    "close" in run {
        for
            q  <- Queues.init[Int](2)
            b  <- q.offer(1)
            c1 <- q.close
            v1 <- IOs.attempt(q.size)
            v2 <- IOs.attempt(q.isEmpty)
            v3 <- IOs.attempt(q.isFull)
            v4 <- IOs.attempt(q.offer(2))
            v5 <- IOs.attempt(q.poll)
            v6 <- IOs.attempt(q.peek)
            v7 <- IOs.attempt(q.drain)
            c2 <- q.close
        yield assert(
            b && c1 == Some(Seq(1)) &&
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

    "drain" in run {
        for
            q <- Queues.init[Int](2)
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
                        q <- Queues.initUnbounded[Int](access)
                        b <- q.isEmpty
                    yield assert(b)
                }
                "offer and poll" in run {
                    for
                        q <- Queues.initUnbounded[Int](access)
                        b <- q.offer(1)
                        v <- q.poll
                    yield assert(b && v == Some(1))
                }
                "peek" in run {
                    for
                        q <- Queues.initUnbounded[Int](access)
                        _ <- q.offer(1)
                        v <- q.peek
                    yield assert(v == Some(1))
                }
                "add and poll" in run {
                    for
                        q <- Queues.initUnbounded[Int](access)
                        _ <- q.add(1)
                        v <- q.poll
                    yield assert(v == Some(1))
                }
            }
        }
    }

    "dropping" - {
        access.foreach { access =>
            access.toString() in run {
                for
                    q <- Queues.initDropping[Int](2)
                    _ <- q.add(1)
                    _ <- q.add(2)
                    _ <- q.add(3)
                    a <- q.poll
                    b <- q.poll
                    c <- q.poll
                yield assert(a == Some(1) && b == Some(2) && c == None)
            }
        }
    }

    "sliding" - {
        access.foreach { access =>
            access.toString() in run {
                for
                    q <- Queues.initSliding[Int](2)
                    _ <- q.add(1)
                    _ <- q.add(2)
                    _ <- q.add(3)
                    a <- q.poll
                    b <- q.poll
                    c <- q.poll
                yield assert(a == Some(2) && b == Some(3) && c == None)
            }
        }
    }
end queuesTest
