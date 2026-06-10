package kyo.internal

import kyo.*

class BrowserNetworkTrackerTest extends kyo.BrowserTest:

    override def timeout = 90.seconds

    // ---- ensureResponseTrackingInstalled ----

    "ensureResponseTrackingInstalled installs the JS interceptor" in {
        // `Browser.waitForRequestUrl` calls `BrowserNetworkTracker.ensureResponseTrackingInstalled`
        // internally. After the call (even if it exhausts retries with no URL match), the in-page
        // `__kyoResponseTrackingInstalled` flag must be set; confirming the installer ran.
        withBrowser {
            onPage("<html><body></body></html>") {
                // Absorb the expected timeout; we only care that the installer script ran.
                Abort.run[BrowserAssertionException] {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(2))) {
                        Browser.waitForRequestUrl("nonexistent-pattern-kyo-test")
                    }
                }.andThen {
                    Browser.eval(
                        "typeof window.__kyoResponseTrackingInstalled !== 'undefined' ? 'true' : ''"
                    ).map { flag =>
                        assert(
                            flag == "true",
                            s"expected window.__kyoResponseTrackingInstalled to be set after ensureResponseTrackingInstalled ran, got '$flag'"
                        )
                    }
                }
            }
        }
    }

    "ensureResponseTrackingInstalled is idempotent across calls" in {
        // Calling `Browser.waitForRequestUrl` (and therefore `ensureResponseTrackingInstalled`)
        // twice in the same page context must not throw a different error on the second call
        // (e.g. from JS state mutation conflict). Both calls must surface only the expected
        // `BrowserAssertionTimedOutException`; not a `BrowserConnectionException` caused by
        // double-initialisation of the in-page interceptor state.
        withBrowser {
            onPage("<html><body></body></html>") {
                val tightSchedule = Schedule.fixed(50.millis).take(2)
                Abort.run[BrowserAssertionException] {
                    Browser.withConfig(_.retrySchedule(tightSchedule)) {
                        Browser.waitForRequestUrl("nonexistent-pattern-kyo-test-a")
                    }
                }.map { firstResult =>
                    assert(
                        firstResult.isFailure,
                        s"expected first waitForRequestUrl to fail with BrowserAssertionException, got $firstResult"
                    )
                }.andThen {
                    Abort.run[BrowserAssertionException] {
                        Browser.withConfig(_.retrySchedule(tightSchedule)) {
                            Browser.waitForRequestUrl("nonexistent-pattern-kyo-test-b")
                        }
                    }.map { secondResult =>
                        assert(
                            secondResult.isFailure,
                            s"expected second waitForRequestUrl to fail with BrowserAssertionException (not a connection error), got $secondResult"
                        )
                        secondResult match
                            case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                                assert(ex.getMessage.contains("Assertion failed"))
                            case other =>
                                fail(
                                    s"expected BrowserAssertionTimedOutException on idempotent second call, got $other - " +
                                        "a different exception type indicates JS state corruption from double-install"
                                )
                        end match
                    }
                }
            }
        }
    }

end BrowserNetworkTrackerTest
