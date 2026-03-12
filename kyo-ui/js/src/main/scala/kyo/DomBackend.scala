package kyo

import kyo.internal.DomStyleSheet
import org.scalajs.dom
import org.scalajs.dom.document
import scala.annotation.tailrec

class DomBackend extends UIBackend:

    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
        DomStyleSheet.injectBase()
        for
            rendered <- Signal.initRef[UI](UI.empty)
            fiber <- Fiber.init {
                for
                    node <- build(ui, rendered)
                    _ = document.body.appendChild(node)
                    _ <- rendered.set(ui)
                    _ <- Async.never
                yield ()
            }
        yield UISession(fiber, rendered)
        end for
    end render

    private def build(ui: UI, rendered: SignalRef[UI])(using Frame): dom.Node < (Async & Scope) =
        ui match
            case UI.Text(value) =>
                document.createTextNode(value)

            case UI.Reactive(signal) =>
                val container = document.createElement("span")
                container.setAttribute("style", "display:contents")
                subscribeUI(container, signal, rendered).map(_ => container)

            case fe: UI.Foreach[?] =>
                val container = document.createElement("span")
                container.setAttribute("style", "display:contents")
                fe.key match
                    case Maybe.Absent =>
                        // unsafe: asInstanceOf for erased type parameter
                        subscribeForeach(
                            container,
                            fe.signal.asInstanceOf[Signal[Chunk[Any]]],
                            fe.render.asInstanceOf[(Int, Any) => UI],
                            fe,
                            rendered
                        ).map(_ => container)
                    case Maybe.Present(keyFn) =>
                        // unsafe: asInstanceOf for erased type parameter
                        subscribeKeyed(
                            container,
                            fe.signal.asInstanceOf[Signal[Chunk[Any]]],
                            keyFn.asInstanceOf[Any => String],
                            fe.render.asInstanceOf[(Int, Any) => UI],
                            fe,
                            rendered
                        ).map(_ => container)
                end match

            case UI.Fragment(children) =>
                val frag = document.createDocumentFragment()
                buildAndAppendChildren(frag, children, rendered).map(_ => frag)

            case elem: UI.Element =>
                buildElement(elem, rendered)
    end build

    private def buildElement(elem: UI.Element, rendered: SignalRef[UI])(using Frame): dom.Element < (Async & Scope) =
        val tag = elemToHtml(elem)
        val el  = document.createElement(tag)
        for
            _ <- applyAttrs(el, elem, elem.attrs, rendered)
            _ <- applySpecific(el, elem, rendered)
            _ <- buildAndAppendChildren(el, elem.children, rendered)
        yield el
        end for
    end buildElement

    /** Map element type to HTML tag name. */
    private def elemToHtml(elem: UI.Element): String = elem match
        case _: UI.Div      => "div"
        case _: UI.Span     => "span"
        case _: UI.P        => "p"
        case _: UI.Section  => "section"
        case _: UI.Main     => "main"
        case _: UI.Header   => "header"
        case _: UI.Footer   => "footer"
        case _: UI.Pre      => "pre"
        case _: UI.Code     => "code"
        case _: UI.Ul       => "ul"
        case _: UI.Ol       => "ol"
        case _: UI.Table    => "table"
        case _: UI.H1       => "h1"
        case _: UI.H2       => "h2"
        case _: UI.H3       => "h3"
        case _: UI.H4       => "h4"
        case _: UI.H5       => "h5"
        case _: UI.H6       => "h6"
        case _: UI.Nav      => "nav"
        case _: UI.Li       => "li"
        case _: UI.Tr       => "tr"
        case _: UI.Form     => "form"
        case _: UI.Textarea => "textarea"
        case _: UI.Select   => "select"
        case _: UI.Hr       => "hr"
        case _: UI.Br       => "br"
        case _: UI.Td       => "td"
        case _: UI.Th       => "th"
        case _: UI.Label    => "label"
        case _: UI.Opt      => "option"
        case _: UI.Button      => "button"
        case _: UI.Checkbox    => "input"
        case _: UI.Radio       => "input"
        case _: UI.Input       => "input"
        case _: UI.Password    => "input"
        case _: UI.Email       => "input"
        case _: UI.Tel         => "input"
        case _: UI.UrlInput    => "input"
        case _: UI.Search      => "input"
        case _: UI.NumberInput => "input"
        case _: UI.DateInput   => "input"
        case _: UI.TimeInput   => "input"
        case _: UI.ColorInput  => "input"
        case _: UI.RangeInput  => "input"
        case _: UI.FileInput   => "input"
        case _: UI.HiddenInput => "input"
        case _: UI.Anchor      => "a"
        case _: UI.Img         => "img"

    /** Apply universal Attrs to the DOM element. */
    private def applyAttrs(el: dom.Element, ui: UI, a: UI.Attrs, rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        for
            _ <- a.identifier.fold(noop)(v =>
                el.setAttribute("id", v); noop
            )
            _ <- applyStyle(el, ui, a, rendered)
            _ <- a.hidden.fold(noop)(v => applyBoolProp(el, ui, "hidden", v, rendered))
            _ <- a.tabIndex.fold(noop)(v =>
                el.setAttribute("tabindex", v.toString); noop
            )
            // Event handlers
            _ <- a.onClick.fold(noop) { action =>
                el.addEventListener("click", (_: dom.Event) => runHandler(action)); noop
            }
            _ <- a.onKeyDown.fold(noop) { f =>
                el.addEventListener(
                    "keydown",
                    (e: dom.Event) =>
                        // unsafe: asInstanceOf for DOM event cast
                        val ke = e.asInstanceOf[dom.KeyboardEvent]
                        runHandler(f(UI.KeyEvent(UI.Keyboard.fromString(ke.key), ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey)))
                ); noop
            }
            _ <- a.onKeyUp.fold(noop) { f =>
                el.addEventListener(
                    "keyup",
                    (e: dom.Event) =>
                        // unsafe: asInstanceOf for DOM event cast
                        val ke = e.asInstanceOf[dom.KeyboardEvent]
                        runHandler(f(UI.KeyEvent(UI.Keyboard.fromString(ke.key), ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey)))
                ); noop
            }
            _ <- a.onFocus.fold(noop) { action =>
                el.addEventListener("focus", (_: dom.Event) => runHandler(action)); noop
            }
            _ <- a.onBlur.fold(noop) { action =>
                el.addEventListener("blur", (_: dom.Event) => runHandler(action)); noop
            }
        yield ()
    end applyAttrs

    /** Apply element-type-specific attributes. */
    private def applySpecific(el: dom.Element, elem: UI.Element, rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        elem match
            case bi: UI.BooleanInput =>
                val typeName = if bi.isInstanceOf[UI.Radio] then "radio" else "checkbox"
                el.setAttribute("type", typeName)
                for
                    _ <- bi.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- bi.checked.fold(noop)(v => applyBoolProp(el, elem, "checked", v, rendered))
                    _ <- bi.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                // unsafe: asInstanceOf for dynamic checked access
                                val isChecked = e.target.asInstanceOf[scalajs.js.Dynamic].checked.asInstanceOf[Boolean]
                                runHandler(f(isChecked))
                        ); noop
                    }
                yield ()

            case ti: UI.TextInput =>
                ti match
                    case _: UI.Textarea => ()
                    case _              => el.setAttribute("type", inputTypeName(ti))
                for
                    _ <- ti.placeholder.fold(noop)(v =>
                        el.setAttribute("placeholder", v); noop
                    )
                    _ <- ti.readOnly.fold(noop) { v =>
                        if v then el.setAttribute("readonly", "")
                        noop
                    }
                    _ <- ti.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- ti.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- ti.onInput.fold(noop) { f =>
                        el.addEventListener(
                            "input",
                            (e: dom.Event) =>
                                // unsafe: asInstanceOf for dynamic value access
                                runHandler(f(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                        ); noop
                    }
                    _ <- ti.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                // unsafe: asInstanceOf for dynamic value access
                                runHandler(f(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                        ); noop
                    }
                    _ <- ti match
                        case ni: UI.NumberInput =>
                            for
                                _ <- ni.min.fold(noop)(v => el.setAttribute("min", v.toString); noop)
                                _ <- ni.max.fold(noop)(v => el.setAttribute("max", v.toString); noop)
                                _ <- ni.step.fold(noop)(v => el.setAttribute("step", v.toString); noop)
                                _ <- ni.onChangeNumeric.fold(noop) { f =>
                                    el.addEventListener(
                                        "change",
                                        (e: dom.Event) =>
                                            val num = e.target.asInstanceOf[scalajs.js.Dynamic].valueAsNumber.asInstanceOf[Double]
                                            if !java.lang.Double.isNaN(num) then runHandler(f(num))
                                    ); noop
                                }
                            yield ()
                        case _ => noop
                yield ()

            case sel: UI.Select =>
                for
                    _ <- sel.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- sel.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- sel.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                        ); noop
                    }
                yield ()

            case pi: UI.PickerInput =>
                el.setAttribute("type", pickerTypeName(pi))
                for
                    _ <- pi.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- pi.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- pi.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                        ); noop
                    }
                yield ()

            case ri: UI.RangeInput =>
                el.setAttribute("type", "range")
                for
                    _ <- ri.min.fold(noop)(v => el.setAttribute("min", v.toString); noop)
                    _ <- ri.max.fold(noop)(v => el.setAttribute("max", v.toString); noop)
                    _ <- ri.step.fold(noop)(v => el.setAttribute("step", v.toString); noop)
                    _ <- ri.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- ri.value.fold(noop)(v => applyNumericValueBinding(el, elem, v, rendered))
                    _ <- ri.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                val num = e.target.asInstanceOf[scalajs.js.Dynamic].valueAsNumber.asInstanceOf[Double]
                                if !java.lang.Double.isNaN(num) then runHandler(f(num))
                        ); noop
                    }
                yield ()

            case fi: UI.FileInput =>
                el.setAttribute("type", "file")
                for
                    _ <- fi.accept.fold(noop)(v => el.setAttribute("accept", v); noop)
                    _ <- fi.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- fi.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                        ); noop
                    }
                yield ()

            case hi: UI.HiddenInput =>
                el.setAttribute("type", "hidden")
                hi.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))

            case btn: UI.Button =>
                btn.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))

            case anchor: UI.Anchor =>
                for
                    _ <- anchor.href.fold(noop)(v => applyStringOrSignalAttr(el, elem, "href", v, rendered))
                    _ <- anchor.target.fold(noop) { v =>
                        val s = v match
                            case UI.Target.Self   => "_self"
                            case UI.Target.Blank  => "_blank"
                            case UI.Target.Parent => "_parent"
                            case UI.Target.Top    => "_top"
                        el.setAttribute("target", s); noop
                    }
                yield ()

            case img: UI.Img =>
                for
                    _ <- img.src.fold(noop)(v => applyStringOrSignalAttr(el, elem, "src", v, rendered))
                    _ <- img.alt.fold(noop)(v =>
                        el.setAttribute("alt", v); noop
                    )
                yield ()

            case form: UI.Form =>
                form.onSubmit.fold(noop) { action =>
                    el.addEventListener(
                        "submit",
                        (e: dom.Event) =>
                            e.preventDefault()
                            runHandler(action)
                    ); noop
                }

            case opt: UI.Opt =>
                for
                    _ <- opt.value.fold(noop)(v => el.setAttribute("value", v); noop)
                    _ <- opt.selected.fold(noop)(v => applyBoolProp(el, elem, "selected", v, rendered))
                yield ()

            case lbl: UI.Label =>
                lbl.forId.fold(noop)(v =>
                    el.setAttribute("for", v); noop
                )

            case td: UI.Td =>
                for
                    _ <- td.colspan.fold(noop)(v =>
                        el.setAttribute("colspan", v.toString); noop
                    )
                    _ <- td.rowspan.fold(noop)(v =>
                        el.setAttribute("rowspan", v.toString); noop
                    )
                yield ()

            case th: UI.Th =>
                for
                    _ <- th.colspan.fold(noop)(v =>
                        el.setAttribute("colspan", v.toString); noop
                    )
                    _ <- th.rowspan.fold(noop)(v =>
                        el.setAttribute("rowspan", v.toString); noop
                    )
                yield ()

            case _ => noop
    end applySpecific

    /** Map TextInput subtypes to HTML input type attribute. */
    private def inputTypeName(ti: UI.TextInput): String = ti match
        case _: UI.Password    => "password"
        case _: UI.Email       => "email"
        case _: UI.Tel         => "tel"
        case _: UI.UrlInput    => "url"
        case _: UI.Search      => "search"
        case _: UI.NumberInput => "number"
        case _: UI.Input       => "text"

    /** Map PickerInput subtypes to HTML input type attribute. */
    private def pickerTypeName(pi: UI.PickerInput): String = pi match
        case _: UI.DateInput  => "date"
        case _: UI.TimeInput  => "time"
        case _: UI.ColorInput => "color"

    private def applyStyle(el: dom.Element, ui: UI, a: UI.Attrs, rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        a.uiStyle match
            case style: Style =>
                val _ = DomStyleSheet(el, style, Maybe.Absent)
                noop
            case sig: Signal[?] =>
                // Reactive style: subscribe and re-apply on change
                // unsafe: asInstanceOf for union type resolution
                subscribe(sig.asInstanceOf[Signal[Style]], el) { style =>
                    // Remove old style class/inline, apply new
                    el.removeAttribute("style")
                    el.removeAttribute("class")
                    val _ = DomStyleSheet(el, style, Maybe.Absent)
                    rendered.set(ui).unit
                }
    end applyStyle

    private val noop: Unit < (Async & Scope) = ()

    @tailrec private def clearChildren(el: dom.Element): Unit =
        Maybe(el.firstChild) match
            case Maybe.Absent => ()
            case Maybe.Present(child) =>
                val _ = el.removeChild(child)
                clearChildren(el)

    private def applyStringOrSignalAttr(el: dom.Element, ui: UI, name: String, v: String | Signal[String], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case s: String =>
                el.setAttribute(name, s)
            case sig: Signal[?] =>
                // unsafe: asInstanceOf for union type resolution
                subscribe(sig.asInstanceOf[Signal[String]], el) { value =>
                    el.setAttribute(name, value)
                    rendered.set(ui).unit
                }

    private def applyBoolProp(el: dom.Element, ui: UI, name: String, v: Boolean | Signal[Boolean], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case b: Boolean =>
                // unsafe: asInstanceOf for dynamic property access
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(name)(b)
            case sig: Signal[?] =>
                // unsafe: asInstanceOf for union type resolution
                subscribe(sig.asInstanceOf[Signal[Boolean]], el) { value =>
                    el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(name)(value)
                    rendered.set(ui).unit
                }

    private def applyValueBinding(el: dom.Element, ui: UI, v: String | SignalRef[String], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case s: String =>
                // unsafe: asInstanceOf for dynamic property access
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(s)
            case ref: SignalRef[?] =>
                // unsafe: asInstanceOf for union type resolution
                val typedRef = ref.asInstanceOf[SignalRef[String]]
                for _ <- subscribe(typedRef, el) { value =>
                        el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(value)
                        rendered.set(ui).unit
                    }
                yield el.addEventListener(
                    "input",
                    (e: dom.Event) =>
                        // unsafe: asInstanceOf for dynamic value access
                        runHandler(typedRef.set(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                )

    private def applyNumericValueBinding(el: dom.Element, ui: UI, v: Double | SignalRef[Double], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case d: Double =>
                // unsafe: asInstanceOf for dynamic property access
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(d.toString)
            case ref: SignalRef[?] =>
                // unsafe: asInstanceOf for union type resolution
                val typedRef = ref.asInstanceOf[SignalRef[Double]]
                for _ <- subscribe(typedRef, el) { value =>
                        el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(value.toString)
                        rendered.set(ui).unit
                    }
                yield el.addEventListener(
                    "input",
                    (e: dom.Event) =>
                        val num = e.target.asInstanceOf[scalajs.js.Dynamic].valueAsNumber.asInstanceOf[Double]
                        if !java.lang.Double.isNaN(num) then
                            // unsafe: asInstanceOf for dynamic value access
                            runHandler(typedRef.set(num))
                )

    private def subscribeUI(container: dom.Element, signal: Signal[UI], rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        for
            initial <- signal.currentWith(a => a)
            node    <- build(initial, rendered)
            _       <- rendered.set(initial)
            _ =
                clearChildren(container); container.appendChild(node)
            _ <- subscribe(signal, container) { ui =>
                for
                    node <- build(ui, rendered)
                    _    <- rendered.set(ui)
                yield
                    clearChildren(container)
                    val _ = container.appendChild(node)
            }
        yield ()
    end subscribeUI

    /** Build items by index without zipWithIndex tuple allocation. */
    private def buildIndexed(
        container: dom.Element,
        items: Chunk[Any],
        render: (Int, Any) => UI,
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        def loop(i: Int): Unit < (Async & Scope) =
            if i >= items.size then ()
            else
                for
                    node <- build(render(i, items(i)), rendered)
                    _ =
                        val _ = container.appendChild(node)
                    _ <- loop(i + 1)
                yield ()
        loop(0)
    end buildIndexed

    private def subscribeForeach(
        container: dom.Element,
        signal: Signal[Chunk[Any]],
        render: (Int, Any) => UI,
        ui: UI,
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        for
            initial <- signal.currentWith(a => a)
            _       <- buildIndexed(container, initial, render, rendered)
            _       <- rendered.set(ui)
            _ <- subscribe(signal, container) { items =>
                clearChildren(container)
                for
                    _ <- buildIndexed(container, items, render, rendered)
                    _ <- rendered.set(ui)
                yield ()
                end for
            }
        yield ()

    private def subscribeKeyed(
        container: dom.Element,
        signal: Signal[Chunk[Any]],
        key: Any => String,
        render: (Int, Any) => UI,
        ui: UI,
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        for
            nodeMap    <- AtomicRef.init(Map.empty[String, dom.Node])
            initial    <- signal.currentWith(a => a)
            initialMap <- buildKeyed(container, initial, key, render, Map.empty, rendered)
            _          <- nodeMap.set(initialMap)
            _          <- rendered.set(ui)
            _ <- subscribe(signal, container) { items =>
                for
                    oldMap <- nodeMap.get
                    newMap <- buildKeyed(container, items, key, render, oldMap, rendered)
                    _ = removeStaleNodes(oldMap, newMap, container)
                    _ <- nodeMap.set(newMap)
                    _ <- rendered.set(ui)
                yield ()
            }
        yield ()
        end for
    end subscribeKeyed

    /** Build keyed children by index, reusing existing nodes from oldMap. Returns new Map. */
    private def buildKeyed(
        container: dom.Element,
        items: Chunk[Any],
        key: Any => String,
        render: (Int, Any) => UI,
        oldMap: Map[String, dom.Node],
        rendered: SignalRef[UI]
    )(using Frame): Map[String, dom.Node] < (Async & Scope) =
        def loop(i: Int, acc: Map[String, dom.Node]): Map[String, dom.Node] < (Async & Scope) =
            if i >= items.size then acc
            else
                val k = key(items(i))
                oldMap.get(k) match
                    case scala.Some(existing) =>
                        val _ = container.appendChild(existing)
                        loop(i + 1, acc + (k -> existing))
                    case scala.None =>
                        for
                            node <- build(render(i, items(i)), rendered)
                            _ =
                                val _ = container.appendChild(node)
                            result <- loop(i + 1, acc + (k -> node))
                        yield result
                end match
        loop(0, Map.empty)
    end buildKeyed

    /** Remove nodes from oldMap that are not present in newMap. */
    private def removeStaleNodes(oldMap: Map[String, dom.Node], newMap: Map[String, dom.Node], container: dom.Element): Unit =
        oldMap.foreach { (k, node) =>
            if !newMap.contains(k) && (node.parentNode eq container) then
                val _ = container.removeChild(node)
        }

    private def subscribe[A](signal: Signal[A], owner: dom.Node)(f: A => Unit < (Async & Scope))(using
        Frame,
        Tag[Emit[Chunk[A]]]
    ): Unit < (Async & Scope) =
        var wasConnected = false
        for
            fiber <- Fiber.initUnscoped(signal.streamChanges.takeWhile { _ =>
                val connected = owner.isConnected
                if connected then wasConnected = true
                !wasConnected || connected
            }.foreach(f))
            _ <- Scope.ensure(fiber.interrupt.unit)
        yield ()
        end for
    end subscribe

    private def runHandler(action: Unit < Async)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(action))
    end runHandler

    private def buildAndAppendChildren(parent: dom.Node, children: kyo.Span[UI], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        def loop(i: Int): Unit < (Async & Scope) =
            if i >= children.size then ()
            else
                for
                    node <- build(children(i), rendered)
                    _ =
                        val _ = parent.appendChild(node)
                    _ <- loop(i + 1)
                yield ()
        loop(0)
    end buildAndAppendChildren

end DomBackend
