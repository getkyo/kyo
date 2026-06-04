package kyo

import kyo.internal.BrowserTab

class BrowserEmulationTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- withEmulation ----

    // Test 1: colorScheme = Dark applies prefers-color-scheme: dark inside the body. The single setEmulatedMedia send
    // composes the prefers-color-scheme feature; matchMedia reports the emulated value (pins the single-call design, PRE-001).
    "withEmulation dark color-scheme matches inside the body" in run {
        withBrowser {
            onPage("<html><body>emulation-dark</body></html>") {
                Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                    Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)")
                }.map { matched =>
                    assert(matched == "true", s"expected prefers-color-scheme: dark to match inside the body but got $matched")
                }
            }
        }
    }

    // Test 2: nested withEmulation restores the prior scheme on exit (LIFO). Outer Light wraps inner Dark; after the inner
    // block exits, the outer block reads prefers-color-scheme: dark as false because the inner Dark was restored to the
    // outer Light from the per-tab cache (pins PRE-007).
    "withEmulation restores the prior scheme after the body" in run {
        withBrowser {
            onPage("<html><body>emulation-lifo</body></html>") {
                Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Light)) {
                    Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                        Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)")
                    }.map { inside =>
                        assert(inside == "true", s"expected dark to match inside the inner block but got $inside")
                        Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)")
                    }
                }.map { afterInner =>
                    assert(afterInner == "false", s"expected the inner Dark to be restored to the outer Light but got $afterInner")
                }
            }
        }
    }

    // Test 3: media = Print applies the print media type inside the body. matchMedia('print') reports the emulated media
    // type (pins the media-param design).
    "withEmulation media=print is applied" in run {
        withBrowser {
            onPage("<html><body>emulation-print</body></html>") {
                Browser.withEmulation(media = Present(Browser.MediaType.Print)) {
                    Browser.eval("String(window.matchMedia('print').matches)")
                }.map { matched =>
                    assert(matched == "true", s"expected print media to match inside the body but got $matched")
                }
            }
        }
    }

    // Test 4: reducedMotion = true sends prefers-reduced-motion: reduce inside the body. matchMedia reports the emulated
    // value, proving the reduced-motion feature rides the single setEmulatedMedia send (pins the design, single call).
    "withEmulation reducedMotion is applied" in run {
        withBrowser {
            onPage("<html><body>emulation-reduced-motion</body></html>") {
                Browser.withEmulation(reducedMotion = true) {
                    Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)")
                }.map { matched =>
                    assert(matched == "true", s"expected prefers-reduced-motion: reduce to match inside the body but got $matched")
                }
            }
        }
    }

    // Test 5: withEmulation restores the prior state on interruption (pins PRE-007). The Browser effect carries
    // Env[BrowserTab] with no same-tab isolate, so the interrupted body is a self-contained Browser.run (the established
    // interruption pattern in BrowserViewportTest / BrowserIsolateTest). The inner tab is captured via a Promise so its
    // emulationOverride cache (a plain AtomicRef readable under Sync) can be inspected after the timeout: the
    // Scope.acquireRelease release fires on interruption and clears the override back to Absent (no prior override existed).
    "withEmulation restores on interruption" in run {
        type E = BrowserReadException | Timeout
        val p = page("<html><body>emulation-interrupt</body></html>")
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Promise.init[BrowserTab, Any].map { tabRef =>
                val timedOutWork: Unit < (Async & Abort[E]) =
                    Async.timeout(3.seconds) {
                        Browser.run(wsUrl) {
                            Browser.goto(p).andThen {
                                Browser.use { tab =>
                                    tabRef.completeDiscard(Result.succeed(tab)).andThen {
                                        Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                                            // Block interruptibly so the outer Async.timeout cancels the body mid-sleep,
                                            // while the Dark override is active.
                                            Async.sleep(30.seconds)
                                        }
                                    }
                                }
                            }
                        }
                    }
                // Abort.run returns only after the timeout's interruption teardown completes, which includes the inner
                // Browser.run's Scope finalizers, so withEmulation's release has already run by the time it resolves.
                Abort.run[E](timedOutWork).map { result =>
                    assert(result.isFailure, s"expected the timeout to interrupt the body but got $result")
                    tabRef.get.map { tab =>
                        // The release cleared the override back to Absent (no prior override existed at entry). Read the
                        // AtomicRef cache directly; the tab object outlives its CDP teardown.
                        tab.emulationOverride.get.map { restored =>
                            assert(
                                restored == Absent,
                                s"withEmulation did not clear the override on interruption: got $restored"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- withHighlights ----

    // Test 6: the highlights overlay is settlement-transparent (pins INV-003, PRE-009). The observer is installed via a
    // no-op afterAction so __kyoMutCount is initialized; injecting the data-kyo-internal overlay does NOT change the
    // counter (the observer filters the tagged-subtree insertion), and the overlay is removed after the body.
    "withHighlights overlay is settlement-transparent" in run {
        withBrowser {
            onPage("<html><body><div id='cta'>cta</div></body></html>") {
                // Install the observer via a no-op afterAction so __kyoMutCount exists.
                kyo.internal.MutationSettlement.afterAction(Browser.eval("'noop'"))(Absent).andThen {
                    Browser.eval("String(window.__kyoMutCount || 0)").map { beforeStr =>
                        Browser.withHighlights(Span(Browser.Annotation(Browser.Selector.css("#cta")))) {
                            Browser.eval("String(window.__kyoMutCount || 0)")
                        }.map { duringStr =>
                            Browser.eval("String(document.querySelectorAll('[data-kyo-internal=\"highlight\"]').length)").map { afterStr =>
                                assert(
                                    duringStr == beforeStr,
                                    s"expected __kyoMutCount unchanged after the overlay injection (before=$beforeStr) but got $duringStr"
                                )
                                assert(afterStr == "0", s"expected the overlay removed after the body but found $afterStr highlight boxes")
                            }
                        }
                    }
                }
            }
        }
    }

    // Test 7: withHighlights draws a box at the matched element and removes it on exit (pins the overlay design, PRE-007).
    // Inside the body exactly one data-kyo-internal highlight box exists for the single annotation; after the body the
    // overlay is removed by the Scope.acquireRelease release.
    "withHighlights draws a box at the element and removes it after" in run {
        withBrowser {
            onPage("<html><body><div id='cta'>CTA</div></body></html>") {
                Browser.withHighlights(Span(Browser.Annotation(Browser.Selector.css("#cta"), label = Present("CTA")))) {
                    Browser.eval("String(document.querySelectorAll('[data-kyo-internal=\"highlight\"]').length)")
                }.map { countInside =>
                    Browser.eval("String(document.querySelectorAll('[data-kyo-internal=\"highlight\"]').length)").map { countAfter =>
                        assert(countInside == "1", s"expected exactly 1 highlight box inside the body but got $countInside")
                        assert(countAfter == "0", s"expected the highlight box removed after the body but got $countAfter")
                    }
                }
            }
        }
    }

end BrowserEmulationTest
