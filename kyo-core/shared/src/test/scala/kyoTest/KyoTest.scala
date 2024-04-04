package kyoTest

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

class KyoTest extends AsyncFreeSpec with NonImplicitAssertions:

    override given executionContext: ExecutionContext = Platform.executionContext

    trait Eq[T]:
        def apply(a: T, b: T): Boolean
    object Eq:
        given eq[T]: Eq[T] = _ == _

    def retry[S](f: => Boolean < S): Boolean < S =
        def loop(): Boolean < S =
            f.map {
                case true  => true
                case false => loop()
            }
        loop()
    end retry

    def timeout =
        if Platform.isDebugEnabled then
            Duration.Inf
        else
            5.seconds

    implicit def toFuture(a: Assertion): Future[Assertion] = Future.successful(a)

    def runJVM(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if Platform.isJVM then
            run(v)
        else
            Future.successful(succeed)

    def runJS(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if Platform.isJS then
            run(v)
        else
            Future.successful(succeed)

    def run(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get)
end KyoTest
