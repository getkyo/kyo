package kyo

import kyo.internal.BrowserTab

class BrowserViewportTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- setViewport ----

    // setViewport applies the CDP override; window.innerWidth reflects it.
    "setViewport changes the rendered viewport width" in run {
        withBrowser {
            onPage("<html><body>set-viewport-width</body></html>") {
                Browser.setViewport(390, 844).andThen {
                    Browser.eval("String(window.innerWidth)").map { width =>
                        assert(width == "390", s"expected innerWidth=390 but got $width")
                    }
                }
            }
        }
    }

    // setViewport threads deviceScaleFactor to both the per-tab cache and the CDP send.
    // devicePixelRatio reports the DPR and the cache holds the ViewportOverride triple.
    "setViewport threads the DPR to the cache and the CDP send" in run {
        withBrowser {
            onPage("<html><body>set-viewport-dpr</body></html>") {
                Browser.setViewport(390, 844, deviceScaleFactor = 3.0).andThen {
                    Browser.eval("String(window.devicePixelRatio)").map { dpr =>
                        assert(dpr == "3", s"expected devicePixelRatio=3 but got $dpr")
                        Browser.use { tab =>
                            tab.viewportOverride.get.map { cached =>
                                assert(
                                    cached == Present(BrowserTab.ViewportOverride(390, 844, 3.0)),
                                    s"expected cache Present(ViewportOverride(390, 844, 3.0)) but got $cached"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // setViewport settles after, so the re-layout has quiesced on return. Reading innerWidth
    // immediately after setViewport returns observes the post-resize value, never a mid-resize race.
    "setViewport settles after so the re-layout has quiesced on return" in run {
        withBrowser {
            // A responsive page: a media query swaps the marker text when the viewport narrows below 500px.
            val html =
                """<html><head><style>
                  |#marker::after { content: 'wide'; }
                  |@media (max-width: 500px) { #marker::after { content: 'narrow'; } }
                  |</style></head><body><span id="marker"></span></body></html>""".stripMargin
            onPage(html) {
                Browser.setViewport(390, 844).andThen {
                    Browser.eval(
                        "window.innerWidth + ',' + getComputedStyle(document.querySelector('#marker'), '::after').content.replace(/\"/g, '')"
                    ).map { observed =>
                        assert(observed == "390,narrow", s"expected post-resize layout 390,narrow but got $observed")
                    }
                }
            }
        }
    }

    // ---- resetViewport ----

    // resetViewport clears the CDP override and the cache. innerWidth returns to
    // the natural width and the per-tab cache returns to Absent.
    "resetViewport clears the override and the cache" in run {
        withBrowser {
            onPage("<html><body>reset-viewport</body></html>") {
                Browser.eval("String(window.innerWidth)").map { natural =>
                    Browser.setViewport(390, 844).andThen {
                        Browser.eval("String(window.innerWidth)").map { overridden =>
                            assert(overridden == "390", s"override not applied: got $overridden")
                            Browser.resetViewport.andThen {
                                Browser.eval("String(window.innerWidth)").map { restored =>
                                    assert(restored == natural, s"resetViewport did not restore: natural=$natural after=$restored")
                                    assert(restored != "390", s"viewport still overridden after reset: got $restored")
                                    Browser.use { tab =>
                                        tab.viewportOverride.get.map { cached =>
                                            assert(cached == Absent, s"expected cache Absent after reset but got $cached")
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

    // ---- withViewport ----

    // withViewport applies the override for the body and restores the prior DPR-carrying override
    // on exit (LIFO). A prior setViewport(800, 600, 2.0) establishes the override to restore.
    "withViewport applies for the body and restores the prior DPR-carrying override after" in run {
        withBrowser {
            onPage("<html><body>with-viewport-lifo</body></html>") {
                Browser.setViewport(800, 600, deviceScaleFactor = 2.0).andThen {
                    Browser.withViewport(390, 844, deviceScaleFactor = 3.0) {
                        Browser.eval("window.innerWidth + ',' + String(window.devicePixelRatio)").map { inside =>
                            assert(inside == "390,3", s"override not active in body: got $inside")
                        }
                    }.andThen {
                        Browser.eval("window.innerWidth + ',' + String(window.devicePixelRatio)").map { after =>
                            assert(after == "800,2", s"withViewport did not restore prior 800x600 dpr=2 (LIFO): got $after")
                        }
                    }
                }
            }
        }
    }

    // withViewport restores the prior override on interruption. The Browser effect
    // carries Env[BrowserTab] and the module deliberately provides no same-tab isolate, so the interrupted body is a
    // self-contained Browser.run (the established interruption pattern in BrowserIsolateTest). The inner tab is captured via
    // a Promise so its viewportOverride cache (a plain AtomicRef, readable under Sync) can be inspected after the timeout:
    // the Scope.acquireRelease release fires on interruption and sets the cache back to the prior override. The override is
    // applied before the body's interruptible Async.sleep runs, so the timeout always cancels with the override active.
    "withViewport restores on interruption" in run {
        type E = BrowserReadException | Timeout
        val p = page("<html><body>with-viewport-interrupt</body></html>")
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Promise.init[BrowserTab, Any].map { tabRef =>
                val timedOutWork: Unit < (Async & Abort[E]) =
                    Async.timeout(3.seconds) {
                        Browser.run(wsUrl) {
                            Browser.goto(p).andThen {
                                // Establish a prior override (800, 600, dpr 2.0) that the restore must re-apply.
                                Browser.setViewport(800, 600, deviceScaleFactor = 2.0).andThen {
                                    Browser.use { tab =>
                                        tabRef.completeDiscard(Result.succeed(tab)).andThen {
                                            Browser.withViewport(390, 844, deviceScaleFactor = 3.0) {
                                                // Block interruptibly so the outer Async.timeout cancels the body mid-sleep.
                                                Async.sleep(30.seconds)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                // Abort.run returns only after the timeout's interruption teardown completes, which includes the inner
                // Browser.run's Scope finalizers, so withViewport's release has already run by the time it resolves.
                Abort.run[E](timedOutWork).map { result =>
                    assert(result.isFailure, s"expected the timeout to interrupt the body but got $result")
                    tabRef.get.map { tab =>
                        // The release re-applied the prior override (800, 600, dpr 2.0), not the body's (390, 844, 3.0)
                        // and not Absent. Read the AtomicRef cache directly; the tab object outlives its CDP teardown.
                        tab.viewportOverride.get.map { restored =>
                            assert(
                                restored == Present(BrowserTab.ViewportOverride(800, 600, 2.0)),
                                s"withViewport did not restore the prior override on interruption: got $restored"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- scrollTo(x, y) ----

    // scrollTo(x, y) moves the scroll position to the coordinate and settles after.
    // A tall page makes the y-offset reachable; window.scrollY reports the landed offset.
    "scrollTo(x, y) scrolls to the coordinate and settles" in run {
        withBrowser {
            onPage("""<html><body style="margin:0"><div style="height:5000px"></div></body></html>""") {
                Browser.scrollTo(0, 1200).andThen {
                    Browser.eval("String(window.scrollY)").map { y =>
                        assert(y == "1200", s"expected scrollY=1200 but got $y")
                    }
                }
            }
        }
    }

    // ---- scrollToElement ----

    // scrollToElement scrolls an off-screen element into view and auto-waits for a late-appearing
    // element. The #footer below the fold is inserted after ~150ms; the auto-wait retries until it
    // appears, then scrolls it into view. The raw getBoundingClientRect check confirms it is fully within the viewport.
    "scrollToElement scrolls the element into view and auto-waits for a late-appearing element" in run {
        val html =
            """<html><body style="margin:0">
              |<div style="height:3000px"></div>
              |<script>
              |  setTimeout(() => {
              |    const f = document.createElement('div');
              |    f.id = 'footer';
              |    f.style.height = '40px';
              |    document.body.appendChild(f);
              |  }, 150);
              |</script>
              |</body></html>""".stripMargin
        withBrowser {
            onPage(html) {
                slow(Browser.scrollToElement(Browser.Selector.css("#footer"))).andThen {
                    Browser.eval(
                        "const r = document.querySelector('#footer').getBoundingClientRect(); String(r.top >= 0 && r.bottom <= window.innerHeight)"
                    ).map { inViewport =>
                        assert(inViewport == "true", s"expected #footer fully in viewport after scroll but got $inViewport")
                    }
                }
            }
        }
    }

    // scrollToElement aborts BrowserElementNotFoundException for a never-appearing element via the
    // narrow retry channel. The abort is BrowserElementNotFoundException, never a
    // BrowserMutationException (proving the channel is BrowserElementException, not widened).
    "scrollToElement aborts BrowserElementNotFoundException for a never-appearing element via the narrow channel" in run {
        withBrowser {
            onPage("<html><body>scroll-to-missing</body></html>") {
                Abort.run[BrowserReadException] {
                    tight(Browser.scrollToElement(Browser.Selector.css("#never")))
                }.map {
                    case Result.Failure(e: BrowserElementNotFoundException) => succeed
                    case other =>
                        fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

end BrowserViewportTest
