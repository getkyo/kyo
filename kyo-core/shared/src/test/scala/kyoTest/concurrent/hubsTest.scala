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
}
