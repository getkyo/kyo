package kyoTest.concurrent

import kyoTest.KyoTest

import kyo.core._
import kyo.ios._
import kyo.concurrent.channels._
import kyo.concurrent.fibers._
import kyo.concurrent.queues._
import kyo.concurrent.timers._
import scala.concurrent.duration._

class channelsTest extends KyoTest {

  private def run[T](io: T > (IOs | Fibers | Timers)): T =
    IOs.run((Fibers.run(IOs.lazyRun(Timers.run(io)))).block)

  "bounded" - {
    "offer and poll" in run {
      for {
        c <- Channel.bounded[Int](2)
        b <- c.offer(1)
        v <- c.poll
      } yield assert(b && v == Some(1))
    }
    "offer and poll in parallel" in run {
      for {
        c <- Channel.bounded[Int](2)
        b <- Fibers.fork(c.offer(1))
        v <- Fibers.fork(c.poll)
      } yield assert(b && v == Some(1))
    }
  }

  "unbounded" - {
    "offer and poll" in run {
      for {
        c <- Channel.unbounded[Int]()
        b <- c.offer(1)
        v <- c.poll
      } yield assert(b && v == Some(1))
    }
    "offer and poll in parallel" in run {
      for {
        c <- Channel.unbounded[Int]()
        b <- Fibers.fork(c.offer(1))
        v <- Fibers.fork(c.poll)
      } yield assert(b && v == Some(1))
    }
    "put and poll" in run {
      for {
        c <- Channel.unbounded[Int]()
        _ <- c.put(1)
        v <- c.poll
      } yield assert(v == Some(1))
    }
    "put and poll in parallel" in run {
      for {
        c <- Channel.unbounded[Int]()
        _ <- Fibers.fork(c.put(1))
        v <- Fibers.fork(c.poll)
      } yield assert(v == Some(1))
    }
    "offer, put, and poll" in run {
      for {
        c  <- Channel.unbounded[Int]()
        b  <- c.offer(1)
        _  <- c.put(2)
        v1 <- c.poll
        v2 <- c.poll
      } yield assert(b && v1 == Some(1) && v2 == Some(2))
    }
  }

  "dropping" in run {
    for {
      c  <- Channel.dropping[Int](2)
      _  <- c.put(1)
      b1 <- c.offer(2)
      b2 <- c.offer(3)
      _  <- c.put(4)
      v1 <- c.poll
      v2 <- c.poll
      v3 <- c.poll
    } yield assert(b1 && !b2 && v1 == Some(1) && v2 == Some(2) && v3 == None)
  }

  "sliding" in run {
    for {
      c  <- Channel.sliding[Int](2)
      _  <- c.put(1)
      b1 <- c.offer(2)
      b2 <- c.offer(3)
      _  <- c.put(4)
      v1 <- c.poll
      v2 <- c.poll
      v3 <- c.poll
    } yield assert(b1 && !b2 && v1 == Some(2) && v2 == Some(4) && v3 == None)
  }

  "blocking" - {
    "offer and poll" in run {
      for {
        c <- Channel.blocking[Int](2)
        b <- c.offer(1)
        v <- c.poll
      } yield assert(b && v == Some(1))
    }
    "put and take" in run {
      for {
        c <- Channel.blocking[Int](2)
        _ <- c.put(1)
        v <- c.take
      } yield assert(v == 1)
    }
    "offer, put, and take" in run {
      for {
        c  <- Channel.blocking[Int](2)
        b  <- c.offer(1)
        _  <- c.put(2)
        v1 <- c.take
        v2 <- c.take
      } yield assert(b && v1 == 1 && v2 == 2)
    }
    "offer, put, and poll" in run {
      for {
        c  <- Channel.blocking[Int](2)
        b  <- c.offer(1)
        _  <- c.put(2)
        v1 <- c.poll
        v2 <- c.poll
      } yield assert(b && v1 == Some(1) && v2 == Some(2))
    }
    "offer, put, and take in parallel" in run {
      for {
        c     <- Channel.blocking[Int](2)
        b     <- Fibers.fork(c.offer(1))
        put   <- Fibers.fork(c.putFiber(2))
        take1 <- Fibers.fork(c.takeFiber)
        take2 <- Fibers.fork(c.takeFiber)
        v1    <- take1.join
        _     <- put.join
        v2    <- take1.join
        v3    <- take2.join
      } yield assert(b && v1 == 1 && v2 == 1 && v3 == 2)
    }
    "blocking put" in run {
      for {
        c  <- Channel.blocking[Int](2)
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
    "blocking take" in run {
      for {
        c  <- Channel.blocking[Int](2)
        f  <- c.takeFiber
        _  <- Fibers.sleep(10.millis)
        d1 <- f.isDone
        _  <- c.put(1)
        d2 <- f.isDone
        v  <- f.join
      } yield assert(!d1 && d2 && v == 1)
    }
  }
}
