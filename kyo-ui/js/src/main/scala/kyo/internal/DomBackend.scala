package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document

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
            exchange = LocalExchange()
            dispatch <- ReactiveUI.subscribe(root, exchange)
            _        <- setupEventDelegation(dispatch.handle)
            _        <- Async.never
        yield ()
        end for
    end mountInto

    /** Exchange that renders UI to HTML and applies directly to the DOM. */
    private class LocalExchange extends UIExchange:
        def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
            HtmlRenderer.render(ui, path).map { html =>
                val finalHtml =
                    if html.isEmpty then
                        s"""<span data-kyo-path="${path.mkString(".")}" data-kyo-reactive></span>"""
                    else html
                val pathAttr = path.mkString(".")
                Sync.defer {
                    val el = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                    if el != null && el.outerHTML != finalHtml then
                        el.outerHTML = finalHtml
                        val updated = document.querySelector(s"""[data-kyo-path="$pathAttr"]""")
                        if updated != null then
                            applyJsPropsSync(updated)
                    end if
                }
            }
    end LocalExchange

    /** Bridge a Kyo Async computation from a JS callback boundary.
      *
      * JS event callbacks have no Kyo context. This is the single controlled crossing point where AllowUnsafe is permitted in this module.
      * All other code remains in safe Kyo style.
      */
    private def fireFromJs(eff: Unit < Async)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(eff).unit))
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
            val toRemove  = scala.collection.mutable.ArrayBuffer.empty[String]
            val attrNames = (0 until el.attributes.length).map(el.attributes(_).name)
            attrNames.foreach { attrName =>
                if attrName.startsWith(propPrefix) then
                    val propName = attrName.stripPrefix(propPrefix)
                    val value    = el.getAttribute(attrName)
                    el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(propName)(value)
                    toRemove += attrName
            }
            toRemove.foreach(el.removeAttribute)
        }
    end applyJsPropsSync

    private def hasAnyKyoProp(el: dom.Element): Boolean =
        (0 until el.attributes.length).exists(i => el.attributes(i).name.startsWith("data-kyo-prop-"))

    /** Set up capture-phase event delegation on document.body. */
    private def setupEventDelegation(dispatch: (Seq[String], UIEvent) => Boolean < Async)(using Frame): Unit < Sync = Sync.defer {
        val handler: scalajs.js.Function1[dom.Event, Unit] = (e: dom.Event) =>
            findPathElement(e.target.asInstanceOf[dom.Element]).foreach { target =>
                val path    = parsePath(target.getAttribute("data-kyo-path"))
                val evAttr  = target.getAttribute("data-kyo-ev")
                val evTypes = if evAttr != null then evAttr.split(",").toSet else Set.empty[String]
                val t       = e.`type`

                val event: Maybe[UIEvent] =
                    if t == "click" then
                        val targetId = Option(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val me       = e.asInstanceOf[dom.MouseEvent]
                        val mouse = MouseEventData(
                            modifiers = UI.Modifiers(me.ctrlKey, me.altKey, me.shiftKey, me.metaKey),
                            targetId = targetId.fold(Absent: Maybe[String])(Present(_))
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
                                    fireFromJs(dispatch(path, ev).unit)
                                reader.readAsText(files(0))
                            end if
                            Absent
                        else
                            Present(UIEvent.Change(path, tgt.value))
                        end if
                    else if t == "submit" && evTypes.contains("submit") then
                        e.preventDefault()
                        val submitTargetId = Option(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        val submitMouse = MouseEventData(
                            modifiers = UI.Modifiers.none,
                            targetId = submitTargetId.fold(Absent: Maybe[String])(Present(_))
                        )
                        Present(UIEvent.Submit(path, submitMouse))
                    else if t == "keydown" && evTypes.contains("keydown") then
                        val ke         = e.asInstanceOf[dom.KeyboardEvent]
                        val kdTargetId = Option(ke.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.KeyDown(
                            path,
                            KeyboardEventData(
                                key = ke.key,
                                modifiers = UI.Modifiers(ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey),
                                targetId = kdTargetId.fold(Absent: Maybe[String])(Present(_))
                            )
                        ))
                    else if t == "keyup" && evTypes.contains("keyup") then
                        val ke         = e.asInstanceOf[dom.KeyboardEvent]
                        val kuTargetId = Option(ke.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.KeyUp(
                            path,
                            KeyboardEventData(
                                key = ke.key,
                                modifiers = UI.Modifiers(ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey),
                                targetId = kuTargetId.fold(Absent: Maybe[String])(Present(_))
                            )
                        ))
                    else if t == "focus" && evTypes.contains("focus") then
                        val focusTargetId = Option(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        // FocusEvent does not carry modifier keys (not a MouseEvent); use Modifiers.none
                        Present(UIEvent.Focus(
                            path,
                            MouseEventData(UI.Modifiers.none, focusTargetId.fold(Absent: Maybe[String])(Present(_)))
                        ))
                    else if t == "blur" && evTypes.contains("blur") then
                        val blurTargetId = Option(e.target.asInstanceOf[dom.Element].id).filter(_.nonEmpty)
                        Present(UIEvent.Blur(path, MouseEventData(UI.Modifiers.none, blurTargetId.fold(Absent: Maybe[String])(Present(_)))))
                    else
                        Absent

                event.foreach { ev =>
                    fireFromJs(dispatch(path, ev).unit)
                }
            }
        end handler

        Seq("click", "input", "change", "submit", "keydown", "keyup", "focus", "blur").foreach { t =>
            document.body.addEventListener(t, handler, true)
        }
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
