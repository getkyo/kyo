package kyoTest.concurrent

import kyo.concurrent.queues._
import kyo._
import kyo.ios._
import kyoTest.KyoTest

class queuesTest extends KyoTest {

  "bounded" - {
    "isEmpty" in run {
      for {
        q <- Queues.init[Int](2)
        b <- q.isEmpty
      } yield assert(b)
    }
    "offer and poll" in run {
      for {
        q <- Queues.init[Int](2)
        b <- q.offer(1)
        v <- q.poll
      } yield assert(b && v == Some(1))
    }
    "peek" in run {
      for {
        q <- Queues.init[Int](2)
        _ <- q.offer(1)
        v <- q.peek
      } yield assert(v == Some(1))
    }
    "full" in run {
      for {
        q <- Queues.init[Int](2)
        _ <- q.offer(1)
        _ <- q.offer(2)
        b <- q.offer(3)
      } yield assert(!b)
    }
    "zero capacity" in run {
      for {
        q <- Queues.init[Int](0)
        b <- q.offer(1)
        v <- q.poll
      } yield assert(!b && v == None)
    }
  }

  "unbounded" - {
    "isEmpty" in run {
      for {
        q <- Queues.initUnbounded[Int]()
        b <- q.isEmpty
      } yield assert(b)
    }
    "offer and poll" in run {
      for {
        q <- Queues.initUnbounded[Int]()
        b <- q.offer(1)
        v <- q.poll
      } yield assert(b && v == Some(1))
    }
    "peek" in run {
      for {
        q <- Queues.initUnbounded[Int]()
        _ <- q.offer(1)
        v <- q.peek
      } yield assert(v == Some(1))
    }
    "add and poll" in run {
      for {
        q <- Queues.initUnbounded[Int]()
        _ <- q.add(1)
        v <- q.poll
      } yield assert(v == Some(1))
    }
  }

  "dropping" in run {
    for {
      q <- Queues.initDropping[Int](2)
      _ <- q.add(1)
      _ <- q.add(2)
      _ <- q.add(3)
      a <- q.poll
      b <- q.poll
      c <- q.poll
    } yield assert(a == Some(1) && b == Some(2) && c == None)
  }

  "sliding" in run {
    for {
      q <- Queues.initSliding[Int](2)
      _ <- q.add(1)
      _ <- q.add(2)
      _ <- q.add(3)
      a <- q.poll
      b <- q.poll
      c <- q.poll
    } yield assert(a == Some(2) && b == Some(3) && c == None)
  }
}
