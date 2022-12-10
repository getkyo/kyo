package kyoTest

import kyo.core._
import kyo.ios._
import kyo.tries._
import kyo.options._

import scala.concurrent.duration._
import scala.util.Try

class iosTest extends KyoTest {

  "execution" in {
    var called = false
    val v =
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
    val v2 = IOs.run(v)
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
        IOs.tryRun(IOs(fail)) < Tries,
        Try(throw ex)
    )
    checkEquals[Try[Int], Nothing](
        IOs.tryRun(IOs(fail)(_ + 1)) < Tries,
        Try(throw ex)
    )
    checkEquals[Try[Int], Nothing](
        IOs.tryRun(IOs(1)(_ => fail)) < Tries,
        Try(throw ex)
    )
    checkEquals[Try[Int], Nothing](
        IOs.tryRun(IOs(IOs(1))(_ => fail)) < Tries,
        Try(throw ex)
    )
    checkEquals[Try[Int], Nothing](
        IOs.tryRun(IOs(IOs(1)(_ => fail))) < Tries,
        Try(throw ex)
    )
    checkEquals[Try[Int], Nothing](
        IOs.tryRun(IOs(1)(_ => IOs(fail))) < Tries,
        Try(throw ex)
    )
  }
}
