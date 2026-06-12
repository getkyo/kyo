package kyo

import kyo.*
import org.scalajs.dom
import scala.scalajs.js

/** Window-level reactive signals and document-wide event subscriptions. JS-only.
  *
  * Browser state that lives on `window` / `document` rather than on any single element: the viewport
  * size, the page-visibility flag, the OS color-scheme preference, and keystrokes or clicks that
  * should be handled regardless of focus. Each is surfaced as a [[kyo.Signal]] or a scope-bound
  * subscription so a Scala.js component consumes it the same way it consumes any other signal,
  * without writing raw DOM listener code.
  *
  *   - [[size]], [[visibility]], and [[prefersColorScheme]] are `Signal`s backed by a single
  *     process-lifetime listener installed lazily on first read.
  *   - [[onKeyDown]], [[onKeyUp]], and [[onClick]] register document-level capture-phase listeners
  *     for the lifetime of the enclosing [[kyo.Scope]]; closing the scope removes the listener.
  *   - [[writeClipboard]], [[storageGet]], [[storageSet]], [[setTitle]], [[scrollToTop]], and
  *     [[scrollIntoViewById]] are typed `Unit < Sync` / `Maybe[String] < Sync` / `Boolean < Sync`
  *     wrappers over the corresponding browser APIs; no `null` escapes their boundaries.
  *
  * Note: only meaningful inside a [[kyo.UI.runMount]] single-page app, where Scala.js runs in the
  * browser. There is no server-side equivalent.
  *
  * @see
  *   [[size]], [[visibility]], [[prefersColorScheme]] for window-state signals
  * @see
  *   [[onKeyDown]], [[onKeyUp]], [[onClick]] for document-wide event handlers
  * @see
  *   [[kyo.UILocation]] for the routing counterpart, [[kyo.UI.KeyboardEvent]] for keyboard payloads,
  *   and [[kyo.UI.MouseEvent]] / [[kyo.UI.MouseEvent.targetClosest]] for click-target walking
  */
