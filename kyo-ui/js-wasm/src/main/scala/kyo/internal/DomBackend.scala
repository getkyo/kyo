package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.scalajs.js

/** Scala.js UI backend. Mounts a UI into the browser DOM. */
private[kyo] object DomBackend:

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

    private def mountInto(ui: UI, container: dom.Element)(using Frame): Unit < (Async & Scope) =
        for
            _    <- DomStyleSheet.injectBase()
            root <- ReactiveUI.normalize(ui, Seq.empty)
            html <- HtmlRenderer.render(ui, Seq.empty)
            _    <- Sync.defer(container.innerHTML = html)
            _    <- applyJsProps(container)
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

    /** Exchange that renders UI to HTML and applies directly to the DOM. */
    private class LocalExchange(root: ReactiveUI) extends UIExchange:
        private def svgContextAt(path: Seq[String]): Boolean =
            ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

        def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
            HtmlRenderer.render(ui, path).map { html =>
                // In SVG context an empty reactive zone needs a <g> placeholder (a <span> is invalid
                // inside <svg>); the non-empty branch already carries the correct tags from HtmlRenderer.
                val tag = if svgContextAt(path) then "g" else "span"
                val finalHtml =
                    if html.isEmpty then
                        s"""<$tag data-kyo-path="${path.mkString(".")}" data-kyo-reactive></$tag>"""
                    else html
                val pathAttr = path.mkString(".")
                Sync.defer {
                    val el = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                    if el != null && el.outerHTML != finalHtml then
                        el.outerHTML = finalHtml
                        val updated = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                        if updated != null then
                            applyJsPropsSync(updated)
                            beginAnimationsSync(updated)
                    end if
                }
            }
    end LocalExchange

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
        val elements   = root.querySelectorAll(s"[$propPrefix*]")
        val self =
            if root.hasAttribute(s"${propPrefix}indeterminate") || hasAnyKyoProp(root) then
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
                        // Speculatively prevent navigation on anchor elements with a kyo handler
                        if target.tagName.toLowerCase == "a" then e.preventDefault()
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
