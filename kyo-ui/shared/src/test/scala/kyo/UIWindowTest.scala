package kyo

/** Phase 3 SPA tests for [[UIWindow]].
  *
  * Each test fires a scenario registered in [[kyo.internal.spa.SpaHarnessMain]] that exercises a real `UIWindow` method inside Chrome
  * via the SPA harness bundle (see [[UITestSpa]]). Cross-platform: shared/ so the file compiles on JVM/Native too, but
  * `requireJsPlatform()` cancels every test on non-JS platforms; the harness is a Scala.js bundle served to a real Chrome and has no
  * meaning off the JS platform.
  */
class UIWindowTest extends UITestSpa:

    "UIWindow.size initial value matches window.innerWidth/innerHeight" in run {
        requireJsPlatform()
        withSpa("uiwindow.size.initial") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // Format is "WxH"; the scenario reads `dom.window.innerWidth/innerHeight` and the
                // assertion is that both dimensions are positive (Chrome's default viewport varies
                // across machine/headless modes; pinning exact pixels would couple the test to the
                // launcher).
                val parts = result.split('x')
                assert(parts.length == 2)
                val w = parts(0).toInt
                val h = parts(1).toInt
                assert(w > 0 && h > 0)
        }
    }

    "UIWindow.size flips after a synthetic resize event" in run {
        requireJsPlatform()
        withSpa("uiwindow.size.afterResize") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The scenario overrides `window.innerWidth/innerHeight` to 800/600 via
                // `Object.defineProperty` and dispatches a `resize` event before reading
                // `UIWindow.size.current`. The listener installed by `UIWindow.size`'s lazy init
                // updates the SignalRef, so the post-event read must observe the override.
                assert(result == "800x600")
        }
    }

    "UIWindow.visibility initial value reflects document.hidden" in run {
        requireJsPlatform()
        withSpa("uiwindow.visibility.initial") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // In Chrome's default visible state, `document.hidden == false` and so
                // `UIWindow.visibility.current == !document.hidden == true`.
                assert(result == "true")
        }
    }

    "UIWindow.visibility flips after a synthetic visibilitychange event" in run {
        requireJsPlatform()
        withSpa("uiwindow.visibility.afterFlip") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The scenario shadows `document.hidden` with a getter returning `true` and
                // dispatches a `visibilitychange` event. The listener installed by
                // `UIWindow.visibility`'s lazy init reads `!document.hidden` and updates the
                // SignalRef, so the post-event read observes `false`.
                assert(result == "false")
        }
    }

    "UIWindow.onKeyDown captures a synthetic Enter keydown" in run {
        requireJsPlatform()
        withSpa("uiwindow.onKeyDown.Enter") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The scenario captures the `UI.Keyboard` from the handler into a `Maybe`-typed
                // `AtomicRef`. `Maybe` is an opaque type and its `toString` unwraps to the inner
                // value, so `Present(Keyboard.Enter).toString == "Enter"`.
                assert(result == "Enter")
        }
    }

    "UIWindow.onKeyUp captures a synthetic Escape keyup" in run {
        requireJsPlatform()
        withSpa("uiwindow.onKeyUp.Escape") { call =>
            for result <- Browser.evalJson[String](call)
            yield assert(result == "Escape")
        }
    }

    "Closing a Scope removes the UIWindow.onKeyDown listener" in run {
        requireJsPlatform()
        withSpa("uiwindow.scope.removesListener") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // Scope close fires the finalizer that calls `removeEventListener`. The post-Scope
                // keydown dispatch therefore observes no handler invocation; the AtomicInt counter
                // stays at 0.
                assert(result == "0")
        }
    }

    "Two concurrent UIWindow.onKeyDown handlers both fire for one event" in run {
        requireJsPlatform()
        withSpa("uiwindow.onKeyDown.twoConcurrent") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // Each handler increments its own counter. After a single keydown both observe one
                // invocation, so the result is "1,1".
                assert(result == "1,1")
        }
    }

    "UIWindow.size returns the same Signal instance across calls" in run {
        requireJsPlatform()
        withSpa("uiwindow.size.sameInstance") { call =>
            for result <- Browser.evalJson[String](call)
            yield
                // The `lazy val sizeRef` is per-object; repeated `UIWindow.size` calls must return
                // the same Signal reference. `(s1 eq s2).toString` is "true".
                assert(result == "true")
        }
    }

end UIWindowTest
