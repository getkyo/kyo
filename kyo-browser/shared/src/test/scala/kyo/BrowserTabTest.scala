package kyo

import kyo.internal.CdpBackend
import kyo.internal.SharedChrome

class BrowserTabTest extends BrowserTest:

    override def timeout = 90.seconds

    private val handler = HttpRoute.getRaw("/held").response(_.bodyBinary).handler { _ =>
        HttpResponse.ok(Span.fromUnsafe("<html><title>Held</title><body>held</body></html>".getBytes("UTF-8")))
            .addHeader("Content-Type", "text/html; charset=utf-8")
    }

    private def stored(using Frame): (String, Chunk[String]) < (Browser & Abort[BrowserReadException]) =
        Browser.eval("String(localStorage.getItem('held'))").map(item => Browser.cookies.map(cookies => (item, cookies.map(_.name))))

    private def store(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.setCookie("held-cookie", "v", "127.0.0.1").andThen(Browser.eval("localStorage.setItem('held','v'); 'ok'").unit)

    private def chrome(using Frame): Browser.Chrome < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        SharedChrome.chromeConfig().map(Browser.Chrome.init)

    "a retained tab keeps its page cookies and storage across runs" in {
        withLocalhostServer(handler) { (host, port) =>
            for
                browser <- chrome
                tab     <- Browser.Tab.init(browser)
                _       <- Browser.run(tab)(Browser.goto(s"http://$host:$port/held").andThen(store))
                actual  <- Browser.run(tab)(Browser.url.map(url => stored.map((url, _))))
            yield assert(actual == (s"http://$host:$port/held", ("v", Chunk("held-cookie"))))
        }
    }

    "retained tabs on one Chrome have isolated storage" in {
        withLocalhostServer(handler) { (host, port) =>
            for
                browser <- chrome
                first   <- Browser.Tab.init(browser)
                second  <- Browser.Tab.init(browser)
                _       <- Browser.run(first)(Browser.goto(s"http://$host:$port/held").andThen(store))
                actual  <- Browser.run(second)(Browser.goto(s"http://$host:$port/held").andThen(stored))
            yield assert(actual == ("null", Chunk.empty))
        }
    }

    "a closed retained tab refuses runs and disposes its context and tracking" in {
        for
            browser <- chrome
            closed  <- Scope.run {
                Browser.Tab.init(browser).map(tab =>
                    Browser.run(tab)(Browser.use(_.targetId.value)).map(target => (tab, target))
                )
            }
            (tab, target) = closed
            result        <- Abort.run[Closed](Browser.run(tab)(Browser.title))
            _             <- assertEventually(CdpBackend.getTargets(tab.held.tab.backend).map(!_.targetInfos.exists(_.targetId == target)))
            registrations <- tab.held.tab.backend.frameEventDispatchers.get
            later         <- Browser.Tab.init(browser).map(next => Browser.run(next)(Browser.url))
        yield
            assert(result.isFailure)
            assert(!registrations.contains(tab.held.tab.sessionId.value))
            assert(later == "about:blank")
    }

    "retained runs from different fibers serialize" in {
        for
            browser <- chrome
            tab     <- Browser.Tab.init(browser)
            entered <- Latch.init(1)
            release <- Latch.init(1)
            first   <-
                Fiber.init(Browser.run(tab)(entered.release.andThen(release.await).andThen(Browser.eval("window.held = 'first'; 'first'"))))
            _      <- entered.await
            second <- Fiber.init(Browser.run(tab)(Browser.eval("String(window.held)")))
            _      <- assertEventually(tab.held.mutex.pendingWaiters.map(_ == 1))
            _      <- release.release.andThen(first.get)
            actual <- second.get
        yield assert(actual == "first")
    }

    "a closed Chrome refuses a new retained tab" in {
        Scope.run(chrome).map { closed =>
            Abort.run[BrowserReadException | Closed](Browser.Tab.init(closed)).map {
                case Result.Failure(_: Closed) => succeed
                case other                     => fail(s"expected closed owner, got $other")
            }
        }
    }

    "retained runs reenter on one fiber without sharing the permit with its fork" in {
        for
            browser <- chrome
            tab     <- Browser.Tab.init(browser)
            nested  <- Browser.run(tab)(Browser.run(tab)(Browser.url))
            child   <- Browser.run(tab) {
                Fiber.init(Browser.run(tab)(Browser.url)).map { child =>
                    assertEventually(tab.held.mutex.pendingWaiters.map(_ == 1)).andThen(child)
                }
            }
            url <- child.get
        yield
            assert(nested == "about:blank")
            assert(url == "about:blank")
    }

    "retained runs preserve the caller clock" in {
        for
            browser <- chrome
            tab     <- Browser.Tab.init(browser)
            actual  <- Clock.withTimeControl(control => control.set(Instant.Epoch).andThen(Browser.run(tab)(Clock.now)))
        yield assert(actual == Instant.Epoch)
    }

    "independently retained tabs can run concurrently on one Chrome" in {
        for
            browser <- chrome
            first   <- Browser.Tab.init(browser)
            second  <- Browser.Tab.init(browser)
            entered <- Latch.init(2)
            release <- Latch.init(1)
            a       <- Fiber.init(Browser.run(first)(entered.release.andThen(release.await).andThen(Browser.eval("'first'"))))
            b       <- Fiber.init(Browser.run(second)(entered.release.andThen(release.await).andThen(Browser.eval("'second'"))))
            _       <- entered.await
            _       <- release.release
            x       <- a.get
            y       <- b.get
        yield assert((x, y) == ("first", "second"))
    }

    "closing a retained tab refuses a queued run before its body starts" in {
        for
            browser <- chrome
            ready   <- Promise.init[Browser.Tab, Any]
            close   <- Latch.init(1)
            owner   <- Fiber.init(Scope.run {
                Browser.Tab.init(browser).map(tab => ready.complete(Result.succeed(tab)).andThen(close.await))
            })
            tab     <- ready.get
            entered <- Latch.init(1)
            release <- Latch.init(1)
            active  <- Fiber.init(Browser.run(tab)(entered.release.andThen(release.await)))
            _       <- entered.await
            invoked <- AtomicBoolean.init(false)
            queued  <- Fiber.init(Abort.run[Closed](Browser.run(tab)(invoked.set(true))))
            _       <- assertEventually(tab.held.mutex.pendingWaiters.map(_ == 1))
            _       <- close.release.andThen(owner.get)
            result  <- queued.get
            actual  <- invoked.get
            _       <- release.release.andThen(active.getResult).unit
        yield
            result match
                case Result.Failure(_: Closed) => succeed
                case other                     => fail(s"expected queued run to close, got $other")
            assert(!actual)
    }

    "a timeout outside the retained handler releases the tab and preserves its state" in {
        for
            browser <- chrome
            tab     <- Browser.Tab.init(browser)
            result  <- Clock.withTimeControl { control =>
                for
                    entered <- Latch.init(1)
                    task    <- Fiber.init(Abort.run[Timeout](Async.timeout(1.second)(Browser.run(tab) {
                        Browser.eval("window.held = 'timed'; 'ok'").andThen(entered.release).andThen(Async.never)
                    })))
                    _      <- entered.await
                    _      <- control.advance(1.second)
                    result <- task.get
                yield result
            }
            actual <- Browser.run(tab)(Browser.eval("String(window.held)"))
        yield
            result match
                case Result.Failure(_: Timeout) => succeed
                case other                      => fail(s"expected timeout, got $other")
            assert(actual == "timed")
    }

    "closing Chrome refuses a pure retained body owned by a longer caller scope" in {
        for
            ready   <- Promise.init[Browser.Chrome, Any]
            close   <- Latch.init(1)
            owner   <- Fiber.init(Scope.run(chrome.map(browser => ready.complete(Result.succeed(browser)).andThen(close.await))))
            browser <- ready.get
            tab     <- Browser.Tab.init(browser)
            _       <- close.release.andThen(owner.get)
            invoked <- AtomicBoolean.init(false)
            result  <- Abort.run[Closed](Browser.run(tab)(invoked.set(true)))
            actual  <- invoked.get
        yield
            assert(result.isFailure)
            assert(!actual)
    }

    "closing Chrome releases retained tracking owned by a longer caller scope" in {
        for
            ready   <- Promise.init[Browser.Chrome, Any]
            close   <- Latch.init(1)
            owner   <- Fiber.init(Scope.run(chrome.map(browser => ready.complete(Result.succeed(browser)).andThen(close.await))))
            browser <- ready.get
            tab     <- Browser.Tab.init(browser)
            before  <- tab.held.tab.backend.frameEventDispatchers.get
            _       <- close.release.andThen(owner.get)
            after   <- tab.held.tab.backend.frameEventDispatchers.get
        yield
            assert(before.contains(tab.held.tab.sessionId.value))
            assert(!after.contains(tab.held.tab.sessionId.value))
    }

    "retained-tab main control keeps state inside a run and starts another with an isolated page" in {
        withLocalhostServer(handler) { (host, port) =>
            SharedChrome.withUrl { (url, started) =>
                for
                    first  <- Browser.run(url)(started.andThen(Browser.goto(s"http://$host:$port/held")).andThen(store).andThen(stored))
                    second <- Browser.run(url)(Browser.goto(s"http://$host:$port/held").andThen(stored))
                yield
                    assert(first == ("v", Chunk("held-cookie")))
                    assert(second == ("null", Chunk.empty))
            }
        }
    }

    "retained-tab main control refuses an unisolated Browser fork" in {
        typeCheckFailure("""
            import kyo.*
            def shared(using Frame) = Async.zip(Browser.title, Browser.url)
        """)
        typeCheck("""
            import kyo.*
            def separated(using Frame) = Browser.isolate.fresh.use(Async.zip(Browser.title, Browser.url))
        """)
    }

end BrowserTabTest
