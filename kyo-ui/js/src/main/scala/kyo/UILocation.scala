package kyo

import kyo.*
import org.scalajs.dom
import scala.scalajs.js

/** Client-side routing via the History API. JS-only.
  *
  * Exposes a `Signal[String]` of the current `pathname + search`, plus thin wrappers over
  * `window.history.{pushState, replaceState, back, forward, go}` that keep the signal in sync.
  *
  * A capture-phase click interceptor installed on `document` rewrites same-origin no-modifier anchor clicks into `pushState`
  * navigations, so plain `<a href="/foo">` links participate in client-side routing without explicit handler wiring. Modifier-key
  * clicks (ctrl/meta/shift/alt) and non-primary mouse buttons are NOT intercepted; the browser handles those natively (new tab, etc.).
  */
object UILocation:

    private lazy val currentRef: SignalRef[String] =
        import AllowUnsafe.embrace.danger
        SignalRef.Unsafe.init(readWindowPath()).safe

    // popstate fires after back/forward/go finishes the navigation. pushState/replaceState do NOT
    // fire popstate; the corresponding methods update `currentRef` inline.
    private lazy val popstateInstalled: Boolean =
        import AllowUnsafe.embrace.danger
        dom.window.addEventListener(
            "popstate",
            { (_: dom.Event) =>
                currentRef.unsafe.set(readWindowPath())
            }
        )
        true
    end popstateInstalled

    // Document-level capture-phase click interceptor. Same shape as UIWindow's resizeInstalled:
    // a `lazy val` whose body installs the listener exactly once and returns `true`.
    private lazy val anchorInterceptInstalled: Boolean =
        import AllowUnsafe.embrace.danger
        dom.document.addEventListener(
            "click",
            { (e: dom.Event) =>
                val me = e.asInstanceOf[dom.MouseEvent]
                // `e.target` is `EventTarget`; for a document-level listener it can be the Document
                // itself or any element. Pattern-match guards the Element shape (same approach as
                // UIWindow.registerKeyListener).
                val anchorOpt: Maybe[dom.html.Anchor] = me.target match
                    case el: dom.Element =>
                        val a = el.closest("a[href]")
                        if a == null then Absent
                        else Present(a.asInstanceOf[dom.html.Anchor])
                    case _ => Absent
                anchorOpt.foreach { a =>
                    val sameOrigin = a.host == dom.window.location.host
                    val noModifier = !me.ctrlKey && !me.metaKey && !me.shiftKey && !me.altKey && me.button == 0
                    val notBlank   = a.target != "_blank"
                    // A same-document link (only the hash differs, e.g. a `#section` table-of-contents
                    // or scroll anchor) must keep the browser's native in-page scrolling: intercepting
                    // it would preventDefault the scroll and push a hash-less path, so the anchor would
                    // appear dead. Only same-origin cross-document navigations are routed client-side.
                    val sameDocument =
                        a.pathname == dom.window.location.pathname && a.search == dom.window.location.search
                    if sameOrigin && noModifier && notBlank && !sameDocument then
                        me.preventDefault()
                        dom.window.history.pushState(null, "", a.pathname + a.search)
                        currentRef.unsafe.set(readWindowPath())
                    end if
                }
            },
            useCapture = true
        )
        true
    end anchorInterceptInstalled

    private def ensureInit(): Unit =
        val _ = currentRef
        val _ = popstateInstalled
        val _ = anchorInterceptInstalled
    end ensureInit

    /** The current URL's `pathname + search`. Updates on `push`, `replace`, popstate (back/forward/go), and intercepted anchor clicks.
      */
    def current(using Frame): Signal[String] =
        ensureInit()
        currentRef

    /** Push a new history entry and update [[current]]. Mirrors `window.history.pushState(null, "", uri)`. */
    def push(uri: String)(using Frame): Unit < Sync = Sync.defer {
        ensureInit()
        dom.window.history.pushState(null, "", uri)
        import AllowUnsafe.embrace.danger
        currentRef.unsafe.set(readWindowPath())
    }

    /** Replace the current history entry and update [[current]]. Mirrors `window.history.replaceState(null, "", uri)`. */
    def replace(uri: String)(using Frame): Unit < Sync = Sync.defer {
        ensureInit()
        dom.window.history.replaceState(null, "", uri)
        import AllowUnsafe.embrace.danger
        currentRef.unsafe.set(readWindowPath())
    }

    /** Navigate back. popstate updates [[current]] asynchronously after the browser commits. */
    def back(using Frame): Unit < Sync =
        Sync.defer {
            ensureInit()
            dom.window.history.back()
        }

    /** Navigate forward. popstate updates [[current]] asynchronously after the browser commits. */
    def forward(using Frame): Unit < Sync =
        Sync.defer {
            ensureInit()
            dom.window.history.forward()
        }

    /** Navigate by `delta` entries. popstate updates [[current]] asynchronously after the browser commits. */
    def go(delta: Int)(using Frame): Unit < Sync =
        Sync.defer {
            ensureInit()
            dom.window.history.go(delta)
        }

    private def readWindowPath(): String =
        dom.window.location.pathname + dom.window.location.search

end UILocation
