package kyo.internal

import kyo.*

class NavigationWatcherSnapshotDriftTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // NavigationWatcher.snapshotState must not swallow a wire-decode failure and degrade to a zero
    // NavSnapshot (which would cause pollNavigated to observe "URL changed" spuriously). Wire-shape drift
    // must surface as a typed BrowserProtocolErrorException via the same path as decodeSettleState.

    "NavigationWatcher.decodeSnapshotState - malformed wire surfaces BrowserProtocolErrorException" in run {
        val bad = "not valid json"
        Abort.run[BrowserReadException](NavigationWatcher.decodeSnapshotState(bad)).map {
            case Result.Failure(_: BrowserProtocolErrorException) =>
                // Sanity: a well-formed payload routes through the success branch, confirming the
                // failure branch is what surfaced for the malformed input.
                val good = """{"url":"https://example.test/page","pushStateCount":2,"beforeUnload":false}"""
                NavigationWatcher.decodeSnapshotState(good).map { snap =>
                    assert(snap.url == "https://example.test/page", s"url was `${snap.url}`")
                    assert(snap.pushStateCount == 2, s"pushStateCount was ${snap.pushStateCount}")
                    assert(!snap.beforeUnload, s"beforeUnload was ${snap.beforeUnload}")
                }
            case other => fail(s"expected Abort.fail(BrowserProtocolErrorException) for invalid JSON, got $other")
        }
    }

    "NavigationWatcher.decodeSnapshotState - wire envelope missing pushStateCount surfaces BrowserProtocolErrorException" in run {
        // A wire envelope that resembles the snapshot shape but is missing the required `pushStateCount`
        // field. The decode must surface as Failure rather than silently producing a degraded snapshot.
        val driftedEnvelope = """{"url":"https://example.test/page","beforeUnload":false}"""
        Abort.run[BrowserReadException](NavigationWatcher.decodeSnapshotState(driftedEnvelope)).map {
            case Result.Failure(_: BrowserProtocolErrorException) => succeed
            case other => fail(s"expected Abort.fail(BrowserProtocolErrorException) for drifted envelope, got $other")
        }
    }

    // Live-browser contract: snapshotState's wire shape decodes correctly when the in-page IIFE returns the
    // canonical envelope. We verify by driving the watcher around a real navigation: armAroundNavigation must
    // observe the pre-navigation URL and detect the change.
    "NavigationWatcher live snapshot decodes a well-formed wire envelope on a real page" in run {
        withBrowserOnLocalhost {
            // The recorded URL must round-trip through snapshotState; after a navigation the gate
            // observes a URL change and resolves. If snapshotState degraded to url="" the gate
            // would resolve immediately on any click because "" != the live URL.
            Browser.eval(
                """(() => {
                  |  setTimeout(() => { history.pushState({}, '', '#drift'); }, 200);
                  |  return 'ok';
                  |})()
                """.stripMargin
            ).andThen {
                NavigationWatcher.waitForNext(Browser.Settle.DomContentLoaded, 5.seconds).andThen {
                    Browser.url.map { u =>
                        assert(u.endsWith("#drift"), s"expected URL to end with '#drift' after waitForNext, got '$u'")
                    }
                }
            }
        }
    }

end NavigationWatcherSnapshotDriftTest
