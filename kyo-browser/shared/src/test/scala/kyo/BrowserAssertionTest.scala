package kyo

import kyo.internal.cdp.Accessibility

class BrowserAssertionTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- assertExists ----

    "assertExists element present returns immediately" in run {
        withBrowser {
            onPage("<button role='button'>Save</button>") {
                Browser.assertExists(Browser.Selector.css("button")).andThen {
                    Browser.count(Browser.Selector.css("button")).map { n =>
                        assert(n >= 1, s"expected at least one matching element after assertExists but got $n")
                    }
                }
            }
        }
    }

    "assertExists element appears after delay retries and succeeds" in run {
        withBrowser {
            onPage(
                "<div id='container'></div><script>setTimeout(function(){document.getElementById('container').innerHTML='<span id=\"delayed\">Hi</span>'},200)</script>"
            ) {
                Browser.assertExists(Browser.Selector.css("#delayed")).andThen {
                    Browser.count(Browser.Selector.css("#delayed")).map { n =>
                        assert(n >= 1, s"expected at least one matching #delayed after assertExists but got $n")
                    }
                }
            }
        }
    }

    "assertExists element never appears fails" in run {
        withBrowser {
            onPage("<div>Nothing</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(Browser.Selector.css("#never-exists"))
                    }
                }.map {
                    case Result.Failure(_: BrowserElementNotFoundException) => succeed
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "assertExists works with CSS selector" in run {
        withBrowser {
            onPage("<div id='target'>Here</div>") {
                Browser.assertExists(Browser.Selector.css("#target")).andThen {
                    Browser.count(Browser.Selector.css("#target")).map { n =>
                        assert(n >= 1, s"expected at least one matching #target after assertExists but got $n")
                    }
                }
            }
        }
    }

    "assertExists works with ID selector" in run {
        withBrowser {
            onPage("<div id='myid'>Found</div>") {
                Browser.assertExists(Browser.Selector.id("myid")).andThen {
                    Browser.count(Browser.Selector.id("myid")).map { n =>
                        assert(n >= 1, s"expected at least one matching #myid after assertExists but got $n")
                    }
                }
            }
        }
    }

    // ---- assertNotExists ----

    "assertNotExists element absent returns immediately" in run {
        withBrowser {
            onPage("<div>Just text</div>") {
                Browser.assertNotExists(Browser.Selector.css("#absent")).andThen {
                    Browser.count(Browser.Selector.css("#absent")).map { n =>
                        assert(n == 0, s"expected zero matches after assertNotExists but got $n")
                    }
                }
            }
        }
    }

    "assertNotExists element always present fails" in run {
        withBrowser {
            onPage("<button id='present'>Click</button>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotExists(Browser.Selector.css("#present"))
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertText ----

    "assertText matches immediately" in run {
        withBrowser {
            onPage("<div id='msg'>Hello</div>") {
                Browser.assertText(Browser.Selector.css("#msg"), "Hello").andThen {
                    Browser.text(Browser.Selector.css("#msg")).map { t =>
                        assert(t == "Hello", s"expected text 'Hello' after assertText but got '$t'")
                    }
                }
            }
        }
    }

    "assertText matches after dynamic JS update retries" in run {
        withBrowser {
            onPage(
                "<div id='msg'>loading</div><script>setTimeout(function(){document.getElementById('msg').textContent='done'},200)</script>"
            ) {
                Browser.assertText(Browser.Selector.css("#msg"), "done").andThen {
                    Browser.text(Browser.Selector.css("#msg")).map { t =>
                        assert(t == "done", s"expected text 'done' after assertText but got '$t'")
                    }
                }
            }
        }
    }

    "assertText never matches fails with expected actual" in run {
        withBrowser {
            onPage("<div id='msg'>wrong</div>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertText(Browser.Selector.css("#msg"), "correct")
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "assertText exact match only partial fails" in run {
        withBrowser {
            onPage("<div id='msg'>Hello World</div>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertText(Browser.Selector.css("#msg"), "Hello")
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "assertText empty expected vs empty actual succeeds" in run {
        withBrowser {
            onPage("<div id='empty'></div>") {
                Browser.assertText(Browser.Selector.css("#empty"), "").andThen {
                    Browser.text(Browser.Selector.css("#empty")).map { t =>
                        assert(t == "", s"expected empty text after assertText but got '$t'")
                    }
                }
            }
        }
    }

    // ---- assertAttribute ----

    "assertAttribute value matches" in run {
        withBrowser {
            onPage("<a id='link' href='https://example.com'>Link</a>") {
                Browser.assertAttribute(Browser.Selector.css("#link"), "href", "https://example.com").andThen {
                    Browser.attribute(Browser.Selector.css("#link"), "href").map { v =>
                        assert(v == "https://example.com", s"expected href 'https://example.com' after assertAttribute but got '$v'")
                    }
                }
            }
        }
    }

    "assertAttribute wrong value fails" in run {
        withBrowser {
            onPage("<a id='link' href='https://example.com'>Link</a>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertAttribute(Browser.Selector.css("#link"), "href", "https://wrong.com")
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertRole / assertAccessibleName ----

    "assertRole succeeds for a matching role" in run {
        withBrowser {
            onPage("<button id='b'>x</button>") {
                Browser.assertRole(Browser.Selector.id("b"), "button").andThen {
                    Browser.role(Browser.Selector.id("b")).map { r =>
                        assert(r == Present("button"), s"expected role='button' after assertRole but got $r")
                    }
                }
            }
        }
    }

    "assertRole mismatch raises with both actual and expected in the message" in run {
        withBrowser {
            onPage("<button id='b'>x</button>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(4))) {
                    tight {
                        Abort.run[BrowserAssertionException] {
                            Browser.assertRole(Browser.Selector.id("b"), "checkbox")
                        }
                    }.map {
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            val msg = e.getMessage
                            assert(msg.contains("button"), s"expected 'button' (actual role) in: $msg")
                            assert(msg.contains("checkbox"), s"expected 'checkbox' (expected role) in: $msg")
                            assert(msg.contains("assertRole"), s"expected 'assertRole' (check label) in: $msg")
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "assertRole retries across a delayed setAttribute('role',...) swap" in run {
        withBrowser {
            onPage(
                "<div id='d' role='generic'>x</div>" +
                    "<script>setTimeout(function(){document.getElementById('d').setAttribute('role','button')},100)</script>"
            ) {
                Browser.assertRole(Browser.Selector.id("d"), "button").andThen {
                    Browser.role(Browser.Selector.id("d")).map { r =>
                        assert(r == Present("button"), s"expected role='button' after delayed swap but got $r")
                    }
                }
            }
        }
    }

    "assertAccessibleName mismatch raises with both actual and expected in the message" in run {
        withBrowser {
            onPage("<button id='b'>Submit</button>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(4))) {
                    tight {
                        Abort.run[BrowserAssertionException] {
                            Browser.assertAccessibleName(Browser.Selector.id("b"), "Save")
                        }
                    }.map {
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            val msg = e.getMessage
                            assert(msg.contains("Submit"), s"expected 'Submit' (actual name) in: $msg")
                            assert(msg.contains("Save"), s"expected 'Save' (expected name) in: $msg")
                            assert(msg.contains("assertAccessibleName"), s"expected 'assertAccessibleName' (check label) in: $msg")
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "assertAccessibleName retries across a delayed aria-label swap" in run {
        withBrowser {
            onPage(
                "<button id='b' aria-label='Submit'>x</button>" +
                    "<script>setTimeout(function(){document.getElementById('b').setAttribute('aria-label','Save')},100)</script>"
            ) {
                Browser.assertAccessibleName(Browser.Selector.id("b"), "Save").andThen {
                    Browser.accessibleName(Browser.Selector.id("b")).map { n =>
                        assert(n == Present("Save"), s"expected accessibleName='Save' after delayed aria-label swap but got $n")
                    }
                }
            }
        }
    }

    "assertAccessibleName succeeds for a matching aria-label" in run {
        withBrowser {
            onPage("<button id='b' aria-label='Save'>Discard</button>") {
                Browser.assertAccessibleName(Browser.Selector.id("b"), "Save").andThen {
                    Browser.accessibleName(Browser.Selector.id("b")).map { n =>
                        assert(n == Present("Save"), s"expected 'Save' after assertAccessibleName but got $n")
                    }
                }
            }
        }
    }

    // ---- assertUrl ----

    "assertUrl matches after goto" in run {
        val p = page("<h1>URL</h1>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.assertUrl(p).andThen {
                    Browser.url.map { u =>
                        assert(u == p, s"expected URL '$p' after assertUrl but got '$u'")
                    }
                }
            }
        }
    }

    "assertUrl never matches fails" in run {
        withBrowser {
            onPage("<h1>Page</h1>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertUrl("https://never-match.example.com")
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- assertTitle ----

    "assertTitle matches" in run {
        withBrowser {
            onPage("<html><head><title>MyTitle</title></head><body></body></html>") {
                Browser.assertTitle("MyTitle").andThen {
                    Browser.title.map { t =>
                        assert(t == "MyTitle", s"expected title 'MyTitle' after assertTitle but got '$t'")
                    }
                }
            }
        }
    }

    "assertTitle JS sets title after delay retries and succeeds" in run {
        withBrowser {
            onPage(
                "<html><head><title></title></head><body><script>setTimeout(function(){document.title='Delayed'},200)</script></body></html>"
            ) {
                Browser.assertTitle("Delayed").andThen {
                    Browser.title.map { t =>
                        assert(t == "Delayed", s"expected title 'Delayed' after assertTitle but got '$t'")
                    }
                }
            }
        }
    }

    // ---- Settlement ----

    "goto then assertExists heading works" in run {
        withBrowser {
            onPage("<h1 role='heading'>Title</h1>") {
                Browser.assertExists(Browser.Selector.heading("Title")).andThen {
                    Browser.count(Browser.Selector.heading("Title")).map { n =>
                        assert(n >= 1, s"expected at least one matching heading after assertExists but got $n")
                    }
                }
            }
        }
    }

    "goto then assertText heading matches immediately" in run {
        withBrowser {
            onPage("<h1 role='heading' aria-label='Title'>Title</h1>") {
                Browser.assertText(Browser.Selector.heading("Title"), "Title").andThen {
                    Browser.text(Browser.Selector.heading("Title")).map { t =>
                        assert(t == "Title", s"expected heading text 'Title' after assertText but got '$t'")
                    }
                }
            }
        }
    }

    "goto page with delayed JS then assertText retries until JS runs" in run {
        withBrowser {
            onPage(
                "<div id='target'>initial</div><script>setTimeout(function(){document.getElementById('target').textContent='loaded'},200)</script>"
            ) {
                Browser.assertText(Browser.Selector.css("#target"), "loaded").andThen {
                    Browser.text(Browser.Selector.css("#target")).map { t =>
                        assert(t == "loaded", s"expected text 'loaded' after assertText but got '$t'")
                    }
                }
            }
        }
    }

    "assertVisible succeeds for a visible element" in run {
        withBrowser {
            onPage("<div id='v'>visible</div>") {
                Browser.assertVisible(Browser.Selector.css("#v")).andThen {
                    // Readback uses an explicit JS visibility probe (no direct typed readback API).
                    Browser.eval(
                        "(() => { const el = document.querySelector('#v'); if (!el) return 'absent'; const s = getComputedStyle(el); return (s.display !== 'none' && s.visibility !== 'hidden' && el.getBoundingClientRect().width > 0) ? 'visible' : 'hidden'; })()"
                    ).map { v =>
                        assert(v == "visible", s"expected 'visible' after assertVisible but got '$v'")
                    }
                }
            }
        }
    }

    "assertVisible waits for a delayed-visibility element" in run {
        withBrowser {
            onPage(
                "<div id='v' style='display:none'>later</div>" +
                    "<script>setTimeout(function(){document.getElementById('v').style.display='block'},200)</script>"
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Browser.assertVisible(Browser.Selector.css("#v"))
                }.andThen {
                    // Readback uses an explicit JS visibility probe (no direct typed readback API).
                    Browser.eval(
                        "(() => { const el = document.querySelector('#v'); if (!el) return 'absent'; const s = getComputedStyle(el); return (s.display !== 'none' && s.visibility !== 'hidden' && el.getBoundingClientRect().width > 0) ? 'visible' : 'hidden'; })()"
                    ).map { v =>
                        assert(v == "visible", s"expected 'visible' after delayed assertVisible but got '$v'")
                    }
                }
            }
        }
    }

    "assertVisible fails with Hidden-phrased message when the element stays hidden" in run {
        withBrowser {
            onPage("<div id='h' style='display:none'>never</div>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertVisible(Browser.Selector.css("#h"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            val msg = e.getMessage
                            assert(msg.contains("assertVisible"), s"expected 'assertVisible' in: $msg")
                            assert(msg.contains("hidden"), s"expected 'hidden' in: $msg")
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    end match
                }
            }
        }
    }

    "assertNotVisible succeeds for a display:none element" in run {
        withBrowser {
            onPage("<div id='h' style='display:none'>hidden</div>") {
                Browser.assertNotVisible(Browser.Selector.css("#h")).map(_ => succeed)
            }
        }
    }

    "assertNotVisible succeeds for a hidden attribute element" in run {
        withBrowser {
            onPage("<div id='h' hidden>hidden</div>") {
                Browser.assertNotVisible(Browser.Selector.css("#h")).map(_ => succeed)
            }
        }
    }

    "assertNotVisible waits for an element that becomes hidden" in run {
        withBrowser {
            onPage(
                "<div id='v'>visible</div>" +
                    "<script>setTimeout(function(){document.getElementById('v').style.display='none'},200)</script>"
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Browser.assertNotVisible(Browser.Selector.css("#v"))
                }.map(_ => succeed)
            }
        }
    }

    "assertNotVisible fails when the element stays visible" in run {
        withBrowser {
            onPage("<div id='v'>still here</div>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotVisible(Browser.Selector.css("#v"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            val msg = e.getMessage
                            assert(msg.contains("assertNotVisible"), s"expected 'assertNotVisible' in: $msg")
                            assert(msg.contains("visible"), s"expected 'visible' actual in: $msg")
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    end match
                }
            }
        }
    }

    "assertNotVisible fails fast with BrowserElementException when element is missing" in run {
        withBrowser {
            onPage("<div>nothing</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertNotVisible(Browser.Selector.css("#absent"))
                    }
                }.map {
                    case Result.Failure(_: BrowserElementNotFoundException) => succeed
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "assertEnabled on button without disabled succeeds" in run {
        withBrowser {
            onPage("<button id='b'>Go</button>") {
                Browser.assertEnabled(Browser.Selector.css("#b")).andThen {
                    // Read live `.disabled` JS property (the boolean attribute reflects only the initial state).
                    Browser.eval("String(document.querySelector('#b').disabled)").map { v =>
                        assert(v == "false", s"expected #b.disabled === false after assertEnabled but got '$v'")
                    }
                }
            }
        }
    }

    "assertEnabled on disabled button fails" in run {
        withBrowser {
            onPage("<button id='b' disabled>Go</button>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertEnabled(Browser.Selector.css("#b"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            val msg = e.getMessage
                            assert(msg.contains("assertEnabled"), s"expected 'assertEnabled' in: $msg")
                            assert(msg.contains("disabled"), s"expected 'disabled' in: $msg")
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    end match
                }
            }
        }
    }

    "assertChecked on checked checkbox succeeds" in run {
        withBrowser {
            onPage("<input id='c' type='checkbox' checked>") {
                Browser.assertChecked(Browser.Selector.css("#c")).andThen {
                    // Read the live HTMLInputElement.checked JS property; NOT the `checked` attribute,
                    // which only reflects the *initial* state.  Using Browser.attribute(..., "checked")
                    // here would silently miss programmatic state changes.
                    Browser.eval("String(document.querySelector('#c').checked)").map { v =>
                        assert(v == "true", s"expected #c.checked === true after assertChecked but got '$v'")
                    }
                }
            }
        }
    }

    "assertChecked on unchecked checkbox fails" in run {
        withBrowser {
            onPage("<input id='c' type='checkbox'>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertChecked(Browser.Selector.css("#c"))
                    }
                }.map {
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        val msg = e.getMessage
                        assert(msg.contains("assertChecked"), s"expected 'assertChecked' in: $msg")
                        assert(msg.contains("not_checked"), s"expected 'not_checked' in: $msg")
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "assertNotChecked on unchecked checkbox succeeds" in run {
        withBrowser {
            onPage("<input id='c' type='checkbox'>") {
                Browser.assertNotChecked(Browser.Selector.css("#c")).map(_ => succeed)
            }
        }
    }

    "assertNotChecked waits for a checkbox that becomes unchecked" in run {
        withBrowser {
            onPage(
                "<input id='c' type='checkbox' checked>" +
                    "<script>setTimeout(function(){document.getElementById('c').checked=false},200)</script>"
            ) {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Browser.assertNotChecked(Browser.Selector.css("#c"))
                }.map(_ => succeed)
            }
        }
    }

    "assertNotChecked on checked checkbox fails" in run {
        withBrowser {
            onPage("<input id='c' type='checkbox' checked>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotChecked(Browser.Selector.css("#c"))
                    }
                }.map {
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        val msg = e.getMessage
                        assert(msg.contains("assertNotChecked"), s"expected 'assertNotChecked' in: $msg")
                        assert(msg.contains("checked"), s"expected 'checked' actual in: $msg")
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    "assertNotChecked fails fast with BrowserElementException when element is missing" in run {
        withBrowser {
            onPage("<div>nothing</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertNotChecked(Browser.Selector.css("#absent"))
                    }
                }.map {
                    case Result.Failure(_: BrowserElementNotFoundException) => succeed
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "assertDisabled on disabled button succeeds" in run {
        withBrowser {
            onPage("<button id='b' disabled>Go</button>") {
                Browser.assertDisabled(Browser.Selector.css("#b")).andThen {
                    // Read the live `.disabled` JS property; same canonical-property-vs-attribute pitfall
                    // applies as for assertChecked.  See the comment at assertChecked (line ~409).
                    Browser.eval("String(document.querySelector('#b').disabled)").map { v =>
                        assert(v == "true", s"expected #b.disabled === true after assertDisabled but got '$v'")
                    }
                }
            }
        }
    }

    "assertFocused succeeds when document.activeElement matches the selector" in run {
        withBrowser {
            onPage("<input id='a' autofocus/><input id='b'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.assertFocused(Browser.Selector.id("a")).map(_ => succeed)
                }
            }
        }
    }

    "assertFocused fails (Abort) when a different element has focus" in run {
        withBrowser {
            onPage("<input id='a' autofocus/><input id='b'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    tight {
                        Abort.run[BrowserAssertionException] {
                            Browser.assertFocused(Browser.Selector.id("b"))
                        }
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other =>
                            fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                    }
                }
            }
        }
    }

    "assertFocused fails (Abort) when the selector matches no element" in run {
        withBrowser {
            onPage("<input id='a'/>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertFocused(Browser.Selector.id("does-not-exist"))
                    }
                }.map {
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        val msg = e.getMessage
                        assert(msg.contains("assertFocused"), s"expected 'assertFocused' in: $msg")
                        assert(msg.contains("not_attached"), s"expected 'not_attached' actual in: $msg")
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertNotFocused succeeds when the element is not focused" in run {
        withBrowser {
            onPage("<input id='a' autofocus/><input id='b'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    Browser.assertNotFocused(Browser.Selector.id("b")).map(_ => succeed)
                }
            }
        }
    }

    "assertNotFocused fails (Abort) when the element is focused" in run {
        withBrowser {
            onPage("<input id='a' autofocus/><input id='b'/>") {
                Browser.eval("document.getElementById('a').focus()").andThen {
                    tight {
                        Abort.run[BrowserAssertionException] {
                            Browser.assertNotFocused(Browser.Selector.id("a"))
                        }
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other =>
                            fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                    }
                }
            }
        }
    }

    "assertNotFocused fails (Abort) when the selector matches no element" in run {
        withBrowser {
            onPage("<input id='a'/>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotFocused(Browser.Selector.id("does-not-exist"))
                    }
                }.map {
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        val msg = e.getMessage
                        assert(msg.contains("assertNotFocused"), s"expected 'assertNotFocused' in: $msg")
                        assert(msg.contains("not_attached"), s"expected 'not_attached' actual in: $msg")
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertCount(3) on three matching elements succeeds" in run {
        withBrowser {
            onPage("<ul><li class='x'>a</li><li class='x'>b</li><li class='x'>c</li></ul>") {
                Browser.assertCount(Browser.Selector.css("li.x"), 3).andThen {
                    Browser.count(Browser.Selector.css("li.x")).map { c =>
                        assert(c == 3, s"expected count 3 after assertCount but got $c")
                    }
                }
            }
        }
    }

    "assertCount(3) on two matching waits then fails" in run {
        withBrowser {
            onPage("<ul><li class='x'>a</li><li class='x'>b</li></ul>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertCount(Browser.Selector.css("li.x"), 3)
                    }
                }.map {
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        val msg = e.getMessage
                        assert(msg.contains("assertCount"), s"expected 'assertCount' in: $msg")
                        assert(msg.contains("expected 3 matching but got 2"), s"expected count phrase in: $msg")
                    case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- accessibility / aria parsing ----

    "accessibility parseAxTree projects a well-formed AX node into the public AxNode shape" in run {
        // End-to-end parsing exercises the custom AxValue Schema and the AxNodeWire decoder.
        val wire =
            """{"id":1,"result":{"nodes":[
              |  {"nodeId":"42","ignored":false,
              |   "role":{"type":"role","value":"button"},
              |   "name":{"type":"computedString","value":"Save"},
              |   "properties":[]}
              |]}}""".stripMargin
        Abort.run(Accessibility.parseAxTree(wire)).map {
            case Result.Success(nodes) =>
                assert(nodes.size == 1)
                val n = nodes.head
                assert(n.nodeId == "42")
                assert(n.role == "button")
                assert(n.name == "Save")
                assert(!n.ignored)
            case other => fail(s"$other")
        }
    }

    "accessibility parseAxTree projects an empty/default wire node into AxNode with all field defaults" in run {
        // Permissive contract: every wire entry yields exactly one AxNode;
        // missing nodeId / role / name / properties default to empty rather than dropping the node entirely.
        val wire = """{"id":1,"result":{"nodes":[{}]}}"""
        Abort.run(Accessibility.parseAxTree(wire)).map {
            case Result.Success(nodes) =>
                assert(nodes.size == 1, s"expected one default node but got ${nodes.size}")
                val n = nodes.head
                assert(n.nodeId == "")
                assert(n.role == "")
                assert(n.name == "")
                assert(!n.ignored)
                assert(n.properties.isEmpty)
            case other => fail(s"$other")
        }
    }

    // ---- curried predicate overloads ----

    "assertText with curried predicate passes when the predicate matches" in run {
        withBrowser {
            onPage("<h1 id='h'>Hello World</h1>") {
                // Postcondition: pin the observed text via Browser.text so a regression that makes
                // assertTextSatisfies return early without actually reading the element is caught.
                Browser.assertTextSatisfies(Browser.Selector.css("#h"), "starts with Hello")(_.startsWith("Hello"))
                    .andThen {
                        Browser.text(Browser.Selector.css("#h")).map { t =>
                            assert(t == "Hello World", s"expected '#h' text 'Hello World' after curried predicate passes but got '$t'")
                        }
                    }
            }
        }
    }

    "assertText with curried predicate aborts with the configured message in the error when the predicate fails" in run {
        withBrowser {
            onPage("<h1 id='h'>Hello World</h1>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertTextSatisfies(Browser.Selector.css("#h"), "starts with Goodbye")(_.startsWith("Goodbye"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                        val msg = ex.getMessage
                        assert(
                            msg.contains("starts with Goodbye"),
                            s"expected configured message 'starts with Goodbye' in error but got: $msg"
                        )
                    case other =>
                        fail(s"expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertUrl with curried predicate accepts a starts-with check" in run {
        withBrowser {
            onPage("<h1>any</h1>") {
                // Postcondition: read the URL explicitly and pin it satisfies the same predicate, so a
                // regression that makes assertUrlSatisfies return early without checking the URL is caught.
                Browser.assertUrlSatisfies("starts with data:")(_.startsWith("data:"))
                    .andThen {
                        Browser.url.map { u =>
                            assert(
                                u.startsWith("data:"),
                                s"expected page URL to start with 'data:' after curried-predicate assert but got '$u'"
                            )
                        }
                    }
            }
        }
    }

    "assertTitle with curried predicate is point-equivalent to the equals overload when the predicate is _ == expected" in run {
        // Drive both forms against the same page; both must succeed without retry. The behavioral contract is "same outcome
        // as the equals overload when the predicate is _ == expected".
        val expected = "My App Settings"
        withBrowser {
            onPage(s"<html><head><title>$expected</title></head><body></body></html>") {
                Browser.assertTitle(expected).map { _ =>
                    Browser.assertTitleSatisfies("equals expected")(_ == expected).map(_ => succeed)
                }
            }
        }
    }

    // ---- assertion failure paths; assert on Abort shape, not message strings ----

    "assertDisabled on an enabled element fails with Abort[BrowserAssertionException]" in run {
        withBrowser {
            onPage("<button id='b'>Active</button>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertDisabled(Browser.Selector.css("#b"))
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertAttribute predicate returning false fails with Abort[BrowserAssertionException]" in run {
        withBrowser {
            onPage("<a id='link' href='https://example.com/page'>Link</a>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertAttributeSatisfies(
                            Browser.Selector.css("#link"),
                            "href",
                            "always-false predicate"
                        )(_ => false)
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertUrl predicate returning false fails with Abort[BrowserAssertionException]" in run {
        withBrowser {
            onPage("<h1>URL</h1>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertUrlSatisfies("always-false predicate")(_ => false)
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "assertTitle predicate returning false fails with Abort[BrowserAssertionException]" in run {
        withBrowser {
            onPage("<html><head><title>SomeTitle</title></head><body></body></html>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertTitleSatisfies("always-false predicate")(_ => false)
                    }
                }.map {
                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                }
            }
        }
    }

    "accessibility AxValue Schema decodes a boolean variant and fails decode on an unknown discriminator" in run {
        // The polymorphic AXValue is modelled as a discriminated sum type on `type`. Boolean / integer / number / string variants
        // decode into their own typed case-class variant; unknown discriminators surface as a typed UnknownVariantException so the
        // caller observes the wire-shape drift rather than silently losing data.
        import Accessibility.AxValue
        import Accessibility.AxValue.asString
        given Frame     = Frame.internal
        val validJson   = """{"type":"boolean","value":true}"""
        val invalidJson = """{"type":"unsupportedKind","value":"x"}"""
        Json.decode[AxValue](validJson) match
            case Result.Success(v: AxValue.`boolean`) =>
                assert(v.value == true)
                assert(v.asString == Present("true"))
            case other => fail(s"expected AxValue.boolean(true): $other")
        end match
        Json.decode[AxValue](invalidJson) match
            case Result.Failure(_: kyo.UnknownVariantException) => succeed
            case other => fail(s"expected UnknownVariantException for unsupported kind, got $other")
        end match
    }

    // ---- Predicate-overload coverage for negative / ordering checks ----

    "assertAttribute predicate succeeds when attribute is missing (assertNoAttribute workaround)" in run {
        // `readAttributeCore` returns "" when `getAttribute` returns null, so `_.isEmpty` is the negative
        // form of assertAttribute (covers absence checks via the predicate overload).
        withBrowser {
            onPage("<a id='a' href='/x'>x</a>") {
                // No 'target' attribute; predicate '_.isEmpty' succeeds.
                Browser.assertAttributeSatisfies(Browser.Selector.id("a"), "target", "absent")(_.isEmpty).map(_ => succeed)
            }
        }
    }

    "assertText body predicate succeeds when substrings appear in order (assertTextOrder workaround)" in run {
        // Ordered-substring assertions ride on the assertText predicate against body text; `t.indexOf`
        // checks within the predicate express the ordering constraint.
        withBrowser {
            onPage("<div>alpha</div><div>beta</div><div>gamma</div>") {
                Browser.assertTextSatisfies(Browser.Selector.css("body"), "alpha then beta then gamma in order") { t =>
                    val a = t.indexOf("alpha"); val b = t.indexOf("beta"); val g = t.indexOf("gamma")
                    a >= 0 && b > a && g > b
                }.map(_ => succeed)
            }
        }
    }

    // ---- assertText predicate ----

    "assertText predicate passes when contains check succeeds" in run {
        withBrowser {
            onPage("<h1 id='title'>Welcome to Dashboard</h1>") {
                // Postcondition: confirm the read text actually contains "Dashboard" so a regression that
                // makes assertTextSatisfies return early without inspecting the element is caught.
                Browser.assertTextSatisfies(
                    Browser.Selector.css("#title"),
                    "contains Dashboard"
                )(_.contains("Dashboard"))
                    .andThen {
                        Browser.text(Browser.Selector.css("#title")).map { t =>
                            assert(t.contains("Dashboard"), s"expected '#title' text to contain 'Dashboard' but got '$t'")
                            assert(t == "Welcome to Dashboard", s"expected '#title' text 'Welcome to Dashboard' but got '$t'")
                        }
                    }
            }
        }
    }

    "assertText predicate fails when never matches" in run {
        withBrowser {
            onPage("<h1 id='title'>Hello World</h1>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertTextSatisfies(
                            Browser.Selector.css("#title"),
                            "contains Dashboard"
                        )(_.contains("Dashboard"))
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                            case other => fail(s"Expected BrowserAssertionTimedOutException for non-matching predicate but got $other")
                    }
                }
            }
        }
    }

    // ---- assertAttribute predicate ----

    "assertAttribute predicate passes" in run {
        withBrowser {
            onPage("<a id='link' href='https://example.com/page'>Link</a>") {
                Browser.assertAttributeSatisfies(
                    Browser.Selector.css("#link"),
                    "href",
                    "href contains example.com"
                )(_.contains("example.com")).map(_ => succeed)
            }
        }
    }

    // ---- assertUrl predicate ----

    "assertUrl predicate with startsWith check" in run {
        withBrowser {
            onPage("<h1>Page</h1>") {
                // Postcondition: read the URL explicitly so the no-throw test becomes an observation test.
                Browser.assertUrlSatisfies("starts with data:")(_.startsWith("data:"))
                    .andThen {
                        Browser.url.map { u =>
                            assert(u.startsWith("data:"), s"expected URL to start with 'data:' after assertUrlSatisfies but got '$u'")
                        }
                    }
            }
        }
    }

    // ---- assertTitle predicate ----

    "assertTitle predicate passes" in run {
        withBrowser {
            onPage("<html><head><title>My App - Settings</title></head><body></body></html>") {
                // Postcondition: read the title explicitly so the no-throw test becomes an observation test
                // that pins the actual page title.
                Browser.assertTitleSatisfies("title contains Settings")(_.contains("Settings"))
                    .andThen {
                        Browser.title.map { t =>
                            assert(
                                t == "My App - Settings",
                                s"expected title 'My App - Settings' after assertTitleSatisfies but got '$t'"
                            )
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Assertion failure messages include the current page URL.
    // Without the URL, a developer debugging an assertText that fails on the wrong page sees
    // only the body snippet and cannot tell what page it came from. Embedding the URL makes
    // "running this against the wrong page" failures self-diagnosing.
    // -------------------------------------------------------------------------

    "BrowserAssertionTimedOutException message embeds the current page URL" in run {
        withBrowser {
            // Page with a div that's deliberately NOT going to match the assertion; so the message we read is the
            // timeout-failure path.
            onPage("<div id='content'>unrelated content here</div>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertText(Browser.Selector.id("content"), "matching string we won't see")
                    }.map {
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            val msg = ex.getMessage
                            assert(
                                msg.contains("data:") || msg.contains("at http"),
                                s"expected the assertion-timeout message to embed the page URL (data:... for data URLs, http... for real pages), but message was: $msg"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    }
                }
            }
        }
    }

    // ---- assertCount predicate form accepts schedule ----

    "assertCount(sel, message, Present(shortSched))(predicate) honors the schedule override" in run {
        // Five matching elements rendered at page load; the predicate matches immediately, so a short
        // schedule succeeds. This verifies the new schedule parameter threads through to withStability.
        withBrowser {
            onPage(
                "<ul>" +
                    "<li class='x'>a</li><li class='x'>b</li><li class='x'>c</li><li class='x'>d</li><li class='x'>e</li>" +
                    "</ul>"
            ) {
                Browser.assertCountSatisfies(
                    Browser.Selector.css("li.x"),
                    "exactly 5",
                    Present(Schedule.fixed(50.millis).take(3))
                )(_ == 5).andThen {
                    Browser.count(Browser.Selector.css("li.x")).map { c =>
                        assert(c == 5, s"expected count 5 after assertCount but got $c")
                    }
                }
            }
        }
    }

end BrowserAssertionTest
