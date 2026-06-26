package kyo

import kyo.internal.BrowserTab
import kyo.internal.CdpBackend
import kyo.internal.CdpTypes.*

class BrowserIsolateTest extends BrowserTest:

    // withFork/withNewTab clone a tab: a dozen-plus CDP round-trips plus a navigation and a snapshot
    // restore. On a saturated CI box (4 vCPUs shared by two test forks and chrome-headless-shell) that can
    // outrun a tight 90s budget, the same CI-contention latency the download suite already budgets for. This
    // is a wait budget, not a perf assertion; the leaf assertions still fail fast on a real regression.
    override def timeout = 180.seconds

    // ---- withNewTab ----

    "withNewTab creates tab at about:blank" in {
        withBrowser {
            Browser.withNewTab {
                Browser.url.map { u =>
                    assert(u == "about:blank", s"Expected 'about:blank' but got '$u'")
                }
            }
        }
    }

    "withNewTab goto and title works independently" in {
        val p = page("<html><head><title>FreshPage</title></head><body>Fresh</body></html>")
        withBrowser {
            Browser.withNewTab {
                Browser.goto(p).map { _ =>
                    Browser.title.map { t =>
                        assert(t == "FreshPage", s"Expected 'FreshPage' but got '$t'")
                    }
                }
            }
        }
    }

    "withNewTab parent tab unaffected" in {
        val parentPage = page("<html><head><title>ParentPage</title></head><body>Parent</body></html>")
        val freshPage  = page("<html><head><title>FreshChild</title></head><body>Child</body></html>")
        withBrowser {
            Browser.goto(parentPage).map { _ =>
                Browser.url.map { parentUrl =>
                    Browser.withNewTab {
                        Browser.goto(freshPage).map { _ =>
                            Browser.title.map { t =>
                                assert(t == "FreshChild", s"Expected 'FreshChild' but got '$t'")
                            }
                        }
                    }.map { _ =>
                        Browser.url.map { urlAfter =>
                            assert(urlAfter == parentUrl, s"Parent URL should be unchanged. Expected '$parentUrl' but got '$urlAfter'")
                        }
                    }
                }
            }
        }
    }

    "withNewTab error inside block still cleans up tab" in {
        withBrowser {
            // Count targets before
            Browser.eval("'before'").map { _ =>
                Abort.run[Throwable] {
                    Browser.withNewTab {
                        Browser.eval("'in-fresh'").map { _ =>
                            Abort.fail(new RuntimeException("intentional error"))
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Failure(e: RuntimeException) =>
                            assert(e.getMessage == "intentional error", s"unexpected message: ${e.getMessage}")
                        case other => fail(s"expected RuntimeException('intentional error') but got $other")
                    end match
                    // If the tab was cleaned up, the parent tab is still functional
                    Browser.url.map { u =>
                        assert(u == "about:blank", s"Parent tab should still be at about:blank but got '$u'")
                    }
                }
            }
        }
    }

    "withNewTab inside withNewTab - nested tabs" in {
        val p1 = page("<h1>Outer</h1>")
        val p2 = page("<h1>Inner</h1>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.withNewTab {
                    Browser.goto(p2).map { _ =>
                        Browser.url.map { u =>
                            assert(u.startsWith("data:text/html"), s"Inner tab should have navigated but got: $u")
                        }
                    }
                }.map { _ =>
                    // Outer tab should still have its URL
                    Browser.url.map { u =>
                        assert(u.startsWith("data:text/html"), s"Outer tab URL should be preserved: $u")
                    }
                }
            }
        }
    }

    // ---- withFork ----

    "withFork preserves URL" in {
        val p = page("<h1>Clone Me</h1>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withFork {
                    Browser.url.map { cloneUrl =>
                        assert(cloneUrl.startsWith("data:text/html"), s"Expected data URL but got: $cloneUrl")
                    }
                }
            }
        }
    }

    "withFork preserves page content" in {
        val p = page("<html><head><title>CloneTitle</title></head><body>CloneBody</body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withFork {
                    Browser.title.map { t =>
                        assert(t == "CloneTitle", s"Expected 'CloneTitle' but got '$t'")
                    }
                }
            }
        }
    }

    "withFork on about:blank works" in {
        withBrowser {
            Browser.withFork {
                Browser.url.map { u =>
                    assert(u == "about:blank", s"Expected 'about:blank' but got '$u'")
                }
            }
        }
    }

    "withFork does not affect parent tab" in {
        val parentPage = page("<html><head><title>Parent</title></head><body>Parent</body></html>")
        val childPage  = page("<html><head><title>Child</title></head><body>Child</body></html>")
        withBrowser {
            Browser.goto(parentPage).map { _ =>
                Browser.withFork {
                    // Navigate to a different page in the clone
                    Browser.goto(childPage).map { _ =>
                        Browser.title.map { t =>
                            assert(t == "Child", s"Clone should have navigated to child page but title is '$t'")
                        }
                    }
                }.map { _ =>
                    // Parent should still be on its original page
                    Browser.title.map { t =>
                        assert(t == "Parent", s"Parent should still be on parent page but title is '$t'")
                    }
                }
            }
        }
    }

    "withFork preserves form field values" in {
        val p = page(
            """<body><input id="name" type="text" value=""><select id="color"><option value="red">Red</option><option value="blue">Blue</option></select></body>"""
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("document.getElementById('name').value = 'Alice'").map { _ =>
                    Browser.eval("document.getElementById('color').value = 'blue'").map { _ =>
                        Browser.withFork {
                            Browser.eval("document.getElementById('name').value").map { name =>
                                Browser.eval("document.getElementById('color').value").map { color =>
                                    assert(name == "Alice", s"Expected 'Alice' but got '$name'")
                                    assert(color == "blue", s"Expected 'blue' but got '$color'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "withFork on page with history gets current URL only" in {
        val p1 = page("<html><head><title>Page1</title></head><body>Page1</body></html>")
        val p2 = page("<html><head><title>Page2</title></head><body>Page2</body></html>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.goto(p2).map { _ =>
                    Browser.url.map { parentUrl =>
                        Browser.withFork {
                            Browser.url.map { cloneUrl =>
                                assert(
                                    cloneUrl == parentUrl,
                                    s"Clone URL should match parent's current URL. Expected '$parentUrl' but got '$cloneUrl'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "withFork mutations in fork don't affect parent" in {
        val p1 = page("<html><head><title>Original</title></head><body>Original</body></html>")
        val p2 = page("<html><head><title>Mutated</title></head><body>Mutated</body></html>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.url.map { parentUrlBefore =>
                    Browser.withFork {
                        Browser.goto(p2).map { _ =>
                            Browser.title.map { t =>
                                assert(t == "Mutated", s"Clone should have navigated but title is '$t'")
                            }
                        }
                    }.map { _ =>
                        Browser.url.map { parentUrlAfter =>
                            assert(
                                parentUrlAfter == parentUrlBefore,
                                s"Parent URL should be unchanged. Expected '$parentUrlBefore' but got '$parentUrlAfter'"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- withNewTab / withFork resource cleanup ----

    "withNewTab tabs cleaned up after scope exit" in {
        withBrowser {
            Browser.use { parent =>
                // Snapshot CDP target IDs before opening the child tab.
                CdpBackend.getTargets(parent.backend).map { before =>
                    val beforeIds = before.targetInfos.map(_.targetId).toSet
                    // Wrap `withNewTab` in a nested `Scope.run` so that the `Scope.ensure(closeTarget)` registered by
                    // `withNewTab` actually fires when *this* block exits; rather than being deferred to the outer
                    // `withBrowser` scope. This is what "cleanup after scope exit" must mean for the assertion to
                    // be meaningful.
                    Scope.run {
                        Browser.withNewTab {
                            Browser.use { child =>
                                // Snapshot inside the scope: child target ID must be present in the live target list.
                                CdpBackend.getTargets(parent.backend).map { during =>
                                    val duringIds = during.targetInfos.map(_.targetId).toSet
                                    val childId   = child.targetId.value
                                    assert(
                                        !beforeIds.contains(childId),
                                        s"Child target ID '$childId' should be NEW (not present before withNewTab)"
                                    )
                                    assert(
                                        duringIds.contains(childId),
                                        s"Child target ID '$childId' should be present in CDP target list during withNewTab; got $duringIds"
                                    )
                                    childId
                                }
                            }
                        }
                    }.map { childId =>
                        // After the `Scope.run` block exits, the child target must be ABSENT from Target.getTargets.
                        // Chrome's `Target.closeTarget` is asynchronous from the page lifecycle's perspective, so
                        // poll briefly: a real cleanup-leak shows as the target staying in the list past the budget.
                        Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(20)) {
                            CdpBackend.getTargets(parent.backend).map { after =>
                                val afterIds = after.targetInfos.map(_.targetId).toSet
                                if afterIds.contains(childId) then
                                    Abort.fail[BrowserConnectionException](
                                        BrowserProtocolErrorException(
                                            "withNewTab cleanup",
                                            s"child target ID '$childId' still present after scope exit; got $afterIds"
                                        )
                                    )
                                else ()
                                end if
                            }
                        }.map { _ =>
                            CdpBackend.getTargets(parent.backend).map { after =>
                                val afterIds = after.targetInfos.map(_.targetId).toSet
                                assert(
                                    !afterIds.contains(childId),
                                    s"Child target ID '$childId' must be absent from Target.getTargets after scope exit; got $afterIds"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "withFork tabs cleaned up after scope exit" in {
        val p = page("<html><head><title>CleanupTest</title></head><body>test</body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withFork {
                    Browser.eval("'clone-tab-alive'").map { v =>
                        assert(v == "clone-tab-alive", s"Clone tab should be functional")
                    }
                }.map { _ =>
                    // After withFork exits, verify parent is still functional
                    Browser.title.map { t =>
                        assert(t == "CleanupTest", s"Parent should still have title 'CleanupTest' but got '$t'")
                    }
                }
            }
        }
    }

    // --- withFork limitations ---
    // These tests document inherent limitations of withFork. They are NOT bugs.
    // withFork works by capturing a snapshot (URL, storage, cookies, form fields)
    // and restoring it in a new tab via navigation + JS restoration. Anything not
    // in that snapshot is lost.

    "withFork does not preserve JS in-memory state" in {
        // SPA state (Redux, Zustand, React component state, global variables) lives
        // in JS heap memory and is not part of the snapshot. After cloning, the page
        // is re-loaded from the URL, so all in-memory state is gone.
        val p = page("<html><body><p id='out'></p></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("window.myState = 42; String(window.myState)").map { before =>
                    assert(before == "42", s"State should be set before clone: $before")
                    Browser.withFork {
                        Browser.eval("String(typeof window.myState)").map { after =>
                            assert(after == "undefined", s"JS in-memory state should be lost in clone, but got: $after")
                        }
                    }
                }
            }
        }
    }

    "withFork does not preserve dynamically added DOM elements" in {
        // Clone navigates to the same URL, which re-renders the original HTML.
        // Any DOM mutations (appendChild, innerHTML changes) are lost because
        // the snapshot only captures the URL, not the live DOM tree.
        val p = page("<html><body><div id='container'></div></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval(
                    """(() => {
                        const el = document.createElement('span');
                        el.id = 'dynamic';
                        el.textContent = 'added at runtime';
                        document.getElementById('container').appendChild(el);
                        return document.getElementById('dynamic').textContent;
                    })()"""
                ).map { before =>
                    assert(before == "added at runtime", s"Element should exist before clone: $before")
                    Browser.withFork {
                        Browser.eval("document.getElementById('dynamic') === null ? 'missing' : 'found'").map { after =>
                            assert(after == "missing", s"Dynamically added element should be lost in clone, but got: $after")
                        }
                    }
                }
            }
        }
    }

    "withFork preserves scroll position" in {
        val p = page(
            """<html><body style="height:5000px"><div id="top">top</div><div id="bottom" style="position:absolute;top:4000px">bottom</div></body></html>"""
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("window.scrollTo(0, 2000); String(window.scrollY)").map { scrolled =>
                    assert(scrolled.toInt > 0, s"Should have scrolled down, but scrollY is $scrolled")
                    Browser.withFork {
                        Browser.eval("String(window.scrollY)").map { cloneScroll =>
                            assert(
                                cloneScroll.toInt >= 1900,
                                s"Scroll position should be preserved in clone (close to 2000), but scrollY is $cloneScroll"
                            )
                        }
                    }
                }
            }
        }
    }

    "withFork preserves focused element" in {
        val p = page(
            """<html><body><input id="first" type="text"><input id="second" type="text"></body></html>"""
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("document.getElementById('second').focus(); document.activeElement.id").map { focused =>
                    assert(focused == "second", s"Expected 'second' to be focused but got '$focused'")
                    Browser.withFork {
                        Browser.eval("document.activeElement ? document.activeElement.id : ''").map { cloneFocused =>
                            assert(cloneFocused == "second", s"Expected 'second' to be focused in clone but got '$cloneFocused'")
                        }
                    }
                }
            }
        }
    }

    "withFork preserves cursor position in text input" in {
        val p = page(
            """<html><body><input id="myinput" type="text" value=""></body></html>"""
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval(
                    """(() => {
                        const el = document.getElementById('myinput');
                        el.value = 'hello world';
                        el.focus();
                        el.setSelectionRange(3, 7);
                        return el.selectionStart + ',' + el.selectionEnd;
                    })()"""
                ).map { cursorBefore =>
                    assert(cursorBefore == "3,7", s"Expected cursor at '3,7' but got '$cursorBefore'")
                    Browser.withFork {
                        Browser.eval(
                            """(() => {
                                const el = document.getElementById('myinput');
                                if (!el || typeof el.selectionStart !== 'number') return 'no-selection';
                                return el.selectionStart + ',' + el.selectionEnd;
                            })()"""
                        ).map { cloneCursor =>
                            assert(cloneCursor == "3,7", s"Expected cursor at '3,7' in clone but got '$cloneCursor'")
                        }
                    }
                }
            }
        }
    }

    "withFork form fields are restored after page re-render" in {
        // This test documents the HAPPY PATH: captureSnapshot reads form field values
        // from the live DOM, and restoreSnapshot re-applies them via JS after the
        // cloned page loads. This works for static HTML pages.
        // Limitation: on pages with controlled inputs (React/Angular), the framework's
        // re-render may overwrite the restored values. This can't be easily tested
        // with data: URLs.
        // Note: POST-based page state is also not preserved; withFork always
        // navigates via GET, so server-rendered content from a POST submission
        // will differ in the fork.
        val p = page(
            """<html><body><input id="field1" type="text" value=""><textarea id="field2"></textarea></body></html>"""
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("document.getElementById('field1').value = 'hello'; 'ok'").map { _ =>
                    Browser.eval("document.getElementById('field2').value = 'world'; 'ok'").map { _ =>
                        Browser.withFork {
                            Browser.eval("document.getElementById('field1').value").map { v1 =>
                                Browser.eval("document.getElementById('field2').value").map { v2 =>
                                    assert(v1 == "hello", s"Form field1 should be restored in clone but got: $v1")
                                    assert(v2 == "world", s"Form field2 should be restored in clone but got: $v2")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- isolate.fresh ----

    "isolate.fresh gives each fiber its own tab" in {
        withBrowser {
            val p1 = page("<h1>Fiber1</h1>")
            val p2 = page("<h1>Fiber2</h1>")
            Browser.isolate.fresh.use {
                Async.zip(
                    Browser.goto(p1).andThen(Browser.url),
                    Browser.goto(p2).andThen(Browser.url)
                ).map { (url1, url2) =>
                    assert(url1 != url2, s"Each fiber should have its own tab, but both got: $url1")
                }
            }
        }
    }

    "isolate.fresh 5 parallel fibers get independent tabs" in {
        withBrowser {
            val p1 = page("<html><head><title>F1</title></head><body>F1</body></html>")
            val p2 = page("<html><head><title>F2</title></head><body>F2</body></html>")
            val p3 = page("<html><head><title>F3</title></head><body>F3</body></html>")
            val p4 = page("<html><head><title>F4</title></head><body>F4</body></html>")
            val p5 = page("<html><head><title>F5</title></head><body>F5</body></html>")
            Browser.isolate.fresh.use {
                Async.zip(
                    Browser.goto(p1).andThen(Browser.title),
                    Browser.goto(p2).andThen(Browser.title),
                    Browser.goto(p3).andThen(Browser.title),
                    Browser.goto(p4).andThen(Browser.title),
                    Browser.goto(p5).andThen(Browser.title)
                ).map { (t1, t2, t3, t4, t5) =>
                    val titles = Set(t1, t2, t3, t4, t5)
                    assert(titles.size == 5, s"Expected 5 distinct titles but got ${titles.size}: $titles")
                    assert(titles == Set("F1", "F2", "F3", "F4", "F5"), s"Unexpected titles: $titles")
                }
            }
        }
    }

    "isolate.fresh error in one fiber doesn't affect others" in {
        withBrowser {
            val p1 = page("<html><head><title>GoodFiber</title></head><body>Good</body></html>")
            Browser.isolate.fresh.use {
                Async.zip(
                    Abort.run[Throwable] {
                        Browser.eval("'before-error'").map { _ =>
                            Abort.fail(new RuntimeException("fiber error"))
                        }
                    },
                    Browser.goto(p1).andThen(Browser.title)
                ).map { (errorResult, title) =>
                    errorResult match
                        case Result.Failure(e: RuntimeException) =>
                            assert(e.getMessage == "fiber error", s"unexpected message: ${e.getMessage}")
                        case other => fail(s"expected RuntimeException('fiber error') but got $other")
                    end match
                    assert(title == "GoodFiber", s"Second fiber should complete normally but got title '$title'")
                }
            }
        }
    }

    "isolate.fresh each fiber navigates independently no cross-contamination" in {
        withBrowser {
            val pA = page("<html><head><title>PageA</title></head><body>A</body></html>")
            val pB = page("<html><head><title>PageB</title></head><body>B</body></html>")
            Browser.isolate.fresh.use {
                Async.zip(
                    Browser.goto(pA).map { _ =>
                        Browser.title.map { t =>
                            // Verify we're on page A, not B
                            assert(t == "PageA", s"Fiber A should see PageA but got '$t'")
                            t
                        }
                    },
                    Browser.goto(pB).map { _ =>
                        Browser.title.map { t =>
                            // Verify we're on page B, not A
                            assert(t == "PageB", s"Fiber B should see PageB but got '$t'")
                            t
                        }
                    }
                ).map { (tA, tB) =>
                    assert(tA == "PageA", s"Expected 'PageA' but got '$tA'")
                    assert(tB == "PageB", s"Expected 'PageB' but got '$tB'")
                }
            }
        }
    }

    "isolate.fresh parent tab unaffected by child fiber operations" in {
        val parentPage = page("<html><head><title>ParentIso</title></head><body>Parent</body></html>")
        withBrowser {
            Browser.goto(parentPage).map { _ =>
                Browser.url.map { parentUrl =>
                    Browser.isolate.fresh.use {
                        val child1 = page("<html><head><title>Child1</title></head><body>C1</body></html>")
                        val child2 = page("<html><head><title>Child2</title></head><body>C2</body></html>")
                        Async.zip(
                            Browser.goto(child1).andThen(Browser.title),
                            Browser.goto(child2).andThen(Browser.title)
                        )
                    }.map { (t1, t2) =>
                        assert(t1 == "Child1", s"Expected 'Child1' but got '$t1'")
                        assert(t2 == "Child2", s"Expected 'Child2' but got '$t2'")
                        // Verify parent is unaffected
                        Browser.url.map { urlAfter =>
                            assert(
                                urlAfter == parentUrl,
                                s"Parent URL should be unchanged after isolate.fresh. Expected '$parentUrl' but got '$urlAfter'"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- isolate.clone ----

    "isolate.clone preserves URL across fibers" in {
        val p = page("<html><head><title>Cloned</title></head><body>Hello</body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.isolate.clone.use {
                    Async.zip(
                        Browser.url,
                        Browser.url
                    ).map { (cloneUrl1, cloneUrl2) =>
                        assert(cloneUrl1.startsWith("data:text/html"), s"Expected data URL but got '$cloneUrl1'")
                        assert(cloneUrl2.startsWith("data:text/html"), s"Expected data URL but got '$cloneUrl2'")
                    }
                }
            }
        }
    }

    "isolate.clone parallel fibers each get clone with same URL" in {
        val p = page("<html><head><title>CloneSource</title></head><body>Source</body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.url.map { parentUrl =>
                    Browser.isolate.clone.use {
                        Async.zip(
                            Browser.url,
                            Browser.url
                        ).map { (url1, url2) =>
                            assert(url1 == parentUrl, s"Clone 1 URL should match parent. Expected '$parentUrl' but got '$url1'")
                            assert(url2 == parentUrl, s"Clone 2 URL should match parent. Expected '$parentUrl' but got '$url2'")
                        }
                    }
                }
            }
        }
    }

    "isolate.clone mutations in one clone don't affect other clone or parent" in {
        val p1 = page("<html><head><title>CloneOrig</title></head><body>Original</body></html>")
        val p2 = page("<html><head><title>Navigated</title></head><body>Navigated</body></html>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.url.map { parentUrl =>
                    Browser.isolate.clone.use {
                        Async.zip(
                            // Fiber 1: navigate away
                            Browser.goto(p2).andThen(Browser.title),
                            // Fiber 2: stay and verify original URL
                            Browser.url.map { u =>
                                assert(
                                    u == parentUrl,
                                    s"Clone 2 should still be at parent URL. Expected '$parentUrl' but got '$u'"
                                )
                                u
                            }
                        )
                    }.map { (t1, url2) =>
                        assert(t1 == "Navigated", s"Clone 1 should have navigated but got '$t1'")
                        // Verify parent is unchanged
                        Browser.url.map { parentUrlAfter =>
                            assert(
                                parentUrlAfter == parentUrl,
                                s"Parent URL should be unchanged. Expected '$parentUrl' but got '$parentUrlAfter'"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- browserContextId: Maybe[String] (TYPES-9) ----

    "withNewTab propagates parent's browserContextId as Maybe.Present to the child tab" in {
        withBrowser {
            Browser.use { parent =>
                val parentCtx = parent.browserContextId match
                    case Present(id) => id
                    case Absent =>
                        fail("Parent tab from Browser.run must have a Present browserContextId")
                Browser.withNewTab {
                    Browser.use { child =>
                        child.browserContextId match
                            case Present(id) =>
                                assert(
                                    id == parentCtx,
                                    s"Child tab should inherit parent's browserContextId. Expected '$parentCtx' but got '$id'"
                                )
                            case Absent =>
                                fail("Child tab from withNewTab should inherit parent's Present browserContextId, but was Absent")
                    }
                }
            }
        }
    }

    "BrowserTab constructed without a context has browserContextId == Maybe.Absent" in {
        withBrowser {
            Browser.use { parent =>
                for
                    fctx      <- AtomicRef.init[Dict[FrameId, ExecutionContextId]](Dict.empty)
                    root      <- AtomicRef.init[Maybe[FrameId]](Absent)
                    console   <- AtomicBoolean.init(false)
                    response  <- AtomicBoolean.init(false)
                    viewport  <- AtomicRef.init[Maybe[BrowserTab.ViewportOverride]](Absent)
                    emulation <- AtomicRef.init[Maybe[BrowserTab.EmulatedMediaState]](Absent)
                    download  <- AtomicRef.init[Maybe[(Browser.DownloadBehavior, Maybe[String])]](Absent)
                    tab = new BrowserTab(
                        TargetId("t-test"),
                        SessionId("s-test"),
                        parent.backend,
                        Absent,
                        fctx,
                        root,
                        console,
                        response,
                        viewport,
                        emulation,
                        download
                    )
                yield tab.browserContextId match
                    case Present(id) => fail(s"Expected Absent but got Present($id)")
                    case Absent =>
                        assert(tab.browserContextId == Absent, "browserContextId must be Absent for a tab constructed without a context")
                end for
            }
        }
    }

    // ---- withFork storage preservation ----

    "withFork preserves localStorage" in {
        withBrowserOnLocalhost {
            Browser.eval("localStorage.setItem('lk', 'lv'); 'ok'").andThen {
                Browser.withFork {
                    Browser.eval("localStorage.getItem('lk')").map { v =>
                        assert(v == "lv", s"Expected localStorage 'lk' to be 'lv' in clone but got '$v'")
                    }
                }
            }
        }
    }

    "withFork preserves sessionStorage" in {
        withBrowserOnLocalhost {
            Browser.eval("sessionStorage.setItem('sk', 'sv'); 'ok'").andThen {
                Browser.withFork {
                    Browser.eval("sessionStorage.getItem('sk')").map { v =>
                        assert(v == "sv", s"Expected sessionStorage 'sk' to be 'sv' in clone but got '$v'")
                    }
                }
            }
        }
    }

    // ---- isolate.clone per-field preservation ----

    "isolate.clone preserves cookies across forks" in {
        withBrowserOnLocalhost {
            Browser.setCookie("ck", "cv", "localhost").andThen {
                Browser.isolate.clone.use {
                    Async.zip(
                        Browser.cookies.map(cs => cs.exists(c => c.name == "ck" && c.value == "cv")),
                        Browser.cookies.map(cs => cs.exists(c => c.name == "ck" && c.value == "cv"))
                    ).map { (f1, f2) =>
                        assert(f1, "Fork 1 should see cookie 'ck' = 'cv'")
                        assert(f2, "Fork 2 should see cookie 'ck' = 'cv'")
                    }
                }
            }
        }
    }

    "isolate.clone preserves form fields across forks" in {
        withBrowser {
            onPage(
                """<html><body><input id="name" type="text" value=""><input id="email" type="text" value=""></body></html>"""
            ) {
                Browser.eval("document.getElementById('name').value = 'Alice'; 'ok'").andThen {
                    Browser.eval("document.getElementById('email').value = 'a@x.com'; 'ok'").andThen {
                        Browser.isolate.clone.use {
                            Async.zip(
                                Browser.eval("document.getElementById('name').value + '|' + document.getElementById('email').value"),
                                Browser.eval("document.getElementById('name').value + '|' + document.getElementById('email').value")
                            ).map { (v1, v2) =>
                                assert(v1 == "Alice|a@x.com", s"Fork 1 should see form fields restored but got '$v1'")
                                assert(v2 == "Alice|a@x.com", s"Fork 2 should see form fields restored but got '$v2'")
                            }
                        }
                    }
                }
            }
        }
    }

    "isolate.clone preserves localStorage across forks" in {
        withBrowserOnLocalhost {
            Browser.eval("localStorage.setItem('ik', 'iv'); 'ok'").andThen {
                Browser.isolate.clone.use {
                    Async.zip(
                        Browser.eval("localStorage.getItem('ik')"),
                        Browser.eval("localStorage.getItem('ik')")
                    ).map { (v1, v2) =>
                        assert(v1 == "iv", s"Fork 1 should see localStorage 'ik' = 'iv' but got '$v1'")
                        assert(v2 == "iv", s"Fork 2 should see localStorage 'ik' = 'iv' but got '$v2'")
                    }
                }
            }
        }
    }

    "isolate.clone preserves sessionStorage across forks" in {
        withBrowserOnLocalhost {
            Browser.eval("sessionStorage.setItem('sik', 'siv'); 'ok'").andThen {
                Browser.isolate.clone.use {
                    Async.zip(
                        Browser.eval("sessionStorage.getItem('sik')"),
                        Browser.eval("sessionStorage.getItem('sik')")
                    ).map { (v1, v2) =>
                        assert(v1 == "siv", s"Fork 1 should see sessionStorage 'sik' = 'siv' but got '$v1'")
                        assert(v2 == "siv", s"Fork 2 should see sessionStorage 'sik' = 'siv' but got '$v2'")
                    }
                }
            }
        }
    }

    "isolate.clone preserves scroll position across forks" in {
        withBrowser {
            onPage(
                """<html><body style="height:5000px"><div id="top">top</div></body></html>"""
            ) {
                Browser.eval("window.scrollTo(0, 2000); String(window.scrollY)").map { scrolled =>
                    assert(scrolled.toInt > 0, s"Parent should have scrolled but scrollY=$scrolled")
                    Browser.isolate.clone.use {
                        Async.zip(
                            Browser.eval("String(window.scrollY)"),
                            Browser.eval("String(window.scrollY)")
                        ).map { (s1, s2) =>
                            assert(s1.toInt >= 1900, s"Fork 1 scrollY should be >=1900 but got $s1")
                            assert(s2.toInt >= 1900, s"Fork 2 scrollY should be >=1900 but got $s2")
                        }
                    }
                }
            }
        }
    }

    "isolate.clone preserves focused element across forks" in {
        withBrowser {
            onPage(
                """<html><body><input id="alpha" type="text"><input id="beta" type="text"></body></html>"""
            ) {
                Browser.eval("document.getElementById('beta').focus(); document.activeElement.id").map { focused =>
                    assert(focused == "beta", s"Parent should have 'beta' focused but got '$focused'")
                    Browser.isolate.clone.use {
                        Async.zip(
                            Browser.eval("document.activeElement ? document.activeElement.id : ''"),
                            Browser.eval("document.activeElement ? document.activeElement.id : ''")
                        ).map { (f1, f2) =>
                            assert(f1 == "beta", s"Fork 1 should have 'beta' focused but got '$f1'")
                            assert(f2 == "beta", s"Fork 2 should have 'beta' focused but got '$f2'")
                        }
                    }
                }
            }
        }
    }

    "isolate.clone preserves cursor position across forks" in {
        withBrowser {
            onPage(
                """<html><body><input id="myinput" type="text" value=""></body></html>"""
            ) {
                Browser.eval(
                    """(() => {
                        const el = document.getElementById('myinput');
                        el.value = 'hello world';
                        el.focus();
                        el.setSelectionRange(2, 6);
                        return el.selectionStart + ',' + el.selectionEnd;
                    })()"""
                ).map { cursorBefore =>
                    assert(cursorBefore == "2,6", s"Parent cursor should be at '2,6' but got '$cursorBefore'")
                    Browser.isolate.clone.use {
                        Async.zip(
                            Browser.eval(
                                """(() => {
                                    const el = document.getElementById('myinput');
                                    if (!el || typeof el.selectionStart !== 'number') return 'no-selection';
                                    return el.selectionStart + ',' + el.selectionEnd;
                                })()"""
                            ),
                            Browser.eval(
                                """(() => {
                                    const el = document.getElementById('myinput');
                                    if (!el || typeof el.selectionStart !== 'number') return 'no-selection';
                                    return el.selectionStart + ',' + el.selectionEnd;
                                })()"""
                            )
                        ).map { (c1, c2) =>
                            assert(c1 == "2,6", s"Fork 1 cursor should be at '2,6' but got '$c1'")
                            assert(c2 == "2,6", s"Fork 2 cursor should be at '2,6' but got '$c2'")
                        }
                    }
                }
            }
        }
    }

    // ---- Cross-cutting: cleanup on interrupt, config inheritance, dialog isolation ----

    "withFork cleanup runs when outer scope is interrupted" in {
        val p = page("<html><head><title>InterruptTest</title></head><body>InterruptTest</body></html>")
        // Capture the shared Chrome's wsUrl so both the outer scope (used to observe targets) and the inner
        // timed-out Browser.run attach to the SAME Chrome process via separate connections.
        type E = BrowserReadException | BrowserSetupException | Timeout
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Browser.run(wsUrl) {
                Browser.use { parent =>
                    val client = parent.backend
                    Browser.goto(p).andThen(CdpBackend.getTargets(client)).map { before =>
                        val beforeIds = before.targetInfos.map(_.targetId).toSet
                        // The timeout interrupts withFork mid-flight; the inner Browser.run's Scope.run (and
                        // the Scope.ensure inside createChildTab) must still fire to dispose the forked context.
                        val timedOutWork: Unit < (Async & Abort[E]) =
                            Async.timeout(300.millis) {
                                Browser.run(wsUrl) {
                                    Browser.goto(p).andThen {
                                        Browser.withFork {
                                            // Block forever-but-interruptible: the outer Async.timeout(300.millis) cancels the fiber.
                                            Promise.init[Unit, Any].map(_.get)
                                        }
                                    }
                                }
                            }
                        Abort.run[E](timedOutWork).map { result =>
                            // race between timeout cancellation and downstream Browser reads; either typed shape is acceptable; the deliverable is the cleanup-on-interrupt observation that follows.
                            assert(result.isFailure, s"Expected timeout failure but got $result")
                            // After interrupt, poll briefly: any new target IDs created by withFork must be gone.
                            Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(40)) {
                                CdpBackend.getTargets(client).map { after =>
                                    val afterIds = after.targetInfos.map(_.targetId).toSet
                                    val leaked   = afterIds -- beforeIds
                                    if leaked.nonEmpty then
                                        Abort.fail[BrowserConnectionException](
                                            BrowserProtocolErrorException(
                                                "withFork interrupt cleanup",
                                                s"target IDs leaked after interrupt: $leaked"
                                            )
                                        )
                                    else ()
                                    end if
                                }
                            }.map { _ =>
                                CdpBackend.getTargets(client).map { after =>
                                    val afterIds = after.targetInfos.map(_.targetId).toSet
                                    val leaked   = afterIds -- beforeIds
                                    assert(
                                        leaked.isEmpty,
                                        s"withFork target(s) must be cleaned up after interrupt; leaked: $leaked"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "isolate.fresh inherits Browser.SessionConfig from the outer withConfig" in {
        val customGrace = 777.millis
        withBrowser {
            Browser.withConfig(_.mutationFirstMutationGrace(customGrace)) {
                Browser.isolate.fresh.use {
                    Async.zip(
                        Browser.configLocal.use(_.mutationFirstMutationGrace),
                        Browser.configLocal.use(_.mutationFirstMutationGrace)
                    ).map { (g1, g2) =>
                        assert(
                            g1 == customGrace,
                            s"Fork 1 should inherit outer config's mutationFirstMutationGrace=$customGrace but got $g1"
                        )
                        assert(
                            g2 == customGrace,
                            s"Fork 2 should inherit outer config's mutationFirstMutationGrace=$customGrace but got $g2"
                        )
                    }
                }
            }
        }
    }

    "isolate.fresh.use surfaces tab-create failure as typed Abort[BrowserConnectionException]" in {
        // `Browser.isolate.fresh` carries `Abort[BrowserConnectionException]` in its `Isolate.Keep` channel,
        // so a typed Abort raised inside the user computation flows through the Isolate ABI directly. There is no
        // throw-tunneling and no `BrowserIsolatedException`. We simulate a tab-create failure by injecting `Abort.fail`
        // from inside `.use { body }`; the surfaced channel must be the typed `Result.Failure(BrowserConnectionException)`,
        // never a panic.
        val cause = BrowserConnectionLostException("simulated tab-create failure", Maybe.empty)
        withBrowser {
            Abort.run[BrowserConnectionException] {
                Browser.isolate.fresh.use {
                    Abort.fail(cause): Unit < (Browser & Abort[BrowserConnectionException])
                }
            }.map {
                case Result.Failure(c: BrowserConnectionException) =>
                    assert(c eq cause, s"Expected the original cause to surface, got $c")
                case other => fail(s"Expected Result.Failure(BrowserConnectionException) but got $other")
            }
        }
    }

    "isolate.clone.use surfaces snapshot-capture failure as typed Abort[BrowserConnectionException]" in {
        // `isolate.clone` also carries the typed Abort directly through the Isolate ABI; a typed `Abort.fail`
        // raised from the user computation handed to `.use { body }` surfaces as a typed `Result.Failure`,
        // never as a panic.
        val cause = BrowserConnectionLostException("simulated snapshot-capture failure", Maybe.empty)
        withBrowser {
            Abort.run[BrowserConnectionException] {
                Browser.isolate.clone.use {
                    Abort.fail(cause): Unit < (Browser & Abort[BrowserConnectionException])
                }
            }.map {
                case Result.Failure(c: BrowserConnectionException) =>
                    assert(c eq cause, s"Expected the original cause to surface, got $c")
                case other => fail(s"Expected Result.Failure(BrowserConnectionException) but got $other")
            }
        }
    }

    "withFork surfaces nested isolate failures via typed Abort, not panic" in {
        // `Browser.isolate.{fresh,clone}` carry `Abort[BrowserConnectionException]` in their `Isolate.Keep`
        // channel, so a typed Abort raised inside the user computation flows through the Isolate ABI directly
        // without throw-tunneling. The `withFork` boundary preserves the typed Abort end-to-end.
        val cause = BrowserConnectionLostException("simulated tab-create failure", Maybe.empty)
        withBrowser {
            Abort.run[BrowserConnectionException] {
                Browser.withFork {
                    Abort.fail(cause): Unit < (Browser & Abort[BrowserConnectionException])
                }
            }.map {
                case Result.Failure(c: BrowserConnectionException) =>
                    assert(c eq cause, s"Expected the original cause to surface, got $c")
                case other => fail(s"Expected Result.Failure(BrowserConnectionException) but got $other")
            }
        }
    }

    "withFork translates a typed Abort[BrowserConnectionException] inside isolate.fresh into a typed outer Abort" in {
        // End-to-end: a typed `Abort.fail[BrowserConnectionException]` raised inside the per-fiber computation
        // handed to `Browser.isolate.fresh.use` flows through the widened `Isolate.Keep` channel and surfaces
        // through the outer `withFork`'s typed `Abort[BrowserConnectionException]`. We assert the cause survives
        // by reference (`eq`) across the boundary.
        val cause = BrowserConnectionLostException("isolate body induced failure", Maybe.empty)
        withBrowser {
            Abort.run[BrowserConnectionException] {
                Browser.withFork {
                    Browser.isolate.fresh.use {
                        Abort.fail(cause): Unit < (Browser & Abort[BrowserConnectionException])
                    }
                }
            }.map {
                case Result.Failure(c: BrowserConnectionException) =>
                    assert(c eq cause, s"Expected the original cause to surface, got $c")
                case other => fail(s"Expected Result.Failure(BrowserConnectionException) but got $other")
            }
        }
    }

    "isolate.fresh handles dialogs in parallel forks without cross-contamination" in {
        val p = page("""<html><body>
            <button id='b' onclick="window.__r1 = prompt('?');">go</button>
        </body></html>""")
        withBrowser {
            Browser.isolate.fresh.use {
                Async.zip(
                    Browser.goto(p).andThen {
                        Browser.withDialogs.prompt("fork1") {
                            Browser.click(Browser.Selector.id("b")).andThen {
                                Browser.eval("String(window.__r1)")
                            }
                        }
                    },
                    Browser.goto(p).andThen {
                        Browser.withDialogs.prompt("fork2") {
                            Browser.click(Browser.Selector.id("b")).andThen {
                                Browser.eval("String(window.__r1)")
                            }
                        }
                    }
                ).map { (r1, r2) =>
                    assert(r1 == "fork1", s"Fork 1's prompt should return 'fork1' but got '$r1'")
                    assert(r2 == "fork2", s"Fork 2's prompt should return 'fork2' but got '$r2'")
                }
            }
        }
    }

    // ---- withPopup ----

    "withPopup captures new tab from window.open" in {
        withBrowser {
            onPage("""<html><body>
            <button id='openBtn' onclick="window.open('about:blank', '_blank');">Open</button>
        </body></html>""") {
                Browser.withPopup() {
                    Browser.click(Browser.Selector.css("#openBtn"))
                } {
                    // In the popup tab context, verify we are in a different tab by evaluating JS
                    Browser.eval("document.title || 'popup-tab'").map { result =>
                        // We successfully ran code in the popup tab context
                        assert(result.nonEmpty, s"Expected to be in popup tab context, got empty result")
                    }
                }
            }
        }
    }

    // ---- withFork (cookies / localStorage state) ----

    "withFork preserves cookies set in the parent tab" in {
        withBrowserOnLocalhost {
            Browser.setCookie("forkCookie", "forkVal", "localhost").andThen {
                Browser.withFork {
                    Browser.cookies.map { cs =>
                        val found = cs.exists(c => c.name == "forkCookie" && c.value == "forkVal")
                        assert(found, s"Expected 'forkCookie=forkVal' inside withFork but got: ${cs.map(c => s"${c.name}=${c.value}")}")
                    }
                }
            }
        }
    }

    "withFork preserves localStorage set in the parent tab" in {
        withBrowserOnLocalhost {
            Browser.eval("localStorage.setItem('forkKey','forkVal'); 'ok'").andThen {
                Browser.withFork {
                    Browser.eval("localStorage.getItem('forkKey')").map { v =>
                        assert(v == "forkVal", s"Expected 'forkVal' in fork localStorage but got '$v'")
                    }
                }
            }
        }
    }

    "withFork mutations do not leak back to the parent" in {
        withBrowserOnLocalhost {
            Browser.eval("localStorage.removeItem('leakKey'); 'ok'").andThen {
                Browser.withFork {
                    Browser.eval("localStorage.setItem('leakKey','leaked'); 'ok'")
                }.andThen {
                    Browser.eval("localStorage.getItem('leakKey')").map { v =>
                        assert(v == "null", s"Expected 'null' in parent localStorage after withFork exit but got '$v'")
                    }
                }
            }
        }
    }

    // ── withPopup schedule clause routes the per-call schedule to the polling loop ──
    // No popup-triggering element; the trigger is a no-op eval. The schedule (50 ms × 300 ms) bounds the
    // wait at well under the default loadSchedule's 8 s; we assert the abort fires in [300, 1500) ms with
    // BrowserProtocolErrorException("withPopup", "no new tab detected"), proving the schedule actually
    // overrides the config default.
    "withPopup with a schedule clause bounds the wait time" in {
        withBrowser {
            onPage("<div>no popup here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Scope.run {
                    Abort.run[BrowserReadException] {
                        Browser.withPopup(
                            schedule = Present(Schedule.fixed(50.millis).maxDuration(300.millis))
                        )(Browser.eval("'noop'").unit)(Browser.url)
                    }
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserProtocolErrorException) =>
                            assert(
                                elapsed >= 300 && elapsed < 1500,
                                s"per-call schedule should land in [300, 1500)ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException, got $other")
                    end match
                }
            }
        }
    }

    // isolate.clone with 3 parallel forks: widens coverage beyond 2-fork tests to surface any concurrency
    // bug in BrowserSnapshot.captureSnapshot when multiple clones spin up in parallel.
    "isolate.clone 3 parallel forks each see independent navigation, parent unaffected" in {
        val p1         = page("<html><head><title>C1</title></head><body>C1</body></html>")
        val p2         = page("<html><head><title>C2</title></head><body>C2</body></html>")
        val p3         = page("<html><head><title>C3</title></head><body>C3</body></html>")
        val parentPage = page("<html><head><title>CloneParent</title></head><body>P</body></html>")
        withBrowser {
            Browser.goto(parentPage).map { _ =>
                Browser.url.map { parentUrlBefore =>
                    Browser.isolate.clone.use {
                        Async.zip(
                            Browser.goto(p1).andThen(Browser.title),
                            Browser.goto(p2).andThen(Browser.title),
                            Browser.goto(p3).andThen(Browser.title)
                        ).map { (t1, t2, t3) =>
                            assert(Set(t1, t2, t3) == Set("C1", "C2", "C3"), s"expected 3 distinct titles but got ($t1, $t2, $t3)")
                        }
                    }.andThen {
                        Browser.url.map { parentUrlAfter =>
                            assert(
                                parentUrlAfter == parentUrlBefore,
                                s"Parent URL should be unchanged. Expected '$parentUrlBefore' but got '$parentUrlAfter'"
                            )
                        }
                    }
                }
            }
        }
    }

    // isolate.clone cleanup on per-fork failure: asserts that surviving forks complete normally and the
    // failed fork's child tab is torn down with no leaked CDP targets.
    "isolate.clone error in one fork doesn't affect others, failed fork's context is torn down" in {
        val p = page("<html><head><title>CloneCleanup</title></head><body>X</body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.use { parent =>
                    val client = parent.backend
                    CdpBackend.getTargets(client).map { before =>
                        val beforeIds = before.targetInfos.map(_.targetId).toSet
                        Browser.isolate.clone.use {
                            Async.zip(
                                Browser.title,
                                Abort.run[Throwable] {
                                    Browser.eval("'before-error'").map { _ =>
                                        Abort.fail(new RuntimeException("clone fork error"))
                                    }
                                },
                                Browser.title
                            )
                        }.map { case (t1, errorResult, t3) =>
                            assert(t1 == "CloneCleanup", s"Fork 1 should complete normally but got title '$t1'")
                            assert(t3 == "CloneCleanup", s"Fork 3 should complete normally but got title '$t3'")
                            errorResult match
                                case Result.Failure(e: RuntimeException) =>
                                    assert(e.getMessage == "clone fork error", s"unexpected message: ${e.getMessage}")
                                case other => fail(s"expected RuntimeException('clone fork error') but got $other")
                            end match
                        }.andThen {
                            Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(40)) {
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    if leaked.nonEmpty then
                                        Abort.fail[BrowserConnectionException](
                                            BrowserProtocolErrorException(
                                                "isolate.clone cleanup-on-failure",
                                                s"clone target IDs leaked after fork failure: $leaked"
                                            )
                                        )
                                    else ()
                                    end if
                                }
                            }.map { _ =>
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    assert(
                                        leaked.isEmpty,
                                        s"isolate.clone forks must be cleaned up after failure; leaked: $leaked"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // isolate.fresh.use cleanup when outer scope is interrupted: outer Async.timeout(300.millis) wraps a
    // Browser.run whose body blocks indefinitely inside isolate.fresh.use. After the timeout fires, poll
    // CdpBackend.getTargets and assert the forked tab's targetId is gone.
    "isolate.fresh.use cleanup runs when outer scope is interrupted" in {
        val p = page("<html><head><title>InterruptFresh</title></head><body>X</body></html>")
        type E = BrowserReadException | BrowserSetupException | Timeout
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Browser.run(wsUrl) {
                Browser.use { parent =>
                    val client = parent.backend
                    Browser.goto(p).andThen(CdpBackend.getTargets(client)).map { before =>
                        val beforeIds = before.targetInfos.map(_.targetId).toSet
                        val timedOutWork: Unit < (Async & Abort[E]) =
                            Async.timeout(300.millis) {
                                Browser.run(wsUrl) {
                                    Browser.goto(p).andThen {
                                        Browser.isolate.fresh.use {
                                            Promise.init[Unit, Any].map(_.get)
                                        }
                                    }
                                }
                            }
                        Abort.run[E](timedOutWork).map { result =>
                            assert(result.isFailure, s"Expected timeout failure but got $result")
                            Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(40)) {
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    if leaked.nonEmpty then
                                        Abort.fail[BrowserConnectionException](
                                            BrowserProtocolErrorException(
                                                "isolate.fresh.use interrupt cleanup",
                                                s"target IDs leaked after interrupt: $leaked"
                                            )
                                        )
                                    else ()
                                    end if
                                }
                            }.map { _ =>
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    assert(
                                        leaked.isEmpty,
                                        s"isolate.fresh.use target(s) must be cleaned up after interrupt; leaked: $leaked"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // isolate.clone.use cleanup when outer scope is interrupted: same structure as the fresh.use interrupt
    // test, but for clone. clone additionally exercises the snapshot-capture path, pinning that the
    // snapshot-restore Scope also propagates interruption to its finalizers.
    "isolate.clone.use cleanup runs when outer scope is interrupted" in {
        val p = page("<html><head><title>InterruptClone</title></head><body>X</body></html>")
        type E = BrowserReadException | BrowserSetupException | Timeout
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Browser.run(wsUrl) {
                Browser.use { parent =>
                    val client = parent.backend
                    Browser.goto(p).andThen(CdpBackend.getTargets(client)).map { before =>
                        val beforeIds = before.targetInfos.map(_.targetId).toSet
                        val timedOutWork: Unit < (Async & Abort[E]) =
                            Async.timeout(300.millis) {
                                Browser.run(wsUrl) {
                                    Browser.goto(p).andThen {
                                        Browser.isolate.clone.use {
                                            Promise.init[Unit, Any].map(_.get)
                                        }
                                    }
                                }
                            }
                        Abort.run[E](timedOutWork).map { result =>
                            assert(result.isFailure, s"Expected timeout failure but got $result")
                            Retry[BrowserConnectionException](Schedule.fixed(50.millis).take(40)) {
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    if leaked.nonEmpty then
                                        Abort.fail[BrowserConnectionException](
                                            BrowserProtocolErrorException(
                                                "isolate.clone.use interrupt cleanup",
                                                s"target IDs leaked after interrupt: $leaked"
                                            )
                                        )
                                    else ()
                                    end if
                                }
                            }.map { _ =>
                                CdpBackend.getTargets(client).map { after =>
                                    val leaked = after.targetInfos.map(_.targetId).toSet -- beforeIds
                                    assert(
                                        leaked.isEmpty,
                                        s"isolate.clone.use target(s) must be cleaned up after interrupt; leaked: $leaked"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end BrowserIsolateTest
