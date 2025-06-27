package kyo.internal

import java.util.concurrent.TimeoutException
import kyo.*
import kyo.kernel.Platform
import scala.annotation.targetName
import scala.concurrent.Future

private[kyo] trait BaseKyoKernelTest[S] extends BaseKyoDataTest:

    def run(v: Future[Assertion] < S)(using Frame): Future[Assertion]

    @targetName("runAssertion")
    def run(v: Assertion < S)(using Frame): Future[Assertion] = run(v.map(Future.successful(_)))

    @targetName("runJVMAssertion")
    def runJVM(v: => Assertion < S)(using Frame): Future[Assertion] = runJVM(v.map(Future.successful(_)))

    @targetName("runJSAssertion")
    def runJS(v: => Assertion < S)(using Frame): Future[Assertion] = runJS(v.map(Future.successful(_)))

    @targetName("runNotJSAssertion")
    def runNotJS(v: => Assertion < S)(using Frame): Future[Assertion] = runNotJS(v.map(Future.successful(_)))

    @targetName("runNativeAssertion")
    def runNative(v: => Assertion < S)(using Frame): Future[Assertion] = runNative(v.map(Future.successful(_)))

    @targetName("runNotNativeAssertion")
    def runNotNative(v: => Assertion < S)(using Frame): Future[Assertion] = runNative(v.map(Future.successful(_)))

    def runJVM(v: => Future[Assertion] < S)(using Frame): Future[Assertion] =
        if Platform.isJVM then
            run(v)
        else
            Future.successful(assertionSuccess)

    def runJS(v: => Future[Assertion] < S)(using Frame): Future[Assertion] =
        if Platform.isJS then
            run(v)
        else
            Future.successful(assertionSuccess)

    def runNotJS(v: => Future[Assertion] < S)(using Frame): Future[Assertion] =
        if !Platform.isJS then
            run(v)
        else
            Future.successful(assertionSuccess)

    def runNative(v: => Future[Assertion] < S)(using Frame): Future[Assertion] =
        if Platform.isNative then
            run(v)
        else
            Future.successful(assertionSuccess)

    def runNotNative(v: => Future[Assertion] < S)(using Frame): Future[Assertion] =
        if !Platform.isNative then
            run(v)
        else
            Future.successful(assertionSuccess)

    def timeout =
        if Platform.isDebugEnabled then
            Duration.Infinity
        else
            8.seconds
end BaseKyoKernelTest
