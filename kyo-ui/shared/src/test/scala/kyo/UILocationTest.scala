package kyo

/** Phase 4 SPA tests for [[UILocation]].
  *
  * Each test fires a scenario registered in [[kyo.internal.spa.SpaHarnessMain]] that exercises a real `UILocation` method inside
  * Chrome via the SPA harness bundle (see [[UITestSpa]]). Cross-platform: shared/ so the file compiles on JVM/Native too, but
  * `requireJsPlatform()` cancels every test on non-JS platforms; `UILocation` is JS-only and the harness is a Scala.js bundle served
  * to a real Chrome.
  */
class UILocationTest extends UITestSpa:

    "UILocation.current initial value matches window.location.pathname + search" in run {
        requireJsPlatform()
        withSpa("uilocation.current.initial") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The harness serves "/" so `pathname` is "/" and `search` is "". The exact value
                // can vary if Chrome's launch URL ever carries a query string, so the assertion
                // anchors on non-empty + leading slash rather than literal equality.
                assert(result.nonEmpty)
                assert(result.startsWith("/"))
        }
    }

    "UILocation.push updates UILocation.current" in run {
        requireJsPlatform()
        withSpa("uilocation.push.updatesCurrent") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The scenario pushes "/foo?x=1", reads `current.current`, then replaces back to the
                // initial path. The mid-state observed by the read is what we assert on.
                assert(result == "/foo?x=1")
        }
    }

    "UILocation.replace updates UILocation.current" in run {
        requireJsPlatform()
        withSpa("uilocation.replace.updatesCurrent") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "/bar")
        }
    }

    "UILocation.back resolves popstate to the previous URL" in run {
        requireJsPlatform()
        withSpa("uilocation.back.popstate") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // push /a, push /b, back, settle. The popstate listener writes the new pathname into
                // currentRef; after the 50ms settle window the post-back read observes "/a".
                assert(result == "/a")
        }
    }

    "UILocation.go(-2) lands two entries back" in run {
        requireJsPlatform()
        withSpa("uilocation.go.delta") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // push /a, /b, /c then go(-2) lands on /a. popstate updates currentRef after the
                // settle window.
                assert(result == "/a")
        }
    }

    "Same-origin no-modifier anchor click is intercepted by UILocation" in run {
        requireJsPlatform()
        withSpa("uilocation.anchor.intercept") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // `anchor.click()` dispatches a real MouseEvent with default modifiers (none) and
                // button 0. The capture-phase listener installed by UILocation rewrites this into a
                // pushState navigation and updates currentRef.
                assert(result == "/anchor-test")
        }
    }

    "Modifier-key (ctrl) anchor click is NOT intercepted; current is preserved" in run {
        requireJsPlatform()
        withSpa("uilocation.anchor.modifier.preserved") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The dispatched MouseEvent has `ctrlKey=true`. The interceptor must let this
                // through. UILocation.current stays at the pre-click value (the harness path "/").
                assert(result == "unchanged")
        }
    }

end UILocationTest
