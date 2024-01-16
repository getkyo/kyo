package kyo

import kyo._

import kyo.tries._
import kyoTest._
import scala.util._
import kyo.caches._

class cachesTest extends KyoTest {

  "sync" in run {
    var calls = 0
    for {
      c <- Caches.init(_.maxSize(4))
      m = c.memo { (v: Int) =>
        calls += 1
        v + 1
      }
      v1 <- m(1)
      v2 <- m(1)
    } yield assert(calls == 1 && v1 == 2 && v2 == 2)
  }

  "async" in run {
    var calls = 0
    for {
      c <- Caches.init(_.maxSize(4))
      m = c.memo { (v: Int) =>
        Fibers.init[Int] {
          calls += 1
          v + 1
        }.map(_.get)
      }
      v1 <- m(1)
      v2 <- m(1)
    } yield assert(calls == 1 && v1 == 2 && v2 == 2)
  }

  "failure" in run {
    val ex    = new Exception
    var calls = 0
    for {
      c <- Caches.init(_.maxSize(4))
      m = c.memo { (v: Int) =>
        Fibers.init[Int] {
          calls += 1
          if (calls == 1)
            IOs.fail(ex)
          else
            v + 1
        }.map(_.get)
      }
      v1 <- Tries.run(m(1))
      v2 <- Tries.run(m(1))
    } yield assert(calls == 2 && v1 == Failure(ex) && v2 == Success(2))
  }
}
