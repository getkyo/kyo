package kyo

import kyo.internal.BrowserTab

class BrowserViewportTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- setViewport ----

    // setViewport applies the CDP override; window.innerWidth reflects it.
    "setViewport changes the rendered viewport width" in {
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
    "setViewport threads the DPR to the cache and the CDP send" in {
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
    "setViewport settles after so the re-layout has quiesced on return" in {
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
    "resetViewport clears the override and the cache" in {
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
    "withViewport applies for the body and restores the prior DPR-carrying override after" in {
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

    // withViewport restores the prior override on interruption. The Browser effect carries Env[BrowserTab] and the module
    // deliberately provides no same-tab isolate, so the interrupted body is a self-contained Browser.run (the established
    // interruption pattern in BrowserIsolateTest). The scenario is driven by an explicit fiber plus two Promises so it is
    // deterministic across the JVM and the single-threaded Scala.js / Scala Native runtimes:
    //   - the body runs in its own fiber (Fiber.initUnscoped), not under Async.timeout, so the test owns the interruption.
    //   - readyLatch is completed AFTER withViewport's override is applied, so the test interrupts only once the body is
    //     provably inside the scope with the override active, never before the acquire ran.
    //   - tabRef captures the tab so its viewportOverride cache (a plain AtomicRef, readable under Sync) can be inspected.
    // After readyLatch resolves the test interrupts the fiber and awaits its Result. The withViewport release does
    // `tab.viewportOverride.set(prior)` then an async CDP send; on interruption the inner Browser.run's Scope.run awaits its
    // finalizers, but on JS / Native the interrupted fiber's Result can resolve before that finalizer chain has run its
    // `.set(prior)` (the ordering only holds on the JVM). So the cache is read by polling until the restore lands, bounded by
    // a fixed schedule, then asserted concretely. The poll re-reads the AtomicRef directly; the tab object outlives its CDP
    // teardown.
    "withViewport restores on interruption" in {
        val priorOverride = BrowserTab.ViewportOverride(800, 600, 2.0)
        val p             = page("<html><body>with-viewport-interrupt</body></html>")
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Promise.init[BrowserTab, Any].map { tabRef =>
                Promise.init[Unit, Any].map { readyLatch =>
                    val body: Unit < (Async & Abort[BrowserReadException]) =
                        Browser.run(wsUrl) {
                            Browser.goto(p).andThen {
                                // Establish a prior override (800, 600, dpr 2.0) that the restore must re-apply.
                                Browser.setViewport(800, 600, deviceScaleFactor = 2.0).andThen {
                                    Browser.use { tab =>
                                        tabRef.completeDiscard(Result.succeed(tab)).andThen {
                                            Browser.withViewport(390, 844, deviceScaleFactor = 3.0) {
                                                // Signal the override is applied, then block interruptibly until the test
                                                // interrupts the fiber with the override active.
                                                readyLatch.completeDiscard(Result.succeed(())).andThen {
                                                    Async.sleep(30.seconds)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    Fiber.initUnscoped(body).map { fiber =>
                        // Wait until the body is fully inside withViewport (override applied), then interrupt and await the
                        // interrupted Result so the fiber has finished unwinding before the cache is inspected.
                        readyLatch.get.andThen {
                            fiber.interrupt.andThen {
                                fiber.getResult.map { result =>
                                    assert(result.isPanic, s"expected the interrupt to abort the body but got $result")
                                    tabRef.get.map { tab =>
                                        // Poll the AtomicRef cache until the release re-applied the prior override.
                                        Retry[BrowserReadException](Schedule.fixed(20.millis).take(150)) {
                                            tab.viewportOverride.get.map { current =>
                                                if current == Present(priorOverride) then ()
                                                else
                                                    Abort.fail(
                                                        BrowserAssertionTimedOutException(
                                                            "withViewport interrupt restore",
                                                            s"Present($priorOverride)",
                                                            current.toString
                                                        )
                                                    )
                                            }
                                        }.andThen {
                                            // The release re-applied the prior override (800, 600, dpr 2.0), not the body's
                                            // (390, 844, 3.0) and not Absent.
                                            tab.viewportOverride.get.map { restored =>
                                                assert(
                                                    restored == Present(priorOverride),
                                                    s"withViewport did not restore the prior override on interruption: got $restored"
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

    // ---- scrollTo(x, y) ----

    // scrollTo(x, y) moves the scroll position to the coordinate and settles after.
    // A tall page makes the y-offset reachable; window.scrollY reports the landed offset.
    "scrollTo(x, y) scrolls to the coordinate and settles" in {
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
    "scrollToElement scrolls the element into view and auto-waits for a late-appearing element" in {
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
    "scrollToElement aborts BrowserElementNotFoundException for a never-appearing element via the narrow channel" in {
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
