package kyoTest.concurrent

import kyo.concurrent.channels._
import kyo.concurrent.fibers._
import kyo.concurrent.queues._
import kyo.concurrent.timers._
import kyo._
import kyo.ios._
import kyoTest.KyoTest

import scala.concurrent.duration._

class channelsTest extends KyoTest {

  "offer and poll" in run {
    for {
      c <- Channels.init[Int](2)
      b <- c.offer(1)
      v <- c.poll
    } yield assert(b && v == Some(1))
  }
  "put and take" in run {
    for {
      c <- Channels.init[Int](2)
      _ <- c.put(1)
      v <- c.take
    } yield assert(v == 1)
  }
  "offer, put, and take" in run {
    for {
      c  <- Channels.init[Int](2)
      b  <- c.offer(1)
      _  <- c.put(2)
      v1 <- c.take
      v2 <- c.take
    } yield assert(b && v1 == 1 && v2 == 2)
  }
  "offer, put, and poll" in run {
    for {
      c  <- Channels.init[Int](2)
      b  <- c.offer(1)
      _  <- c.put(2)
      v1 <- c.poll
      v2 <- c.poll
      b2 <- c.isEmpty
    } yield assert(b && v1 == Some(1) && v2 == Some(2) && b2)
  }
  "offer, put, and take in parallel" in runJVM {
    for {
      c     <- Channels.init[Int](2)
      b     <- Fibers.fork(c.offer(1))
      put   <- Fibers.fork(c.putFiber(2))
      f     <- c.isFull
      take1 <- Fibers.fork(c.takeFiber)
      take2 <- Fibers.fork(c.takeFiber)
      v1    <- take1.get
      _     <- put.get
      v2    <- take1.get
      v3    <- take2.get
    } yield assert(b && f && v1 == 1 && v2 == 1 && v3 == 2)
  }
  "blocking put" in runJVM {
    for {
      c  <- Channels.init[Int](2)
      _  <- c.put(1)
      _  <- c.put(2)
      f  <- c.putFiber(3)
      _  <- Fibers.sleep(10.millis)
      d1 <- f.isDone
      v1 <- c.poll
      d2 <- f.isDone
      v2 <- c.poll
      v3 <- c.poll
    } yield assert(!d1 && d2 && v1 == Some(1) && v2 == Some(2) && v3 == Some(3))
  }
  "blocking take" in runJVM {
    for {
      c  <- Channels.init[Int](2)
      f  <- c.takeFiber
      _  <- Fibers.sleep(10.millis)
      d1 <- f.isDone
      _  <- c.put(1)
      d2 <- f.isDone
      v  <- f.get
    } yield assert(!d1 && d2 && v == 1)
  }
  "no buffer" in runJVM {
    for {
      c <- Channels.init[Int](0)
      _ <- c.putFiber(1)
      v <- c.take
      f <- c.isFull
      e <- c.isEmpty
    } yield assert(v == 1 && f && e)
  }
  "contention" - {
    "with buffer" in runJVM {
      for {
        c  <- Channels.init[Int](10)
        f1 <- Fibers.parallelFiber(List.fill(1000)(Fibers.parallel(List.fill(100)(c.put(1)))))
        f2 <- Fibers.parallelFiber(List.fill(1000)(Fibers.parallel(List.fill(100)(c.take))))
        _  <- f1.get
        _  <- f2.get
        b  <- c.isEmpty
      } yield assert(b)
    }

    "no buffer" in runJVM {
      for {
        c  <- Channels.init[Int](0)
        f1 <- Fibers.parallelFiber(List.fill(100)(c.put(1)))
        f2 <- Fibers.parallelFiber(List.fill(100)(c.take))
        _  <- f1.get
        _  <- f2.get
        b  <- c.isEmpty
      } yield assert(b)
    }
  }
}
