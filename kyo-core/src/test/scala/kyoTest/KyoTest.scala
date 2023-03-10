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

class KyoTest extends AnyFreeSpec with Assertions {

  trait Eq[T] {
    def apply(a: T, b: T): Boolean
  }
  object Eq {
    given [T]: Eq[T] = _ == _
  }

  // def timeout = Duration.Inf
  def timeout = 10.seconds

  def run[T](v: T > (IOs | Fibers | Resources | Clocks | Consoles | Randoms | Timers)): T =
    KyoApp.run(timeout)(v)

  class Check[T, S](equals: Boolean)(using t: Tag[T], s: Tag[S], eq: Eq[T]) {
    def apply[T2, S2](value: T2 > S2, expected: Any)(using t2: Tag[T2], s2: Tag[S2]): Unit =
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
