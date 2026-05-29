package kyo.internal.spa

import kyo.*
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.js

/** Entry point for the kyo-ui SPA test harness bundle.
  *
  * Linked by `kyo-ui-spa-harnessJS/Compile/fastLinkJS` with ModuleKind.ESModule. The bundle is served to Chrome as a `<script
  * type="module">` by [[kyo.UITestSpa]]; its module-init side effect is to populate [[UITestEntry]] with the named scenarios that
  * in-Chrome tests will invoke via `window.kyoUiTest.runScenario(...)`.
  *
  * To add a scenario, register it here, re-link the bundle, and restart the test JVM.
  */
object SpaHarnessMain:

    // Test-only harness inside `kyo.*`. `Frame.internal` is the boundary value here because the
    // scenario-thunk shape (`() => Future[String]`) is imposed by `UITestEntry`'s JS-facing
    // contract and there is no user code "above" main to thread a Frame from. Same justification
    // shape as UIWindow's `AllowUnsafe.embrace.danger`: narrow, single-call-site boundary, test infra.
    private given harnessFrame: Frame = Frame.internal

    private val SettleMillis = 50

    /** Bridge a Kyo computation that yields a `String` into the `() => Future[String]` shape that [[UITestEntry]] expects. The fiber
      * runs on the in-Chrome Kyo scheduler; the returned Future resolves once the fiber completes.
      *
      * `Sync.Unsafe.evalOrThrow` is the only viable bridge here: the scenario-thunk shape (`() => Future[String]`) is set by
      * [[UITestEntry]] and called from JS, so the Kyo computation has nowhere to thread `Sync` to. The unsafe scope is the harness's
      * scenario boundary and matches the pattern used by [[kyo.UIWindow]]'s listener bridge.
      */
    private def bridge(computation: => String < (Async & Scope & Abort[Throwable])): Future[String] =
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped[Throwable, String, Any, Any](Scope.run(computation)).map(_.toFuture)
        )
    end bridge

    /** Dispatch a synthetic resize event on `window`. Browsers do not let us write `innerWidth` directly via assignment, so we override
      * the getter with `Object.defineProperty`. The caller restores the original descriptor on exit.
      */
    private def dispatchResize(width: Int, height: Int): () => Unit =
        val winDyn     = js.Dynamic.global.window
        val originalW  = js.Object.getOwnPropertyDescriptor(winDyn.asInstanceOf[js.Object], "innerWidth")
        val originalH  = js.Object.getOwnPropertyDescriptor(winDyn.asInstanceOf[js.Object], "innerHeight")
        val widthDesc  = js.Dynamic.literal(configurable = true, get = (() => width): js.Function0[Int])
        val heightDesc = js.Dynamic.literal(configurable = true, get = (() => height): js.Function0[Int])
        val _          = js.Dynamic.global.Object.defineProperty(winDyn, "innerWidth", widthDesc)
        val _          = js.Dynamic.global.Object.defineProperty(winDyn, "innerHeight", heightDesc)
        val event      = new dom.Event("resize")
        val _          = dom.window.dispatchEvent(event)
        () =>
            if originalW != null && !js.isUndefined(originalW) then
                val _ = js.Dynamic.global.Object.defineProperty(winDyn, "innerWidth", originalW)
            if originalH != null && !js.isUndefined(originalH) then
                val _ = js.Dynamic.global.Object.defineProperty(winDyn, "innerHeight", originalH)
    end dispatchResize

    /** Dispatch a synthetic visibilitychange event after overriding `document.hidden`. Mirrors `dispatchResize`; the override is a getter
      * because `document.hidden` is a read-only property in the spec.
      */
    private def dispatchVisibilityChange(hidden: Boolean): () => Unit =
        val docDyn     = js.Dynamic.global.document
        val original   = js.Object.getOwnPropertyDescriptor(docDyn.asInstanceOf[js.Object], "hidden")
        val descriptor = js.Dynamic.literal(configurable = true, get = (() => hidden): js.Function0[Boolean])
        val _          = js.Dynamic.global.Object.defineProperty(docDyn, "hidden", descriptor)
        val event      = new dom.Event("visibilitychange")
        val _          = dom.document.dispatchEvent(event)
        () =>
            if original != null && !js.isUndefined(original) then
                val _ = js.Dynamic.global.Object.defineProperty(docDyn, "hidden", original)
    end dispatchVisibilityChange

    /** Dispatch a synthetic KeyboardEvent on `document`. `bubbles=true, cancelable=true, composed=true` matches the shape kyo-ui's own
      * delegator uses for its synthetic events; capture-phase listeners see this event.
      */
    private def dispatchKey(eventType: String, key: String): Unit =
        val init = js.Dynamic.literal(
            key = key,
            bubbles = true,
            cancelable = true,
            composed = true
        )
        val ctor  = js.Dynamic.global.KeyboardEvent
        val event = js.Dynamic.newInstance(ctor)(eventType, init)
        val _     = dom.document.asInstanceOf[js.Dynamic].dispatchEvent(event)
        ()
    end dispatchKey

    def main(args: Array[String]): Unit =
        // Phase 2.5 smoke scenario. Keep; `UITestSpaSmokeTest` covers it as a regression check.
        UITestEntry.register("ping", () => Future.successful("pong"))

        // 1. UIWindow.size initial value.
        UITestEntry.register(
            "uiwindow.size.initial",
            () =>
                bridge {
                    for sz <- UIWindow.size.current
                    yield s"${sz._1}x${sz._2}"
                }
        )

        // 2. UIWindow.size after a synthetic resize event.
        UITestEntry.register(
            "uiwindow.size.afterResize",
            () =>
                bridge {
                    // Force the resize listener to install before we dispatch.
                    for
                        _ <- UIWindow.size.current
                        restore = dispatchResize(800, 600)
                        _  <- Async.sleep(SettleMillis.millis)
                        sz <- UIWindow.size.current
                        _ = restore()
                    yield s"${sz._1}x${sz._2}"
                }
        )

        // 3. UIWindow.visibility initial value.
        UITestEntry.register(
            "uiwindow.visibility.initial",
            () =>
                bridge {
                    for v <- UIWindow.visibility.current
                    yield v.toString
                }
        )

        // 3b. UIWindow.visibility flips after a synthetic visibilitychange event.
        UITestEntry.register(
            "uiwindow.visibility.afterFlip",
            () =>
                bridge {
                    for
                        _ <- UIWindow.visibility.current
                        restore = dispatchVisibilityChange(hidden = true)
                        _ <- Async.sleep(SettleMillis.millis)
                        v <- UIWindow.visibility.current
                        _ = restore()
                    yield v.toString
                }
        )

        // 4. UIWindow.onKeyDown captures an Enter keydown.
        UITestEntry.register(
            "uiwindow.onKeyDown.Enter",
            () =>
                bridge {
                    for
                        ref <- AtomicRef.init[Maybe[UI.Keyboard]](Absent)
                        _   <- UIWindow.onKeyDown(ke => ref.set(Present(ke.key)))
                        _ = dispatchKey("keydown", "Enter")
                        _ <- Async.sleep(SettleMillis.millis)
                        v <- ref.get
                    yield v.toString
                }
        )

        // 5. UIWindow.onKeyUp captures an Escape keyup.
        UITestEntry.register(
            "uiwindow.onKeyUp.Escape",
            () =>
                bridge {
                    for
                        ref <- AtomicRef.init[Maybe[UI.Keyboard]](Absent)
                        _   <- UIWindow.onKeyUp(ke => ref.set(Present(ke.key)))
                        _ = dispatchKey("keyup", "Escape")
                        _ <- Async.sleep(SettleMillis.millis)
                        v <- ref.get
                    yield v.toString
                }
        )

        // 6. Closing a Scope removes the registered listener.
        UITestEntry.register(
            "uiwindow.scope.removesListener",
            () =>
                bridge {
                    for
                        count <- AtomicInt.init(0)
                        _     <- Scope.run(UIWindow.onKeyDown(_ => count.incrementAndGet.unit))
                        _ = dispatchKey("keydown", "a")
                        _ <- Async.sleep(SettleMillis.millis)
                        v <- count.get
                    yield v.toString
                }
        )

        // 7. Two concurrent onKeyDown handlers both fire for one event.
        UITestEntry.register(
            "uiwindow.onKeyDown.twoConcurrent",
            () =>
                bridge {
                    for
                        c1 <- AtomicInt.init(0)
                        c2 <- AtomicInt.init(0)
                        _  <- UIWindow.onKeyDown(_ => c1.incrementAndGet.unit)
                        _  <- UIWindow.onKeyDown(_ => c2.incrementAndGet.unit)
                        _ = dispatchKey("keydown", "b")
                        _  <- Async.sleep(SettleMillis.millis)
                        v1 <- c1.get
                        v2 <- c2.get
                    yield s"$v1,$v2"
                }
        )

        // 8. UIWindow.size returns the same Signal instance across calls.
        UITestEntry.register(
            "uiwindow.size.sameInstance",
            () =>
                bridge {
                    Sync.defer {
                        val s1 = UIWindow.size
                        val s2 = UIWindow.size
                        (s1 eq s2).toString
                    }
                }
        )

        // Phase 4: UILocation scenarios.

        // 1. UILocation.current initial value matches dom.window.location.pathname + .search.
        UITestEntry.register(
            "uilocation.current.initial",
            () =>
                bridge {
                    for v <- UILocation.current.current
                    yield v
                }
        )

        // 2. UILocation.push updates UILocation.current.
        UITestEntry.register(
            "uilocation.push.updatesCurrent",
            () =>
                bridge {
                    for
                        initial <- UILocation.current.current
                        _       <- UILocation.push("/foo?x=1")
                        v       <- UILocation.current.current
                        _       <- UILocation.replace(initial)
                    yield v
                }
        )

        // 3. UILocation.replace updates UILocation.current.
        UITestEntry.register(
            "uilocation.replace.updatesCurrent",
            () =>
                bridge {
                    for
                        initial <- UILocation.current.current
                        _       <- UILocation.replace("/bar")
                        v       <- UILocation.current.current
                        _       <- UILocation.replace(initial)
                    yield v
                }
        )

        // 4. UILocation.back resolves popstate to the previous URL.
        UITestEntry.register(
            "uilocation.back.popstate",
            () =>
                bridge {
                    for
                        initial <- UILocation.current.current
                        _       <- UILocation.push("/a")
                        _       <- UILocation.push("/b")
                        _       <- UILocation.back
                        _       <- Async.sleep(SettleMillis.millis)
                        v       <- UILocation.current.current
                        _       <- UILocation.replace(initial)
                    yield v
                }
        )

        // 5. UILocation.go(delta) lands on the entry `delta` entries from current.
        UITestEntry.register(
            "uilocation.go.delta",
            () =>
                bridge {
                    for
                        initial <- UILocation.current.current
                        _       <- UILocation.push("/a")
                        _       <- UILocation.push("/b")
                        _       <- UILocation.push("/c")
                        _       <- UILocation.go(-2)
                        _       <- Async.sleep(SettleMillis.millis)
                        v       <- UILocation.current.current
                        _       <- UILocation.replace(initial)
                    yield v
                }
        )

        // 6. Same-origin no-modifier anchor click is intercepted and updates UILocation.current.
        UITestEntry.register(
            "uilocation.anchor.intercept",
            () =>
                bridge {
                    for
                        // Touch UILocation.current to install the capture-phase listener before the
                        // synthetic click fires.
                        initial <- UILocation.current.current
                        anchor = insertAnchor("/anchor-test")
                        _      = anchor.click()
                        _ <- Async.sleep(SettleMillis.millis)
                        v <- UILocation.current.current
                        _ = removeAnchor(anchor)
                        _ <- UILocation.replace(initial)
                    yield v
                }
        )

        // 7. Modifier-key click (ctrlKey) is NOT intercepted; UILocation.current does not change.
        UITestEntry.register(
            "uilocation.anchor.modifier.preserved",
            () =>
                bridge {
                    for
                        initial <- UILocation.current.current
                        anchor  <- Sync.defer(insertAnchor("/should-not-intercept"))
                        _       <- Sync.defer(dispatchCtrlClick(anchor))
                        _       <- Async.sleep(SettleMillis.millis)
                        v       <- UILocation.current.current
                        _       <- Sync.defer(removeAnchor(anchor))
                        _       <- UILocation.replace(initial)
                    yield if v == initial then "unchanged" else s"changed:$v"
                }
        )
        ()
    end main

    /** Insert an `<a href={href}>label</a>` into `document.body` and return it. The href is set via JS-property assignment so the
      * browser parses it into `pathname`/`search` etc.
      */
    private def insertAnchor(href: String): dom.html.Anchor =
        val a = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
        a.setAttribute("href", href)
        a.textContent = "test-anchor"
        val _ = dom.document.body.appendChild(a)
        a
    end insertAnchor

    private def removeAnchor(a: dom.html.Anchor): Unit =
        if a.parentNode != null then
            val _ = a.parentNode.removeChild(a)
    end removeAnchor

    /** Dispatch a click on the anchor with `ctrlKey` set. `anchor.click()` cannot set modifier state, so we synthesize a MouseEvent
      * via the JS constructor and dispatch it. We pre-emptively `preventDefault()` on the event after construction so an untrusted
      * dispatch cannot trip any host-side default action in headless Chrome. `bubbles=true, cancelable=true` matches the shape
      * kyo-ui's own synthetic events use.
      */
    private def dispatchCtrlClick(anchor: dom.html.Anchor): Unit =
        val init = js.Dynamic.literal(
            bubbles = true,
            cancelable = true,
            composed = true,
            ctrlKey = true,
            button = 0
        )
        val ctor  = js.Dynamic.global.MouseEvent
        val event = js.Dynamic.newInstance(ctor)("click", init)
        val _     = event.preventDefault()
        val _     = anchor.asInstanceOf[js.Dynamic].dispatchEvent(event)
        ()
    end dispatchCtrlClick

end SpaHarnessMain
