package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.scalajs.js

/** Scala.js UI backend. Mounts a UI into the browser DOM. */
private[kyo] object DomBackend:

    /** The page-scoped drain channel, captured once per mount so the viewport scroll/resize listeners (raw JS
      * callbacks, outside any Kyo context) can bridge their `deliverMeasureById` effect back in via [[fireFromJs]].
      * Set in `mountInto` before any op can be emitted. Module-level mutable state is safe on the single-threaded runtime.
      */
    private var sessionEvents: Maybe[Channel[Unit < Async]] = Absent

    /** Live viewport observers for the SPA transport, keyed by element id. Each entry is the single handler
      * registered for BOTH `window` scroll (capture phase) and resize; Unobserve removes it from both and drops the
      * entry. Backed by a native `js.Map` (mirrors `UIMouseEventOps`), giving `contains`/`apply`/`update`/`remove`.
      */
    private val viewportObservers: js.WrappedMap[String, js.Function1[dom.Event, Unit]] =
        new js.WrappedMap(js.Map.empty[String, js.Function1[dom.Event, Unit]])

    /** Mark attribute `name` on `el` as owned by the imperative id-addressed channel (SetClassById/SetStyleById),
      * applied out of the render pass so CSS transitions on the toggled class/style fire. The owned names live in a
      * `__kyoOwn` expando dict ON the element, so the flag is reclaimed with the node (no session-lived set that only
      * ever grows) and `morphAttrs` shields each owned attribute BY NAME. Mirrors `__kyoMark` in HtmlRenderer.clientJs.
      */
    private def markOwned(el: dom.Element, name: String): Unit =
        val d = el.asInstanceOf[js.Dynamic]
        val own =
            if js.isUndefined(d.__kyoOwn) then
                val fresh = js.Dictionary.empty[Boolean]
                d.__kyoOwn = fresh.asInstanceOf[js.Any]
                fresh
            else d.__kyoOwn.asInstanceOf[js.Dictionary[Boolean]]
        own.update(name, true)
    end markOwned

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
        // Late-bound to break the emit<->Commands construction cycle (emit resolves measure callbacks via Commands,
        // Commands needs emit). Set before any op is emitted.
        var sessionCommands: UI.Commands = null
        for
            _    <- DomStyleSheet.injectBase()
            root <- ReactiveUI.normalize(ui, Seq.empty)
            html <- HtmlRenderer.render(ui, Seq.empty)
            _    <- Sync.defer(container.innerHTML = html)
            // Comment markers produce no node handles from an innerHTML assignment; one full scan
            // builds the path->range registry the patch path resolves against.
            _        <- Sync.defer { scanRoot = container; rebuildRegions() }
            _        <- applyJsProps(container)
            _        <- Sync.defer(beginAnimationsSync(container))
            commands <- UI.Commands.init(op => applyOpLocal(op, () => sessionCommands))
            _ = sessionCommands = commands
            // Env.run so component handlers and mounted effects resolve `UI.commands` at run time (the subscribe
            // region fibers and the event-drain fiber all fork inside this scope).
            _ <- Env.run(commands) {
                val exchange = LocalExchange(root)
                for
                    dispatch <- ReactiveUI.subscribe(root, exchange)
                    // Single-consumer drain owned by the ambient page Scope: every JS event effect is run by a
                    // Fiber.init consumer (interrupted on page teardown). The single consumer preserves event ordering
                    // and is scoped, so page teardown interrupt propagates to the drain via the ambient Scope.
                    events <- Channel.init[Unit < Async](256)
                    _ = sessionEvents = Present(events)
                    // runPartial captures only the Closed failure (the channel closed on page teardown -> stop draining);
                    // a Panic propagates rather than being silently swallowed as a clean drain end. The drain carries the
                    // session's scroll sink: a handler calling UI.scrollIntoView scrolls the local document, the
                    // browser-mount counterpart of the server session's WebSocket op.
                    _ <- Fiber.init(UICommands.scrollSink.let(Present(scrollLocal)) {
                        Loop.foreach(Abort.runPartial[Closed](events.take).map {
                            case Result.Success(eff) => eff.andThen(Loop.continue)
                            case Result.Failure(_)   => Loop.done
                        })
                    })
                    _ <- setupEventDelegation(dispatch.handle, events)
                    _ <- setupPointerDelegation(dispatch.handle, events)
                    _ <- Async.never
                yield ()
                end for
            }
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

    // Keyed-list keys are user data and become path segments: escape the CSS attribute-selector
    // metacharacters (backslash, double quote) so a key cannot break or redirect the query.
    private def pathSelector(joined: String): String =
        s"""[data-kyo-path="${joined.replace("\\", "\\\\").replace("\"", "\\\"")}"]"""

    // ---- local op application for the SPA transport (Command / RequestMeasure) ----

    private def queryByPath(path: Seq[String]): dom.Element =
        document.querySelector(pathSelector(path.mkString(".")))

    /** Resolve a path-addressed command/measure target: the element carrying the path, else (a region
      * path: regions have no element of their own) the region's first element child, else null.
      */
    private def resolveElementByPath(path: Seq[String]): dom.Element =
        val el = queryByPath(path)
        if el != null then el
        else
            regions.get(path.mkString(".")).orNull match
                case null => null
                case r =>
                    var found: dom.Element = null
                    foreachRangeElement(r)(e => if found == null then found = e)
                    found
        end if
    end resolveElementByPath

    /** A conservative "focusable" CSS selector: what a focus command may land on. Mirrors
      * the reactive-focus-restore query used elsewhere in this backend / HtmlRenderer.
      */
    private val FocusableSelector = "input,textarea,select,button,a[href],[tabindex],[contenteditable]"

    /** Focus `el` if it is itself focusable, else its FIRST focusable descendant. Lets a
      * focus command target a non-focusable WRAPPER (e.g. an InputGroup around several
      * fields) and land on the first field inside it. A focusable element (an `<input>`,
      * …) matches the selector and focuses itself, so existing focus targets are unchanged.
      */
    private def focusInto(el: dom.Element): Unit =
        if el != null then
            val dyn         = el.asInstanceOf[scalajs.js.Dynamic]
            val selfMatches = scalajs.js.typeOf(dyn.matches) == "function" && dyn.matches(FocusableSelector).asInstanceOf[Boolean]
            val target      = if selfMatches then el else el.querySelector(FocusableSelector)
            if target != null then
                val tdyn = target.asInstanceOf[scalajs.js.Dynamic]
                if scalajs.js.typeOf(tdyn.focus) == "function" then discard(tdyn.focus())

    /** Apply a whitelisted `verb` to `el` (shared by path- and id-addressed commands). Unknown verbs are ignored. */
    private def applyVerbDom(el: dom.Element, verb: String): Unit =
        if el != null then
            val dyn = el.asInstanceOf[scalajs.js.Dynamic]
            verb match
                case "focus" => focusInto(el)
                case "scrollIntoView" =>
                    if scalajs.js.typeOf(dyn.scrollIntoView) == "function" then
                        discard(dyn.scrollIntoView(scalajs.js.Dynamic.literal(block = "nearest")))
                case _ => ()
            end match
        end if
    end applyVerbDom

    private def applyCommandDom(path: Seq[String], verb: String): Unit =
        applyVerbDom(resolveElementByPath(path), verb)

    /** Self-addressing: resolve the command target by DOM id (getElementById) instead of the render path. */
    private def applyCommandDomById(id: String, verb: String): Unit =
        applyVerbDom(document.getElementById(id), verb)

    private def measureRect(el: dom.Element): Maybe[UI.Rect] =
        if el == null then Absent
        else
            val r = el.getBoundingClientRect()
            Present(UI.Rect(r.left, r.top, r.width, r.height, dom.window.innerWidth, dom.window.innerHeight))

    private def measureDom(path: Seq[String]): Maybe[UI.Rect] =
        measureRect(resolveElementByPath(path))

    /** Self-addressing: measure the element with DOM id `id` (getElementById). */
    private def measureDomById(id: String): Maybe[UI.Rect] =
        measureRect(document.getElementById(id))

    private def applyOpLocal(op: HtmlOp, commands: () => UI.Commands)(using Frame): Unit < Async =
        op match
            case HtmlOp.Command(path, verb) => Sync.defer(applyCommandDom(path, verb))
            case HtmlOp.RequestMeasure(path) =>
                Sync.defer(measureDom(path)).map {
                    case Present(rect) => commands().deliverMeasure(path, rect)
                    case Absent        => Kyo.unit
                }
            case HtmlOp.CommandById(id, verb) => Sync.defer(applyCommandDomById(id, verb))
            case HtmlOp.RequestMeasureById(id) =>
                Sync.defer(measureDomById(id)).map {
                    case Present(rect) => commands().deliverMeasureById(id, rect)
                    case Absent        => Kyo.unit
                }
            case HtmlOp.SetClassById(id, className, on) =>
                Sync.defer {
                    val el = document.getElementById(id)
                    if el != null then
                        markOwned(el, "class")
                        discard(el.classList.toggle(className, on))
                }
            case HtmlOp.SetStyleById(id, css) =>
                Sync.defer {
                    val el = document.getElementById(id)
                    if el != null then
                        markOwned(el, "style")
                        mergeStyleDomById(id, css)
                }
            // set an attribute in place (element stays in the DOM, so a CSS `>` anchored on it keeps matching).
            case HtmlOp.SetAttrById(id, name, value) =>
                Sync.defer {
                    val el = document.getElementById(id)
                    if el != null then
                        markOwned(el, name)
                        el.setAttribute(name, value)
                }
            // measure now + deliver, then attach the continuous scroll/resize observer for `id`.
            case HtmlOp.ObserveViewportById(id) =>
                Sync.defer(registerViewportObserver(id, commands)).andThen(
                    Sync.defer(measureDomById(id)).map {
                        case Present(rect) => commands().deliverMeasureById(id, rect)
                        case Absent        => Kyo.unit
                    }
                )
            case HtmlOp.UnobserveViewportById(id) =>
                Sync.defer(unregisterViewportObserver(id))
            // Replace/Remove/InjectCss reach the DOM through LocalExchange, never this imperative channel.
            case _ => Kyo.unit
    end applyOpLocal

    /** Merges a serialized `Style` declaration string ("prop:val;prop:val") onto getElementById(id) with setProperty
      * per declaration, so it merges over other inline props rather than clobbering them (unlike a full `style=""`
      * replace). Blank declarations and those without a `:` are skipped.
      */
    private def mergeStyleDomById(id: String, css: String): Unit =
        val el = document.getElementById(id)
        if el != null then
            val style = el.asInstanceOf[dom.HTMLElement].style
            css.split(';').foreach { decl =>
                val trimmed = decl.trim
                if trimmed.nonEmpty then
                    val colon = trimmed.indexOf(':')
                    if colon > 0 then
                        style.setProperty(trimmed.substring(0, colon).trim, trimmed.substring(colon + 1).trim)
                end if
            }
        end if
    end mergeStyleDomById

    /** Attaches a single handler to `window` scroll (capture) + resize that re-measures getElementById(id) and
      * bridges the deliver back into the drain via [[fireFromJs]]. Guards against double-registration for the same id.
      */
    private def registerViewportObserver(id: String, commands: () => UI.Commands)(using Frame): Unit =
        if !viewportObservers.contains(id) then
            val handler: js.Function1[dom.Event, Unit] = (_: dom.Event) =>
                measureDomById(id) match
                    case Present(rect) => sessionEvents.foreach(ev => fireFromJs(ev, commands().deliverMeasureById(id, rect)))
                    case Absent        => ()
            viewportObservers(id) = handler
            dom.window.addEventListener("scroll", handler, true)
            dom.window.addEventListener("resize", handler)
        end if
    end registerViewportObserver

    /** Removes the scroll/resize handler registered for `id` (from both listeners) and drops the map entry. */
    private def unregisterViewportObserver(id: String): Unit =
        if viewportObservers.contains(id) then
            val handler = viewportObservers(id)
            dom.window.removeEventListener("scroll", handler, true)
            dom.window.removeEventListener("resize", handler)
            discard(viewportObservers.remove(id))
        end if
    end unregisterViewportObserver

    /** Exchange that renders UI to HTML and applies directly to the DOM. */
    private class LocalExchange(root: ReactiveUI) extends UIExchange:

        // In-place attr patch, ownership-marked (__kyoOwn) so a parent region's morph won't reconcile the live value back.
        override def onAttrPatch(path: Seq[String], name: String, value: String)(using Frame): Unit < Async =
            Sync.defer {
                val el = queryByPath(path)
                if el != null then
                    markOwned(el, name)
                    el.setAttribute(name, value)
            }

        override def onBoolAttrPatch(path: Seq[String], name: String, value: Boolean)(using Frame): Unit < Async =
            Sync.defer {
                val el = queryByPath(path)
                if el != null then
                    markOwned(el, name)
                    if value then el.setAttribute(name, "") else el.removeAttribute(name)
            }

        // Class twin: toggle in place (so CSS transitions fire) rather than re-render; own "class" against the morph.
        override def onClassPatch(path: Seq[String], name: String, on: Boolean)(using Frame): Unit < Async =
            Sync.defer {
                val el = queryByPath(path)
                if el != null then
                    markOwned(el, "class")
                    discard(el.classList.toggle(name, on))
            }

        def onChange(path: Seq[String], ui: UI, mount: Boolean)(using Frame): Unit < Async =
            // Render content at its nested-reactive sub-path (contentPath) so a reactive-valued region paints a
            // DISTINCT inner marker span matching SSR/walkStatic. mountSlot=true stamps the `s` flag on Mounted
            // placeholders for the mount guards below. The payload is a bare fragment: the region's own live
            // markers stay in the DOM and are never re-sent, so the path stays addressable across replacements
            // regardless of what the content is (Fragment, Text, RawHtml: none carry a path-bearing root).
            HtmlRenderer.render(ui, HtmlRenderer.contentPath(path, ui), mountSlot = true).map { html =>
                Sync.defer {
                    val pathAttr = path.mkString(".")
                    val r        = lookupRegion(pathAttr)
                    if r == null then
                        // No marker pair at this path: either an ELEMENT region (a signal-bound field like
                        // `input.value(ref)`: the region root IS the live element carrying the path; no wrapper
                        // of any kind exists) morphed 1:1 in place, or genuinely unpainted DOM (e.g. Boundary
                        // pre-reveal), which stays a silent no-op.
                        val el = queryByPath(path)
                        if el != null && el.parentNode != null then
                            val toContainer = parseToContainer(el.parentNode.asInstanceOf[dom.Element], html)
                            val toRoot      = if toContainer != null then firstElementChildOf(toContainer) else null
                            if toRoot != null then
                                morphNode(el, toRoot)
                                val live = queryByPath(path)
                                if live != null then
                                    applyJsPropsSync(live)
                                    beginAnimationsSync(live)
                            end if
                        end if
                    // Leave the live mount region untouched ONLY when the new content is itself a mount slot
                    // (`s` = the SAME mount re-rendering, which owns its subtree). Different content or an empty
                    // gate-closed repaint falls through so the morph reconciles/empties the region.
                    else if !mount && r.mount && payloadRootIsMountSlot(html) then ()
                    else
                        val parent = r.start.parentNode.asInstanceOf[dom.Element]
                        // Capture focus and caret of the active element inside the replaced region (mirrors the
                        // clientJs Replace handler on the JS DOM API). Plain DOM inside the already-suspended
                        // Sync.defer; no new AllowUnsafe crossing.
                        val ae           = document.activeElement
                        val insideRegion = ae != null && (ae ne document.body) && rangeContains(r, ae)
                        // Use the active element's own data-kyo-path when it carries one (nested element),
                        // otherwise fall back to the region path so restoreFocus searches the range (common
                        // case: value-bound input inside the region has no data-kyo-path of its own).
                        val activePath =
                            if insideRegion then
                                if ae.hasAttribute("data-kyo-path") then ae.getAttribute("data-kyo-path")
                                else pathAttr
                            else null
                        val (selStart, selEnd) = if insideRegion then readSelection(ae) else (Absent, Absent)
                        val toContainer        = parseToContainer(parent, html)
                        if toContainer != null then
                            // Morph the marker-delimited range instead of replacing, so focus/caret/scroll/
                            // transitions on reused nodes survive.
                            morphRange(parent, r.start.nextSibling, r.end, toContainer.firstChild, null)
                            if mount && !r.mount then
                                // The mount's own first paint claims the region: the `m` flag rides the live
                                // start marker (source of truth, survives registry rebuilds); the registry
                                // entry is a cache. Client-only mutation, never in server-rendered HTML.
                                r.mount = true
                                r.start.data = RegionMarker.openData(pathAttr, mount = true)
                            end if
                            // A morph imports subtrees, which carries new nested-region markers with it:
                            // refresh the registry for exactly this range.
                            rescanRange(r)
                            foreachRangeElement(r) { el =>
                                applyJsPropsSync(el)
                                beginAnimationsSync(el)
                            }
                            if activePath != null then
                                restoreFocus(activePath, selStart, selEnd)
                        end if
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
        val located = document.querySelector(pathSelector(capturedPath))
        val focusTarget: dom.Element =
            if located != null then located
            else
                // A region path has no element carrying it; resolve via the registry and take the first
                // focus-capable element in the range (mirrors the old descend-into-wrapper behavior).
                regions.get(capturedPath).orNull match
                    case null => null
                    case r =>
                        var found: dom.Element = null
                        foreachRangeElement(r) { el =>
                            if found == null then
                                val sel = "input,textarea,select,[contenteditable]"
                                if el.matches(sel) then found = el
                                else
                                    val inner = el.querySelector(sel)
                                    if inner != null then found = inner
                                end if
                        }
                        found
        if focusTarget != null then
            val _ = focusTarget.asInstanceOf[scalajs.js.Dynamic].focus()
            (selStart, selEnd) match
                case (Present(s), Present(e)) =>
                    val dyn = focusTarget.asInstanceOf[scalajs.js.Dynamic]
                    if scalajs.js.typeOf(dyn.setSelectionRange) == "function" then
                        try
                            val _ = dyn.setSelectionRange(s, e)
                        catch
                            // setSelectionRange throws InvalidStateError on input types that do not
                            // support text selection (e.g. email, number). Mirrors HtmlRenderer.clientJs:583:
                            // `catch(e){if(e.name!=='InvalidStateError')throw e;}`. Re-throw any other
                            // JS exception so genuine failures are not silently dropped.
                            case ex: scalajs.js.JavaScriptException
                                if ex.exception.asInstanceOf[scalajs.js.Dynamic].name.asInstanceOf[String] == "InvalidStateError" =>
                                ()
                    end if
                case _ => ()
            end match
        end if
    end restoreFocus

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

    /** Set up capture-phase event delegation on document.body. */
    private def setupEventDelegation(dispatch: (Seq[String], UIEvent) => Boolean < Async, events: Channel[Unit < Async])(using
        Frame
    ): Unit < Sync = Sync.defer {
        val handler: scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) =>
            findPathElement(e.target.asInstanceOf[dom.Element]).foreach { target =>
                val path    = parsePath(target.getAttribute("data-kyo-path"))
                val evAttr  = target.getAttribute("data-kyo-ev")
                val evTypes = if evAttr != null then evAttr.split(",").toSet else Set.empty[String]
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
                        val submitTargetId = Maybe(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val submitMouse = MouseEventData(
                            modifiers = UI.Modifiers.none,
                            targetId = submitTargetId
                        )
                        Present(UIEvent.Submit(path, submitMouse))
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

    // ---- DOM morphing: patch a sibling range in place toward new HTML, preserving element identity ----
    // Replacing wholesale discards DOM-local state (focus, caret, scroll, in-flight transitions, pointer
    // capture) and node identity; morphing reuses nodes and patches only diffs. Reconciliation is
    // SIBLING-SCOPED, keyed on `data-kyo-path` for elements (unique among siblings: Foreach items get
    // `path :+ key`, element children `path :+ i`) and on the region path for marker-delimited spans, which
    // move/insert/remove as ONE logical child. A region owns the sibling range between its two comment
    // markers, so patches never touch out-of-range siblings of the same parent.
    private val SvgNs = "http://www.w3.org/2000/svg"

    // ---- pointer/drag delegation (SPA transport) ----

    // Drag-session state. Module-level mutable is safe on the single-threaded JS runtime (mutated only inside JS
    // event callbacks). A session is active between a pointerdown on a declaring element and its pointerup.
    private var ptrActive: Boolean             = false
    private var ptrEl: dom.Element             = null
    private var ptrPath: Seq[String]           = Seq.empty
    private var ptrRaf: Int                    = 0
    private var ptrPendingEv: dom.PointerEvent = null

    /** True if `start` or any ancestor up to (not including) body declares event token `t` in its data-kyo-ev. */
    private def declaredInChainAt(start: dom.Element, t: String): Boolean =
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
    end declaredInChainAt

    private def pointerPayload(el: dom.Element, ev: dom.PointerEvent): UI.PointerEvent =
        val r   = el.getBoundingClientRect()
        val tid = Maybe(ev.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
        UI.PointerEvent(
            x = ev.clientX - r.left,
            y = ev.clientY - r.top,
            rectX = r.left,
            rectY = r.top,
            rectW = r.width,
            rectH = r.height,
            buttons = ev.buttons,
            targetId = tid
        )
    end pointerPayload

    private def setupPointerDelegation(
        dispatch: (Seq[String], UIEvent) => Boolean < Async,
        events: Channel[Unit < Async]
    )(using Frame): Unit < Sync = Sync.defer {
        val down: scalajs.js.Function1[dom.Event, Unit] = (e0: dom.Event) =>
            val e = e0.asInstanceOf[dom.PointerEvent]
            findPathElement(e.target.asInstanceOf[dom.Element]).foreach { el =>
                if declaredInChainAt(el, "pointerdown") then
                    try
                        val d = el.asInstanceOf[scalajs.js.Dynamic]
                        if scalajs.js.typeOf(d.setPointerCapture) == "function" then discard(d.setPointerCapture(e.pointerId))
                    catch case _: Throwable => ()
                    end try
                    ptrActive = true
                    ptrEl = el
                    ptrPath = parsePath(el.getAttribute("data-kyo-path"))
                    fireFromJs(events, dispatch(ptrPath, UIEvent.PointerDown(ptrPath, pointerPayload(el, e))).unit)
                end if
            }

        val move: scalajs.js.Function1[dom.Event, Unit] = (e0: dom.Event) =>
            // Only stream during an active session; coalesce to at most one dispatch per animation frame.
            if ptrActive && ptrEl != null then
                ptrPendingEv = e0.asInstanceOf[dom.PointerEvent]
                if ptrRaf == 0 then
                    ptrRaf = dom.window.requestAnimationFrame { (_: Double) =>
                        ptrRaf = 0
                        if ptrActive && ptrEl != null && ptrPendingEv != null then
                            val ev = ptrPendingEv
                            ptrPendingEv = null
                            fireFromJs(events, dispatch(ptrPath, UIEvent.PointerMove(ptrPath, pointerPayload(ptrEl, ev))).unit)
                        end if
                    }
                end if

        val up: scalajs.js.Function1[dom.Event, Unit] = (e0: dom.Event) =>
            if ptrActive && ptrEl != null then
                val e = e0.asInstanceOf[dom.PointerEvent]
                try
                    val d = ptrEl.asInstanceOf[scalajs.js.Dynamic]
                    if scalajs.js.typeOf(d.releasePointerCapture) == "function" then discard(d.releasePointerCapture(e.pointerId))
                catch case _: Throwable => ()
                end try
                if ptrRaf != 0 then
                    dom.window.cancelAnimationFrame(ptrRaf)
                    ptrRaf = 0
                ptrPendingEv = null
                val el   = ptrEl
                val path = ptrPath
                ptrActive = false
                ptrEl = null
                ptrPath = Seq.empty
                fireFromJs(events, dispatch(path, UIEvent.PointerUp(path, pointerPayload(el, e))).unit)

        document.body.addEventListener("pointerdown", down, true)
        document.body.addEventListener("pointermove", move, true)
        document.body.addEventListener("pointerup", up, true)
    }
    end setupPointerDelegation

    // ---- region registry: joined path -> live comment-marker range ----

    /** A live region's marker pair. `mount` mirrors the `m` flag on the start marker's data (cache only;
      * the marker text is the source of truth and survives registry rebuilds).
      */
    final private class RegionRange(val start: dom.Comment, val end: dom.Comment, var mount: Boolean)

    /** Path -> live range. Rebuilt by full scans (initial paint, stale-lookup fallback) and refreshed by
      * range-scoped rescans after every patch (a morph imports subtrees via importNode, which brings new
      * nested-region markers with them). Module-level mutable is safe on the single-threaded runtime.
      */
    private var regions: js.Dictionary[RegionRange] = js.Dictionary.empty
    private var scanRoot: dom.Node                  = null

    private def commentData(n: dom.Node): String = n.asInstanceOf[dom.Comment].data

    /** Register a scanned pair, repairing parser separation first: the whole-page parser keeps an open
      * marker that precedes a table's FIRST row as a `<table>` child while the rows and the close
      * marker land inside the implied `<tbody>` it synthesizes. Adopting the open marker into that row
      * group makes the pair share a parent again (the invariant every range walk relies on) and puts
      * the rows inside the range. Twin of `__kyoRegPair` in clientJs.
      */
    private def registerPair(path: String, start: dom.Comment, end: dom.Comment, mount: Boolean): Unit =
        if (start.parentNode ne end.parentNode) && end.parentNode != null && (end.parentNode.parentNode eq start.parentNode) then
            discard(end.parentNode.insertBefore(start, end.parentNode.firstChild))
        regions(path) = new RegionRange(start, end, mount)
    end registerPair

    /** Register every marker pair under `root` (whole-subtree TreeWalker) into `regions`. Paths are
      * document-unique, so pairing open->close by path needs no stack; unbalanced markers are dropped.
      */
    private def scanRegionsInto(root: dom.Node): Unit =
        val walker = document.createTreeWalker(root, dom.NodeFilter.SHOW_COMMENT, null, false)
        val opens  = js.Dictionary.empty[(dom.Comment, Boolean)]
        var n      = walker.nextNode()
        while n != null do
            RegionMarker.parse(commentData(n)) match
                case Present(p) =>
                    if p.isClose then
                        opens.get(p.path) match
                            case Some((start, mount)) =>
                                registerPair(p.path, start, n.asInstanceOf[dom.Comment], mount)
                                discard(opens.remove(p.path))
                            case None => ()
                    else opens(p.path) = (n.asInstanceOf[dom.Comment], p.mount)
                case Absent => ()
            end match
            n = walker.nextNode()
        end while
    end scanRegionsInto

    private def rebuildRegions(): Unit =
        regions = js.Dictionary.empty
        if scanRoot != null then scanRegionsInto(scanRoot)
    end rebuildRegions

    /** Re-register marker pairs inside `r` after a patch. A pair always shares one parent, so direct
      * comment siblings pair at this level and everything deeper is contained in element siblings.
      * Bounded by patch size, not page size.
      */
    private def rescanRange(r: RegionRange): Unit =
        val opens = js.Dictionary.empty[(dom.Comment, Boolean)]
        var n     = r.start.nextSibling
        while n != null && (n ne r.end) do
            if n.nodeType == 8 then
                RegionMarker.parse(commentData(n)) match
                    case Present(p) =>
                        if p.isClose then
                            opens.get(p.path) match
                                case Some((start, mount)) =>
                                    registerPair(p.path, start, n.asInstanceOf[dom.Comment], mount)
                                    discard(opens.remove(p.path))
                                case None => ()
                        else opens(p.path) = (n.asInstanceOf[dom.Comment], p.mount)
                    case Absent => ()
            else if n.nodeType == 1 then scanRegionsInto(n)
            end if
            n = n.nextSibling
        end while
    end rescanRange

    /** Locate a live region by joined path. Connectivity-validated; a stale or missing entry triggers
      * ONE full rescan, then retries; still missing -> null. Callers no-op on null, preserving the old
      * "querySelector returned null -> silently skip" contract Boundary pre-reveal patches rely on.
      */
    private def lookupRegion(pathAttr: String): RegionRange =
        def connected(r: RegionRange): Boolean =
            r != null && r.start.isConnected && r.end.isConnected
        val direct = regions.get(pathAttr).orNull
        if connected(direct) then direct
        else
            rebuildRegions()
            val retried = regions.get(pathAttr).orNull
            if connected(retried) then retried else null
        end if
    end lookupRegion

    /** True when `node` is one of the range's direct children or a descendant of one. */
    private def rangeContains(r: RegionRange, node: dom.Node): Boolean =
        var n     = r.start.nextSibling
        var found = false
        while !found && n != null && (n ne r.end) do
            if (n eq node) || (n.nodeType == 1 && n.contains(node)) then found = true
            n = n.nextSibling
        found
    end rangeContains

    private def foreachRangeElement(r: RegionRange)(f: dom.Element => Unit): Unit =
        var n = r.start.nextSibling
        while n != null && (n ne r.end) do
            if n.nodeType == 1 then f(n.asInstanceOf[dom.Element])
            n = n.nextSibling
    end foreachRangeElement

    // ---- logical children: a marker-delimited span is ONE keyed child ----

    /** For an open marker, its matching close marker among following siblings; null when unbalanced (the
      * caller then treats the comment as a plain positional node). Pairs never nest at one level (paths
      * are unique among siblings), so a direct path match suffices.
      */
    private def spanClose(open: dom.Node, openPath: String): dom.Node =
        var n                = open.nextSibling
        var result: dom.Node = null
        while result == null && n != null do
            if n.nodeType == 8 then
                RegionMarker.parse(commentData(n)) match
                    case Present(p) if p.isClose && p.path == openPath => result = n
                    case _                                             => ()
            end if
            n = n.nextSibling
        end while
        result
    end spanClose

    /** The reconciliation key of a logical child: an element's `data-kyo-path`, an open marker's region
      * path, else null (text, plain comments, and unkeyed elements reconcile positionally). Markers are
      * NEVER matched positionally: mispairing would rewrite marker text and corrupt region identity.
      */
    private def logicalKey(node: dom.Node): String =
        if node.nodeType == 1 then
            val el = node.asInstanceOf[dom.Element]
            if el.hasAttribute("data-kyo-path") then el.getAttribute("data-kyo-path") else null
        else if node.nodeType == 8 then
            RegionMarker.parse(commentData(node)) match
                case Present(p) if !p.isClose && spanClose(node, p.path) != null => p.path
                case _                                                           => null
        else null

    /** Next logical sibling: past the whole span for an open marker, else nextSibling. */
    private def logicalNext(node: dom.Node): dom.Node =
        if node.nodeType == 8 then
            RegionMarker.parse(commentData(node)) match
                case Present(p) if !p.isClose =>
                    val close = spanClose(node, p.path)
                    if close != null then close.nextSibling else node.nextSibling
                case _ => node.nextSibling
        else node.nextSibling

    private def eachSpanNode(first: dom.Node)(f: dom.Node => Unit): Unit =
        val last =
            if first.nodeType == 8 then
                RegionMarker.parse(commentData(first)) match
                    case Present(p) if !p.isClose =>
                        val close = spanClose(first, p.path)
                        if close != null then close else first
                    case _ => first
            else first
        var n    = first
        var stop = false
        while !stop && n != null do
            val next = n.nextSibling
            stop = n eq last
            f(n)
            n = next
        end while
    end eachSpanNode

    private def moveLogicalBefore(parent: dom.Element, node: dom.Node, ref: dom.Node): Unit =
        eachSpanNode(node)(n => discard(parent.insertBefore(n, ref)))

    private def removeLogical(parent: dom.Element, node: dom.Node): Unit =
        eachSpanNode(node)(n => discard(parent.removeChild(n)))

    private def insertLogicalClone(parent: dom.Element, toNode: dom.Node, ref: dom.Node): Unit =
        eachSpanNode(toNode)(n => discard(parent.insertBefore(document.importNode(n, true), ref)))

    /** Patch matched logical children (same key). Element vs element morphs in place; span vs span
      * recurses on the two content ranges, unless the live span carries the `m` (mount root) flag and
      * the incoming one the `s` (mount slot) flag: that is the SAME mount re-rendering, which owns and
      * repaints its own subtree, so the span is opaque and its start marker is never touched (the `m`
      * flag must survive). A kind mismatch at one key replaces wholesale.
      */
    private def patchLogical(parent: dom.Element, m: dom.Node, toNode: dom.Node): Unit =
        val fromIsSpan = m.nodeType == 8
        val toIsSpan   = toNode.nodeType == 8
        if !fromIsSpan && !toIsSpan then morphNode(m, toNode)
        else if fromIsSpan && toIsSpan then
            (RegionMarker.parse(commentData(m)), RegionMarker.parse(commentData(toNode))) match
                case (Present(f), Present(t)) =>
                    val fClose = spanClose(m, f.path)
                    val tClose = spanClose(toNode, t.path)
                    if fClose == null || tClose == null then ()
                    else if f.mount && t.slot then ()
                    else morphRange(parent, m.nextSibling, fClose, toNode.nextSibling, tClose)
                case _ => ()
        else
            insertLogicalClone(parent, toNode, m)
            removeLogical(parent, m)
        end if
    end patchLogical

    /** True when the payload's first node is an open marker carrying the `s` (mount slot) flag: the
      * region's new content root IS a mount placeholder. Bounded to the leading comment so a descendant
      * marker cannot false-match.
      */
    private def payloadRootIsMountSlot(html: String): Boolean =
        if !html.startsWith("<!--") then false
        else
            val end = html.indexOf("-->")
            end > 4 && (RegionMarker.parse(html.substring(4, end)) match
                case Present(p) => !p.isClose && p.slot
                case Absent     => false)

    private def firstElementChildOf(parent: dom.Node): dom.Element =
        var c = parent.firstChild
        while c != null && c.nodeType != 1 do c = c.nextSibling
        if c == null then null else c.asInstanceOf[dom.Element]
    end firstElementChildOf

    /** Parse a region payload (a bare content fragment, zero..n roots) in the parse context its live
      * parent dictates, returning the detached node whose childNodes are the new content (kept inside
      * the template; the morph imports nodes on insert). The context wrap keeps the fragment parser from
      * foster-parenting or silently dropping context-sensitive content: a `<tr>` payload outside a table
      * parse is discarded wholesale, an `<option>` outside select likewise, and bare SVG elements in an
      * HTML template become unknown elements. Comment markers survive every one of these parse modes,
      * which is what makes marker-delimited regions parseable at all. Twin of `__kyoParseCtx` in
      * clientJs; keep in lockstep.
      */
    private def parseToContainer(parent: dom.Element, html: String): dom.Node =
        val tpl = document.createElement("template").asInstanceOf[dom.HTMLTemplateElement]
        val tag = parent.tagName
        val (prefix, suffix, depth) =
            if parent.namespaceURI == SvgNs && tag.toLowerCase != "foreignobject" then ("<svg>", "</svg>", 1)
            else
                tag match
                    // Explicit <tbody> (not the parser's implied one) so the descent depth is fixed.
                    case "TABLE" | "THEAD" | "TBODY" | "TFOOT" => ("<table><tbody>", "</tbody></table>", 2)
                    case "TR"                                  => ("<table><tbody><tr>", "</tr></tbody></table>", 3)
                    case "SELECT" | "OPTGROUP"                 => ("<select>", "</select>", 1)
                    case _                                     => ("", "", 0)
        tpl.innerHTML = prefix + html + suffix
        var container: dom.Node = tpl.content
        var d                   = depth
        while d > 0 && container != null do
            container = firstElementChildOf(container)
            d -= 1
        container
    end parseToContainer

    private def morphNode(fromNode: dom.Node, toNode: dom.Node): Unit =
        if toNode.nodeType != 1 then
            if fromNode.nodeValue != toNode.nodeValue then fromNode.nodeValue = toNode.nodeValue
        else
            val fromEl = fromNode.asInstanceOf[dom.Element]
            val toEl   = toNode.asInstanceOf[dom.Element]
            if fromEl.tagName != toEl.tagName then
                discard(fromEl.parentNode.replaceChild(document.importNode(toEl, true), fromEl))
            else morphEl(fromEl, toEl)
    end morphNode

    private def morphEl(fromEl: dom.Element, toEl: dom.Element): Unit =
        morphAttrs(fromEl, toEl)
        // A focused contenteditable would lose its caret if its children were rewritten mid-edit; leave its
        // subtree alone (INPUT/TEXTAREA have no element children, so need no such guard).
        val editing = (fromEl eq document.activeElement) && fromEl.hasAttribute("contenteditable")
        if !editing then morphChildren(fromEl, toEl)
    end morphEl

    private def morphAttrs(fromEl: dom.Element, toEl: dom.Element): Unit =
        // An attribute the imperative id-addressed channel owns (its name is in the element's `__kyoOwn` expando dict)
        // is never reconciled: server HTML never carries the client-set value, so reconciling would clobber it.
        val own                         = fromEl.asInstanceOf[js.Dynamic].__kyoOwn
        val ownDict                     = if js.isUndefined(own) then null else own.asInstanceOf[js.Dictionary[Boolean]]
        def owns(name: String): Boolean = ownDict != null && ownDict.contains(name)
        val tag                         = fromEl.tagName
        val activeInput =
            (fromEl eq document.activeElement) && (tag == "INPUT" || tag == "TEXTAREA")
        val toAttrs = toEl.attributes
        var i       = 0
        while i < toAttrs.length do
            val a    = toAttrs(i)
            val name = a.name
            if !owns(name) && fromEl.getAttribute(name) != a.value then fromEl.setAttribute(name, a.value)
            i += 1
        end while
        // Remove attributes gone from `to`. Walk the live NamedNodeMap backward so a removal never shifts an
        // index still to be visited (no intermediate collection allocated).
        val fromAttrs = fromEl.attributes
        var j         = fromAttrs.length - 1
        while j >= 0 do
            val name = fromAttrs(j).name
            if !owns(name) && !toEl.hasAttribute(name) then fromEl.removeAttribute(name)
            j -= 1
        end while
        // Active-input preservation: two-way binding echoes each keystroke back as a re-render. Never overwrite the
        // focused field's live `.value` (its caret) with its own echo (value already matches); assign only a genuine
        // external change (submit-clear, programmatic update).
        if activeInput then
            val nv =
                if tag == "TEXTAREA" then toEl.textContent
                else
                    val v = toEl.getAttribute("value")
                    if v == null then "" else v
            val dyn = fromEl.asInstanceOf[scalajs.js.Dynamic]
            if nv != dyn.value.asInstanceOf[String] then dyn.value = nv
        end if
    end morphAttrs

    private def morphChildren(fromParent: dom.Element, toParent: dom.Element): Unit =
        morphRange(fromParent, fromParent.firstChild, null, toParent.firstChild, null)

    /** Reconcile the live sibling range [fromStart, fromEnd) of `fromParent` toward the target range
      * [toStart, toEnd) (nodes inside a detached template). Null bounds mean "to the end of the parent";
      * a region's close marker serves as the from-side sentinel, so out-of-range siblings (including a
      * region's own markers) are never visited and `insertBefore(node, sentinel)` appends at the range
      * end. Keyed lookups are sibling-scoped over LOGICAL children (keys unique among siblings); `toKeyed`
      * records which stale from-children are reused elsewhere so an unkeyed slot doesn't destroy them.
      * Twin of `__kyoMorphRange` in clientJs; keep in lockstep.
      */
    private def morphRange(
        fromParent: dom.Element,
        fromStart: dom.Node,
        fromEnd: dom.Node,
        toStart: dom.Node,
        toEnd: dom.Node
    ): Unit =
        var fromKeyed: js.Dictionary[dom.Node] = null
        var toKeyed: js.Dictionary[Boolean]    = null
        var scan                               = fromStart
        while scan != null && (scan ne fromEnd) do
            val k = logicalKey(scan)
            if k != null then
                if fromKeyed == null then fromKeyed = js.Dictionary.empty[dom.Node]
                fromKeyed(k) = scan
            scan = logicalNext(scan)
        end while
        scan = toStart
        while scan != null && (scan ne toEnd) do
            val k = logicalKey(scan)
            if k != null then
                if toKeyed == null then toKeyed = js.Dictionary.empty[Boolean]
                toKeyed(k) = true
            scan = logicalNext(scan)
        end while
        var curFrom = fromStart
        var curTo   = toStart
        while curTo != null && (curTo ne toEnd) do
            val toNext = logicalNext(curTo)
            val tKey   = logicalKey(curTo)
            if tKey != null then
                val m = if fromKeyed != null then fromKeyed.get(tKey).orNull else null
                if m != null then
                    if m ne curFrom then moveLogicalBefore(fromParent, m, curFrom)
                    else curFrom = logicalNext(curFrom)
                    patchLogical(fromParent, m, curTo)
                else
                    insertLogicalClone(fromParent, curTo, curFrom)
                end if
            else
                var handled = false
                var loop    = true
                while loop && curFrom != null && (curFrom ne fromEnd) do
                    val fNext = logicalNext(curFrom)
                    val fKey  = logicalKey(curFrom)
                    if fKey != null then
                        // A keyed from-child at an unkeyed slot: keep it if `to` reuses it elsewhere (its own slot
                        // moves it into place), else it's stale and removed. Null toKeyed = `to` has no keyed child.
                        if toKeyed == null || !toKeyed.contains(fKey) then removeLogical(fromParent, curFrom)
                        curFrom = fNext
                    else if compatible(curFrom, curTo) then
                        morphNode(curFrom, curTo)
                        curFrom = fNext
                        handled = true
                        loop = false
                    else
                        removeLogical(fromParent, curFrom)
                        curFrom = fNext
                    end if
                end while
                if !handled then insertLogicalClone(fromParent, curTo, curFrom)
            end if
            curTo = toNext
        end while
        while curFrom != null && (curFrom ne fromEnd) do
            val fNext = logicalNext(curFrom)
            removeLogical(fromParent, curFrom)
            curFrom = fNext
        end while
    end morphRange

    /** Two nodes may be patched into each other positionally: same node kind, and for elements the same tag. */
    private def compatible(a: dom.Node, b: dom.Node): Boolean =
        a.nodeType == b.nodeType &&
            (a.nodeType != 1 || a.asInstanceOf[dom.Element].tagName == b.asInstanceOf[dom.Element].tagName)

end DomBackend
