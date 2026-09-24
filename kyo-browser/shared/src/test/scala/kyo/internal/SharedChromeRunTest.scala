package kyo.internal

import kyo.*

/** [[SharedChrome.Instance.run]] over a real connection: each launch of the instance under test hands out the suite's shared Chrome (or,
  * where a leaf asks for it, an address nothing listens on), so replacing a generation never kills a Chrome another leaf uses.
  */
class SharedChromeRunTest extends kyo.BrowserTest:

    private val unreachable = "ws://127.0.0.1:1/devtools/browser/none"

    private def instance(launches: AtomicInt, deadFirst: Boolean): SharedChrome.Instance =
        new SharedChrome.Instance(
            frame =>
                given Frame = frame
                launches.incrementAndGet.map(n => if deadFirst && n == 1 then unreachable else SharedChrome.init)
            ,
            _ => Kyo.unit
        )

    "a body that has started is not run again when the connection is lost inside it" in {
        for
            launches <- AtomicInt.init(0)
            runs     <- AtomicInt.init(0)
            shared = instance(launches, deadFirst = false)
            result <- Abort.run[BrowserReadException | BrowserSetupException] {
                shared.run { _ =>
                    runs.incrementAndGet.andThen(Abort.fail(BrowserConnectionLostException("connection lost inside the body")))
                }
            }
            count    <- runs.get
            launched <- launches.get
        yield
            assert(count == 1, s"the body should run once, ran $count times")
            assert(launched == 1, s"a body that started is not retried, so nothing is relaunched for it, got $launched launches")
            result match
                case Result.Failure(e: BrowserConnectionLostException) => assert(e.getMessage.contains("connection lost inside the body"))
                case other                                             => fail(s"expected the body's connection loss, got $other")
            end match
    }

    "a connection that fails before the body starts is retried against a relaunch" in {
        for
            launches <- AtomicInt.init(0)
            runs     <- AtomicInt.init(0)
            shared = instance(launches, deadFirst = true)
            url      <- shared.run(_ => runs.incrementAndGet.andThen(Browser.url))
            count    <- runs.get
            launched <- launches.get
        yield
            assert(url == "about:blank", s"the body should run in a fresh tab, got '$url'")
            assert(count == 1, s"the body should run once, ran $count times")
            assert(launched == 2, s"the unreachable first launch should be replaced by one relaunch, got $launched launches")
    }

end SharedChromeRunTest