object UIWindow:

    private lazy val sizeRef: SignalRef[(Int, Int)] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init((dom.window.innerWidth.toInt, dom.window.innerHeight.toInt)).safe

    private lazy val visibilityRef: SignalRef[Boolean] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init(!dom.document.hidden).safe

    private lazy val prefersDarkRef: SignalRef[Boolean] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init(dom.window.matchMedia("(prefers-color-scheme: dark)").matches).safe

    // Single process-lifetime resize listener. `lazy val` runs the side effect exactly once on
    // first access, matching the `DomStyleSheet.baseInjected` pattern.
    private lazy val resizeInstalled: Boolean =
        import AllowUnsafe.embrace.danger
        dom.window.addEventListener(
            "resize",
            { (_: dom.Event) =>
                sizeRef.unsafe.set((dom.window.innerWidth.toInt, dom.window.innerHeight.toInt))
            }
        )
        true
    end resizeInstalled

    private lazy val visibilityInstalled: Boolean =
        import AllowUnsafe.embrace.danger
        dom.document.addEventListener(
            "visibilitychange",
            { (_: dom.Event) =>
                visibilityRef.unsafe.set(!dom.document.hidden)
            }
        )
        true
    end visibilityInstalled

    // Single process-lifetime media-query listener. A color-scheme preference is page-lifetime
    // (like resize/visibility), so the listener installs once via the `lazy val` and is never
    // removed. `MediaQueryList extends EventTarget`, so `addEventListener("change", ...)` is the
    // typed, non-deprecated path.
    private lazy val prefersColorSchemeInstalled: Boolean =
        import AllowUnsafe.embrace.danger
        dom.window
            .matchMedia("(prefers-color-scheme: dark)")
            .addEventListener(
                "change",
                { (_: dom.Event) =>
                    prefersDarkRef.unsafe.set(dom.window.matchMedia("(prefers-color-scheme: dark)").matches)
                }
            )
        true
    end prefersColorSchemeInstalled

    private def ensureInit(): Unit =
        val _ = sizeRef
        val _ = visibilityRef
        val _ = prefersDarkRef
        val _ = resizeInstalled
        val _ = visibilityInstalled
        val _ = prefersColorSchemeInstalled
    end ensureInit

    /** The viewport size as `(width, height)` in CSS pixels. Updates on the window `resize` event. */
    def size(using Frame): Signal[(Int, Int)] =
        ensureInit()
        sizeRef

    /** Whether the page is currently visible (`!document.hidden`). Updates on the `visibilitychange` event, so it flips when the tab is
      * backgrounded or restored.
      */
    def visibility(using Frame): Signal[Boolean] =
        ensureInit()
        visibilityRef

    /** Subscribe to document-level `keydown` events for the lifetime of the enclosing [[kyo.Scope]]. The handler receives a typed
      * [[kyo.UI.KeyboardEvent]]; closing the scope removes the listener.
      */
    def onKeyDown(f: UI.KeyboardEvent => Any < Async)(using Frame): Unit < (Async & Scope) =
        registerKeyListener("keydown", f)

    /** Subscribe to document-level `keyup` events for the lifetime of the enclosing [[kyo.Scope]]. See [[onKeyDown]]. */
    def onKeyUp(f: UI.KeyboardEvent => Any < Async)(using Frame): Unit < (Async & Scope) =
        registerKeyListener("keyup", f)

    /** Subscribe to document-level capture-phase `click` events for the lifetime of the enclosing
      * [[kyo.Scope]]; closing the scope removes the listener. The handler receives a typed
      * [[kyo.UI.MouseEvent]] on which [[kyo.UI.MouseEvent.targetClosest]] walks from the click target to
      * a matching ancestor. This is the document-level primitive for clicks the per-element `onClick`
      * DSL cannot reach: targets inside [[kyo.UI.rawHtml]]-injected HTML (no `data-kyo-path`) and clicks
      * outside any element (a dismiss-on-outside-click).
      */
    def onClick(f: UI.MouseEvent => Any < Async)(using Frame): Unit < (Async & Scope) =
        registerClickListener(f)

    /** Write `text` to the system clipboard via `navigator.clipboard.writeText`. The returned Promise is
      * discarded (fire-and-forget); a rejection in this notification-class UI context is non-fatal.
      */
    def writeClipboard(text: String)(using Frame): Unit < Sync =
        Sync.defer(discard(dom.window.navigator.clipboard.writeText(text)))

    /** Read `key` from `localStorage`, total: `Present` for a stored value, `Absent` for a missing key
      * (`localStorage.getItem` returns `null`, mapped to `Absent`, so no `null` escapes).
      */
    def storageGet(key: String)(using Frame): Maybe[String] < Sync =
        Sync.defer(Maybe(dom.window.localStorage.getItem(key)))

    /** Write `value` under `key` in `localStorage`. */
    def storageSet(key: String, value: String)(using Frame): Unit < Sync =
        Sync.defer(dom.window.localStorage.setItem(key, value))

    /** A `Signal[Boolean]` that is `true` when the OS prefers a dark color scheme, tracking
      * `matchMedia("(prefers-color-scheme: dark)").matches` and updating on the media query's `change`
      * event. Read [[kyo.Signal.current]] for a one-shot snapshot. Backed by a single process-lifetime
      * listener installed lazily on first read, mirroring [[size]] and [[visibility]].
      */
    def prefersColorScheme(using Frame): Signal[Boolean] =
        ensureInit()
        prefersDarkRef

    /** Set `document.title`. */
    def setTitle(title: String)(using Frame): Unit < Sync =
        Sync.defer(dom.document.title = title)

    /** Scroll the window to `(0, 0)`. */
    def scrollToTop(using Frame): Unit < Sync =
        Sync.defer(dom.window.scrollTo(0, 0))

    /** Scroll the element with `id` into view, returning `true` when it was found and scrolled, `false`
      * when no element has that `id` (total: no `null` escapes, no throw; the caller decides whether to
      * retry on `false`).
      */
    def scrollIntoViewById(id: String)(using Frame): Boolean < Sync =
        Sync.defer {
            val el = dom.document.getElementById(id)
            if el == null then false
            else
                el.scrollIntoView()
                true
            end if
        }

    private def registerClickListener(
        f: UI.MouseEvent => Any < Async
    )(using Frame): Unit < (Async & Scope) =
        Sync.defer {
            val jsHandler: js.Function1[dom.MouseEvent, Unit] = (e: dom.MouseEvent) =>
                // `e.target` for a document-level listener can be the Document itself (synthetic test
                // events dispatch on `document` directly), not an Element. Pattern-match guards the
                // Element shape and falls back to Absent, matching registerKeyListener.
                val targetId = e.target match
                    case el: dom.Element => Maybe(el.id).filter(_.nonEmpty)
                    case _               => Absent
                val mEvent = UI.MouseEvent(targetId, UI.Modifiers(e.ctrlKey, e.altKey, e.shiftKey, e.metaKey))
                // Associate the native event with this payload so targetClosest can reach `.target`,
                // then drop the association once the handler effect completes. Sync.ensure runs forget
                // on success, failure, AND interrupt, so the side table holds the entry for the whole
                // handler lifetime (sync prefix and across async suspension) and never leaks it.
                UIMouseEventOps.remember(mEvent, e)
                import AllowUnsafe.embrace.danger
                // Unsafe: bridges a native JS callback boundary into the Kyo effect system;
                // Fiber.initUnscoped detaches the fiber from the current scope so the callback
                // can return immediately while the handler runs asynchronously.
                discard(Sync.Unsafe.evalOrThrow(
                    Fiber.initUnscoped(Sync.ensure(UIMouseEventOps.forget(mEvent))(f(mEvent).unit))
                ))
            dom.document.addEventListener("click", jsHandler, useCapture = true)
            jsHandler
        }.map { jsHandler =>
            Scope.ensure(Sync.defer(dom.document.removeEventListener("click", jsHandler, useCapture = true)))
        }

    private def registerKeyListener(
        eventName: String,
        f: UI.KeyboardEvent => Any < Async
    )(using Frame): Unit < (Async & Scope) =
        Sync.defer {
            val jsHandler: js.Function1[dom.KeyboardEvent, Unit] = (e: dom.KeyboardEvent) =>
                // `e.target` for a document-level listener can be the Document itself (when an event
                // is dispatched on `document` directly, as synthetic test events typically are), not
                // an Element. A blind `asInstanceOf[dom.Element]` throws under Scala.js's
                // class-cast checking. Pattern-match guards the Element shape and falls back to
                // Absent for non-Element targets.
                val targetId = e.target match
                    case el: dom.Element => Maybe(el.id).filter(_.nonEmpty)
                    case _               => Absent
                val kEvent = UI.KeyboardEvent(
                    key = UI.Keyboard.fromString(e.key),
                    modifiers = UI.Modifiers(e.ctrlKey, e.altKey, e.shiftKey, e.metaKey),
                    targetId = targetId
                )
                import AllowUnsafe.embrace.danger
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(f(kEvent).unit)))
            dom.document.addEventListener(eventName, jsHandler, useCapture = true)
            jsHandler
        }.map { jsHandler =>
            Scope.ensure(Sync.defer(dom.document.removeEventListener(eventName, jsHandler, useCapture = true)))
        }

end UIWindow
