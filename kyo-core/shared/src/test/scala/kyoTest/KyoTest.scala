package kyoTest

import izumi.reflect._
import kyo.clocks._
import kyo.fibers._
import kyo.timers._
import kyo.consoles._
import kyo._
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
import org.scalatest.concurrent.ScalaFutures._
import scala.concurrent.ExecutionContext
import org.scalatest.time.Span
import org.scalatest.time.Seconds

class KyoTest extends AsyncFreeSpec with Assertions {

  implicit override def executionContext: ExecutionContext = Platform.executionContext

  trait Eq[T] {
    def apply(a: T, b: T): Boolean
  }
  object Eq {
    implicit def eq[T]: Eq[T] = _ == _
  }

  def retry[S](f: => Boolean < S): Boolean < S = {
    def loop(): Boolean < S =
      f.map {
        case true  => true
        case false => loop()
      }
    loop()
  }

  def timeout =
    if (Platform.isDebugEnabled) {
      Duration.Inf
    } else {
      5.seconds
    }

  implicit def toFuture(a: Assertion): Future[Assertion] = Future.successful(a)

  def runJVM(
      v: => Assertion < KyoApp.Effects
  ): Future[Assertion] =
    if (Platform.isJVM) {
      run(v)
    } else {
      Future.successful(succeed)
    }

  def runJS(
      v: => Assertion < KyoApp.Effects
  ): Future[Assertion] =
    if (Platform.isJS) {
      run(v)
    } else {
      Future.successful(succeed)
    }

  def run(
      v: => Assertion < KyoApp.Effects
  ): Future[Assertion] = {
    IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get)
  }

}
