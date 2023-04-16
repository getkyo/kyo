import kyo.concurrent.queues._
import kyo.core._
import kyo.ios._
import kyoTest.KyoTest

class queuesTest extends KyoTest {

  "bounded" - {
    "isEmpty" in run {
      for {
        q <- Queues.bounded[Int](2)
        b <- q.isEmpty
      } yield assert(b)
    }
    "offer and poll" in run {
      for {
        q <- Queues.bounded[Int](2)
        b <- q.offer(1)
        v <- q.poll
      } yield assert(b && v == Some(1))
    }
    "peek" in run {
      for {
        q <- Queues.bounded[Int](2)
        _ <- q.offer(1)
        v <- q.peek
      } yield assert(v == Some(1))
    }
    "full" in run {
      for {
        q <- Queues.bounded[Int](2)
        _ <- q.offer(1)
        _ <- q.offer(2)
        b <- q.offer(3)
      } yield assert(!b)
    }
  }

  "unbounded" - {
    "isEmpty" in run {
      for {
        q <- Queues.unbounded[Int]()
        b <- q.isEmpty
      } yield assert(b)
    }
    "offer and poll" in run {
      for {
        q <- Queues.unbounded[Int]()
        b <- q.offer(1)
        v <- q.poll
      } yield assert(b && v == Some(1))
    }
    "peek" in run {
      for {
        q <- Queues.unbounded[Int]()
        _ <- q.offer(1)
        v <- q.peek
      } yield assert(v == Some(1))
    }
    "add and poll" in run {
      for {
        q <- Queues.unbounded[Int]()
        _ <- q.add(1)
        v <- q.poll
      } yield assert(v == Some(1))
    }
  }
}
