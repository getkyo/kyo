package kyoTest.concurrent

import kyo.concurrent.hubs._
import kyo.concurrent.fibers._
import kyo.concurrent.queues._
import kyo.concurrent.timers._
import kyo._
import kyo.ios._
import kyo.tries._
import kyoTest.KyoTest

import scala.concurrent.duration._
import kyo.lists.Lists

class hubsTest extends KyoTest {

  "listen/offer/take" in run {
    for {
      h <- Hubs.init[Int](2)
      l <- h.listen
      b <- h.offer(1)
      v <- l.take
    } yield assert(b && v == 1)
  }
  "listen/listen/offer/take" in run {
    for {
      h  <- Hubs.init[Int](2)
      l1 <- h.listen
      l2 <- h.listen
      b  <- h.offer(1)
      v1 <- l1.take
      v2 <- l2.take
    } yield assert(b && v1 == 1 && v2 == 1)
  }
  "listen/offer/listen/take/poll" in run {
    for {
      h  <- Hubs.init[Int](2)
      l1 <- h.listen
      b  <- h.offer(1)
      _  <- retry(h.isEmpty) // wait transfer
      l2 <- h.listen
      v1 <- l1.take
      v2 <- l2.poll
    } yield assert(b && v1 == 1 && v2 == None)
  }
  "listen/offer/take/listen/poll" in run {
    for {
      h  <- Hubs.init[Int](2)
      l1 <- h.listen
      b  <- h.offer(1)
      v1 <- l1.take
      l2 <- h.listen
      v2 <- l2.poll
    } yield assert(b && v1 == 1 && v2 == None)
  }
  "offer/listen/poll" in run {
    for {
      h <- Hubs.init[Int](2)
      b <- h.offer(1)
      _ <- retry(h.isEmpty) // wait transfer
      l <- h.listen
      v <- l.poll
    } yield assert(b && v == None)
  }
  "offer/listen/poll/offer/take" in run {
    for {
      h  <- Hubs.init[Int](2)
      b1 <- h.offer(1)
      _  <- retry(h.isEmpty) // wait transfer
      l  <- h.listen
      v1 <- l.poll
      b2 <- h.offer(2)
      v2 <- l.take
    } yield assert(b1 && v1 == None && b2 && v2 == 2)
  }
  "close hub" in run {
    for {
      h  <- Hubs.init[Int](2)
      b  <- h.offer(1)
      _  <- retry(h.isEmpty) // wait transfer
      l  <- h.listen
      c1 <- h.close
      v1 <- Tries.run(h.listen)
      v2 <- Tries.run(h.offer(2))
      v3 <- Tries.run(l.poll)
      c2 <- l.close
    } yield assert(
        b && c1 == Some(Seq()) && v1.isFailure && v2.isFailure && v3.isFailure && c2 == None
    )
  }
  "close listener w/ buffer" in run {
    for {
      h  <- Hubs.init[Int](2)
      l1 <- h.listen(2)
      b1 <- h.offer(1)
      _  <- retry(l1.isEmpty.map(!_))
      c1 <- l1.close
      l2 <- h.listen(2)
      b2 <- h.offer(2)
      _  <- retry(l2.isEmpty.map(!_))
      v2 <- l2.poll
      c2 <- l2.close
    } yield assert(
        b1 && c1 == Some(Seq(1)) && b2 && v2 == Some(2) && c2 == Some(Seq())
    )
  }
  "offer beyond capacity" in run {
    for {
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
    } yield assert(!b && v1 == 1 && v2 == 2 && v3 == 3 && v4.isEmpty)
  }
  "concurrent listeners taking values" in run {
    for {
      h  <- Hubs.init[Int](10)
      l1 <- h.listen
      l2 <- h.listen
      _  <- h.offer(1)
      v1 <- l1.take
      v2 <- l2.take
    } yield assert(v1 == 1 && v2 == 1) // Assuming listeners take different values
  }
  "listener removal" in run {
    for {
      h <- Hubs.init[Int](2)
      l <- h.listen
      _ <- h.offer(1)
      _ <- retry(h.isEmpty)
      c <- l.close
      _ <- h.offer(2)
      v <- Tries.run(l.poll)
    } yield assert(c == Some(Seq()) && v.isFailure)
  }
  "hub closure with pending offers" in run {
    for {
      h <- Hubs.init[Int](2)
      _ <- h.offer(1)
      _ <- h.close
      v <- Tries.run(h.offer(2))
    } yield assert(v.isFailure)
  }
  "create listener on empty hub" in run {
    for {
      h <- Hubs.init[Int](2)
      l <- h.listen
      v <- l.poll
    } yield assert(v.isEmpty)
  }
  "contention" - {
    "writes" in run {
      for {
        h  <- Hubs.init[Int](2)
        l  <- h.listen
        _  <- Lists.fill(100)(Fibers.fork(h.put(1)))
        t  <- Lists.fill(100)(l.take)
        e1 <- h.isEmpty
        e2 <- l.isEmpty
      } yield assert(t == List.fill(100)(1) && e1 && e2)
    }
    "reads + writes" in run {
      for {
        h  <- Hubs.init[Int](2)
        l  <- h.listen
        _  <- Lists.fill(100)(Fibers.fork(h.put(1)))
        t  <- Lists.fill(100)(Fibers.fork(l.take).map(_.get))
        e1 <- h.isEmpty
        e2 <- l.isEmpty
      } yield assert(t == List.fill(100)(1) && e1 && e2)
    }
    "listeners" in run {
      for {
        h  <- Hubs.init[Int](2)
        l  <- Lists.fill(100)(Fibers.fork(h.listen).map(_.get))
        _  <- Fibers.fork(h.put(1))
        t  <- Lists.traverse(l)(l => Fibers.fork(l.take).map(_.get))
        e1 <- h.isEmpty
        e2 <- Lists.traverse(l)(_.isEmpty)
      } yield assert(t == List.fill(100)(1) && e1 && e2 == Lists.fill(100)(true))
    }
  }

}
