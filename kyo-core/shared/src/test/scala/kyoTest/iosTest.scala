package kyo

import kyoTest.KyoTest
import atomics.AtomicInt
import kyo._
import kyo.ios._
import kyo.options._
import kyo.tries._
import kyo.envs._

import java.io.Closeable
import scala.concurrent.duration._
import scala.util.Success
import scala.util.Try
import org.scalatest.compatible.Assertion
import scala.util.Failure

class iosTest extends KyoTest {

  "lazyRun" - {
    "execution" in run {
      var called = false
      val v =
        IOs {
          called = true
          1
        }
      assert(!called)
      assert(IOs.runLazy(v) == 1)
      assert(called)
    }
    "next handled effects can execute" in run {
      var called = false
      val v =
        Envs[Int].get.map { i =>
          IOs {
            called = true
            i
          }
        }
      assert(!called)
      val v2 = IOs.runLazy(v)
      assert(!called)
      assert(
          Envs[Int].run(1)(v2) ==
            1
      )
      assert(called)
    }
    "failure" in run {
      val ex        = new Exception
      def fail: Int = throw ex

      val ios = List(
          IOs(fail),
          IOs(fail).map(_ + 1),
          IOs(1).map(_ => fail),
          IOs(IOs(1)).map(_ => fail)
      )
      ios.foreach { io =>
        assert(Try(IOs.runLazy(io)) == Try(fail))
      }
      succeed
    }
    "stack-safe" in run {
      val frames = 100000
      def loop(i: Int): Int < IOs =
        IOs {
          if (i < frames)
            loop(i + 1)
          else
            i
        }
      assert(
          IOs.runLazy(loop(0)) ==
            frames
      )
    }
  }
  "run" - {
    "execution" in run {
      var called = false
      val v: Int < IOs =
        IOs {
          called = true
          1
        }
      assert(!called)
      assert(
          IOs.run[Int](v) ==
            1
      )
      assert(called)
    }
    "stack-safe" in run {
      val frames = 100000
      def loop(i: Int): Assertion < IOs =
        IOs {
          if (i < frames)
            loop(i + 1)
          else
            succeed
        }
      loop(0)
    }
    "failure" in run {
      val ex        = new Exception
      def fail: Int = throw ex

      val ios = List(
          IOs(fail),
          IOs(fail).map(_ + 1),
          IOs(1).map(_ => fail),
          IOs(IOs(1)).map(_ => fail)
      )
      ios.foreach { io =>
        assert(Try(IOs.run(io)) == Try(fail))
      }
      succeed
    }
    "doesn't accept other pending effects" in {
      assertDoesNotCompile("IOs.run[Int < Options](Options.get(Some(1)))")
    }
  }

  "ensure" - {
    "success" in {
      var called = false
      assert(
          Tries.run(IOs.run(IOs.ensure[Int, Any] { called = true }(1))) ==
            Try(1)
      )
      assert(called)
    }
    "failure" in {
      val ex     = new Exception
      var called = false
      assert(
          Tries.run(IOs.run(IOs.ensure { called = true } {
            IOs[Int, Any](throw ex)
          })) ==
            Failure(ex)
      )
      assert(called)
    }
  }
}
