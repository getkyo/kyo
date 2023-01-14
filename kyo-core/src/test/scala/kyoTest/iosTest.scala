package kyoTest

import kyo.core._
import kyo.ios._
import kyo.options._
import kyo.tries._

import scala.concurrent.duration._
import scala.util.Try

class iosTest extends KyoTest {

  "lazyRun" - {
    "execution" in {
      var called = false
      val v =
        IOs {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          IOs.lazyRun(v),
          1
      )
      assert(called)
    }
    "next handled effects can execute" in {
      var called = false
      val v =
        (Option(1) > Options) { i =>
          IOs {
            called = true
            i
          }
        }
      assert(!called)
      val v2 = IOs.lazyRun(v)
      assert(!called)
      checkEquals[Option[Int], Nothing](
          v2 < Options,
          Option(1)
      )
      assert(called)
    }
    "failure" in {
      val ex        = new Exception
      def fail: Int = throw ex

      val ios = List(
          IOs(fail),
          IOs(fail)(_ + 1),
          IOs(1)(_ => fail),
          IOs(IOs(1))(_ => fail)
      )
      ios.foreach { io =>
        assert(Try(IOs.lazyRun(io)) == Try(fail))
      }
    }
    "stack-safe" in {
      val frames = 100000
      def loop(i: Int): Int > IOs =
        IOs {
          if (i < frames)
            loop(i + 1)
          else
            i
        }
      checkEquals[Int, Nothing](
          IOs.lazyRun(loop(0)),
          frames
      )
    }
    "stack-safe pure transforms" in {
      val frames = 10000
      var i      = IOs(0)
      for (_ <- 0 until frames) {
        i = i(_ + 1)
      }
      IOs.run(i)
    }
  }
  "run" - {
    "execution" in {
      var called = false
      val v: Int > IOs =
        IOs {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          IOs.run(v),
          1
      )
      assert(called)
    }
    "stack-safe" in {
      val frames = 100000
      def loop(i: Int): Int > IOs =
        IOs {
          if (i < frames)
            loop(i + 1)
          else
            i
        }
      IOs.run(loop(0))
    }
    "failure" in {
      val ex        = new Exception
      def fail: Int = throw ex

      val ios = List(
          IOs(fail),
          IOs(fail)(_ + 1),
          IOs(1)(_ => fail),
          IOs(IOs(1))(_ => fail)
      )
      ios.foreach { io =>
        assert(Try(IOs.run(io)) == Try(fail))
      }
    }
  }

  "eval" - {
    "done" in {
      val io = IOs.eval(() => false)(IOs(1)(_ + 1))
      assert(IOs.isDone(io))
      assert(IOs.run(io) == 2)
    }
    "not done" in {
      var steps = 0
      def loop(i: Int): Int > IOs =
        IOs {
          steps += 1
          if (i < 100)
            loop(i + 1)
          else
            i
        }

      val io = IOs.eval(() => steps == 50)(loop(0))
      assert(steps == 50)
      assert(!IOs.isDone(io))
      assert(IOs.run(io) == 100)
      assert(steps == 101)
    }
  }
}
