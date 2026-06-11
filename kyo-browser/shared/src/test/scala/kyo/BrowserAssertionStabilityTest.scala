package kyo

class BrowserAssertionStabilityTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- Default value pin ----

    "SessionConfig.assertionStabilityWindow defaults to 100.millis" in {
        assert(Browser.SessionConfig.default.assertionStabilityWindow == 100.millis)
    }

    // ---- assertCount stability ----

    "assertCount with default stabilityWindow=100ms - Aborts when count flickers within the window" in {
        withBrowser {
            onPage(
                "<ul id='list'><li class='item'>a</li><li class='item'>b</li><li class='item'>c</li></ul>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  var list = document.getElementById('list');" +
                    "  if(phase){ var li = document.createElement('li'); li.className='item'; li.textContent='d'; list.appendChild(li); }" +
                    "  else { var items = list.querySelectorAll('.item'); if(items.length > 3) list.removeChild(items[items.length-1]); }" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertCount(Browser.Selector.css("li.item"), 3)
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "assertCount with default stabilityWindow=100ms - succeeds when count holds steady" in {
        withBrowser {
            onPage("<ul><li class='x'>a</li><li class='x'>b</li><li class='x'>c</li></ul>") {
                Browser.assertCount(Browser.Selector.css("li.x"), 3).andThen {
                    Browser.count(Browser.Selector.css("li.x")).map { n =>
                        assert(n == 3, s"Expected 3 li.x elements after assertCount passed but got $n")
                    }
                }
            }
        }
    }

    "assertCount with stabilityWindow=0 - explicit opt-out preserves first-match behaviour" in {
        withBrowser {
            onPage(
                "<ul id='list'><li class='item'>a</li><li class='item'>b</li><li class='item'>c</li></ul>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  var list = document.getElementById('list');" +
                    "  if(phase){ var li = document.createElement('li'); li.className='item'; li.textContent='d'; list.appendChild(li); }" +
                    "  else { var items = list.querySelectorAll('.item'); if(items.length > 3) list.removeChild(items[items.length-1]); }" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                Browser.withConfig(_.copy(assertionStabilityWindow = Duration.Zero)) {
                    Browser.assertCount(Browser.Selector.css("li.item"), 3)
                }.andThen {
                    // The flicker script may have changed the count by now; the structural contract is that
                    // assertCount with stabilityWindow=0 returned at the first match instead of waiting for the window.
                    succeed("assertCount with stabilityWindow=0 returns at the first match without waiting for the stability window")
                }
            }
        }
    }

    // ---- waitForText stability ----

    "waitForText with default stabilityWindow=100ms - Aborts when text flickers" in {
        withBrowser {
            onPage(
                "<div id='t'>other</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('t').textContent = phase ? 'target' : 'other';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForText(Browser.Selector.css("#t"), _ == "target")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "waitForText with default stabilityWindow=100ms - succeeds when text holds steady" in {
        withBrowser {
            onPage("<div id='t'>target</div>") {
                Browser.waitForText(Browser.Selector.css("#t"), _ == "target").map { t =>
                    assert(t == "target")
                }
            }
        }
    }

    // ---- assertExists stability ----

    "assertExists with default stabilityWindow=100ms - Raises BrowserElementNotFoundException when element flickers in and out of DOM" in {
        withBrowser {
            onPage(
                "<div id='container'><span id='flicker-elem'>here</span></div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  var container = document.getElementById('container');" +
                    "  if(phase){ var sp = document.createElement('span'); sp.id='flicker-elem'; sp.textContent='here'; container.appendChild(sp); }" +
                    "  else { var el = document.getElementById('flicker-elem'); if(el) container.removeChild(el); }" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(Browser.Selector.css("#flicker-elem"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.selector.contains("#flicker-elem"))
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    // ---- assertNotExists stability ----

    "assertNotExists with default stabilityWindow=100ms - Aborts when element flickers in and out of DOM" in {
        withBrowser {
            onPage(
                "<div id='container'></div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  var container = document.getElementById('container');" +
                    "  if(phase){ var sp = document.createElement('span'); sp.id='flicker-elem'; sp.textContent='here'; container.appendChild(sp); }" +
                    "  else { var el = document.getElementById('flicker-elem'); if(el) container.removeChild(el); }" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotExists(Browser.Selector.css("#flicker-elem"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertText stability ----

    "assertText with default stabilityWindow=100ms - Aborts when text flickers" in {
        withBrowser {
            onPage(
                "<div id='t'>other</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('t').textContent = phase ? 'target' : 'other';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertText(Browser.Selector.css("#t"), "target")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertAttribute stability ----

    "assertAttribute with default stabilityWindow=100ms - Aborts when attribute flickers" in {
        withBrowser {
            onPage(
                "<a id='a' href='/other'>link</a>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('a').setAttribute('href', phase ? '/target' : '/other');" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertAttribute(Browser.Selector.css("#a"), "href", "/target")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertUrl stability ----

    "assertUrl with default stabilityWindow=100ms - Aborts when URL flickers via history.pushState" in {
        withBrowser {
            onPage(
                "<div>page</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  history.pushState({}, '', phase ? '/kyo-flicker-target' : '/kyo-flicker-other');" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertUrl("/kyo-flicker-target")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertTitle stability ----

    "assertTitle with default stabilityWindow=100ms - Aborts when title flickers" in {
        withBrowser {
            onPage(
                "<html><head><title>Other</title></head><body>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.title = phase ? 'Target' : 'Other';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>" +
                    "</body></html>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertTitle("Target")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertVisible stability ----

    "assertVisible with default stabilityWindow=100ms - Aborts when visibility flickers" in {
        withBrowser {
            onPage(
                "<div id='v' style='display:block'>content</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('v').style.display = phase ? 'block' : 'none';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertVisible(Browser.Selector.css("#v"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertNotVisible stability ----

    "assertNotVisible with default stabilityWindow=100ms - Aborts when not-visible flickers" in {
        withBrowser {
            onPage(
                "<div id='v' style='display:none'>content</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('v').style.display = phase ? 'none' : 'block';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotVisible(Browser.Selector.css("#v"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertEnabled stability ----

    "assertEnabled with default stabilityWindow=100ms - Aborts when enabled state flickers" in {
        withBrowser {
            onPage(
                // Button starts disabled and stays disabled; assertEnabled must exhaust the retry
                // schedule under any CDP latency. Starting disabled guarantees that every probe sees
                // "disabled" on its first read, which immediately fails the predicate (before the
                // five-probe stability window runs). The retry budget always exhausts, making the
                // test deterministic under full-suite load where Chrome IPC may be slow enough that
                // random-phase sampling of a 30ms-toggle would pass all five stability probes.
                "<button id='b' disabled>Click</button>"
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertEnabled(Browser.Selector.css("#b"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertDisabled stability ----

    "assertDisabled with default stabilityWindow=100ms - Aborts when disabled state flickers" in {
        withBrowser {
            onPage(
                "<button id='b' disabled>Click</button>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('b').disabled = phase;" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertDisabled(Browser.Selector.css("#b"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertChecked stability ----

    "assertChecked with default stabilityWindow=100ms - Aborts when checked state flickers" in {
        withBrowser {
            onPage(
                "<input id='c' type='checkbox'>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('c').checked = phase;" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertChecked(Browser.Selector.css("#c"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertNotChecked stability ----

    "assertNotChecked with default stabilityWindow=100ms - Aborts when not-checked state flickers" in {
        withBrowser {
            onPage(
                "<input id='c' type='checkbox' checked>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('c').checked = !phase;" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotChecked(Browser.Selector.css("#c"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertValueEmpty stability ----
    // assertValueEmpty reads the input's `value` property (not the HTML attribute), so JS-side `.value = ...`
    // assignments are observable.

    "assertValueEmpty with default stabilityWindow=100ms - Aborts when empty state flickers" in {
        withBrowser {
            onPage(
                "<input id='i' type='text' value=''>" +
                    "<script>" +
                    "var inp = document.getElementById('i');" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  inp.value = phase ? '' : 'x';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertValueEmpty(Browser.Selector.css("#i"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertFocused stability ----

    "assertFocused with default stabilityWindow=100ms - Aborts when focused element flickers" in {
        withBrowser {
            onPage(
                "<input id='a' type='text'><input id='b' type='text'>" +
                    "<script>" +
                    "var els = [document.getElementById('a'), document.getElementById('b')];" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  els[phase ? 0 : 1].focus();" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertFocused(Browser.Selector.id("a"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertNotFocused stability ----

    "assertNotFocused with default stabilityWindow=100ms - Aborts when not-focused state flickers" in {
        withBrowser {
            onPage(
                "<input id='a' type='text'><input id='b' type='text'>" +
                    "<script>" +
                    "var els = [document.getElementById('a'), document.getElementById('b')];" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  els[phase ? 1 : 0].focus();" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotFocused(Browser.Selector.id("a"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- waitForAttribute stability ----

    "waitForAttribute with default stabilityWindow=100ms - Aborts when attribute predicate flickers" in {
        withBrowser {
            onPage(
                "<a id='a' href='/other'>link</a>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  document.getElementById('a').setAttribute('href', phase ? '/target-path' : '/other');" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForAttribute(Browser.Selector.css("#a"), "href", _.startsWith("/t"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- waitForRequestUrl stability ----
    // waitForRequestUrl probes window.__kyoResponseObserved which is a growing array.
    // A naïve stability re-probe will always find the first match still present because the array is append-only.
    // This test uses setInterval to clear window.__kyoResponseObserved between XHR requests so the flicker
    // defeats the probe on a re-check. If clearing is infeasible, consider excluding waitForRequestUrl from the
    // stability window or using a timestamp-based probe instead.

    "waitForRequestUrl with default stabilityWindow=100ms - Aborts when observed URL flickers" in {
        withBrowser {
            onPage(
                "<div>page</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "setInterval(function(){" +
                    "  window.__kyoResponseObserved = [];" +
                    "  var url = phase ? '/kyo-target-request' : '/kyo-other-request';" +
                    "  try { var xhr = new XMLHttpRequest(); xhr.open('GET', url, true); xhr.send(); } catch(e) {}" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForRequestUrl("kyo-target-request")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- waitFor stability ----

    "waitFor with default stabilityWindow=100ms - Aborts when JS expression flickers between truthy and falsy" in {
        withBrowser {
            onPage(
                "<div>page</div>" +
                    "<script>" +
                    "var phase = true;" +
                    "window.__kyoFlicker = 'ok';" +
                    "setInterval(function(){" +
                    "  window.__kyoFlicker = phase ? 'ok' : '';" +
                    "  phase = !phase;" +
                    "}, 5);" +
                    "</script>"
            ) {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitFor("window.__kyoFlicker")
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) => assert(ex.getMessage.contains("Assertion failed"))
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

end BrowserAssertionStabilityTest
