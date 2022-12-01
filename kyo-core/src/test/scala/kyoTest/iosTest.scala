package kyoTest

import kyo.core._
import kyo.ios._
import kyo.options._

import scala.concurrent.duration._

class iosTest extends KyoTest {

  "shallow handle" - {
    "defers execution" in {
      var called = false
      val v =
        IOs {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          (v < IOs)(_.run()),
          1
      )
      assert(called)
    }
    "next handled effects can execute IO" in {
      var called = false
      val v =
        (Option(1) > Options) { i =>
          IOs {
            called = true
            i
          }
        }
      assert(!called)
      val v2 = (v < IOs)(_.run())
      assert(!called)
      checkEquals[Option[Int], Nothing](
          v2 < Options,
          Option(1)
      )
      assert(called)
    }
    "runFor" - {
      "done" in {
        checkEquals[Either[IO[Int], Int], Nothing](
            (IOs(1)(_ + 1) << IOs)(_.runFor(1.minute)),
            Right(2)
        )
      }
      "always done" in {
        val io = IOs {
          Thread.sleep(100)
          1
        } { i =>
          IOs(i + 1)
        }
        (io < IOs).runFor(1.millis) match {
          case Left(rest) =>
            fail(
                "shallow handle's returned IO should represent only the last step of the computation"
            )
          case Right(done) =>
            assert(done == 2)
        }
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
          (loop(0) < IOs).run(),
          frames
      )
    }
  }
  "deep handle" - {
    "defers execution" in {
      var called = false
      val v =
        IOs {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          (v << IOs)(_.run()),
          1
      )
      assert(called)
    }
    "runFor" - {
      "done" in {
        checkEquals[Either[IO[Int], Int], Nothing](
            (IOs(1)(_ + 1) << IOs)(_.runFor(1.minute)),
            Right(2)
        )
      }
      "not done" in {
        val io = IOs {
          Thread.sleep(100)
          1
        } { i =>
          IOs(i + 1)
        }
        (io << IOs).runFor(1.millis) match {
          case Left(rest) =>
            assert(rest.run() == 2)
          case Right(done) =>
            fail("should not be done")
        }
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
          (loop(0) << IOs).run(),
          frames
      )
    }
  }
}
