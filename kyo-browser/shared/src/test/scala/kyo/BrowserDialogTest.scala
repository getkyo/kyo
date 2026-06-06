package kyo

class BrowserDialogTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- withDialogs ----

    "withDialogs.accept returns true for confirm and the empty string for prompt" in {
        withBrowser {
            onPage("""<html><body>
            <button id='confirmBtn' onclick="window.__confirmResult = confirm('proceed?');">Confirm</button>
            <button id='promptBtn' onclick="window.__promptResult = prompt('name?');">Prompt</button>
        </body></html>""") {
                Browser.withDialogs.accept {
                    Browser.click(Browser.Selector.css("#confirmBtn")).andThen {
                        Browser.click(Browser.Selector.css("#promptBtn")).andThen {
                            Browser.eval("String(window.__confirmResult)").map { confirmResult =>
                                assert(confirmResult == "true", s"Expected confirm to return true, got '$confirmResult'")
                            }.andThen {
                                Browser.eval("String(window.__promptResult)").map { promptResult =>
                                    assert(promptResult == "", s"Expected prompt to return empty string, got '$promptResult'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "withDialogs.prompt suppresses alert and returns expected confirm/prompt values" in {
        withBrowser {
            onPage("""<html><body>
            <button id='alertBtn' onclick="window.__alertCalled = true; alert('hello');">Alert</button>
            <button id='confirmBtn' onclick="window.__confirmResult = confirm('proceed?');">Confirm</button>
            <button id='promptBtn' onclick="window.__promptResult = prompt('name?');">Prompt</button>
        </body></html>""") {
                Browser.withDialogs.prompt("Alice") {
                    // Click alert button; should not block
                    Browser.click(Browser.Selector.css("#alertBtn")).andThen {
                        // Click confirm button; should return true
                        Browser.click(Browser.Selector.css("#confirmBtn")).andThen {
                            // Click prompt button; should return "Alice"
                            Browser.click(Browser.Selector.css("#promptBtn")).andThen {
                                Browser.eval("String(window.__confirmResult)").map { confirmResult =>
                                    assert(confirmResult == "true", s"Expected confirm to return true, got '$confirmResult'")
                                }.andThen {
                                    Browser.eval("String(window.__promptResult)").map { promptResult =>
                                        assert(promptResult == "Alice", s"Expected prompt to return 'Alice', got '$promptResult'")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "withDialogs.dismiss returns false for confirm and null for prompt" in {
        withBrowser {
            onPage("""<html><body>
            <button id='confirmBtn' onclick="window.__confirmResult = confirm('proceed?');">Confirm</button>
            <button id='promptBtn' onclick="window.__promptResult = prompt('name?');">Prompt</button>
        </body></html>""") {
                Browser.withDialogs.dismiss {
                    Browser.click(Browser.Selector.css("#confirmBtn")).andThen {
                        Browser.click(Browser.Selector.css("#promptBtn")).andThen {
                            Browser.eval("String(window.__confirmResult)").map { confirmResult =>
                                assert(confirmResult == "false", s"Expected confirm to return false, got '$confirmResult'")
                            }.andThen {
                                Browser.eval("String(window.__promptResult)").map { promptResult =>
                                    assert(promptResult == "null", s"Expected prompt to return null, got '$promptResult'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a dialog opening without withDialogs is auto-dismissed and does not hang" in {
        withBrowser {
            onPage("""<html><body>
            <button id='b' onclick="window.__r = confirm('proceed?');">Confirm</button>
        </body></html>""") {
                // No withDialogs! confirm() opens a CDP-level dialog. The Absent-handler branch must
                // auto-dismiss with permissive defaults so confirm() returns false synchronously;
                // otherwise Chrome freezes JS waiting for Page.handleJavaScriptDialog and the click
                // hangs at the test's 30-second Async.timeout.
                Browser.click(Browser.Selector.id("b")).andThen {
                    Browser.eval("String(window.__r)").map { r =>
                        assert(r == "false", s"expected confirm to auto-dismiss to 'false' but got '$r'")
                    }
                }
            }
        }
    }

    "nested withDialogs restores outer handler on inner exit" in {
        withBrowser {
            onPage("""<html><body>
            <button id='b' onclick="window.__r1 = prompt('?'); window.__r2 = prompt('?');">go</button>
        </body></html>""") {
                Browser.withDialogs.prompt("outer") {
                    Browser.withDialogs.prompt("inner") {
                        // Inside inner: clicking the button fires TWO prompts. Both should be handled
                        // by the inner handler, so __r1 should be "inner".
                        Browser.click(Browser.Selector.id("b")).andThen {
                            Browser.eval("String(window.__r1)").map { r =>
                                assert(r == "inner", s"expected inner-scope prompt to return 'inner' but got '$r'")
                            }
                        }
                    }.andThen {
                        // Back in outer scope: clear state and re-trigger. The getAndSet save/restore
                        // must leave the AtomicRef holding Present(true, "outer"); otherwise inner's
                        // Scope.ensure would have cleared the handler entirely and outer's prompt
                        // would auto-dismiss to null instead of returning "outer".
                        Browser.eval("window.__r1 = null; window.__r2 = null; void 0").andThen {
                            Browser.click(Browser.Selector.id("b")).andThen {
                                Browser.eval("String(window.__r1)").map { r =>
                                    assert(r == "outer", s"expected outer-scope prompt to return 'outer' after inner exit but got '$r'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- withDialogs.recorded ----

    "withDialogs.recorded captures alert, confirm, prompt in order with the right type and response" in {
        withBrowser {
            onPage("""<html><body>
            <button id='a' onclick="alert('a-msg');">A</button>
            <button id='c' onclick="confirm('c-msg');">C</button>
            <button id='p' onclick="window.__p = prompt('p-msg', 'def');">P</button>
        </body></html>""") {
                Browser.withDialogs.recorded {
                    Browser.withDialogs.prompt("answer") {
                        Browser.click(Browser.Selector.id("a")).andThen {
                            Browser.click(Browser.Selector.id("c")).andThen {
                                Browser.click(Browser.Selector.id("p"))
                            }
                        }
                    }
                }.map { case (events, _) =>
                    assert(events.size == 3, s"Expected 3 captured dialog events but got ${events.size}: $events")
                    assert(events(0).kind == Browser.DialogType.Alert, s"Expected Alert but got ${events(0)}")
                    assert(events(0).message == "a-msg", s"Expected alert message 'a-msg' but got '${events(0).message}'")
                    assert(events(1).kind == Browser.DialogType.Confirm, s"Expected Confirm but got ${events(1)}")
                    assert(events(1).message == "c-msg", s"Expected confirm message 'c-msg' but got '${events(1).message}'")
                    assert(events(2).kind == Browser.DialogType.Prompt, s"Expected Prompt but got ${events(2)}")
                    assert(events(2).message == "p-msg", s"Expected prompt message 'p-msg' but got '${events(2).message}'")
                    assert(
                        events(2).response == Present("answer"),
                        s"Expected prompt response Present('answer') but got ${events(2).response}"
                    )
                }
            }
        }
    }

end BrowserDialogTest
