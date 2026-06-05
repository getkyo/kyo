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

        // 8. Client runMount reactive update with a Fragment value. Guards the two DomBackend bugs
        // that the server (runHandlers) path never exercises: (a) applyJsProps's invalid
        // querySelectorAll selector that killed the mount before subscribe, and (b) onChange dropping
        // the data-kyo-path boundary span for values (Fragment/Text/RawHtml) that render without a
        // path-carrying root, which left the second update unable to find the node. The reactive
        // value is a Fragment, and two successive sets are applied: with bug (a) no update lands at
        // all (stuck at "A"); with bug (b) only the first lands (stuck at "B"); the fix reaches "C".
        UITestEntry.register(
            "runmount.reactive.fragment",
            () =>
                bridge {
                    val target = "kyo-spa-runmount-target"
                    for
                        ref <- Signal.initRef[String]("A")
                        // UI.Ast.Reactive directly, not Signal.render, to avoid ambiguity with StringContext.render.
                        ui = UI.div(
                            UI.Ast.Reactive(ref.map(s => UI.fragment(UI.span(s).id("frag-a"), UI.span(s).id("frag-b"))))
                        )
                        _ <- Sync.defer {
                            val existing = dom.document.getElementById(target)
                            if existing != null && existing.parentNode != null then
                                val _ = existing.parentNode.removeChild(existing)
                            val c = dom.document.createElement("div")
                            c.setAttribute("id", target)
                            val _ = dom.document.body.appendChild(c)
                        }
                        // runMount parks on Async.never, so fork it like the real browser boot.
                        _ <- Fiber.initUnscoped(Scope.run(UI.runMount(ui, s"#$target")))
                        _ <- pollText("frag-a", "A", 100)
                        _ <- ref.set("B")
                        _ <- pollText("frag-a", "B", 100)
                        _ <- ref.set("C")
                        _ <- pollText("frag-a", "C", 100)
                        out <- Sync.defer {
                            val el = dom.document.getElementById("frag-a")
                            if el != null then el.textContent else "MISSING"
                        }
                    yield out
                    end for
                }
        )
        // 9. Reactive value-bound input keeps focus and caret across a reactive re-render (JS mount
        // path). Mounts UI.input.value(ref).id("focus-input") via UI.runMount, establishes the typed
        // pre-replace state in one shot (value="kyo", focus, caret at 3), then fires EXACTLY ONE
        // reactive re-render via ref.set("kyo"), which drives LocalExchange.onChange -> the
        // `el.outerHTML = finalHtml` replace that detaches the focused input. After the replace it
        // deterministically waits on DOM node identity (the new input node is a DIFFERENT node, or the
        // old node has been detached) so the observation reads the POST-replace state, then returns
        // "<activeElement.id>:<selectionStart>".
        //
        // The scenario is sensitive to the DomBackend focus restore by construction: it never calls
        // setSelectionRange or focus AFTER the replace (Chrome's setSelectionRange auto-focuses an
        // unfocused input, which previously masked the defect), and it waits for the destructive
        // replace to settle on node identity (not on `.value`, which is preserved on the new node and
        // never observes the post-replace focus state).
        // Without the DomBackend focus restore: the new input is not focused, document.activeElement is
        // document.body (id=""), selectionStart is not a number -> ":null". Assertion fails.
        // With the fix: restoreFocus runs inside onChange after the replace, refocusing the new input
        // and restoring caret 3 -> "focus-input:3". Assertion passes.
        UITestEntry.register(
            "runmount.reactive.input.focus",
            () =>
                bridge {
                    val target = "kyo-spa-runmount-focus-target"
                    for
                        ref <- Signal.initRef[String]("")
                        ui = UI.input.value(ref).id("focus-input")
                        _ <- Sync.defer {
                            val existing = dom.document.getElementById(target)
                            if existing != null && existing.parentNode != null then
                                val _ = existing.parentNode.removeChild(existing)
                            val c = dom.document.createElement("div")
                            c.setAttribute("id", target)
                            val _ = dom.document.body.appendChild(c)
                        }
                        _ <- Fiber.initUnscoped(Scope.run(UI.runMount(ui, s"#$target")))
                        // Wait for the input to exist (non-null node poll), not a value-poll: the
                        // mounted input renders with value="" so a value-poll would race the mount.
                        _ <- pollExists("focus-input", 100)
                        // Establish the typed pre-replace state in one shot: value, focus, caret at 3.
                        // This setSelectionRange is BEFORE the replace, so it is legitimate user-state
                        // setup, not masking. Capture the original node so the settle below can detect
                        // the outerHTML replace by node identity.
                        oldNode <- Sync.defer {
                            val el = dom.document.getElementById("focus-input").asInstanceOf[dom.html.Input]
                            el.value = "kyo"
                            el.focus()
                            el.setSelectionRange(3, 3)
                            el: dom.Node
                        }
                        // Fire EXACTLY ONE reactive re-render. ref.set triggers LocalExchange.onChange,
                        // which runs `el.outerHTML = finalHtml`, detaching the focused input. No focus
                        // or setSelectionRange call follows this point.
                        _ <- ref.set("kyo")
                        // Deterministically wait for the replace to COMPLETE by polling on node
                        // identity, not value: the new input carries the same value but is a different
                        // DOM node, so only node identity proves the destructive replace landed.
                        _ <- pollReplaced("focus-input", oldNode, 100)
                        out <- Sync.defer {
                            val ae = dom.document.activeElement
                            val id = if ae != null then ae.id else "null"
                            val caret =
                                val dyn = ae.asInstanceOf[scalajs.js.Dynamic]
                                if ae != null && scalajs.js.typeOf(dyn.selectionStart) == "number" then
                                    dyn.selectionStart.asInstanceOf[Int].toString
                                else "null"
                            end caret
                            s"$id:$caret"
                        }
                    yield out
                    end for
                }
        )
        ()
    end main

    /** Poll `getElementById(id).textContent` until it equals `expected` or `attempts` 20ms ticks elapse, whichever comes first.
      * Returning on exhaustion (rather than failing) lets the scenario hand the final observed value back to the driver, so a stuck
      * update surfaces as a wrong-value assertion in the test rather than an opaque timeout.
      */
    private def pollText(id: String, expected: String, attempts: Int)(using Frame): Unit < Async =
        Loop(0) { n =>
            if n >= attempts then Loop.done(())
            else
                Sync.defer {
                    val el = dom.document.getElementById(id)
                    if el != null then el.textContent else ""
                }.map { text =>
                    if text == expected then Loop.done(())
                    else Async.sleep(20.millis).andThen(Loop.continue(n + 1))
                }
        }
    end pollText

    /** Poll until `getElementById(id)` is a non-null node, or `attempts` 20ms ticks elapse. A non-null
      * poll (not a value-poll) is the correct mount-ready signal for a value-bound input that renders
      * with value="": the element appears in the DOM before any reactive update, and a value-poll would
      * race the mount. Returns on exhaustion so a never-mounted element surfaces downstream as a wrong
      * observation rather than an opaque timeout.
      */
    private def pollExists(id: String, attempts: Int)(using Frame): Unit < Async =
        Loop(0) { n =>
            if n >= attempts then Loop.done(())
            else
                Sync.defer(dom.document.getElementById(id) != null).map { found =>
                    if found then Loop.done(())
                    else Async.sleep(20.millis).andThen(Loop.continue(n + 1))
                }
        }
    end pollExists

    /** Poll until the reactive `outerHTML` replace has landed, signalled by DOM node IDENTITY: the
      * current `getElementById(id)` is a DIFFERENT node than `oldNode`, OR `oldNode` has been detached
      * (`oldNode.parentNode == null`). Node identity is the only reliable settle here: the new input
      * carries the same `.value`, so a value-poll would return immediately and observe the PRE-replace
      * focus state, masking the post-replace focus loss the test must detect. Returns on exhaustion so
      * a replace that never lands surfaces as a wrong observation rather than an opaque timeout.
      */
    private def pollReplaced(id: String, oldNode: dom.Node, attempts: Int)(using Frame): Unit < Async =
        Loop(0) { n =>
            if n >= attempts then Loop.done(())
            else
                Sync.defer {
                    val current: dom.Node = dom.document.getElementById(id)
                    (current != null && (current ne oldNode)) || oldNode.parentNode == null
                }.map { replaced =>
                    if replaced then Loop.done(())
                    else Async.sleep(20.millis).andThen(Loop.continue(n + 1))
                }
        }
    end pollReplaced

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
