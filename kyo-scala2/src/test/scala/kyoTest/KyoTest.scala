package kyoTest

import kyo.KyoApp
import kyo.aborts._
import kyo.clocks._
import kyo.concurrent.fibers._
import kyo.concurrent.timers._
import kyo.consoles._
import kyo.scala2._
import kyo.ios._
import kyo.randoms.Randoms
import kyo.resources._
import kyo.tries._
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.duration._
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import org.scalatest.compatible.Assertion
import scala.concurrent.ExecutionContext

class KyoTest extends AsyncFreeSpec with Assertions {

  trait Eq[T] {
    def apply(a: T, b: T): Boolean
  }
  object Eq {
    implicit def eq[T]: Eq[T] = _ == _
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

  implicit def toFuture(a: Assertion): Future[Assertion] = Future.successful(a)

  def runJVM(
      v: => Assertion > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers)
  ): Future[Assertion] =
    run(v)

  def runJS(
      v: => Assertion > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers)
  ): Future[Assertion] =
    run(v)

  def run(
      v: => Assertion > (IOs with Fibers with Resources with Clocks with Consoles with Randoms with Timers)
  ): Future[Assertion] = {
    val v1 = KyoApp.runFiber(timeout)(v)
    val v2 = v1.toFuture
    IOs.run(KyoApp.runFiber(timeout)(v).toFuture)
  }

  class Check[T, S](equals: Boolean) {
    def apply[T2, S2](value: T2 > S2, expected: Any): Assertion =
      // assert(t.tag =:= t2.tag, "value tag doesn't match")
      // assert(
      //     s2.tag =:= Tag[Any].tag || s.tag =:= Tag[Any].tag || s.tag =:= s2.tag,
      //     "effects tag doesn't match"
      // )
      if (equals)
        assert(Eq.eq(value.asInstanceOf[T], expected.asInstanceOf[T]))
      else
        assert(!Eq.eq(value.asInstanceOf[T], expected.asInstanceOf[T]))
  }

  def checkEquals[T, S]    = new Check[T, S](true)
  def checkNotEquals[T, S] = new Check[T, S](false)
}
