package kyoTest

import kyo.core._
import kyo.defers._
import kyo.options._

import scala.concurrent.duration._
import scala.util.Try

class defersTest extends KyoTest {

  "shallow handle" - {
    "execution" in {
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
    "next handled effects can execute" in {
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
    "failure" in {
      val ex        = new Exception
      def fail: Int = throw ex
      checkEquals[Try[Int], Nothing](
          (Defers(fail) < Defers)(d => Try(d.run())),
          Try(throw ex)
      )
      checkEquals[Try[Int], Nothing](
          (Defers(fail)(_ + 1) < Defers)(d => Try(d.run())),
          Try(throw ex)
      )
      checkEquals[Try[Int], Nothing](
          (Defers(1)(_ => fail) < Defers)(d => Try(d.run())),
          Try(throw ex)
      )
      checkEquals[Try[Int], Nothing](
          (Defers(Defers(1))(_ => fail) < Defers)(d => Try(d.run())),
          Try(throw ex)
      )
      checkEquals[Try[Int], Nothing](
          (Defers(1)(_ => Defers(fail)) < Defers)(d => Try(d.run())),
          Try(throw ex)
      )
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
    "stack-safe pure transforms" in {
      val frames = 10
      var i      = Defers(0)
      for (_ <- 0 until frames) {
        i = i(_ + 1)
      }
      checkEquals[Int, Nothing](
          (i < Defers).run(),
          frames
      )
    }
  }
  "deep handle" - {
    "execution" in {
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
        def loop(i: Int): Int > Defers =
          if (i < 100)
            Defers {
              Thread.sleep(1)
              loop(i + 1)
            }
          else
            i
        val start = System.currentTimeMillis()
        (loop(0) << Defers).runFor(1.millis) match {
          case Left(rest) =>
            val delay = System.currentTimeMillis() - start
            assert(delay < 20)
            assert(rest.run() == 100)
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
