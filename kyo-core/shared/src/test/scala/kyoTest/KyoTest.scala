package kyoTest

import izumi.reflect._
import kyo.KyoApp
import kyo.aborts._
import kyo.clocks._
import kyo.concurrent.fibers._
import kyo.concurrent.timers._
import kyo.consoles._
import kyo.core._
import kyo.ios._
import kyo.randoms.Randoms
import kyo.resources._
import kyo.tries._
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

import scala.compiletime.erasedValue
import scala.compiletime.testing.typeChecks
import scala.concurrent.duration._
import scala.quoted.*
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import org.scalatest.compatible.Assertion
import scala.concurrent.ExecutionContext

class KyoTest extends AsyncFreeSpec with Assertions {

  implicit override def executionContext = Platform.executionContext

  trait Eq[T] {
    def apply(a: T, b: T): Boolean
  }
  object Eq {
    given [T]: Eq[T] = _ == _
  }

  def retry[S](f: => Boolean > S): Boolean > S = {
    def loop(): Boolean > S =
      f.map {
        case true  => true
        case false => loop()
      }
    loop()
  }

  // def timeout = Duration.Inf
  def timeout = 10.seconds

  given Conversion[Assertion, Future[Assertion]] = (a: Assertion) => Future.successful(a)

  def runJVM(v: => Assertion > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers))
      : Future[Assertion] =
    if (Platform.isJVM) {
      run(v)
    } else {
      Future.successful(succeed)
    }

  def runJS(v: => Assertion > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers))
      : Future[Assertion] =
    if (Platform.isJS) {
      run(v)
    } else {
      Future.successful(succeed)
    }

  def run(v: => Assertion > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers))
      : Future[Assertion] =
    IOs.run(KyoApp.runFiber(timeout)(v).toFuture)

  class Check[T, S](equals: Boolean)(using t: Tag[T], s: Tag[S], eq: Eq[T]) {
    def apply[T2, S2](value: T2 > S2, expected: Any)(using t2: Tag[T2], s2: Tag[S2]): Assertion =
      assert(t.tag =:= t2.tag, "value tag doesn't match")
      assert(
          s2.tag =:= Tag[Any].tag || s.tag =:= Tag[Nothing].tag || s.tag =:= s2.tag,
          "effects tag doesn't match"
      )
      if (equals)
        assert(eq(value.asInstanceOf[T], expected.asInstanceOf[T]))
      else
        assert(!eq(value.asInstanceOf[T], expected.asInstanceOf[T]))
  }

  def checkEquals[T, S](using t: Tag[T], s: Tag[S], eq: Eq[T])    = new Check[T, S](true)
  def checkNotEquals[T, S](using t: Tag[T], s: Tag[S], eq: Eq[T]) = new Check[T, S](false)
}
