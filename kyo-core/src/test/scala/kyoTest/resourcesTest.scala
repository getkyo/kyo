package kyoTest

import kyo.core._
import kyo.ios._
import kyo.options._
import kyo.resources._

import java.io.Closeable

class resourcesTest extends KyoTest {

  trait Context {
    case class Resource(id: Int, var closes: Int = 0) extends Closeable {
      var acquires = 0
      def apply() = {
        acquires += 1
        this
      }
      def close() = closes += 1
    }

    val r1 = Resource(1)
    val r2 = Resource(2)
  }

  "acquire + close" in new Context {
    IOs.run {
      Resources.run(Resources.acquire(r1()))
    }
    assert(r1.closes == 1)
    assert(r2.closes == 0)
    assert(r1.acquires == 1)
    assert(r2.acquires == 0)
  }

  "acquire + tranform + close" in new Context {
    IOs.run {
      Resources.run(Resources.acquire(r1()).map(_ => assert(r1.closes == 0)))
    }
    assert(r1.closes == 1)
    assert(r2.closes == 0)
    assert(r1.acquires == 1)
    assert(r2.acquires == 0)
  }

  "acquire + effectful tranform + close" in new Context {
    val r =
      IOs.lazyRun {
        Resources.run(Resources.acquire(r1()).map { _ =>
          assert(r1.closes == 0)
          Option(1) > Options
        })
      }
    assert(r1.closes == 0)
    assert(r2.closes == 0)
    assert(r1.acquires == 1)
    assert(r2.acquires == 0)
    r < Options
    assert(r1.closes == 1)
    assert(r2.closes == 0)
    assert(r1.acquires == 1)
    assert(r2.acquires == 0)
  }

  "two acquires + close" in new Context {
    IOs.run {
      Resources.run(Resources.acquire(r1()).map(_ => Resources.acquire(r2())))
    }
    assert(r1.closes == 1)
    assert(r2.closes == 1)
    assert(r1.acquires == 1)
    assert(r2.acquires == 1)
  }

  "two acquires + for-comp + close" in new Context {
    val r: Int =
      IOs.run {
        Resources.run {
          for {
            r1 <- Resources.acquire(r1())
            i1 <- r1.id * 3
            r2 <- Resources.acquire(r2())
            i2 <- r2.id * 5
          } yield i1 + i2
        }
      }
    assert(r == 13)
    assert(r1.closes == 1)
    assert(r2.closes == 1)
    assert(r1.acquires == 1)
    assert(r2.acquires == 1)
  }

  "two acquires + effectful for-comp + close" in new Context {
    val r: Int > Options =
      IOs.lazyRun {
        Resources.run {
          val io: Int > (Resources | Options) =
            for {
              r1 <- Resources.acquire(r1())
              i1 <- Option(r1.id * 3) > Options
              r2 <- Resources.acquire(r2())
              i2 <- Option(r2.id * 3) > Options
            } yield i1 + i2
          io
        }
      }
    assert(r1.closes == 0)
    assert(r2.closes == 0)
    assert(r1.acquires == 1)
    assert(r2.acquires == 0)
    r < Options
    assert(r1.closes == 1)
    assert(r2.closes == 1)
    assert(r1.acquires == 1)
    assert(r2.acquires == 1)
  }

  "nested" in new Context {
    val r = IOs.run(Resources.run(Resources.run(Resources.acquire(r1()))))
    assert(r == r1)
    assert(r1.acquires == 1)
    assert(r1.closes == 1)
  }
}
