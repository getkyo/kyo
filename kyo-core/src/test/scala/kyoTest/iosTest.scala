package kyoTest

import kyo.core._
import kyo.defers._
import kyo.options._

import scala.concurrent.duration._

class iosTest extends KyoTest {

  "shallow handle" - {
    "ios execution" in {
      var called = false
      val v =
        Defers {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          (v < Defers)(_.run()),
          1
      )
      assert(called)
    }
    "next handled effects can execute IO" in {
      var called = false
      val v =
        (Option(1) > Options) { i =>
          Defers {
            called = true
            i
          }
        }
      assert(!called)
      val v2 = (v < Defers)(_.run())
      assert(!called)
      checkEquals[Option[Int], Nothing](
          v2 < Options,
          Option(1)
      )
      assert(called)
    }
    "runFor" - {
      "done" in {
        checkEquals[Either[Defer[Int], Int], Nothing](
            (Defers(1)(_ + 1) << Defers)(_.runFor(1.minute)),
            Right(2)
        )
      }
      "always done" in {
        val io = Defers {
          Thread.sleep(100)
          1
        } { i =>
          Defers(i + 1)
        }
        (io < Defers).runFor(1.millis) match {
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
      def loop(i: Int): Int > Defers =
        Defers {
          if (i < frames)
            loop(i + 1)
          else
            i
        }
      checkEquals[Int, Nothing](
          (loop(0) < Defers).run(),
          frames
      )
    }
  }
  "deep handle" - {
    "ios execution" in {
      var called = false
      val v =
        Defers {
          called = true
          1
        }
      assert(!called)
      checkEquals[Int, Nothing](
          (v << Defers)(_.run()),
          1
      )
      assert(called)
    }
    "runFor" - {
      "done" in {
        checkEquals[Either[Defer[Int], Int], Nothing](
            (Defers(1)(_ + 1) << Defers)(_.runFor(1.minute)),
            Right(2)
        )
      }
      "not done" in {
        val io = Defers {
          Thread.sleep(100)
          1
        } { i =>
          Defers(i + 1)
        }
        (io << Defers).runFor(1.millis) match {
          case Left(rest) =>
            assert(rest.run() == 2)
          case Right(done) =>
            fail("should not be done")
        }
      }
    }
    "stack-safe" in {
      val frames = 100000
      def loop(i: Int): Int > Defers =
        Defers {
          if (i < frames)
            loop(i + 1)
          else
            i
        }
      checkEquals[Int, Nothing](
          (loop(0) << Defers).run(),
          frames
      )
    }
  }
}
