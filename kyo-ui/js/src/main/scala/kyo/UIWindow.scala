package kyo

import kyo.*
import org.scalajs.dom
import scala.scalajs.js

/** Window-level reactive signals and document-wide key subscriptions. JS-only.
  *
  * Browser state that lives on `window` / `document` rather than on any single element: the viewport size, the page-visibility flag, and
  * keystrokes that should be handled regardless of focus. Each is surfaced as a [[kyo.Signal]] or a scope-bound subscription so a Scala.js
  * component consumes it the same way it consumes any other signal, without writing raw DOM listener code.
  *
  *   - [[size]] and [[visibility]] are `Signal`s backed by a single process-lifetime listener installed lazily on first read.
  *   - [[onKeyDown]] and [[onKeyUp]] register document-level capture-phase listeners for the lifetime of the enclosing [[kyo.Scope]];
  *     closing the scope removes the listener.
  *
  * Note: only meaningful inside a [[kyo.UI.runMount]] single-page app, where Scala.js runs in the browser. There is no server-side
  * equivalent.
  *
  * @see
  *   [[size]], [[visibility]] for window-state signals
  * @see
  *   [[onKeyDown]], [[onKeyUp]] for document-wide key handlers
  * @see
  *   [[kyo.UILocation]] for the routing counterpart, and [[kyo.UI.KeyboardEvent]] for the handler payload
  */
object UIWindow:

    private lazy val sizeRef: SignalRef[(Int, Int)] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init((dom.window.innerWidth.toInt, dom.window.innerHeight.toInt)).safe

    private lazy val visibilityRef: SignalRef[Boolean] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init(!dom.document.hidden).safe

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

    private def ensureInit(): Unit =
        val _ = sizeRef
        val _ = visibilityRef
        val _ = resizeInstalled
        val _ = visibilityInstalled
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
