package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.scalajs.js

/** Scala.js UI backend. Mounts a UI into the browser DOM. The implicit default `Backend` for the
  * un-keyed UI tree (a DOM element has no `BackendNode.backend` key); registered at first mount.
  */
private[kyo] object DomBackend extends Backend:

    def key: String = "dom"

    /** Mount a UI into the page body. */
    def mount(ui: UI)(using Frame): Unit < (Async & Scope) =
        // `document.body` is read inside the effect, not at the call site, so building the mount
        // value stays pure (no DOM access until it runs, so it is safe to construct under Node/SSR).
        Sync.defer(document.body).map(body => mountInto(ui, body))

    /** Mount a UI into a specific DOM element selected by CSS selector. */
    def mount(ui: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        Sync.defer {
            val target = document.querySelector(selector)
            if target == null then Abort.panic(UIException(s"Element not found: $selector"))
            else mountInto(ui, target.asInstanceOf[dom.Element])
        }
    end mount

    // The Backend SPI mount: the DOM backend mounts the whole tree into `host`. Returns a Live
    // handle whose teardown is the ambient page Scope (mountInto forks under it). `path` is the
    // mount root (Seq.empty for the page body); the DOM backend owns the un-keyed tree.
    def mount(node: UI, host: dom.Element, path: Seq[String])(using Frame): Backend.Live < (Async & Scope) =
        mountInto(node, host).andThen(DomBackend.live)

    // The DOM backend is not on the reactive patch path: the server emits Replace for every DOM
    // region (never SetProp), so no server op reaches this. It exists to satisfy the SPI; were it
    // called it would re-render the subtree at `path` and replace it (today's LocalExchange/Replace),
    // ignoring `key` and treating `encoded` as rendered HTML.
    def patch(path: Seq[String], key: String, encoded: String)(using Frame): Unit < Async =
        Sync.defer {
            val p  = path.mkString(".")
            val el = dom.document.querySelector(s"""[data-kyo-path="$p"]""")
            if el != null && el.outerHTML != encoded then el.outerHTML = encoded
        }

    // DOM structural reactivity rides Replace, not ReplaceSubtree; this satisfies the SPI with the
    // same subtree-swap semantics.
    def replaceSubtree(path: Seq[String], encoded: String)(using Frame): Unit < Async =
        patch(path, "", encoded)

    private val live: Backend.Live = new Backend.Live {}

    /** The private dom-host registry entry: mounts the transitional `Host`'s own mount closure
      * (if any) through the uniform registry dispatch (`Backend.lookup("dom-host")`), the renamed
      * successor of the old direct `DomHostMount`-special-case run. `private[kyo]`, transitional:
      * deleted together with `Host`.
      */
    private[kyo] object DomHostBackend extends Backend:
        def key: String = "dom-host"

        def mount(node: UI, host: dom.Element, path: Seq[String])(using Frame): Backend.Live < (Async & Scope) =
            node match
                case h: UI.Ast.Host =>
                    h.mount match
                        case Present(m: DomBackendMount) => m.run(host).andThen(DomBackend.live)
                        case _                           => DomBackend.live
                case _ => DomBackend.live

        // The dom-host backend never receives a server op: Host carries no boundProps, and DOM
        // structural reactivity rides Replace (never SetProp/ReplaceSubtree). Satisfies the SPI.
        def patch(path: Seq[String], key: String, encoded: String)(using Frame): Unit < Async = Kyo.unit

        def replaceSubtree(path: Seq[String], encoded: String)(using Frame): Unit < Async = Kyo.unit
    end DomHostBackend

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
            // Pre-register the DOM backend + the transitional dom-host entry so the generalized
            // fireHostMounts below can dispatch through the registry. Idempotent (re-registering on a
            // later mount just overwrites the same map entries); the page's single main fiber registers
            // before any mount dispatch reads it (Backend.scala:47-48).
            _    <- Sync.defer { Backend.register(DomBackend); Backend.register(DomHostBackend) }
            _    <- DomStyleSheet.injectBase()
            root <- ReactiveUI.normalize(ui, Seq.empty)
            html <- HtmlRenderer.render(ui, Seq.empty)
            _    <- Sync.defer(container.innerHTML = html)
            _    <- applyJsProps(container)
            _    <- fireHostMounts(ui, Seq.empty)
            _    <- Sync.defer(beginAnimationsSync(container))
            exchange = LocalExchange(root)
            dispatch <- ReactiveUI.subscribe(root, exchange)
            // Single-consumer drain owned by the ambient page Scope: every JS event effect is run by a
            // Fiber.init consumer (interrupted on page teardown). The single consumer preserves event ordering
            // and is scoped, so page teardown interrupt propagates to the drain via the ambient Scope.
            events <- Channel.init[Unit < Async](256)
            // runPartial captures only the Closed failure (the channel closed on page teardown -> stop draining); a
            // Panic propagates rather than being silently swallowed as a clean drain end.
            _ <- Fiber.init(Loop.foreach(Abort.runPartial[Closed](events.take).map {
                case Result.Success(eff) => eff.andThen(Loop.continue)
                case Result.Failure(_)   => Loop.done
            }))
            _ <- setupEventDelegation(dispatch.handle, events)
            _ <- Async.never
        yield ()
        end for
    end mountInto

    /** Hydrates an ALREADY-SSR'd page: walks `ui` (rebuilt client-side via the SAME builder function
      * the server called, so `data-kyo-path`/`data-kyo-backend` match the SSR markup by construction)
      * and dispatches each `BackendNode`'s mount through the registry onto its existing
      * placeholder element (a foreign backend's `<canvas>`, say), WITHOUT touching `container.innerHTML`,
      * unlike `mountInto`. A server-pushed page's own reactivity rides the inline WS listener
      * `HtmlRenderer.clientJs` already emits into the SSR'd HTML (`SetProp`/`ReplaceSubtree` routed
      * through `window.__kyoBackends`); this walk exists only to REGISTER each
      * backend node's live mount so that dispatch has somewhere to land, mirroring `mountInto`'s own
      * `fireHostMounts` step with no `ReactiveUI.normalize`/`LocalExchange`/`subscribe` half (a
      * server-driven page needs no CLIENT-local reactive subscription).
      */
    private[kyo] def hydrateBackendNodes(ui: UI)(using Frame): Unit < (Async & Scope) =
        // Pre-register the DOM backend + the transitional dom-host entry, mirroring mountInto's own
        // idempotent registration (Backend.scala:47-48); a page that only embeds a foreign backend node
        // (no plain DOM host) still needs DomBackend registered for the dom-host registry entry it
        // shares with mountInto's own Host dispatch path.
        Sync.defer { Backend.register(DomBackend); Backend.register(DomHostBackend) }
            .andThen(fireHostMounts(ui, Seq.empty))

    /** Walk the original AST tracking `data-kyo-path` exactly as `HtmlRenderer.renderTo`
      * assigns it (children indexed by position; `KeyedChild` keeps its parent path), and for
      * each `BackendNode` resolve the live element by its path and dispatch its mount through the
      * registry (`Backend.lookup(bn.backend)`), exactly once. A backend node inside a reactive
      * (`Reactive`/`Foreach`) zone is NOT fired: it sits under a signal whose subtree a re-render
      * may replace, so only one in a const subtree mounts. A skipped mount is not silent: the walk
      * descends a reactive region's current content with `underReactive = true` and logs a warning
      * when it finds a backend node with mount intent, so the no-op is visible to the author.
      */
    private def fireHostMounts(ui: UI, path: Seq[String], underReactive: Boolean = false)(using Frame): Unit < (Async & Scope) =
        ui match
            case bn: UI.Ast.BackendNode =>
                // The transitional Host carries an OPTIONAL mount closure (a bare `UI.host()` with no
                // closure is a legitimately inert placeholder); every other BackendNode (a future
                // registered scene) always has real mount work, so it defaults to true.
                val hasMountIntent = bn match
                    case h: UI.Ast.Host => h.mount.nonEmpty
                    case _              => true
                if !hasMountIntent then Kyo.unit
                else if underReactive then
                    Log.warn(
                        s"UI backend node '${bn.placeholder.tag}' (backend='${bn.backend}') carries a client mount but " +
                            "sits inside a reactive region (UI.show/when/render/foreach); its mount is not fired because " +
                            "a reactive boundary may replace the subtree. Place it in a non-reactive position so its mount runs."
                    )
                else
                    Sync.defer(document.querySelector(s"""[data-kyo-path="${path.mkString(".")}"]""")).map {
                        case null => ()
                        case element =>
                            Backend.lookup(bn.backend) match
                                case Present(backend) => backend.mount(bn, element.asInstanceOf[dom.Element], path).unit
                                case Absent           => Kyo.unit
                    }
                end if
            case elem: UI.Ast.Element =>
                Kyo.foreachDiscard(elem.children.toSeq.zipWithIndex) { (child, i) =>
                    fireHostMounts(child, path :+ i.toString, underReactive)
                }
            case UI.Ast.Fragment(children) =>
                Kyo.foreachDiscard(children.toSeq.zipWithIndex) { (child, i) =>
                    val childPath = child match
                        case kc: UI.Ast.KeyedChild[?] => path :+ kc.key
                        case _                        => path :+ i.toString
                    fireHostMounts(child, childPath, underReactive)
                }
            case UI.Ast.KeyedChild(_, child) =>
                fireHostMounts(child, path, underReactive)
            case r: UI.Ast.Reactive[?] =>
                // Descend the region's CURRENT content only to surface a skipped host (never to fire one):
                // a re-render may replace this subtree, so a host here cannot be safely mounted.
                r.signal.current(using r.frame).map(cur => fireHostMounts(cur, path, underReactive = true))
            case f: UI.Ast.Foreach[?, ?] @unchecked =>
                // Reduce the keyed list to its current rendered content (as ReactiveUI.normalize does) and
                // descend it to surface a skipped host; the items render under a signal, so none is fired.
                val rendered: Signal[UI] =
                    f.signal.map { items =>
                        UI.Ast.Fragment[UI](Chunk.from(items.toSeq.zipWithIndex.map((item, i) => f.render(i, item)))): UI
                    }
                rendered.current(using f.frame).map(cur => fireHostMounts(cur, path, underReactive = true))
            case _ =>
                // Text and RawHtml carry no fireable host.
                Kyo.unit
    end fireHostMounts

    /** Exchange that renders UI to HTML and applies directly to the DOM (a DomRegion), or patches a
      * backend node's live prop directly (a PropRegion). The PropRegion arm is present but unreached
      * until a backend node ancestor carries the `data-kyo-backend` marker (a later phase); it
      * resolves the owning backend via the same up-the-tree walk the wire client's `backendForPath`
      * uses, then routes to `Backend.patch`.
      */
    private class LocalExchange(root: ReactiveUI) extends UIExchange:
        private def svgContextAt(path: Seq[String]): Boolean =
            ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

        def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
            region match
                case domRegion: ReactiveUI.Region.DomRegion =>
                    val path = domRegion.path
                    val ui   = value.asInstanceOf[UI]
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
                                el.outerHTML = finalHtml
                                val updated = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                                if updated != null then
                                    applyJsPropsSync(updated)
                                    beginAnimationsSync(updated)
                                if activePath != null then
                                    restoreFocus(activePath, selStart, selEnd)
                            end if
                        }
                    }
                case prop: ReactiveUI.Region.PropRegion =>
                    Sync.defer(backendForPath(prop.path)).map {
                        case Present(backend) => backend.patch(prop.path, prop.key, prop.encode(value))
                        case Absent           => Kyo.unit
                    }
                case _: ReactiveUI.Region.StructuralRegion =>
                    // Deliberate no-op. In a client-local mount the 3D structure is driven
                    // LOCALLY by the reconciler's own runReactiveRegion, the SINGLE writer of
                    // region.prevKeyed. Routing this emission to a backend's replaceSubtree would make
                    // LocalExchange a SECOND writer of the same keyed state, breaking dispose-once/keyed-
                    // reuse. The client owns structure here.
                    Kyo.unit
    end LocalExchange

    /** Resolves the live backend owning `path`: walk up from the element at `path` to the nearest
      * ancestor marked `data-kyo-backend` and look up its registered `Backend` by that marker's
      * value. Mirrors the client's `backendForPath` (`HtmlRenderer.clientJs`); returns `Absent`
      * until a backend node ancestor carries the marker (a later phase).
      */
    private def backendForPath(path: Seq[String]): Maybe[Backend] =
        def walkUp(el: dom.Element): Maybe[Backend] =
            if el == null || (el eq document.body) then Absent
            else if el.hasAttribute("data-kyo-backend") then Backend.lookup(el.getAttribute("data-kyo-backend"))
            else
                el.parentNode match
                    case p: dom.Element => walkUp(p)
                    case _              => Absent
        walkUp(document.querySelector(s"""[data-kyo-path="${path.mkString(".")}"]"""))
    end backendForPath

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

end DomBackend
