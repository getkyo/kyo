package kyo.internal

import kyo.*
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Try

trait BaseKyoTest:

    type Assertion

    def success: Assertion

    given tryCanEqual[T]: CanEqual[Try[T], Try[T]]                   = CanEqual.derived
    given eitherCanEqual[T, U]: CanEqual[Either[T, U], Either[T, U]] = CanEqual.derived
    given throwableCanEqual: CanEqual[Throwable, Throwable]          = CanEqual.derived

    def retry[S](f: => Boolean < S): Boolean < S =
        def loop(): Boolean < S =
            f.map {
                case true  => true
                case false => loop()
            }
        loop()
    end retry

    def timeout =
        if kyo.internal.Platform.isDebugEnabled then
            Duration.Infinity
        else
            5.seconds

    implicit def assertionToFuture[S](a: Assertion): Future[Assertion] < S = Future.successful(a)

    def runJVM(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if kyo.internal.Platform.isJVM then
            run(v)
        else
            Future.successful(success)

    def runJS(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        if kyo.internal.Platform.isJS then
            run(v)
        else
            Future.successful(success)

    def run(
        v: => Assertion < KyoApp.Effects
    ): Future[Assertion] =
        val a = KyoApp.runFiber(timeout)(v)
        val b = IOs.run(a.toFuture)
        val c = b.map(_.get)(using Platform.executionContext)
        println((a, b, c))
        c
        // IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get)(using Platform.executionContext)
    end run

end BaseKyoTest
