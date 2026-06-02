package kyo

/** Client `runMount` reactive-update regression test.
  *
  * Drives the `runmount.reactive.fragment` scenario registered by `kyo.internal.spa.SpaHarnessMain`: it mounts a UI whose reactive value
  * is a `Fragment` into a real Chrome DOM via `UI.runMount`, then applies two successive `SignalRef` sets ("A" -> "B" -> "C").
  *
  * This is the only test that exercises the client `DomBackend` mount path; every other reactive test goes through the server
  * `runHandlers`/SSE path (`UITest.withUI`). It guards two bugs that path cannot catch:
  *   - `applyJsProps` once built an invalid `querySelectorAll("[data-kyo-prop-*]")` that threw before `ReactiveUI.subscribe`, so no
  *     update ever landed (the scenario would stay at "A").
  *   - `LocalExchange.onChange` once replaced the reactive node with bare HTML, dropping the `data-kyo-path` boundary span for values
  *     (`Fragment`, `Text`, `RawHtml`) that render without a path-carrying root, so only the first update landed (stuck at "B").
  *
  * Cancels on JVM and Native; the harness is JS-only by construction.
  */
class UITestSpaRunMountTest extends UITestSpa:

    "client runMount applies successive reactive Fragment updates" in run {
        requireJsPlatform()
        withSpa("runmount.reactive.fragment") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "C")
        }
    }

end UITestSpaRunMountTest
