package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.annotation.tailrec
import scala.scalajs.js

/** Scala.js UI backend. Mounts a UI into the browser DOM. */
private[kyo] object DomBackend:

    /** One seeded `data-kyo-focus-auto` element and where focus should go when it leaves the document.
      *
      * @param path
      *   `data-kyo-path` of the seeded element
      * @param returnTo
      *   `data-kyo-path` of the element focused just before seeding, `Absent` when nothing was focused
      * @param restore
      *   whether the seeded element declared `data-kyo-focus-restore`
      */
    final private case class FocusSeed(path: String, returnTo: Maybe[String], restore: Boolean)

    /** Seeded focus-auto elements, innermost last. Mirrors `__focusReturnStack` in HtmlRenderer.clientJs. Module-level
      * mutable state is safe: all mutation runs inside `Sync.defer` on the single-threaded JS runtime.
      */
    private var focusReturnStack: Chunk[FocusSeed] = Chunk.empty

    /** Mount a UI into the page body. */
    def mount(ui: UI)(using Frame): Unit < (Async & Scope) =
        mountInto(ui, document.body)

    /** Mount a UI into a specific DOM element selected by CSS selector. */
    def mount(ui: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        Sync.defer {
            val target = document.querySelector(selector)
            if target == null then Abort.panic(UIException(s"Element not found: $selector"))
            else mountInto(ui, target.asInstanceOf[dom.Element])
        }
    end mount

    /** Injects a rendered stylesheet CSS string into the live document.
      *
      * The base reset is injected first (idempotently) so it precedes the authored CSS in document
      * order, matching the SSG page head where `baseCss` is emitted before `head.css`. The reset is a
      * foundational layer authored stylesheets are meant to override (e.g. `body { font-family }`); if
      * it were appended AFTER the sheet (as happens when an app calls `runStylesheet` before `runMount`,
      * which injects the reset), its equal-specificity `body` rule would win on document order and clobber
      * the app's own `body` font, producing a fallback-font flash. Injecting the reset first here makes the
      * cascade order independent of which entry point runs first.
      */
    private[kyo] def injectStylesheet(sheet: Stylesheet)(using Frame): Unit < Sync =
        DomStyleSheet.injectBase().andThen(Sync.defer(DomStyleSheet.injectStylesheet(sheet.render)))

    private def mountInto(ui: UI, container: dom.Element)(using Frame): Unit < (Async & Scope) =
        for
            _    <- DomStyleSheet.injectBase()
            root <- ReactiveUI.normalize(ui, Seq.empty)
            html <- HtmlRenderer.render(ui, Seq.empty)
            _    <- Sync.defer(container.innerHTML = html)
            _    <- applyJsProps(container)
            _    <- Sync.defer(seedEnter(container, Set.empty))
            _    <- Sync.defer(seedFocusAuto(container, Set.empty))
            _    <- Sync.defer(beginAnimationsSync(container))
            _    <- setupInputMasking()
            exchange = LocalExchange(root)
            dispatch <- ReactiveUI.subscribe(root, exchange)
            // Single-consumer drain owned by the ambient page Scope: every JS event effect is run by a
            // Fiber.init consumer (interrupted on page teardown). The single consumer preserves event ordering
            // and is scoped, so page teardown interrupt propagates to the drain via the ambient Scope.
            events <- Channel.init[Unit < Async](256)
            // runPartial captures only the Closed failure (the channel closed on page teardown -> stop draining); a
            // Panic propagates rather than being silently swallowed as a clean drain end.
            // The drain carries the session's scroll sink: a handler calling UI.scrollIntoView scrolls the
            // local document, the browser-mount counterpart of the server session's WebSocket op.
            _ <- Fiber.init(UICommands.scrollSink.let(Present(scrollLocal)) {
                Loop.foreach(Abort.runPartial[Closed](events.take).map {
                    case Result.Success(eff) => eff.andThen(Loop.continue)
                    case Result.Failure(_)   => Loop.done
                })
            })
            _ <- setupEventDelegation(dispatch.handle, events)
            _ <- Async.never
        yield ()
        end for
    end mountInto

    // The local-document scroll sink installed on the mount's event-drain fiber; mirrors the embedded
    // client's ScrollIntoView handling exactly (missing id = no-op, smooth scroll to the block start),
    // so the same command behaves the same under either runner.
    private def scrollLocal(id: String)(using Frame): Unit < Async =
        Sync.defer {
            val el = document.getElementById(id)
            if el != null then
                discard(el.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(behavior = "smooth", block = "start")))
        }

    /** Exchange that renders UI to HTML and applies directly to the DOM. */
    private class LocalExchange(root: ReactiveUI) extends UIExchange:
        private def svgContextAt(path: Seq[String]): Boolean =
            ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

        // In-process: the update is a DOM write, not bytes on a wire, so this replaces the region whole
        // rather than diffing it. `previous` is what the server-side exchange uses to send only what moved.
        def onChange(path: Seq[String], previous: Maybe[UI], ui: UI)(using Frame): Unit < Async =
            HtmlRenderer.render(ui, path).map { html =>
                // Always wrap the rendered html in the reactive boundary element so the node carrying
                // data-kyo-path=path survives subsequent replacements. A Fragment, Text, or RawHtml value
                // renders without a path-carrying root, so an unwrapped replace would drop the marker and
                // the next update could not locate the node. In SVG context the boundary is a <g> (a <span>
                // is invalid inside <svg>); otherwise a <span> (CSS sets `display: contents` so it is layout-
                // transparent).
                val tag       = if svgContextAt(path) then "g" else "span"
                val pathAttr  = path.mkString(".")
                val finalHtml = s"""<$tag data-kyo-path="$pathAttr" data-kyo-reactive>$html</$tag>"""
                Sync.defer {
                    val el = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                    if el != null && el.outerHTML != finalHtml then
                        // Capture focus and caret of the active element inside the replaced region,
                        // keyed on data-kyo-path identity (mirrors HtmlRenderer.clientJs:576-583 on
                        // the JS DOM API). Plain DOM inside the already-suspended Sync.defer; no new
                        // AllowUnsafe crossing.
                        val ae = document.activeElement
                        val insideRegion = ae != null && (ae ne document.body) &&
                            (ae.getAttribute("data-kyo-path") == pathAttr || el.contains(ae))
                        // Use the active element's own data-kyo-path when it carries one (nested
                        // reactive region), otherwise fall back to pathAttr so the region wrapper
                        // itself is queried (common case: value-bound input inside the region has
                        // no data-kyo-path of its own).
                        val activePath =
                            if insideRegion then
                                if ae.hasAttribute("data-kyo-path") then ae.getAttribute("data-kyo-path")
                                else pathAttr
                            else null
                        val (selStart, selEnd) = if insideRegion then readSelection(ae) else (Absent, Absent)
                        val oldEnter           = enterPaths(el)
                        val ghosts             = prepareLeaveGhosts(el, leaveSurvSet(finalHtml))
                        val oldFocusAuto       = focusAutoPaths(el)
                        el.outerHTML = finalHtml
                        val updated = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                        if updated != null then
                            applyJsPropsSync(updated)
                            beginAnimationsSync(updated)
                        if activePath != null then
                            restoreFocus(activePath, selStart, selEnd)
                        if updated != null then
                            seedEnter(updated, oldEnter)
                            // Seed AFTER restoreFocus so a newly-appeared focus-auto element wins over restore-to-trigger.
                            seedFocusAuto(updated, oldFocusAuto)
                        end if
                        spawnGhosts(ghosts)
                        sweepFocusAuto()
                    end if
                }
            }
    end LocalExchange

    private def readSelection(el: dom.Element): (Maybe[Int], Maybe[Int]) =
        val dyn = el.asInstanceOf[scalajs.js.Dynamic]
        def asInt(v: scalajs.js.Dynamic): Maybe[Int] =
            if scalajs.js.typeOf(v) == "number" then Present(v.asInstanceOf[Int]) else Absent
        (asInt(dyn.selectionStart), asInt(dyn.selectionEnd))
    end readSelection

    private def restoreFocus(capturedPath: String, selStart: Maybe[Int], selEnd: Maybe[Int]): Unit =
        val located = document.querySelector(s"""[data-kyo-path="$capturedPath"]""")
        if located != null then
            val focusTarget =
                if located.hasAttribute("data-kyo-reactive") then
                    val inner = located.querySelector("input,textarea,select,[contenteditable]")
                    if inner != null then inner else located
                else located
            val _ = focusTarget.asInstanceOf[scalajs.js.Dynamic].focus()
            (selStart, selEnd) match
                case (Present(s), Present(e)) => setSelection(focusTarget, s, e)
                case _                        => ()
        end if
    end restoreFocus

    /** The set of `data-kyo-path` values of every `data-kyo-focus-auto` element inside `root`, `root` itself included.
      *
      * Callers capture this BEFORE replacing a region so that [[seedFocusAuto]] can tell a newly appeared element from
      * one that was already on screen: an echo re-render of an open overlay must not steal focus back from the user.
      */
    private def focusAutoPaths(root: dom.Element): Set[String] =
        val els = root.querySelectorAll("[data-kyo-focus-auto]")
        val descendants = (0 until els.length).flatMap { i =>
            Maybe(els(i).asInstanceOf[dom.Element].getAttribute("data-kyo-path")).toList
        }.toSet
        if root.hasAttribute("data-kyo-focus-auto") && root.hasAttribute("data-kyo-path") then
            descendants + root.getAttribute("data-kyo-path")
        else descendants
    end focusAutoPaths

    /** Seed the FIRST `data-kyo-focus-auto` element under `newRoot` whose path is not in `oldSet` (i.e. it newly
      * appeared): record the previously focused element's path plus the focus-restore flag on the stack, then call
      * `.focus()` on it. On the initial mount `oldSet` is empty, so any focus-auto element is seeded, like native
      * `autofocus`. Mirrors `seedFocusAuto` in HtmlRenderer.clientJs.
      */
    private def seedFocusAuto(newRoot: dom.Element, oldSet: Set[String]): Unit =
        val els = newRoot.querySelectorAll("[data-kyo-focus-auto]")
        val candidates =
            (if newRoot.hasAttribute("data-kyo-focus-auto") then Seq(newRoot) else Seq.empty) ++
                (0 until els.length).map(els(_).asInstanceOf[dom.Element])
        candidates.find { el =>
            val p = el.getAttribute("data-kyo-path")
            p != null && !oldSet.contains(p)
        }.foreach { el =>
            val ae = document.activeElement
            val ret =
                if ae != null && (ae ne document.body) then Maybe(ae.getAttribute("data-kyo-path"))
                else Absent
            focusReturnStack =
                focusReturnStack.append(
                    FocusSeed(el.getAttribute("data-kyo-path"), ret, el.hasAttribute("data-kyo-focus-restore"))
                )
            discard(el.asInstanceOf[scalajs.js.Dynamic].focus())
        }
    end seedFocusAuto

    /** Unwind stack entries whose seeded focus-auto element left the document, returning focus at most once.
      *
      * Stops at the first entry whose element is still in the document: that seed is still on screen, and
      * restoring an entry below it would move focus out of it. Below that, exactly one restore may land: a
      * deeper entry belongs to a seed that closed while a newer one stayed open, so its return target is stale
      * and must not override the one just restored. Its entry is still dropped, so it cannot fire on a later
      * sweep either. Mirrors `sweepFocusAuto` in HtmlRenderer.clientJs.
      */
    @tailrec
    private def sweepFocusAuto(restored: Boolean = false): Unit =
        focusReturnStack.lastMaybe match
            case Present(seed)
                if document.querySelector(s"""[data-kyo-path="${seed.path}"][data-kyo-focus-auto]""") == null =>
                focusReturnStack = focusReturnStack.dropLeftAndRight(0, 1)
                val landed = !restored && seed.restore && seed.returnTo.exists(retPath => focusIfPresent(retPath))
                sweepFocusAuto(restored || landed)
            case _ => ()
    end sweepFocusAuto

    /** Focus the element carrying `path`; `false` when it is no longer in the document (nothing focused). */
    private def focusIfPresent(path: String): Boolean =
        val el = document.querySelector(s"""[data-kyo-path="$path"]""")
        if el == null then false
        else
            discard(el.asInstanceOf[scalajs.js.Dynamic].focus())
            true
        end if
    end focusIfPresent

    /** Moves the caret on `el`, tolerating the two documented ways that is a no-op.
      *
      * Elements outside input and textarea (select, contenteditable) have no `setSelectionRange` at all, and on
      * input types without a text selection (email, number, both of which kyo-ui offers as text inputs) it throws
      * `InvalidStateError`. In either case the value is already set and only the caret stays put. Any other
      * JavaScript exception is a real failure and propagates rather than being swallowed. Mirrored by
      * `kyoSetCaret` in `HtmlRenderer.clientJs`.
      */
    private def setSelection(el: dom.Element, start: Int, end: Int): Unit =
        val dyn = el.asInstanceOf[scalajs.js.Dynamic]
        if scalajs.js.typeOf(dyn.setSelectionRange) == "function" then
            try discard(dyn.setSelectionRange(start, end))
            catch
                case ex: scalajs.js.JavaScriptException
                    if ex.exception.asInstanceOf[scalajs.js.Dynamic].name.asInstanceOf[String] == "InvalidStateError" =>
                    ()
        end if
    end setSelection

    // Bridge a Kyo Async computation from a JS callback boundary by offering it to the page-scoped drain
    // channel. The single AllowUnsafe site narrows to the offer crossing (the JS callback has no Kyo
    // context); a drop on a closed channel is fine (the page is being torn down anyway).
    private def fireFromJs(events: Channel[Unit < Async], eff: Unit < Async)(using Frame): Unit =
        // Unsafe: JS event callbacks run outside any Kyo context; this is the one controlled crossing point.
        import AllowUnsafe.embrace.danger
        // runPartial drops only a Closed (offer on a torn-down channel); a Panic propagates to evalOrThrow and
        // surfaces (thrown at the boundary) rather than being swallowed by the discard.
        discard(Sync.Unsafe.evalOrThrow(Abort.runPartial[Closed](events.offer(eff)).unit))
    end fireFromJs

    /** Scan `root` and all descendants for `data-kyo-prop-*` attributes, apply each as a direct
      * DOM property on the element, then remove the data attribute so it does not linger.
      */
    private def applyJsProps(root: dom.Element)(using Frame): Unit < Sync =
        Sync.defer(applyJsPropsSync(root))

    private def applyJsPropsSync(root: dom.Element): Unit =
        val propPrefix = "data-kyo-prop-"
        // CSS has no attribute-name-prefix selector, so `[data-kyo-prop-*]` is not a valid selector and
        // throws SyntaxError. Collect the root plus every descendant and keep those carrying any
        // data-kyo-prop-* attribute; the apply loop reads the prop name off each attribute.
        val elements = root.querySelectorAll("*")
        val self =
            if hasAnyKyoProp(root) then
                Seq(root)
            else
                Seq.empty
        (self ++ (0 until elements.length).map(elements(_).asInstanceOf[dom.Element])).foreach { el =>
            val attrNames = (0 until el.attributes.length).map(el.attributes(_).name)
            val toRemove  = attrNames.filter(_.startsWith(propPrefix))
            toRemove.foreach { attrName =>
                val propName = attrName.stripPrefix(propPrefix)
                val value    = el.getAttribute(attrName)
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(propName)(value)
            }
            toRemove.foreach(el.removeAttribute)
        }
    end applyJsPropsSync

    private def hasAnyKyoProp(el: dom.Element): Boolean =
        (0 until el.attributes.length).exists(i => el.attributes(i).name.startsWith("data-kyo-prop-"))

    /** Start every freshly-inserted SMIL animation under `root`.
      *
      * Chart transition `<animate>` elements use `begin="indefinite"` so they do not auto-play against the
      * shared SVG document timeline (which would make a post-load update snap to the frozen `to` value).
      * Calling `beginElement()` after the node is inserted starts the tween relative to now. The call is
      * deferred one animation frame so the SMIL engine has registered the newly inserted elements; a node
      * that was already replaced again by then throws and is ignored.
      */
    private def beginAnimationsSync(root: dom.Element): Unit =
        val anims = root.querySelectorAll("animate,animateTransform,animateMotion")
        if anims.length > 0 then
            discard(dom.window.requestAnimationFrame { (_: Double) =>
                var i = 0
                while i < anims.length do
                    try anims(i).asInstanceOf[scalajs.js.Dynamic].beginElement()
                    catch case _: Throwable => ()
                    i += 1
                end while
            })
        end if
    end beginAnimationsSync

    /** True when `start` or any of its ancestors below `document.body` declares event type `t` in `data-kyo-ev`.
      *
      * ReactiveUI.dispatchToElement bubbles an event to every ancestor that declared a handler for its type, so the
      * SPA forwarding gate must forward when ANY ancestor declared it, not just the target (checking only the
      * target's own data-kyo-ev would drop e.g. a keydown meant for an ancestor panel before bubble dispatch runs).
      */
    private[kyo] def declaredInChain(start: dom.Element, t: String): Boolean =
        var n: dom.Element = start
        var found          = false
        while !found && n != null && (n ne document.body) do
            val ev = n.getAttribute("data-kyo-ev")
            if ev != null && ev.split(",").contains(t) then found = true
            else
                n = n.parentNode match
                    case p: dom.Element => p
                    case _              => null
            end if
        end while
        found
    end declaredInChain

    /** Set up capture-phase event delegation on document.body. */
    private def setupEventDelegation(dispatch: (Seq[String], UIEvent) => Boolean < Async, events: Channel[Unit < Async])(using
        Frame
    ): Unit < Sync = Sync.defer {
        final class ChainTypes(target: dom.Element):
            def contains(t: String): Boolean = declaredInChain(target, t)

        // A submit button's click makes the browser fire a native `submit` right after, but the Click dispatch
        // already emulates onSubmit; this flag (set on Click, cleared on a 0-timeout) suppresses that one
        // following native submit so the form handler runs once. Mirrors clientJs's `_kyoClickSubmit` guard.
        var clickSubmitGuard = false

        val handler: scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) =>
            // Runs before path resolution so it fires even when the focused descendant is not a path element;
            // the keydown is still forwarded below, so the region's own onKeyDown runs (see `scrollKeyPrevented`).
            if e.`type` == "keydown" then
                val ke  = e.asInstanceOf[dom.KeyboardEvent]
                val tgt = e.target.asInstanceOf[dom.Element]
                if tgt != null && scrollKeyPrevented(ke.key, tgt) && tgt.closest("[data-kyo-scroll-keys]") != null then
                    e.preventDefault()
            end if
            findPathElement(e.target.asInstanceOf[dom.Element]).foreach { target =>
                val path    = parsePath(target.getAttribute("data-kyo-path"))
                val evTypes = ChainTypes(target)
                val t       = e.`type`

                val event: Maybe[UIEvent] =
                    if t == "click" then
                        val targetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val me       = e.asInstanceOf[dom.MouseEvent]
                        val mouse = MouseEventData(
                            modifiers = UI.Modifiers(me.ctrlKey, me.altKey, me.shiftKey, me.metaKey),
                            targetId = targetId
                        )
                        // Prevent the browser's default navigation only when the anchor carries a kyo
                        // click handler (so the handler, not the href, drives the action). A plain href
                        // keeps native behavior: an in-page `#anchor` scrolls, and a cross-document route
                        // is handled by UILocation's interceptor. Prevent-defaulting every anchor here
                        // would also kill those.
                        if target.tagName.toLowerCase == "a" && evTypes.contains("click") then e.preventDefault()
                        clickSubmitGuard = true
                        discard(dom.window.setTimeout(() => clickSubmitGuard = false, 0))
                        Present(UIEvent.Click(path, mouse))
                    else if t == "input" && evTypes.contains("input") then
                        Present(UIEvent.Input(path, e.target.asInstanceOf[dom.html.Input].value))
                    else if t == "change" && evTypes.contains("change") then
                        val tgt = e.target.asInstanceOf[dom.html.Input]
                        val typ = tgt.`type`
                        if typ == "checkbox" || typ == "radio" then
                            Present(UIEvent.ChangeChecked(path, tgt.checked))
                        else if typ == "number" || typ == "range" then
                            Present(UIEvent.ChangeNumeric(path, tgt.value.toDouble))
                        else if typ == "file" then
                            val files = tgt.files
                            if files.length > 0 then
                                val reader = new dom.FileReader()
                                reader.onload = (_: dom.Event) =>
                                    val content = reader.result.asInstanceOf[String]
                                    val ev      = UIEvent.Change(path, content)
                                    fireFromJs(events, dispatch(path, ev).unit)
                                reader.readAsText(files(0))
                            end if
                            Absent
                        else
                            Present(UIEvent.Change(path, tgt.value))
                        end if
                    else if t == "submit" && evTypes.contains("submit") then
                        e.preventDefault()
                        if clickSubmitGuard then Absent
                        else
                            val submitTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                            val submitMouse = MouseEventData(
                                modifiers = UI.Modifiers.none,
                                targetId = submitTargetId
                            )
                            Present(UIEvent.Submit(path, submitMouse))
                        end if
                    else if t == "keydown" && evTypes.contains("keydown") then
                        val ke         = e.asInstanceOf[dom.KeyboardEvent]
                        val kdTargetId = Maybe(ke.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.KeyDown(
                            path,
                            KeyboardEventData(
                                key = ke.key,
                                modifiers = UI.Modifiers(ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey),
                                targetId = kdTargetId
                            )
                        ))
                    else if t == "keyup" && evTypes.contains("keyup") then
                        val ke         = e.asInstanceOf[dom.KeyboardEvent]
                        val kuTargetId = Maybe(ke.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.KeyUp(
                            path,
                            KeyboardEventData(
                                key = ke.key,
                                modifiers = UI.Modifiers(ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey),
                                targetId = kuTargetId
                            )
                        ))
                    else if t == "focus" && evTypes.contains("focus") then
                        val focusTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        // FocusEvent does not carry modifier keys (not a MouseEvent); use Modifiers.none
                        Present(UIEvent.Focus(
                            path,
                            MouseEventData(UI.Modifiers.none, focusTargetId)
                        ))
                    else if t == "blur" && evTypes.contains("blur") then
                        val blurTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.Blur(path, MouseEventData(UI.Modifiers.none, blurTargetId)))
                    else if t == "mouseover" && evTypes.contains("mouseover") then
                        val hoverTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val me            = e.asInstanceOf[dom.MouseEvent]
                        Present(UIEvent.Hover(
                            path,
                            MouseEventData(
                                modifiers = UI.Modifiers(me.ctrlKey, me.altKey, me.shiftKey, me.metaKey),
                                targetId = hoverTargetId
                            )
                        ))
                    else if t == "mouseout" && evTypes.contains("mouseout") then
                        val unhoverTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val me              = e.asInstanceOf[dom.MouseEvent]
                        Present(UIEvent.Unhover(
                            path,
                            MouseEventData(
                                modifiers = UI.Modifiers(me.ctrlKey, me.altKey, me.shiftKey, me.metaKey),
                                targetId = unhoverTargetId
                            )
                        ))
                    else if t == "wheel" && evTypes.contains("wheel") then
                        val wheelTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val we            = e.asInstanceOf[dom.WheelEvent]
                        Present(UIEvent.Scroll(
                            path,
                            deltaX = we.deltaX,
                            deltaY = we.deltaY,
                            modifiers = UI.Modifiers(we.ctrlKey, we.altKey, we.shiftKey, we.metaKey),
                            targetId = wheelTargetId
                        ))
                    else
                        Absent

                event.foreach { ev =>
                    fireFromJs(events, dispatch(path, ev).unit)
                }
            }
        end handler

        Seq("click", "input", "change", "submit", "keydown", "keyup", "focus", "blur", "mouseover", "mouseout").foreach { t =>
            document.body.addEventListener(t, handler, true)
        }
        document.body.addEventListener(
            "wheel",
            handler,
            js.Dynamic.literal(capture = true, passive = false).asInstanceOf[dom.EventListenerOptions]
        )
    }
    end setupEventDelegation

    private def findPathElement(el: dom.Element): Maybe[dom.Element] =
        if el == null || (el eq document.body) then Absent
        else if el.hasAttribute("data-kyo-path") then Present(el)
        else
            el.parentNode match
                case p: dom.Element => findPathElement(p)
                case _              => Absent

    private def parsePath(p: String): Seq[String] =
        if p == null || p.isEmpty then Seq.empty
        else p.split("\\.").toSeq

    // ---- enter/leave transition mirror (SPA transport) ----

    /** The set of `data-kyo-path` values of every `data-kyo-enter` element inside `root`, `root` itself included. */
    private def enterPaths(root: dom.Element): Set[String] =
        val els = root.querySelectorAll("[data-kyo-enter]")
        val ds = (0 until els.length).flatMap { i =>
            Maybe(els(i).asInstanceOf[dom.Element].getAttribute("data-kyo-path")).toList
        }.toSet
        if root.hasAttribute("data-kyo-enter") && root.hasAttribute("data-kyo-path") then
            ds + root.getAttribute("data-kyo-path")
        else ds
    end enterPaths

    /** Animate every `data-kyo-enter` element under `newRoot` (root included) whose path is not in `oldSet`: add the enter
      * classes, force a reflow, then remove them next frame so the CSS transition runs from the enter-from state.
      */
    private def seedEnter(newRoot: dom.Element, oldSet: Set[String]): Unit =
        val els = newRoot.querySelectorAll("[data-kyo-enter]")
        val cand =
            (if newRoot.hasAttribute("data-kyo-enter") then Seq(newRoot) else Seq.empty) ++
                (0 until els.length).map(els(_).asInstanceOf[dom.Element])
        cand.foreach { el =>
            val p = el.getAttribute("data-kyo-path")
            if p != null && !oldSet.contains(p) then
                val cls     = el.getAttribute("data-kyo-enter").split("\\s+").filter(_.nonEmpty)
                val clsList = el.asInstanceOf[scalajs.js.Dynamic].classList
                cls.foreach(c => clsList.add(c))
                val _ = el.asInstanceOf[scalajs.js.Dynamic].offsetWidth // force reflow
                discard(dom.window.requestAnimationFrame { (_: Double) =>
                    cls.foreach(c => clsList.remove(c))
                })
            end if
        }
    end seedEnter

    /** The set of paths of `data-kyo-leave` elements in an HTML fragment (which leave-elements survive a region
      * replace). Keyed on leave-carrying elements, NOT all `data-kyo-path`: a reactive wrapper span shares its path
      * with the (leaving) element it wraps, so an all-path set would wrongly report the element as surviving.
      */
    private def leaveSurvSet(html: String): Set[String] =
        val tpl = document.createElement("template").asInstanceOf[scalajs.js.Dynamic]
        tpl.innerHTML = html
        val content = tpl.content.asInstanceOf[dom.DocumentFragment]
        val els     = content.querySelectorAll("[data-kyo-leave]")
        (0 until els.length).flatMap { i =>
            Maybe(els(i).asInstanceOf[dom.Element].getAttribute("data-kyo-path")).toList
        }.toSet
    end leaveSurvSet

    /** Strip `data-kyo-*` and `id` from a subtree so a ghost clone is inert (no selector collisions). */
    private def stripKyo(el: dom.Element): Unit =
        def strip(e: dom.Element): Unit =
            val dyn = e.asInstanceOf[scalajs.js.Dynamic]
            if scalajs.js.typeOf(dyn.getAttributeNames) == "function" then
                val names = dyn.getAttributeNames().asInstanceOf[scalajs.js.Array[String]]
                names.foreach(n => if n.startsWith("data-kyo-") || n == "id" then e.removeAttribute(n))
        end strip
        strip(el)
        val ds = el.querySelectorAll("*")
        (0 until ds.length).foreach(i => strip(ds(i).asInstanceOf[dom.Element]))
    end stripKyo

    /** Prepare leave ghosts for the OUTERMOST `data-kyo-leave` elements under `root` being removed (path not in `surv`).
      * Captures rect + clone WHILE the node is still in the DOM; returns (ghostNode, leaveClasses) descriptors.
      */
    private def prepareLeaveGhosts(root: dom.Element, surv: Set[String]): Seq[(dom.Element, String)] =
        val els = root.querySelectorAll("[data-kyo-leave]")
        val cand =
            (if root.getAttribute("data-kyo-leave") != null then Seq(root) else Seq.empty) ++
                (0 until els.length).map(els(_).asInstanceOf[dom.Element])
        val removed = cand.filter { e =>
            val p = e.getAttribute("data-kyo-path")
            p == null || !surv.contains(p)
        }
        val outer = removed.filterNot(e => removed.exists(o => (o ne e) && o.contains(e)))
        outer.map { node =>
            val rect  = node.asInstanceOf[scalajs.js.Dynamic].getBoundingClientRect()
            val leave = node.getAttribute("data-kyo-leave")
            val g     = node.cloneNode(true).asInstanceOf[dom.Element]
            stripKyo(g)
            val st = g.asInstanceOf[scalajs.js.Dynamic].style
            st.position = "fixed"
            st.left = rect.left.asInstanceOf[Double].toString + "px"
            st.top = rect.top.asInstanceOf[Double].toString + "px"
            st.width = rect.width.asInstanceOf[Double].toString + "px"
            st.height = rect.height.asInstanceOf[Double].toString + "px"
            st.margin = "0"
            st.pointerEvents = "none"
            g.setAttribute("data-kyo-ghost", "1")
            (g, if leave == null then "" else leave)
        }
    end prepareLeaveGhosts

    /** Append prepared ghosts to `<body>`, add their leave classes next frame, remove on transitionend/animationend or a 1s safety. */
    private def spawnGhosts(ghosts: Seq[(dom.Element, String)]): Unit =
        ghosts.foreach { case (g, leave) =>
            discard(document.body.appendChild(g))
            val cls     = leave.split("\\s+").filter(_.nonEmpty)
            val clsList = g.asInstanceOf[scalajs.js.Dynamic].classList
            discard(dom.window.requestAnimationFrame((_: Double) => cls.foreach(c => clsList.add(c))))
            var done = false
            def cleanup(): Unit =
                if !done then
                    done = true
                    if g.parentNode != null then discard(g.parentNode.removeChild(g))
            val listener: scalajs.js.Function1[dom.Event, Unit] = (_: dom.Event) => cleanup()
            g.addEventListener("transitionend", listener)
            g.addEventListener("animationend", listener)
            val to: scalajs.js.Function0[Unit] = () => cleanup()
            discard(dom.window.setTimeout(to, 1000.0))
        }
    end spawnGhosts

    // ---- input filter/mask (SPA transport) ----
    // The character-level decisions live in the shared InputMasking so they are testable without a DOM;
    // what stays here is the DOM wiring.

    private def dispatchInput(t: dom.EventTarget): Unit =
        val ctor = scalajs.js.Dynamic.global.Event
        val ev   = scalajs.js.Dynamic.newInstance(ctor)("input", scalajs.js.Dynamic.literal(bubbles = true))
        discard(t.asInstanceOf[scalajs.js.Dynamic].dispatchEvent(ev))
    end dispatchInput

    private def setValue(t: dom.html.Input, v: String): Unit =
        t.value = v
        setSelection(t, v.length, v.length)
        dispatchInput(t)
    end setValue

    private def setFilteredAt(t: dom.html.Input, txt: String, s: Int, e: Int): Unit =
        val v = t.value
        t.value = v.substring(0, s) + txt + v.substring(e)
        val np = s + txt.length
        setSelection(t, np, np)
        dispatchInput(t)
    end setFilteredAt

    private def setupInputMasking()(using Frame): Unit < Sync = Sync.defer {
        val handler: scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) =>
            val tRaw = e.target
            if tRaw != null then
                val el = tRaw.asInstanceOf[dom.Element]
                // Interactive.data lets any element carry data-kyo-filter, and everything below assumes a value
                // property and a text selection. Throwing from a beforeinput capture listener would break typing
                // for the whole page, so anything but a text field is left alone.
                val isTextField = el.tagName == "INPUT" || el.tagName == "TEXTAREA"
                val filt        = if isTextField then el.getAttribute("data-kyo-filter") else null
                val mask        = if isTextField then el.getAttribute("data-kyo-mask") else null
                if filt != null || mask != null then
                    val t   = tRaw.asInstanceOf[dom.html.Input]
                    val dyn = e.asInstanceOf[scalajs.js.Dynamic]
                    val it  = if scalajs.js.typeOf(dyn.inputType) == "string" then dyn.inputType.asInstanceOf[String] else ""
                    def selStart: Int =
                        val d = t.asInstanceOf[scalajs.js.Dynamic]
                        if scalajs.js.typeOf(d.selectionStart) == "number" then d.selectionStart.asInstanceOf[Int] else t.value.length
                    def selEnd: Int =
                        val d = t.asInstanceOf[scalajs.js.Dynamic]
                        if scalajs.js.typeOf(d.selectionEnd) == "number" then d.selectionEnd.asInstanceOf[Int] else selStart
                    def transferText: String =
                        val dt = dyn.dataTransfer
                        if dt != null && scalajs.js.typeOf(dt) == "object" then dt.getData("text").asInstanceOf[String]
                        else if scalajs.js.typeOf(dyn.data) == "string" then dyn.data.asInstanceOf[String]
                        else ""
                    end transferText
                    // insertCompositionText is deliberately absent below: preventDefault on it does not filter the
                    // input, it aborts the composition, which breaks CJK input, dead keys and mobile autocorrect.
                    // Composition is let through and the finished text is corrected by the compositionend listener.
                    if filt != null then
                        if it.startsWith("delete") then ()
                        else if it == "insertText" || it == "insertReplacementText" then
                            if scalajs.js.typeOf(dyn.data) == "string" then
                                val ds = dyn.data.asInstanceOf[String]
                                val f1 = InputMasking.filterStr(filt, ds, t.value)
                                if f1 != ds then
                                    e.preventDefault()
                                    if f1.nonEmpty then setFilteredAt(t, f1, selStart, selEnd)
                        else if it == "insertFromPaste" || it == "insertFromDrop" then
                            e.preventDefault()
                            val f2 = InputMasking.filterStr(filt, transferText, t.value)
                            if f2.nonEmpty then setFilteredAt(t, f2, selStart, selEnd)
                        end if
                    else if mask != null then
                        val tokens = InputMasking.parseMask(mask)
                        if it.startsWith("delete") then
                            e.preventDefault()
                            val raw = InputMasking.maskRaw(tokens, t.value)
                            val nr  = if raw.nonEmpty then raw.substring(0, raw.length - 1) else raw
                            setValue(t, InputMasking.maskFormat(tokens, nr))
                        else if it == "insertText" || it == "insertReplacementText" ||
                            it == "insertFromPaste" || it == "insertFromDrop"
                        then
                            e.preventDefault()
                            val ins = if it == "insertFromPaste" || it == "insertFromDrop" then transferText
                            else if scalajs.js.typeOf(dyn.data) == "string" then dyn.data.asInstanceOf[String]
                            else ""
                            var raw2 = InputMasking.maskRaw(tokens, t.value)
                            var ci   = 0
                            var full = false
                            while ci < ins.length && !full do
                                InputMasking.maskClassAt(tokens, raw2.length) match
                                    case Present(cls) =>
                                        val ch = ins.charAt(ci)
                                        if InputMasking.maskOk(cls, ch) then raw2 = raw2 + ch
                                    case Absent => full = true
                                end match
                                ci += 1
                            end while
                            setValue(t, InputMasking.maskFormat(tokens, raw2))
                        end if
                    end if
                end if
            end if
        document.body.addEventListener("beforeinput", handler, true)
        document.body.addEventListener("compositionend", compositionEndHandler, true)
    }
    end setupInputMasking

    /** Corrects the whole value once a composition finishes.
      *
      * An IME, a dead key or mobile autocorrect produces its text only when the composition ends, so there is no
      * per-character event to constrain; the finished value is filtered or formatted here instead. Writing back only
      * on a change keeps a composition that already conforms free of a caret jump and of a spurious input event.
      * Mirrored by `kyoCompositionEnd` in `HtmlRenderer.clientJs`.
      */
    private val compositionEndHandler: scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) =>
        val tRaw = e.target
        if tRaw != null then
            val el = tRaw.asInstanceOf[dom.Element]
            if el.tagName == "INPUT" || el.tagName == "TEXTAREA" then
                val t    = tRaw.asInstanceOf[dom.html.Input]
                val filt = el.getAttribute("data-kyo-filter")
                val mask = el.getAttribute("data-kyo-mask")
                val v    = t.value
                val nv =
                    if filt != null then InputMasking.filterStr(filt, v, "")
                    else if mask != null then InputMasking.maskNormalize(mask, v)
                    else v
                if nv != v then setValue(t, nv)
            end if
        end if

    /** True when `key` is a page-scrolling navigation key a `preventScrollKeys` region should suppress on `target`.
      * Vertical keys are exempt when `target` consumes them itself (caret line movement, option change) — there the
      * browser default is not a page scroll, so there is nothing to suppress. A single-line input stays suppressed for
      * vertical keys on purpose: that is the combobox case where `ArrowDown` drives the listbox highlight.
      * Horizontal/edge keys are exempt for any text-editable target, so a filter input keeps caret movement.
      */
    private def scrollKeyPrevented(key: String, target: dom.Element): Boolean =
        val tag              = target.tagName
        def contentEditable  = target.asInstanceOf[scalajs.js.Dynamic].isContentEditable.asInstanceOf[Boolean]
        def editable         = tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT" || contentEditable
        def verticalConsumer = tag == "TEXTAREA" || tag == "SELECT" || contentEditable
        key match
            case "ArrowUp" | "ArrowDown" | "PageUp" | "PageDown" => !verticalConsumer
            case "ArrowLeft" | "ArrowRight" | "Home" | "End"     => !editable
            case _                                               => false
        end match
    end scrollKeyPrevented

end DomBackend
