package kyo

import kyo.UI.AST.*
import org.scalajs.dom
import org.scalajs.dom.document
import scala.annotation.tailrec

class DomBackend extends UIBackend:

    def render(ui: UI)(using Frame): UISession < (Async & Scope) =
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

    private def build(ui: UI, rendered: SignalRef[UI])(using Frame): dom.Node < (Async & Scope) =
        ui match
            case Text(value) =>
                document.createTextNode(value)

            case rt @ ReactiveText(signal) =>
                val node = document.createTextNode("")
                subscribe(signal) { v =>
                    node.textContent = v
                    rendered.set(rt).unit
                }.map(_ => node)

            case rn @ ReactiveNode(signal) =>
                val container = document.createElement("span")
                subscribeUI(container, signal, rendered).map(_ => container)

            case fi: ForeachIndexed[?] =>
                val container = document.createElement("span")
                subscribeForeach(container, fi, rendered).map(_ => container)

            case fk: ForeachKeyed[?] =>
                val container = document.createElement("span")
                subscribeKeyed(container, fk, rendered).map(_ => container)

            case Fragment(children) =>
                buildChildren(children, rendered).map { nodes =>
                    val frag = document.createDocumentFragment()
                    nodes.foreach(frag.appendChild)
                    frag
                }

            case elem: Element =>
                buildElement(elem, rendered)
    end build

    private def buildElement(elem: Element, rendered: SignalRef[UI])(using Frame): dom.Element < (Async & Scope) =
        val tag = tagName(elem)
        val el  = document.createElement(tag)
        for
            _ <- applyCommon(el, elem, elem.common, rendered)
            _ <- applySpecific(el, elem, rendered)
            _ <- buildAndAppendChildren(el, elem.children, rendered)
        yield el
        end for
    end buildElement

    private def applyCommon(el: dom.Element, ui: UI, c: CommonAttrs, rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        for
            _ <- applyClasses(el, ui, c.classes, rendered)
            _ <- c.dynamicClassName.fold(noop)(sig =>
                subscribe(sig) { v =>
                    el.setAttribute("class", v)
                    rendered.set(ui).unit
                }
            )
            _ <- c.identifier.fold(noop)(v =>
                el.setAttribute("id", v); noop
            )
            _ <- c.style.fold(noop)(v => applyStringAttr(el, ui, "style", v, rendered))
            _ <- c.hidden.fold(noop)(v => applyBoolProp(el, ui, "hidden", v, rendered))
            _ <- c.onClick.fold(noop) { action =>
                el.addEventListener("click", (_: dom.Event) => runHandler(action)); noop
            }
            _ <- c.onKeyDown.fold(noop) { f =>
                el.addEventListener(
                    "keydown",
                    (e: dom.Event) =>
                        val ke = e.asInstanceOf[dom.KeyboardEvent]
                        runHandler(f(KeyEvent(ke.key, ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey)))
                ); noop
            }
            _ <- c.onKeyUp.fold(noop) { f =>
                el.addEventListener(
                    "keyup",
                    (e: dom.Event) =>
                        val ke = e.asInstanceOf[dom.KeyboardEvent]
                        runHandler(f(KeyEvent(ke.key, ke.ctrlKey, ke.altKey, ke.shiftKey, ke.metaKey)))
                ); noop
            }
            _ <- c.onFocus.fold(noop) { action =>
                el.addEventListener("focus", (_: dom.Event) => runHandler(action)); noop
            }
            _ <- c.onBlur.fold(noop) { action =>
                el.addEventListener("blur", (_: dom.Event) => runHandler(action)); noop
            }
            _ <- applyGenericAttrs(el, ui, c.attrs, rendered)
            _ <- applyGenericHandlers(el, c.handlers)
        yield ()
    end applyCommon

    private val noop: Unit < (Async & Scope) = ()

    private def applyClasses(el: dom.Element, ui: UI, classes: Chunk[(String, Maybe[Signal[Boolean]])], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        Kyo.foreach(classes) { (name, maybeSig) =>
            maybeSig match
                case Absent =>
                    val _ = el.classList.add(name)
                    noop
                case Present(sig) =>
                    subscribe(sig) { v =>
                        val _ = el.classList.toggle(name, v)
                        rendered.set(ui).unit
                    }
        }.unit

    @tailrec private def clearChildren(el: dom.Element): Unit =
        Maybe(el.firstChild) match
            case Absent => ()
            case Present(child) =>
                val _ = el.removeChild(child)
                clearChildren(el)

    private def applyGenericAttrs(el: dom.Element, ui: UI, attrs: Map[String, String | Signal[String]], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        Kyo.foreach(attrs.toList) { (name, value) =>
            applyStringAttr(el, ui, name, value, rendered)
        }.unit

    private def applyGenericHandlers(el: dom.Element, handlers: Map[String, Unit < Async])(using Frame): Unit < (Async & Scope) =
        handlers.foreach { (name, action) =>
            el.addEventListener(name, (_: dom.Event) => runHandler(action))
        }

    private def applyStringAttr(el: dom.Element, ui: UI, name: String, v: String | Signal[String], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case s: String =>
                el.setAttribute(name, s)
            case sig: Signal[?] =>
                subscribe(sig.asInstanceOf[Signal[String]]) { value =>
                    el.setAttribute(name, value)
                    rendered.set(ui).unit
                }

    private def applyBoolProp(el: dom.Element, ui: UI, name: String, v: Boolean | Signal[Boolean], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case b: Boolean =>
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(name)(b)
            case sig: Signal[?] =>
                subscribe(sig.asInstanceOf[Signal[Boolean]]) { value =>
                    el.asInstanceOf[scalajs.js.Dynamic].updateDynamic(name)(value)
                    rendered.set(ui).unit
                }

    private def applySpecific(el: dom.Element, elem: Element, rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        elem match
            case i: Input =>
                for
                    _ <- i.typ.fold(noop)(v =>
                        el.setAttribute("type", v); noop
                    )
                    _ <- i.placeholder.fold(noop)(v =>
                        el.setAttribute("placeholder", v); noop
                    )
                    _ <- i.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- i.checked.fold(noop)(v => applyBoolProp(el, elem, "checked", v, rendered))
                    _ <- i.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- i.onInput.fold(noop) { f =>
                        el.addEventListener(
                            "input",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[dom.html.Input].value))
                        ); noop
                    }
                yield ()

            case t: Textarea =>
                for
                    _ <- t.placeholder.fold(noop)(v =>
                        el.setAttribute("placeholder", v); noop
                    )
                    _ <- t.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- t.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- t.onInput.fold(noop) { f =>
                        el.addEventListener(
                            "input",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[dom.html.TextArea].value))
                        ); noop
                    }
                yield ()

            case b: Button =>
                b.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))

            case anchor: Anchor =>
                for
                    _ <- anchor.href.fold(noop)(v => applyStringAttr(el, elem, "href", v, rendered))
                    _ <- anchor.target.fold(noop)(v =>
                        el.setAttribute("target", v); noop
                    )
                yield ()

            case f: Form =>
                f.onSubmit.fold(noop) { action =>
                    el.addEventListener(
                        "submit",
                        (e: dom.Event) =>
                            e.preventDefault()
                            runHandler(action)
                    ); noop
                }

            case s: Select =>
                for
                    _ <- s.disabled.fold(noop)(v => applyBoolProp(el, elem, "disabled", v, rendered))
                    _ <- s.value.fold(noop)(v => applyValueBinding(el, elem, v, rendered))
                    _ <- s.onChange.fold(noop) { f =>
                        el.addEventListener(
                            "change",
                            (e: dom.Event) =>
                                runHandler(f(e.target.asInstanceOf[dom.html.Select].value))
                        ); noop
                    }
                yield ()

            case o: Option =>
                for
                    _ <- o.value.fold(noop)(v =>
                        el.setAttribute("value", v); noop
                    )
                    _ <- o.selected.fold(noop)(v => applyBoolProp(el, elem, "selected", v, rendered))
                yield ()

            case l: Label =>
                l.forId.fold(noop)(v =>
                    el.setAttribute("for", v); noop
                )

            case td: Td =>
                for
                    _ <- td.colspan.fold(noop)(v =>
                        el.setAttribute("colspan", v.toString); noop
                    )
                    _ <- td.rowspan.fold(noop)(v =>
                        el.setAttribute("rowspan", v.toString); noop
                    )
                yield ()

            case th: Th =>
                for
                    _ <- th.colspan.fold(noop)(v =>
                        el.setAttribute("colspan", v.toString); noop
                    )
                    _ <- th.rowspan.fold(noop)(v =>
                        el.setAttribute("rowspan", v.toString); noop
                    )
                yield ()

            case i: Img =>
                el.setAttribute("src", i.src)
                el.setAttribute("alt", i.alt)

            case _ => ()
    end applySpecific

    private def applyValueBinding(el: dom.Element, ui: UI, v: String | SignalRef[String], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        v match
            case s: String =>
                el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(s)
            case ref: SignalRef[?] =>
                val typedRef = ref.asInstanceOf[SignalRef[String]]
                for _ <- subscribe(typedRef) { value =>
                        el.asInstanceOf[scalajs.js.Dynamic].updateDynamic("value")(value)
                        rendered.set(ui).unit
                    }
                yield el.addEventListener(
                    "input",
                    (e: dom.Event) =>
                        runHandler(typedRef.set(e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]))
                )

    private def interruptPrev(ref: AtomicRef[Maybe[Fiber[Unit, Scope]]])(using Frame): Unit < (Async & Scope) =
        for
            prev <- ref.get
            _ <- prev match
                case Present(f) => Kyo.lift(f.interrupt.unit)
                case Absent     => ((): Unit < (Async & Scope))
        yield ()

    private def subscribeUI(container: dom.Element, signal: Signal[UI], rendered: SignalRef[UI])(using Frame): Unit < (Async & Scope) =
        for
            ref <- AtomicRef.init[Maybe[Fiber[Unit, Scope]]](Absent)
            _ <- subscribe(signal) { ui =>
                for
                    _ <- interruptPrev(ref)
                    fiber <- Fiber.initUnscoped {
                        for node <- build(ui, rendered)
                        yield
                            clearChildren(container)
                            val _ = container.appendChild(node)
                    }
                    _ <- ref.set(Present(fiber))
                    _ <- rendered.set(ui)
                yield ()
            }
        yield ()
    end subscribeUI

    private def subscribeForeach(container: dom.Element, fi: ForeachIndexed[?], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        for
            ref <- AtomicRef.init[Maybe[Fiber[Unit, Scope]]](Absent)
            _ <- subscribeList(
                fi.signal.asInstanceOf[Signal[Chunk[Any]]],
                ref,
                container,
                fi.render.asInstanceOf[(Int, Any) => UI],
                fi,
                rendered
            )
        yield ()
    end subscribeForeach

    private def subscribeList(
        signal: Signal[Chunk[Any]],
        ref: AtomicRef[Maybe[Fiber[Unit, Scope]]],
        container: dom.Element,
        render: (Int, Any) => UI,
        ui: UI,
        rendered: SignalRef[UI]
    )(using Frame): Unit < (Async & Scope) =
        subscribe(signal) { items =>
            for
                _ <- interruptPrev(ref)
                fiber <- Fiber.initUnscoped {
                    clearChildren(container)
                    Kyo.foreach(items.zipWithIndex) { (item, idx) =>
                        for node <- build(render(idx, item), rendered)
                        yield
                            val _ = container.appendChild(node)
                    }.unit
                }
                _ <- ref.set(Present(fiber))
                _ <- rendered.set(ui)
            yield ()
        }

    private def subscribeKeyed(container: dom.Element, fk: ForeachKeyed[?], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        val signal = fk.signal.asInstanceOf[Signal[Chunk[Any]]]
        val key    = fk.key.asInstanceOf[Any => String]
        val render = fk.render.asInstanceOf[(Int, Any) => UI]
        for
            nodeMap <- AtomicRef.init(Map.empty[String, dom.Node])
            _ <- subscribe(signal) { items =>
                for
                    oldMap <- nodeMap.get
                    result <- Kyo.foreach(items.zipWithIndex) { (item, idx) =>
                        val k = key(item)
                        oldMap.get(k) match
                            case scala.Some(existing) =>
                                val _ = container.appendChild(existing)
                                (k, existing): (String, dom.Node) < (Async & Scope)
                            case scala.None =>
                                for node <- build(render(idx, item), rendered)
                                yield
                                    val _ = container.appendChild(node)
                                    (k, node)
                        end match
                    }
                    newMap = result.toSeq.toMap
                    _ = oldMap.foreach { (k, node) =>
                        if !newMap.contains(k) then
                            val _ = container.removeChild(node)
                    }
                    _ <- nodeMap.set(newMap)
                    _ <- rendered.set(fk)
                yield ()
            }
        yield ()
        end for
    end subscribeKeyed

    private def subscribe[A](signal: Signal[A])(f: A => Unit < (Async & Scope))(using Frame, Tag[Emit[Chunk[A]]]): Unit < (Async & Scope) =
        for
            fiber <- Fiber.initUnscoped(signal.streamChanges.foreach(f))
            _     <- Scope.ensure(fiber.interrupt.unit)
        yield ()

    private def runHandler(action: Unit < Async)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(action))
    end runHandler

    private def buildChildren(children: Chunk[UI], rendered: SignalRef[UI])(using Frame): Chunk[dom.Node] < (Async & Scope) =
        Kyo.foreach(children)(build(_, rendered))

    private def buildAndAppendChildren(el: dom.Element, children: Chunk[UI], rendered: SignalRef[UI])(using
        Frame
    ): Unit < (Async & Scope) =
        buildChildren(children, rendered).map { nodes =>
            nodes.foreach(el.appendChild)
        }

    private def tagName(elem: Element): String =
        elem match
            case _: Div      => "div"
            case _: P        => "p"
            case _: Span     => "span"
            case _: Ul       => "ul"
            case _: Ol       => "ol"
            case _: Li       => "li"
            case _: Nav      => "nav"
            case _: Header   => "header"
            case _: Footer   => "footer"
            case _: Section  => "section"
            case _: Main     => "main"
            case _: Label    => "label"
            case _: Pre      => "pre"
            case _: Code     => "code"
            case _: Table    => "table"
            case _: Tr       => "tr"
            case _: Td       => "td"
            case _: Th       => "th"
            case _: H1       => "h1"
            case _: H2       => "h2"
            case _: H3       => "h3"
            case _: H4       => "h4"
            case _: H5       => "h5"
            case _: H6       => "h6"
            case _: Hr       => "hr"
            case _: Br       => "br"
            case _: Button   => "button"
            case _: Anchor   => "a"
            case _: Form     => "form"
            case _: Select   => "select"
            case _: Option   => "option"
            case _: Input    => "input"
            case _: Textarea => "textarea"
            case _: Img      => "img"
    end tagName

end DomBackend
