package kyo

import kyo.internal.BrowserTab
import kyo.internal.CdpBackend
import kyo.internal.EmulatedMediaFeature
import kyo.internal.SetEmulatedMediaParams

class BrowserEmulationTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- withEmulation ----

    // colorScheme = Dark applies prefers-color-scheme: dark inside the body. The single setEmulatedMedia send
    // composes the prefers-color-scheme feature; matchMedia reports the emulated value.
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

    // nested withEmulation restores the prior scheme on exit (LIFO). Outer Light wraps inner Dark; after the inner
    // block exits, the outer block reads prefers-color-scheme: dark as false because the inner Dark was restored to the
    // outer Light from the per-tab cache.
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

    // media = Print applies the print media type inside the body. matchMedia('print') reports the emulated media
    // type.
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

    // reducedMotion = true sends prefers-reduced-motion: reduce inside the body. matchMedia reports the emulated
    // value, proving the reduced-motion feature rides the single setEmulatedMedia send.
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

    // withEmulation restores the prior state on interruption. The Browser effect carries Env[BrowserTab] with no same-tab
    // isolate, so the interrupted body is a self-contained Browser.run (the established interruption pattern in
    // BrowserViewportTest / BrowserIsolateTest). The scenario is driven by an explicit fiber plus two Promises so it is
    // deterministic across the JVM and the single-threaded Scala.js / Scala Native runtimes:
    //   - the body runs in its own fiber (Fiber.initUnscoped), not under Async.timeout, so the test owns the interruption.
    //   - readyLatch is completed AFTER withEmulation's override is applied, so the test interrupts only once the body is
    //     provably inside the scope with the Dark override active, never before the acquire ran.
    //   - tabRef captures the tab so its emulationOverride cache (a plain AtomicRef readable under Sync) can be inspected.
    // After readyLatch resolves the test interrupts the fiber and awaits its Result. The withEmulation release clears the
    // override back to Absent (no prior override existed at entry) then does an async CDP send; on JS / Native the
    // interrupted fiber's Result can resolve before that finalizer chain has run its `.set(Absent)` (the ordering only holds
    // on the JVM). So the cache is read by polling until the clear lands, bounded by a fixed schedule, then asserted
    // concretely. The poll re-reads the AtomicRef directly; the tab object outlives its CDP teardown.
    "withEmulation restores on interruption" in run {
        val p = page("<html><body>emulation-interrupt</body></html>")
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Promise.init[BrowserTab, Any].map { tabRef =>
                Promise.init[Unit, Any].map { readyLatch =>
                    val body: Unit < (Async & Abort[BrowserReadException]) =
                        Browser.run(wsUrl) {
                            Browser.goto(p).andThen {
                                Browser.use { tab =>
                                    tabRef.completeDiscard(Result.succeed(tab)).andThen {
                                        Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                                            // Signal the override is applied, then block interruptibly until the test
                                            // interrupts the fiber with the Dark override active.
                                            readyLatch.completeDiscard(Result.succeed(())).andThen {
                                                Async.sleep(30.seconds)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    Fiber.initUnscoped(body).map { fiber =>
                        // Wait until the body is fully inside withEmulation (override applied), then interrupt and await the
                        // interrupted Result so the fiber has finished unwinding before the cache is inspected.
                        readyLatch.get.andThen {
                            fiber.interrupt.andThen {
                                fiber.getResult.map { result =>
                                    assert(result.isPanic, s"expected the interrupt to abort the body but got $result")
                                    tabRef.get.map { tab =>
                                        // Poll the AtomicRef cache until the release cleared the override back to Absent.
                                        Retry[BrowserReadException](Schedule.fixed(20.millis).take(150)) {
                                            tab.emulationOverride.get.map { current =>
                                                if current == Absent then ()
                                                else
                                                    Abort.fail(
                                                        BrowserAssertionTimedOutException(
                                                            "withEmulation interrupt clear",
                                                            "Absent",
                                                            current.toString
                                                        )
                                                    )
                                            }
                                        }.andThen {
                                            // The release cleared the override back to Absent (no prior override existed at
                                            // entry), not the body's Dark override.
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
                    }
                }
            }
        }
    }

    // A color-scheme-only withEmulation composes ONLY the prefers-color-scheme feature, so it must not perturb the page's
    // real prefers-reduced-motion. The body reads matchMedia('(prefers-reduced-motion: reduce)') before and inside the block
    // and asserts the value is identical, while also confirming the color-scheme emulation actually took effect inside.
    "withEmulation color-scheme-only leaves prefers-reduced-motion untouched" in run {
        withBrowser {
            onPage("<html><body>emulation-cs-only</body></html>") {
                Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map { hostReduce =>
                    Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                        Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map { insideReduce =>
                            Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)").map { insideDark =>
                                assert(
                                    insideDark == "true",
                                    s"expected the color-scheme emulation to apply dark inside the body but got $insideDark"
                                )
                                assert(
                                    insideReduce == hostReduce,
                                    s"color-scheme-only withEmulation must not change prefers-reduced-motion (host=$hostReduce) but got $insideReduce inside the body"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // On scope exit with NO prior emulation, the restore CLEARS all media emulation so the host's real values return, rather than
    // forcing prefers-reduced-motion: no-preference. To witness the full clear independently of the host's own reduced-motion
    // value (which is no-preference in headless Chrome), a raw CDP override forces prefers-color-scheme: dark BEFORE the block; the
    // block's emulationOverride cache is still Absent, so its release takes the clear path. After the block, both prefers-color-scheme
    // and prefers-reduced-motion report their host values, proving the clear removed every media override, not just some of them.
    "withEmulation restore with no prior clears all media emulation back to host" in run {
        withBrowser {
            onPage("<html><body>emulation-clear</body></html>") {
                Browser.use { tab =>
                    // Read the host values first (no emulation active).
                    Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)").map { hostDark =>
                        Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map { hostReduce =>
                            // Force a NON-host color-scheme via raw CDP. This bypasses the emulationOverride cache, so the
                            // withEmulation below still sees prior = Absent and takes the clear path on exit.
                            CdpBackend.setEmulatedMedia(
                                tab.session,
                                SetEmulatedMediaParams(Present(""), Present(Seq(EmulatedMediaFeature("prefers-color-scheme", "dark"))))
                            ).andThen {
                                Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)").map { forcedDark =>
                                    Browser.withEmulation(reducedMotion = true) {
                                        Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)")
                                    }.map { insideReduce =>
                                        Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)").map { afterDark =>
                                            Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map {
                                                afterReduce =>
                                                    assert(
                                                        forcedDark == "true",
                                                        s"expected the raw override to force dark before the block but got $forcedDark"
                                                    )
                                                    assert(
                                                        insideReduce == "true",
                                                        s"expected reducedMotion emulation to apply inside the block but got $insideReduce"
                                                    )
                                                    assert(
                                                        afterDark == hostDark,
                                                        s"expected prefers-color-scheme cleared back to host=$hostDark after the block but got $afterDark"
                                                    )
                                                    assert(
                                                        afterReduce == hostReduce,
                                                        s"expected prefers-reduced-motion cleared back to host=$hostReduce after the block but got $afterReduce"
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

    // A nested color-scheme-only withEmulation inside an outer reducedMotion=true block must restore the OUTER reduced-motion on
    // inner exit, not clear it: the inner exit re-applies the outer's prior state from the per-tab cache. Inside the inner block
    // the reduced-motion is the host value (the inner color-scheme-only apply replaces the feature set); after the inner exits but
    // still inside the outer body, prefers-reduced-motion: reduce matches again because the outer state was re-applied.
    "withEmulation inner color-scheme-only restores the outer reduced-motion on exit" in run {
        withBrowser {
            onPage("<html><body>emulation-nested-rm</body></html>") {
                Browser.withEmulation(reducedMotion = true) {
                    Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map { outerReduce =>
                        Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark)) {
                            Browser.eval("String(window.matchMedia('(prefers-color-scheme: dark)').matches)")
                        }.map { innerDark =>
                            Browser.eval("String(window.matchMedia('(prefers-reduced-motion: reduce)').matches)").map { afterInner =>
                                assert(outerReduce == "true", s"expected the outer reducedMotion emulation to apply but got $outerReduce")
                                assert(innerDark == "true", s"expected the inner color-scheme emulation to apply dark but got $innerDark")
                                assert(
                                    afterInner == "true",
                                    s"expected the outer reduced-motion restored after the inner color-scheme block exits but got $afterInner"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- withHighlights ----

    // the highlights overlay is settlement-transparent. The observer is installed via a
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

    // withHighlights draws a box at the matched element and removes it on exit.
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

    // Test 8: nested withHighlights each tear down exactly their own overlay (token-scoped removal).
    //
    // withHighlights takes a body, so withHighlights(outer) { withHighlights(inner) { ... } } type-checks
    // and is the supported LIFO composition. Each invocation tags its container with a unique token and
    // removes only that container. The nest asserts, via the same eval path the code uses:
    //   - inside the inner body: BOTH overlay containers present (2 [data-kyo-internal="highlights"]),
    //   - after the inner scope exits but still inside the outer body: the OUTER overlay is STILL present
    //     (exactly 1 [data-kyo-internal="highlights"]); the inner exit did not clobber it,
    //   - after the outer scope exits: ZERO [data-kyo-internal] nodes remain (no leak).
    // Under the old single-global-slot model the inner exit deleted the slot and the outer overlay leaked.
    "nested withHighlights each remove only their own overlay and leave no leak" in run {
        withBrowser {
            onPage("<html><body><div id='a'>A</div><div id='b'>B</div></body></html>") {
                val highlightsCount = "String(document.querySelectorAll('[data-kyo-internal=\"highlights\"]').length)"
                Browser.withHighlights(Span(Browser.Annotation(Browser.Selector.css("#a")))) {
                    Browser.withHighlights(Span(Browser.Annotation(Browser.Selector.css("#b")))) {
                        Browser.eval(highlightsCount)
                    }.map { countInsideInner =>
                        // Inner scope has exited here; still inside the outer body.
                        Browser.eval(highlightsCount).map { countAfterInner =>
                            (countInsideInner, countAfterInner)
                        }
                    }
                }.map { case (countInsideInner, countAfterInner) =>
                    Browser.eval("String(document.querySelectorAll('[data-kyo-internal]').length)").map { countAfterOuter =>
                        assert(
                            countInsideInner == "2",
                            s"expected BOTH overlays present inside the inner body but got $countInsideInner highlights containers"
                        )
                        assert(
                            countAfterInner == "1",
                            s"expected the OUTER overlay still present after the inner scope exits but got $countAfterInner highlights containers"
                        )
                        assert(
                            countAfterOuter == "0",
                            s"expected ZERO data-kyo-internal nodes after the outer scope exits but got $countAfterOuter (overlay leaked)"
                        )
                    }
                }
            }
        }
    }

end BrowserEmulationTest
