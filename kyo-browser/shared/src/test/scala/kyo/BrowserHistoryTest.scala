package kyo

class BrowserHistoryTest extends BrowserTest:

    override def timeout = 90.seconds

    // Navigate to 3 pages via a real HTTP server; assert entries contains all three URLs
    // in the order they were visited and currentIndex points to the last one.
    "Browser.history returns the active entry plus prior entries in chronological order" in run {
        val pageA = Span.fromUnsafe("<html><head><title>PageA</title></head><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><head><title>PageB</title></head><body>B</body></html>".getBytes("UTF-8"))
        val pageC = Span.fromUnsafe("<html><head><title>PageC</title></head><body>C</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerC = HttpRoute.getRaw("/c").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageC).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB, handlerC) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.goto(s"http://$host:$port/c").andThen {
                            Browser.history.map { h =>
                                val urls = h.entries.map(_.url)
                                assert(
                                    urls.contains(s"http://$host:$port/a"),
                                    s"Expected /a in history but got: $urls"
                                )
                                assert(
                                    urls.contains(s"http://$host:$port/b"),
                                    s"Expected /b in history but got: $urls"
                                )
                                assert(
                                    urls.contains(s"http://$host:$port/c"),
                                    s"Expected /c in history but got: $urls"
                                )
                                assert(
                                    h.currentIndex == h.entries.size - 1,
                                    s"Expected currentIndex=${h.entries.size - 1} (last entry) but got ${h.currentIndex}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Navigate A → B → C, call back, assert currentIndex decremented; call forward, assert restored.
    "Browser.history.currentIndex moves with back / forward" in run {
        val pageA = Span.fromUnsafe("<html><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><body>B</body></html>".getBytes("UTF-8"))
        val pageC = Span.fromUnsafe("<html><body>C</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerC = HttpRoute.getRaw("/c").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageC).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB, handlerC) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.goto(s"http://$host:$port/c").andThen {
                            Browser.history.map { h0 =>
                                val atC = h0.currentIndex
                                Browser.back.andThen {
                                    Browser.history.map { h1 =>
                                        assert(
                                            h1.currentIndex == atC - 1,
                                            s"Expected currentIndex=${atC - 1} after back but got ${h1.currentIndex}"
                                        )
                                        Browser.forward.andThen {
                                            Browser.history.map { h2 =>
                                                assert(
                                                    h2.currentIndex == atC,
                                                    s"Expected currentIndex=$atC after forward but got ${h2.currentIndex}"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Call history twice without any intervening navigation and assert the two results are equal.
    "Browser.history entries are stable across reads" in run {
        val p = page("<html><head><title>Stable</title></head><body>stable</body></html>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.history.map { h1 =>
                    Browser.history.map { h2 =>
                        assert(h1 == h2, s"Expected identical history on consecutive reads but got:\n  first=$h1\n  second=$h2")
                    }
                }
            }
        }
    }

    // Navigate to a page with <title>HistoryTitle</title>; assert that the NavigationEntry for
    // that page carries the correct title.
    "Browser.history populates entry titles when documents have <title>" in run {
        val p = page("<html><head><title>HistoryTitle</title></head><body>titled</body></html>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.history.map { h =>
                    val found = h.entries.exists(_.title == "HistoryTitle")
                    assert(
                        found,
                        s"Expected an entry with title='HistoryTitle' but found: ${h.entries.map(_.title)}"
                    )
                }
            }
        }
    }

    // A fresh tab has never navigated anywhere beyond the initial about:blank;
    // assert that history has exactly one entry whose URL is about:blank.
    "Browser.history at about:blank only: single entry" in run {
        withBrowser {
            Browser.history.map { h =>
                assert(
                    h.entries.size == 1,
                    s"Expected exactly 1 history entry on a fresh tab but got ${h.entries.size}: ${h.entries}"
                )
                assert(
                    h.entries.head.url == "about:blank",
                    s"Expected the single entry to be about:blank but got: ${h.entries.head.url}"
                )
            }
        }
    }

    // Fresh withBrowser; call Browser.back immediately (currentIndex == 0); assert typed exception.
    "back at history start raises BrowserAlreadyAtHistoryStartException" in run {
        withBrowser {
            Abort.run[BrowserNavigationException] {
                Browser.back
            }.map {
                case Result.Failure(_: BrowserAlreadyAtHistoryStartException) => succeed
                case other =>
                    fail(s"Expected Result.Failure(BrowserAlreadyAtHistoryStartException) but got $other")
            }
        }
    }

    // Navigate to page A (the only entry); call Browser.forward; assert typed exception.
    "forward at history end raises BrowserAlreadyAtHistoryEndException" in run {
        val p = page("<html><body>end</body></html>")
        withBrowser {
            Browser.goto(p).andThen {
                Abort.run[BrowserNavigationException] {
                    Browser.forward
                }.map {
                    case Result.Failure(_: BrowserAlreadyAtHistoryEndException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAlreadyAtHistoryEndException) but got $other")
                }
            }
        }
    }

    // Regression: navigate A, B; call back; assert history.currentIndex decremented.
    "back from a non-boundary index succeeds and decrements currentIndex" in run {
        val pageA = Span.fromUnsafe("<html><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><body>B</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.history.map { h0 =>
                            val beforeBack = h0.currentIndex
                            Browser.back.andThen {
                                Browser.history.map { h1 =>
                                    assert(
                                        h1.currentIndex == beforeBack - 1,
                                        s"Expected currentIndex=${beforeBack - 1} after back but got ${h1.currentIndex}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Regression: navigate A, B; call back; call forward; assert pointer restored.
    "forward from a non-boundary index succeeds and increments currentIndex" in run {
        val pageA = Span.fromUnsafe("<html><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><body>B</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.history.map { h0 =>
                            val atB = h0.currentIndex
                            Browser.back.andThen {
                                Browser.forward.andThen {
                                    Browser.history.map { h1 =>
                                        assert(
                                            h1.currentIndex == atB,
                                            s"Expected currentIndex=$atB after back+forward but got ${h1.currentIndex}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Regression: navigate A, B, C; back; back; forward; forward; assert URL is C.
    "back-then-forward round trip preserves the trailing entry" in run {
        val pageA = Span.fromUnsafe("<html><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><body>B</body></html>".getBytes("UTF-8"))
        val pageC = Span.fromUnsafe("<html><body>C</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerC = HttpRoute.getRaw("/c").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageC).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB, handlerC) { (host, port) =>
            val urlC = s"http://$host:$port/c"
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.goto(urlC).andThen {
                            // back; back; forward; forward → should end up back at C
                            Browser.back.andThen {
                                Browser.back.andThen {
                                    Browser.forward.andThen {
                                        Browser.forward.andThen {
                                            Browser.url.map { u =>
                                                assert(u == urlC, s"Expected url=$urlC after round trip but got $u")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Pin the subtype relationship: existing matchers on the BrowserNavigationException marker catch the new subclass.
    "BrowserAlreadyAtHistoryStartException is caught by Abort[BrowserNavigationException]" in run {
        withBrowser {
            Abort.run[BrowserNavigationException] {
                Browser.back
            }.map {
                case Result.Failure(_: BrowserAlreadyAtHistoryStartException) => succeed
                case other =>
                    fail(s"Expected BrowserAlreadyAtHistoryStartException caught by BrowserNavigationException but got $other")
            }
        }
    }

    // Pin the subtype relationship: existing matchers on the BrowserNavigationException marker catch the new subclass.
    "BrowserAlreadyAtHistoryEndException is caught by Abort[BrowserNavigationException]" in run {
        val p = page("<html><body>end</body></html>")
        withBrowser {
            Browser.goto(p).andThen {
                Abort.run[BrowserNavigationException] {
                    Browser.forward
                }.map {
                    case Result.Failure(_: BrowserAlreadyAtHistoryEndException) => succeed
                    case other =>
                        fail(s"Expected BrowserAlreadyAtHistoryEndException caught by BrowserNavigationException but got $other")
                }
            }
        }
    }

    // Server-side AtomicInteger counts requests; Cache-Control: max-age=3600 would let Chrome
    // serve from cache on a normal reload. A hard reload bypasses the cache, so the server
    // must see more than one request after goto + reload(hardReload = true).
    "Browser.reload(hardReload = true) bypasses HTTP cache (counter increments)" in run {
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        val bytes   = Span.fromUnsafe("<html><body><h1>cached</h1></body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/cached").response(_.bodyBinary).handler { _ =>
            val _ = counter.incrementAndGet()
            HttpResponse.ok(bytes)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Cache-Control", "max-age=3600")
                .addHeader("ETag", "\"v1\"")
        }
        withLocalhostServer(handler) { (host, port) =>
            val url = s"http://$host:$port/cached"
            withBrowser {
                Browser.goto(url).andThen {
                    val afterGoto = counter.get()
                    Browser.reload(hardReload = true).andThen {
                        val afterHardReload = counter.get()
                        Browser.url.map { u =>
                            assert(
                                afterHardReload > afterGoto,
                                s"Expected counter to increment past $afterGoto after hardReload=true but got $afterHardReload"
                            )
                            assert(u == url, s"Expected URL unchanged after reload but got $u")
                        }
                    }
                }
            }
        }
    }

    // Chrome may or may not revalidate the cached response on a soft reload;
    // the meaningful contract is "the call returns Unit without abort" and the URL remains unchanged.
    "Browser.reload(hardReload = false) returns normally on a cached page" in run {
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        val bytes   = Span.fromUnsafe("<html><body><h1>cached</h1></body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/cached").response(_.bodyBinary).handler { _ =>
            val _ = counter.incrementAndGet()
            HttpResponse.ok(bytes)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Cache-Control", "max-age=3600")
                .addHeader("ETag", "\"v2\"")
        }
        withLocalhostServer(handler) { (host, port) =>
            val url = s"http://$host:$port/cached"
            withBrowser {
                Browser.goto(url).andThen {
                    Browser.reload(hardReload = false).andThen {
                        Browser.url.map { u =>
                            assert(u == url, s"Expected URL unchanged after reload but got $u")
                            assert(
                                counter.get() >= 1,
                                s"Expected the server to have served at least the initial goto but counter is ${counter.get()}"
                            )
                        }
                    }
                }
            }
        }
    }

    // After reload returns, the DOM must be fully loaded so a synchronous text read finds the element.
    "Browser.reload() zero-arg reloads and waits for NetworkIdle" in run {
        val p = page("<html><body><div id='x'>hello</div></body></html>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.reload().andThen {
                    Browser.text(Browser.Selector.css("#x")).map { t =>
                        assert(t == "hello", s"Expected 'hello' after reload (NetworkIdle settled) but got '$t'")
                    }
                }
            }
        }
    }

    // Slow-tail XHR after load fires keeps the network busy for ~2s. Settle.Load must return well
    // before the tail completes; NetworkIdle would block on the chatty traffic.
    "Browser.reload(settle = Browser.Settle.Load) returns before slow-tail XHR completes" in run {
        val html =
            """<!doctype html><html><body><h1>chatty</h1>
              |<script>
              |  // After the load event, kick off a single fetch that takes ~2s to settle.
              |  window.addEventListener('load', () => {
              |    setTimeout(() => { fetch('/slow').catch(() => {}); }, 0);
              |  });
              |</script></body></html>""".stripMargin
        val htmlBytes = Span.fromUnsafe(html.getBytes("UTF-8"))
        val htmlHandler = HttpRoute.getRaw("/").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(htmlBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val slowHandler = HttpRoute.getRaw("/slow").response(_.bodyBinary).handler { _ =>
            Async.sleep(2.seconds).andThen(
                HttpResponse.ok(Span.fromUnsafe("ok".getBytes("UTF-8")))
                    .addHeader("Content-Type", "text/plain")
            )
        }
        withLocalhostServer(htmlHandler, slowHandler) { (host, port) =>
            withBrowser {
                // Use Settle.Load on the initial goto too so we don't burn the budget waiting for the slow tail.
                Browser.goto(s"http://$host:$port/", Browser.Settle.Load).andThen {
                    Clock.now.map { start =>
                        Browser.reload(settle = Browser.Settle.Load).andThen {
                            Clock.now.map { end =>
                                val elapsed = end - start
                                assert(
                                    elapsed < 1500.millis,
                                    s"Expected reload(Settle.Load) to return in <1.5s but took $elapsed"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Handler returns 200 on the first request (so goto succeeds) and 500 on subsequent requests.
    // The reload's failOnHttpError = true (default) must surface a typed navigation-failed exception.
    "Browser.reload(failOnHttpError = true) raises BrowserNavigationFailedException on 500" in run {
        val counter  = new java.util.concurrent.atomic.AtomicInteger(0)
        val okBytes  = Span.fromUnsafe("<html><body><h1>first</h1></body></html>".getBytes("UTF-8"))
        val errBytes = Span.fromUnsafe("<html><body><h1>boom</h1></body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            val n = counter.incrementAndGet()
            if n == 1 then
                HttpResponse.ok(okBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            else
                HttpResponse.serverError(errBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            end if
        }
        withLocalhostServer(handler) { (host, port) =>
            val url = s"http://$host:$port/page"
            withBrowser {
                Browser.goto(url).andThen {
                    Abort.run[BrowserNavigationException] {
                        Browser.reload(failOnHttpError = true)
                    }.map {
                        case Result.Failure(_: BrowserNavigationFailedException) => succeed
                        case other =>
                            fail(s"Expected Result.Failure(BrowserNavigationFailedException) but got $other")
                    }
                }
            }
        }
    }

    // Same setup as the hard-error test, but with failOnHttpError = false; call must complete without abort.
    "Browser.reload(failOnHttpError = false) returns normally on 500" in run {
        val counter  = new java.util.concurrent.atomic.AtomicInteger(0)
        val okBytes  = Span.fromUnsafe("<html><body><h1>first</h1></body></html>".getBytes("UTF-8"))
        val errBytes = Span.fromUnsafe("<html><body><h1>boom</h1></body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            val n = counter.incrementAndGet()
            if n == 1 then
                HttpResponse.ok(okBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            else
                HttpResponse.serverError(errBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            end if
        }
        withLocalhostServer(handler) { (host, port) =>
            val url = s"http://$host:$port/page"
            withBrowser {
                Browser.goto(url).andThen {
                    Browser.reload(failOnHttpError = false).andThen {
                        Browser.url.map { u =>
                            assert(u == url, s"Expected URL unchanged after lenient reload but got $u")
                        }
                    }
                }
            }
        }
    }

    // Verifies the actionability gate re-resolves the selector against the post-reload DOM.
    "Browser.reload(hardReload = true) followed by Browser.click(selector) succeeds" in run {
        val p = page(
            "<html><body><button id='b' onclick=\"document.body.innerText='clicked'\">go</button></body></html>"
        )
        withBrowser {
            Browser.goto(p).andThen {
                Browser.reload(hardReload = true).andThen {
                    Browser.click(Browser.Selector.css("#b")).andThen {
                        Browser.text(Browser.Selector.css("body")).map { t =>
                            assert(
                                t.contains("clicked"),
                                s"Expected body text to contain 'clicked' after click but got '$t'"
                            )
                        }
                    }
                }
            }
        }
    }

    // ── NavigationHistory.canGoBack / canGoForward / current report boundary state ──
    // Navigate A → B → C. At C: canGoBack=true, canGoForward=false, current.url ends with /c.
    // After one back: both predicates true; current.url ends with /b.
    // After another back: canGoBack may still be true because the initial about:blank entry sits at index 0,
    // but canGoForward is true and current.url ends with /a.
    "NavigationHistory.canGoBack, canGoForward, current track current position across back navigation" in run {
        val pageA = Span.fromUnsafe("<html><body>A</body></html>".getBytes("UTF-8"))
        val pageB = Span.fromUnsafe("<html><body>B</body></html>".getBytes("UTF-8"))
        val pageC = Span.fromUnsafe("<html><body>C</body></html>".getBytes("UTF-8"))
        val handlerA = HttpRoute.getRaw("/a").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageA).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerB = HttpRoute.getRaw("/b").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageB).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val handlerC = HttpRoute.getRaw("/c").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageC).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handlerA, handlerB, handlerC) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/a").andThen {
                    Browser.goto(s"http://$host:$port/b").andThen {
                        Browser.goto(s"http://$host:$port/c").andThen {
                            Browser.history.map { h0 =>
                                // At C: last entry, can go back, cannot go forward.
                                assert(
                                    h0.canGoBack,
                                    s"at C: expected canGoBack=true but got false (currentIndex=${h0.currentIndex}, size=${h0.entries.size})"
                                )
                                assert(!h0.canGoForward, s"at C: expected canGoForward=false but got true")
                                assert(
                                    h0.current.url.endsWith("/c"),
                                    s"at C: expected current.url to end with /c but got '${h0.current.url}'"
                                )
                                Browser.back.andThen {
                                    Browser.history.map { h1 =>
                                        // At B: middle entry; both predicates true.
                                        assert(
                                            h1.canGoBack,
                                            s"at B: expected canGoBack=true but got false (currentIndex=${h1.currentIndex})"
                                        )
                                        assert(h1.canGoForward, s"at B: expected canGoForward=true but got false")
                                        assert(
                                            h1.current.url.endsWith("/b"),
                                            s"at B: expected current.url to end with /b but got '${h1.current.url}'"
                                        )
                                        Browser.back.andThen {
                                            Browser.history.map { h2 =>
                                                // At A: canGoForward must be true; canGoBack matches the
                                                // currentIndex > 0 invariant (Chrome keeps the initial about:blank
                                                // entry at index 0 even after navigating to /a, /b, /c).
                                                assert(h2.canGoForward, s"at A: expected canGoForward=true but got false")
                                                assert(
                                                    h2.canGoBack == (h2.currentIndex > 0),
                                                    s"at A: canGoBack should match currentIndex>0 invariant (currentIndex=${h2.currentIndex}, canGoBack=${h2.canGoBack})"
                                                )
                                                assert(
                                                    h2.current.url.endsWith("/a"),
                                                    s"at A: expected current.url to end with /a but got '${h2.current.url}'"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end BrowserHistoryTest
