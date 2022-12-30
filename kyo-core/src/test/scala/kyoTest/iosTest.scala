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
    //   "runFor" - {
    //     "done" in {
    //       checkEquals[Either[IO[Int], Int], Nothing](
    //           (IOs(1)(_ + 1) << IOs)(_.runFor(1.minute)),
    //           Right(2)
    //       )
    //     }
    //     "always done" in {
    //       val io = IOs {
    //         Thread.sleep(100)
    //         1
    //       } { i =>
    //         IOs(i + 1)
    //       }
    //       (io < IOs).runFor(1.millis) match {
    //         case Left(rest) =>
    //           fail(
    //               "shallow handle's returned IO should represent only the last step of the computation"
    //           )
    //         case Right(done) =>
    //           assert(done == 2)
    //       }
    //     }
    //   }
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
      val r: Int = IOs.run(i)
      print(r)
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
    // "runFor" - {
    //   "done" in {
    //     checkEquals[Either[IO[Int], Int], Nothing](
    //         (IOs(1)(_ + 1) << IOs)(_.runFor(1.minute)),
    //         Right(2)
    //     )
    //   }
    //   "not done" in {
    //     def loop(i: Int): Int > IOs =
    //       if (i < 100)
    //         IOs {
    //           Thread.sleep(1)
    //           loop(i + 1)
    //         }
    //       else
    //         i
    //     val start = System.currentTimeMillis()
    //     (loop(0) << IOs).runFor(1.millis) match {
    //       case Left(rest) =>
    //         val delay = System.currentTimeMillis() - start
    //         assert(delay < 20)
    //         assert(rest.run() == 100)
    //       case Right(done) =>
    //         fail("should not be done")
    //     }
    //   }
    // }
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
}
