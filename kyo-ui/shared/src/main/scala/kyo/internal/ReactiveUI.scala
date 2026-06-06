package kyo.internal

import kyo.*
import kyo.Svg
import kyo.UI.*

/** Normalized reactive UI node. */
private[kyo] case class ReactiveUI(
    path: Seq[String],
    signal: Signal[UI],
    isConst: Boolean,
    children: Seq[ReactiveUI],
    handle: (Seq[String], UIEvent) => Boolean < Async,
    svgContext: Boolean = false
)

private[kyo] object ReactiveUI:

    import UI.Ast.*

    def init(
        path: Seq[String],
        signal: Signal[UI],
        isConst: Boolean,
        children: Seq[ReactiveUI],
        svgContext: Boolean = false
    )(
        handle: (Seq[String], UIEvent) => Boolean < Async
    ): ReactiveUI =
        ReactiveUI(path, signal, isConst, children, handle, svgContext)

    /** Locate the ReactiveUI node at `path` in the resolved tree. Used by the exchanges to look up the
      * recorded svgContext flag so an empty placeholder uses the correct (<g> vs <span>) tag.
      */
    private[kyo] def findNode(root: ReactiveUI, path: Seq[String]): Maybe[ReactiveUI] =
        if root.path == path then Present(root)
        else
            root.children.foldLeft(Absent: Maybe[ReactiveUI]) { (acc, child) =>
                if acc.nonEmpty then acc else findNode(child, path)
            }

    def normalize(ui: UI, path: Seq[String], svg: Boolean = false): ReactiveUI < Sync =
        given Frame = ui.frame
        ui match
            case ui: Reactive[?] =>
                for
                    current   <- ui.signal.current
                    (kids, _) <- walkStatic(current, path, svg)
                yield init(path, ui.signal, isConst = false, kids, svgContext = svg) {
                    (targetPath, event) =>
                        for
                            currentUI     <- ui.signal.current
                            (_, freshHdl) <- walkStatic(currentUI, path, svg)
                            result        <- freshHdl(targetPath, event)
                        yield result
                }
                end for

            case ui: Foreach[?, ?] @unchecked =>
                val sig =
                    ui.signal.map { items =>
                        val arr = items.toSeq.zipWithIndex.map { (item, i) =>
                            val key = if ui.key.nonEmpty then ui.key.get(item) else i.toString
                            KeyedChild[UI](key, ui.render(i, item))
                        }
                        Fragment[UI](Chunk.from(arr)): UI
                    }
                for
                    current   <- sig.current
                    (kids, _) <- walkStatic(current, path, svg)
                yield init(path, sig, isConst = false, kids, svgContext = svg) {
                    (targetPath, event) =>
                        for
                            currentUI     <- sig.current
                            (_, freshHdl) <- walkStatic(currentUI, path, svg)
                            result        <- freshHdl(targetPath, event)
                        yield result
                }
                end for

            case ui: Element =>
                // If the element has SignalRef-bound attributes (e.g. .value(ref) or .checked(ref)),
                // it must re-render when those signals change. We expose a "reactive" signal that
                // emits whenever any bound SignalRef updates; rendering reads each ref's current value
                // afresh, so the new HTML reflects the updated signal state.
                val refs = collectSignalRefs(ui)
                if refs.isEmpty then
                    for (kids, hdl) <- walkStatic(ui, path, svg)
                    yield ReactiveUI(path, Signal.initConst(ui), isConst = true, kids, hdl, svgContext = svg)
                else
                    val elementSignal = Signal.initRaw[UI](
                        currentWith = [B, S] => g => g(ui),
                        nextWith = [B, S] => g => Signal.awaitAny(refs).andThen(g(ui))
                    )
                    for (kids, hdl) <- walkStatic(ui, path, svg)
                    yield ReactiveUI(path, elementSignal, isConst = false, kids, hdl, svgContext = svg)
                end if

            case ui =>
                // Catch-all for static leaf nodes: Text, Fragment, KeyedChild.
                // All interactive types extend Element (handled above) and all reactive types are
                // Reactive or Foreach (also handled above). If a new UI subtype is added, the
                // exhaustiveness checker will NOT warn here; any new type that is interactive must
                // be added as an explicit case above before this catch-all.
                ReactiveUI(path, Signal.initConst(ui), isConst = true, Seq.empty, (_, _) => true, svgContext = svg)
        end match
    end normalize

    /** Collects all SignalRef-bound attributes from an Element, so the element can be made reactive over those signals (its HTML re-renders
      * when any of them change).
      */
    private def collectSignalRefs(elem: Element): Seq[Signal[?]] =
        val builder = Seq.newBuilder[Signal[?]]
        def addValue(v: Maybe[Bound[?]]): Unit = v match
            case Present(Bound.Ref(ref)) => builder += ref
            case _                       => ()
        elem match
            case ti: TextInput    => addValue(ti.value)
            case ri: RangeInput   => addValue(ri.value)
            case pi: PickerInput  => addValue(pi.value)
            case bi: BooleanInput => addValue(bi.checked)
            case h: HiddenInput   => addValue(h.value)
            case _                =>
        end match
        builder.result()
    end collectSignalRefs

    private type Handler = (Seq[String], UIEvent) => Boolean < Async

    /** Walk a static UI tree. Collect reactive children, build handle. */
    private def walkStatic(ui: UI, basePath: Seq[String], svg: Boolean = false)(using Frame): (Seq[ReactiveUI], Handler) < Sync =
        ui match
            case elem: Element =>
                // ForeignObject bridges back to HTML, so reset svg context to false. It MUST be matched
                // before SvgElement (ForeignObject IS an SvgElement).
                val childSvg = elem match
                    case _: Svg.ForeignObject => false
                    case _: Svg.SvgElement    => true
                    case _                    => svg
                for childWalks <- Kyo.foreach(elem.children.toSeq.zipWithIndex) { (child, i) =>
                        val childPath = basePath :+ i.toString
                        child match
                            case _: Reactive[?] | _: Foreach[?, ?] =>
                                for rui <- normalize(child, childPath, childSvg)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case childElem: Element if collectSignalRefs(childElem).nonEmpty =>
                                // Element with SignalRef-bound attributes is reactive over those signals;
                                // normalize it so subscribeNode wires updates.
                                for rui <- normalize(childElem, childPath, childSvg)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case _ =>
                                for (innerKids, innerHandle) <- walkStatic(child, childPath, childSvg)
                                yield (innerKids, Seq((i, innerHandle)))
                        end match
                    }
                yield
                    val reactiveChildren = childWalks.flatMap(_._1)
                    val staticHandlers   = childWalks.flatMap(_._2)
                    val handle: Handler = (targetPath, event) =>
                        dispatch(elem, basePath, targetPath, event, reactiveChildren, staticHandlers)
                    (reactiveChildren, handle)
                end for

            case Fragment(children) =>
                for childWalks <- Kyo.foreach(children.toSeq.zipWithIndex) { (child, i) =>
                        val childPath = child match
                            case kc: KeyedChild[?] => basePath :+ kc.key
                            case _                 => basePath :+ i.toString
                        val inner = child match
                            case kc: KeyedChild[?] => kc.child
                            case _                 => child
                        walkStatic(inner, childPath, svg)
                    }
                yield
                    val allKids    = childWalks.flatMap(_._1)
                    val allHandles = childWalks.zipWithIndex.map { case ((_, h), i) => (i, h) }
                    val keyMap = children.toSeq.zipWithIndex.collect {
                        case (kc: KeyedChild[?], i) => kc.key -> i
                    }.toMap
                    val handle: Handler = (targetPath, event) =>
                        if targetPath.size > basePath.size then
                            val segment = targetPath(basePath.size)
                            val idx     = Maybe.fromOption(keyMap.get(segment)).orElse(Maybe.fromOption(segment.toIntOption))
                            idx.flatMap(i => Maybe.fromOption(allHandles.lift(i)).map(_._2))
                                .fold(true: Boolean < Async)(h => h(targetPath, event))
                        else true
                    (allKids, handle)

            case _: Reactive[?] | _: Foreach[?, ?] =>
                // When walkStatic is called with a Reactive or Foreach as the top-level node
                // (not as a child of an Element), normalize it at basePath so subscribeNode
                // sets up a subscription for it. This handles the case where an outer reactive's
                // signal value is itself a Reactive or Foreach (e.g. outer.map { _ => inner.map(UI.span(_)) }).
                for rui <- normalize(ui, basePath, svg)
                yield (Seq(rui), rui.handle)

            case _ =>
                val noHandle: Handler = (_, _) => true
                (Seq.empty[ReactiveUI], noHandle)

    /** Dispatch an event through an element. */
    private def dispatch(
        elem: Element,
        myPath: Seq[String],
        targetPath: Seq[String],
        event: UIEvent,
        reactiveChildren: Seq[ReactiveUI],
        staticHandlers: Seq[(Int, Handler)]
    )(using Frame): Boolean < Async =
        if targetPath == myPath then
            dispatchToElement(elem, event, isTarget = true)
        else if targetPath.startsWith(myPath) && targetPath.size > myPath.size then
            val childSegment = targetPath(myPath.size)
            val childIdx     = childSegment.toIntOption

            // Find the reactive child whose path is a prefix of (or equals) targetPath. Matching by
            // `lastOption.contains(childSegment)` is incorrect when reactive children are nested
            // multiple levels deep; their last segment may collide with a sibling's index at the
            // current level. Using prefix-match correctly routes only when the target lies inside.
            val reactiveChild = reactiveChildren.find(rc => targetPath.startsWith(rc.path))

            for
                // Disabled-target (Form submit suppression), Button-target (only Button clicks submit
                // forms), and Select-target (Enter on Select must not submit) all resolve through any
                // Reactive/Foreach boundary wrapping the target, so signal-typed setters do not hide it.
                targetDisabled <- event match
                    case _: UIEvent.Click => isTargetDisabled(elem, myPath, targetPath)
                    case _                => Kyo.lift(false)
                targetIsButton <- event match
                    case _: UIEvent.Click => isTargetButton(elem, myPath, targetPath)
                    case _                => Kyo.lift(false)
                targetIsSelect <- event match
                    case _: UIEvent.KeyDown => isTargetSelect(elem, myPath, targetPath)
                    case _                  => Kyo.lift(false)
                bubble = dispatchToElement(
                    elem,
                    event,
                    isTarget = false,
                    disabledTarget = targetDisabled,
                    submitOrigin = targetIsButton,
                    selectTarget = targetIsSelect
                )
                result <- Maybe.fromOption(reactiveChild) match
                    case Present(child) =>
                        safeDispatch(child.handle, targetPath, event).map(keep => if keep then bubble else false)
                    case Absent =>
                        Maybe.fromOption(childIdx).flatMap(i => Maybe.fromOption(staticHandlers.find(_._1 == i)).map(_._2))
                            .fold(bubble)(childHandle =>
                                safeDispatch(childHandle, targetPath, event).map(keep => if keep then bubble else false)
                            )
            yield result
            end for
        else
            true

    /** Invoke a Maybe[Any < Async] handler, discarding the result. */
    private def invoke(handler: Maybe[Any < Async])(using Frame): Unit < Async =
        if handler.isEmpty then () else handler.get.unit

    /** Invoke a Maybe[A => Any < Async] handler with a value, discarding the result. */
    private def invokeWith[A](handler: Maybe[A => Any < Async], value: A)(using Frame): Unit < Async =
        if handler.isEmpty then () else handler.get(value).unit

    /** Dispatch an event safely, logging and recovering from handler errors without stopping the bubble chain. */
    private def safeDispatch(handle: (Seq[String], UIEvent) => Boolean < Async, path: Seq[String], event: UIEvent)(using
        Frame
    ): Boolean < Async =
        Abort.recover[Throwable](
            onFail = err =>
                Log.error(s"Handler error during ${event.getClass.getSimpleName} at ${path.mkString(".")}: ${err.getMessage}")
                    .andThen(true), // continue bubbling
            onPanic = thr =>
                Log.error(s"Handler panic during ${event.getClass.getSimpleName} at ${path.mkString(".")}: ${thr.getMessage}")
                    .andThen(true) // continue bubbling
        )(handle(path, event))

    /** Read current checked value from Maybe[Bound[Boolean]]. */
    private def readChecked(checked: Maybe[Bound[Boolean]])(using Frame): Boolean < Sync =
        checked match
            case Present(Bound.Const(b)) => b
            case Present(Bound.Ref(ref)) => ref.get
            case _                       => false

    /** Toggle checked and auto-set SignalRef if bound. Returns the new value. */
    private def toggleChecked(checked: Maybe[Bound[Boolean]])(using Frame): Boolean < Sync =
        checked match
            case Present(Bound.Ref(ref)) =>
                ref.getAndUpdate(!_).map(!_)
            case Present(Bound.Const(b)) => !b
            case _                       => true

    /** Cycle a Select to the next or previous option. Updates SignalRef and fires onChange. */
    private def cycleSelectOption(sel: Select, forward: Boolean)(using Frame): Unit < Async =
        val options = sel.children.toSeq.collect { case opt: Opt => opt }
        val values  = options.flatMap(_.value.toList)
        if values.isEmpty then Kyo.lift(())
        else
            for
                current <- sel.value match
                    case Present(Bound.Ref(ref)) => ref.get
                    case Present(Bound.Const(s)) => Kyo.lift(s)
                    case _                       => Kyo.lift("")
                currentIdx = values.indexOf(current).max(0)
                nextIdx =
                    if forward then (currentIdx + 1)    % values.size
                    else (currentIdx - 1 + values.size) % values.size
                newValue = values(nextIdx)
                _ <- sel.value match
                    case Present(Bound.Ref(ref)) => ref.set(newValue)
                    case _                       => Kyo.lift(())
                _ <- invokeWith(sel.onChange, newValue)
            yield ()
        end if
    end cycleSelectOption

    private def formatDouble(v: Double): String = NumberFormat.double(v)

    /** Check if element is disabled. */
    private def isDisabled(elem: Element): Boolean =
        elem match
            case hd: HasDisabled => hd.disabled.getOrElse(false)
            case _               => false

    /** Check if element is readOnly. */
    private def isReadOnly(elem: Element): Boolean =
        elem match
            case ti: TextInput => ti.readOnly.getOrElse(false)
            case _             => false

    /** Check if element is hidden. */
    private def isHidden(elem: Element): Boolean =
        elem.attrs.hidden.getOrElse(false)

    /** Resolve the (possibly reactive) node at `targetPath` and test `predicate` against the concrete element there.
      *
      * Mirrors the renderer's path scheme: element children are index-addressed, a `Reactive`'s rendered content occupies the same path as
      * the boundary, `Foreach` items live at `path :+ key` (or `:+ index`), and `Fragment` children at `path :+ index`. Resolving through
      * these boundaries is what lets an element wrapped by a signal-typed setter (e.g. `.disabled(Signal)`, which wraps it in a `Reactive`)
      * still be recognized by its concrete type, so button-click form submit and disabled detection keep working through such wrappers.
      */
    private def targetSatisfies(node: UI, nodePath: Seq[String], targetPath: Seq[String], predicate: Element => Boolean)(using
        Frame
    ): Boolean < Sync =
        node match
            case kc: KeyedChild[?] =>
                targetSatisfies(kc.child, nodePath, targetPath, predicate)
            case r: Reactive[?] =>
                // A Reactive's rendered content occupies the same path as the boundary, so re-test at nodePath.
                r.signal.current(using r.frame).map(cur => targetSatisfies(cur, nodePath, targetPath, predicate))
            case Fragment(children) =>
                if targetPath.size <= nodePath.size then false
                else
                    val seg = targetPath(nodePath.size)
                    Maybe.fromOption(seg.toIntOption) match
                        case Present(i) if i >= 0 && i < children.size =>
                            targetSatisfies(children(i), nodePath :+ seg, targetPath, predicate)
                        case _ => false
                    end match
            case fe: Foreach[?, ?] @unchecked =>
                if targetPath.size <= nodePath.size then false
                else
                    val seg = targetPath(nodePath.size)
                    fe.applyTyped {
                        [T] =>
                            (signal, keyFn, renderFn) =>
                                signal.current(using fe.frame).map { items =>
                                    val idx = keyFn match
                                        case Present(f) => items.indexWhere(it => f(it) == seg)
                                        case Absent     => Maybe.fromOption(seg.toIntOption).getOrElse(-1)
                                    if idx >= 0 && idx < items.size then
                                        targetSatisfies(renderFn(idx, items(idx)), nodePath :+ seg, targetPath, predicate)
                                    else false
                            }
                    }
            case e: Element =>
                if targetPath.size <= nodePath.size then predicate(e)
                else
                    val seg = targetPath(nodePath.size)
                    Maybe.fromOption(seg.toIntOption) match
                        case Present(i) if i >= 0 && i < e.children.size =>
                            targetSatisfies(e.children(i), nodePath :+ seg, targetPath, predicate)
                        case _ => false
                    end match
            case _: Text => false

    private def isTargetDisabled(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, isDisabled)

    private def isTargetButton(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Button])

    private def isTargetSelect(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Select])

    /** Dispatch event to element. isTarget=true when element is the click target, false on bubble. disabledTarget=true when the original
      * click target was disabled (prevents Form onSubmit on bubble).
      */
    private def dispatchToElement(
        elem: Element,
        event: UIEvent,
        isTarget: Boolean,
        disabledTarget: Boolean = false,
        submitOrigin: Boolean = false,
        selectTarget: Boolean = false
    )(
        using Frame
    ): Boolean < Async =
        val attrs = elem.attrs
        event match
            case ev: UIEvent.Click =>
                // Disabled or hidden elements ignore their own click handler, but allow bubbling
                if isTarget && (isDisabled(elem) || isHidden(elem)) then true
                else
                    val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                    val self = if isTarget then
                        invoke(attrs.onClickSelf).andThen(invokeWith(attrs.onClickSelfEvt, mouse))
                    else Kyo.lift(())
                    // Checkbox/radio toggle is handled by UIControlSession.click() which dispatches
                    // ChangeChecked after Click. Don't toggle here to avoid double-toggle.
                    val activateToggle = Kyo.lift(())
                    // When a Click on a Button bubbles to a Form, trigger onSubmit (browser behavior)
                    // Only Button clicks submit forms (not radio, checkbox, or input clicks)
                    val formSubmit = if !isTarget && !disabledTarget && submitOrigin then
                        elem match
                            case f: Form =>
                                invoke(f.onSubmit).andThen(invokeWith(f.onSubmitEvt, mouse))
                            case _ => Kyo.lift(())
                    else Kyo.lift(())
                    self
                        .andThen(invokeWith(attrs.onClickEvt, mouse))
                        .andThen(invoke(attrs.onClick))
                        .andThen(activateToggle)
                        .andThen(formSubmit)
                        .andThen(true)
            case ev: UIEvent.Focus =>
                val isFocusable = elem.isInstanceOf[Focusable] || elem.attrs.tabIndex.nonEmpty
                if isTarget && isFocusable then
                    val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                    invoke(attrs.onFocus).andThen(invokeWith(attrs.onFocusEvt, mouse)).andThen(true)
                else if isTarget then Kyo.lift(false) // not focusable; reject
                else true
                end if
            case ev: UIEvent.Blur =>
                if isTarget then
                    val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                    invoke(attrs.onBlur).andThen(invokeWith(attrs.onBlurEvt, mouse)).andThen(true)
                else true
            case e: UIEvent.KeyDown =>
                if isTarget && isDisabled(elem) then true
                else
                    val kbEvent = UI.KeyboardEvent(
                        key = Keyboard.fromString(e.keyboard.key),
                        modifiers = e.keyboard.modifiers,
                        targetId = e.keyboard.targetId
                    )
                    val keyHandler = invokeWith(attrs.onKeyDown, kbEvent)
                    // Enter/Space on button/anchor triggers onClick (browser behavior)
                    val activateClick = if isTarget && (e.keyboard.key == "Enter" || e.keyboard.key == " ") then
                        elem match
                            case _: Button => invoke(attrs.onClick)
                            case _: Anchor => invoke(attrs.onClick)
                            case _         => Kyo.lift(())
                    else Kyo.lift(())
                    // Enter/Space on checkbox/radio triggers onChange (browser behavior)
                    val activateToggle = if isTarget && (e.keyboard.key == "Enter" || e.keyboard.key == " ") then
                        elem match
                            case cb: Checkbox =>
                                toggleChecked(cb.checked).map(newVal => invokeWith(cb.onChange, newVal))
                            case rb: Radio =>
                                val autoSet = rb.checked match
                                    case Present(Bound.Ref(ref)) => ref.set(true)
                                    case _                       => Kyo.lift(())
                                autoSet.andThen(invokeWith(rb.onChange, true))
                            case _ => Kyo.lift(())
                    else Kyo.lift(())
                    // ArrowDown/ArrowUp/Space/Enter on Select cycles through options (browser behavior)
                    val selectCycle =
                        if isTarget && (e.keyboard.key == "ArrowDown" || e.keyboard.key == "ArrowUp" || e.keyboard.key == " " || e.keyboard.key == "Enter")
                        then
                            elem match
                                case sel: Select => cycleSelectOption(sel, forward = e.keyboard.key != "ArrowUp")
                                case _           => Kyo.lift(())
                        else Kyo.lift(())
                    // Enter key bubbling to Form triggers onSubmit (browser behavior)
                    // Enter on Select should NOT submit; it interacts with the dropdown
                    val formSubmit = if !isTarget && e.keyboard.key == "Enter" && !selectTarget then
                        elem match
                            case f: Form => invoke(f.onSubmit)
                            case _       => Kyo.lift(())
                    else Kyo.lift(())
                    keyHandler.andThen(activateClick).andThen(activateToggle).andThen(selectCycle).andThen(formSubmit).andThen(true)
            case e: UIEvent.KeyUp =>
                val kbEvent = UI.KeyboardEvent(
                    key = Keyboard.fromString(e.keyboard.key),
                    modifiers = e.keyboard.modifiers,
                    targetId = e.keyboard.targetId
                )
                invokeWith(attrs.onKeyUp, kbEvent).andThen(true)
            case e: UIEvent.Input =>
                if isTarget && (isDisabled(elem) || isReadOnly(elem)) then true
                else
                    elem match
                        case ti: TextInput =>
                            val autoSet = ti.value match
                                case Present(Bound.Ref(ref)) => ref.set(e.value)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(ti.onInput, e.value)).andThen(true)
                        case _ => true
            case e: UIEvent.Change =>
                if isTarget && (isDisabled(elem) || isReadOnly(elem)) then true
                else
                    elem match
                        case ti: TextInput =>
                            val autoSet = ti.value match
                                case Present(Bound.Ref(ref)) => ref.set(e.value)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(ti.onChange, e.value)).andThen(true)
                        case dd: Dropdown =>
                            val autoSet = dd.value match
                                case Present(Bound.Ref(ref)) => ref.set(e.value)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(dd.onChange, e.value)).andThen(true)
                        case pi: PickerInput =>
                            val autoSet = pi.value match
                                case Present(Bound.Ref(ref)) => ref.set(e.value)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(pi.onChange, e.value)).andThen(true)
                        case fi: FileInput => invokeWith(fi.onChange, e.value).andThen(true)
                        case _             => true
            case e: UIEvent.ChangeChecked =>
                if isTarget && isDisabled(elem) then true
                else
                    elem match
                        case bi: BooleanInput =>
                            val autoSet = bi.checked match
                                case Present(Bound.Ref(ref)) => ref.set(e.checked)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(bi.onChange, e.checked)).andThen(true)
                        case _ => true
            case e: UIEvent.ChangeNumeric =>
                if isTarget && isDisabled(elem) then true
                else
                    elem match
                        case ni: NumberInput =>
                            val autoSet = ni.value match
                                case Present(Bound.Ref(ref)) => ref.set(formatDouble(e.value))
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(ni.onChangeNumeric, e.value)).andThen(true)
                        case ri: RangeInput =>
                            val autoSet = ri.value match
                                case Present(Bound.Ref(ref)) => ref.set(e.value)
                                case _                       => Kyo.lift(())
                            autoSet.andThen(invokeWith(ri.onChange, e.value)).andThen(true)
                        case _ => true
            case ev: UIEvent.Submit =>
                elem match
                    case f: Form =>
                        val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                        invoke(f.onSubmit).andThen(invokeWith(f.onSubmitEvt, mouse)).andThen(true)
                    case _ => true
            case ev: UIEvent.Hover =>
                val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                invoke(attrs.onHover).andThen(invokeWith(attrs.onHoverEvt, mouse)).andThen(true)
            case ev: UIEvent.Unhover =>
                val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                invoke(attrs.onUnhover).andThen(invokeWith(attrs.onUnhoverEvt, mouse)).andThen(true)
            case ev: UIEvent.Scroll =>
                val wheel = UI.WheelEvent(ev.deltaX, ev.deltaY, ev.targetId, ev.modifiers)
                invoke(attrs.onScroll).andThen(invokeWith(attrs.onScrollEvt, wheel)).andThen(true)
            case _ => true
        end match
    end dispatchToElement

    // ---- Subscribe ----

    /** Result of subscribe: dispatch handler + fibers + signal change tracking. */
    case class Subscription(handle: Handler, fibers: Seq[Fiber[Unit, Any]], lastSignalChangeTime: AtomicRef[Instant])

    /** Subscribe all reactive boundaries. Returns dispatch handle + fibers for cleanup. */
    def subscribe(rui: ReactiveUI, exchange: UIExchange)(using Frame): Subscription < Async =
        for
            signalChangeTime <- AtomicRef.init(Instant.Epoch)
            fibers           <- subscribeNode(rui, exchange, signalChangeTime)
        yield Subscription(rui.handle, fibers, signalChangeTime)

    private def subscribeNode(rui: ReactiveUI, exchange: UIExchange, signalChangeTime: AtomicRef[Instant])(using
        Frame
    ): Seq[Fiber[Unit, Any]] < Async =
        if rui.isConst then
            for children <- Kyo.foreach(rui.children)(subscribeNode(_, exchange, signalChangeTime))
            yield children.flatten
        else
            // Reactive node: subscribe self + dynamically (re)subscribe descendants whenever the signal
            // emits. The descendant set can change between emissions (e.g. nested `UI.when(outer)` whose
            // body contains another `UI.when(inner)`. When `outer` flips true, the inner reactive
            // didn't exist at normalize time so its signal would never get subscribed without this
            // re-walk). We track the live descendant fibers in a ref and interrupt them on each change.
            // Don't use streamChanges here; for derived signals (e.g. items.map(items => Fragment(...))),
            // currentWith may produce new instances on every read (closures, mutable Style arrays don't
            // structurally compare), causing streamChanges to emit infinitely. Drive updates directly
            // off `next` (Promise-based, only completes on actual upstream change).
            for
                childFibersRef <- AtomicRef.init(Seq.empty[Fiber[Unit, Any]])
                selfFiber <- Fiber.initUnscoped {
                    Abort.run[Throwable] {
                        val emitAndResubscribe: Unit < Async =
                            for
                                now          <- Clock.now
                                _            <- signalChangeTime.set(now)
                                current      <- rui.signal.current
                                _            <- exchange.onChange(rui.path, current)
                                (newKids, _) <- walkStatic(current, rui.path, rui.svgContext)
                                oldFibers    <- childFibersRef.get
                                _            <- Kyo.foreachDiscard(oldFibers)(_.interrupt)
                                newFibers    <- Kyo.foreach(newKids)(subscribeNode(_, exchange, signalChangeTime))
                                _            <- childFibersRef.set(newFibers.flatten)
                            yield ()
                        // Fork the next-change wait BEFORE emitting, then await it. Signal conflates
                        // intermediate values (latest-wins by design), so the loop only needs to be
                        // woken once and re-read `current`. The hazard is a lost wakeup of the final
                        // change: if a change lands after `current` is read but before the waiter is
                        // registered, it completes a waiterless promise and, when it is the last
                        // change, the region never re-renders. Forking `next` ahead of `emit`
                        // registers the waiter first, closing that window.
                        Loop.forever {
                            for
                                nextFiber <- Fiber.initUnscoped(rui.signal.next)
                                _         <- emitAndResubscribe
                                _         <- nextFiber.get
                            yield Loop.continue(())
                        }
                    }.map { result =>
                        result.foldError(
                            _ => (),
                            err => Log.error(s"Reactive subscription fiber failed at path=${rui.path.mkString(".")}", err.exception)
                        )
                    }
                }
            yield Seq(selfFiber)
        end if
    end subscribeNode

    // ---- Shared UI tree utilities (used by both backends) ----

    /** Resolve all reactive signals in a UI tree, returning a static snapshot. */
    def resolveReactives(ui: UI)(using Frame): UI < Sync =
        ui match
            case r: Reactive[?] =>
                for
                    current  <- r.signal.current(using r.frame)
                    resolved <- resolveReactives(current)
                yield resolved
            case fe: Foreach[?, ?] @unchecked =>
                fe.applyTyped {
                    [T] =>
                        (signal, keyFn, renderFn) =>
                            for
                                items <- signal.current(using fe.frame)
                                children = items.toSeq.zipWithIndex.map { (item, i) =>
                                    val key = keyFn match
                                        case Present(f) => f(item)
                                        case Absent     => i.toString
                                    KeyedChild[UI](key, renderFn(i, item))
                                }
                                resolved <- Kyo.foreach(children)(resolveReactives)
                            yield Fragment[UI](Chunk.from(resolved))
                            end for
                }
            case elem: Element =>
                Kyo.foreach(elem.children.toSeq)(resolveReactives).map { resolved =>
                    val resolvedChunk = Chunk.from(resolved)
                    if resolvedChunk == elem.children then ui
                    else rebuildElement(elem, resolvedChunk)
                }
            case Fragment(children) =>
                Kyo.foreach(children.toSeq)(resolveReactives).map(r => Fragment[UI](Chunk.from(r)))
            case KeyedChild(key, child) =>
                resolveReactives(child).map(r => KeyedChild[UI](key, r))
            case _ => ui

    private[kyo] def rebuildElement(elem: Element, newChildren: Chunk[UI]): UI =
        given Frame = elem.frame
        elem match
            case e: Div            => e.copy(children = newChildren)
            case e: SpanElement    => e.copy(children = newChildren)
            case e: P              => e.copy(children = newChildren)
            case e: Section        => e.copy(children = newChildren)
            case e: Main           => e.copy(children = newChildren)
            case e: Header         => e.copy(children = newChildren)
            case e: Footer         => e.copy(children = newChildren)
            case e: Pre            => e.copy(children = newChildren)
            case e: Code           => e.copy(children = newChildren)
            case e: Ul             => e.copy(children = newChildren)
            case e: Ol             => e.copy(children = newChildren)
            case e: Table          => e.copy(children = newChildren)
            case e: H1             => e.copy(children = newChildren)
            case e: H2             => e.copy(children = newChildren)
            case e: H3             => e.copy(children = newChildren)
            case e: H4             => e.copy(children = newChildren)
            case e: H5             => e.copy(children = newChildren)
            case e: H6             => e.copy(children = newChildren)
            case e: Nav            => e.copy(children = newChildren)
            case e: Li             => e.copy(children = newChildren)
            case e: Tr             => e.copy(children = newChildren)
            case e: Form           => e.copy(children = newChildren)
            case e: Label          => e.copy(children = newChildren)
            case e: Button         => e.copy(children = newChildren)
            case e: Td             => e.copy(children = newChildren)
            case e: Th             => e.copy(children = newChildren)
            case e: Select         => e.copy(children = newChildren)
            case e: Opt            => e.copy(children = newChildren)
            case e: Anchor         => e.copy(children = newChildren)
            case e: Svg.SvgElement => rebuildSvgElement(e, newChildren)
            case _                 => elem
        end match
    end rebuildElement

    /** Exhaustive match over all SvgElement concrete classes. NO case _ fallback: a missing arm is
      * a compile error (the kyo-ui build escalates the non-exhaustive-match warning to an error for
      * this file; see build.sbt).
      */
    private def rebuildSvgElement(elem: Svg.SvgElement, newChildren: Chunk[UI]): UI =
        given Frame = elem.frame
        elem match
            case e: Svg.Root           => e.copy(children = newChildren)
            case e: Svg.G              => e.copy(children = newChildren)
            case e: Svg.Defs           => e.copy(children = newChildren)
            case e: Svg.Symbol         => e.copy(children = newChildren)
            case e: Svg.Switch         => e.copy(children = newChildren)
            case e: Svg.SvgAnchor      => e.copy(children = newChildren)
            case e: Svg.Use            => e.copy(children = newChildren)
            case e: Svg.Rect           => e.copy(children = newChildren)
            case e: Svg.Circle         => e.copy(children = newChildren)
            case e: Svg.Ellipse        => e.copy(children = newChildren)
            case e: Svg.Line           => e.copy(children = newChildren)
            case e: Svg.Polyline       => e.copy(children = newChildren)
            case e: Svg.Polygon        => e.copy(children = newChildren)
            case e: Svg.Path           => e.copy(children = newChildren)
            case e: Svg.Text           => e.copy(children = newChildren)
            case e: Svg.TSpan          => e.copy(children = newChildren)
            case e: Svg.TextPath       => e.copy(children = newChildren)
            case e: Svg.LinearGradient => e.copy(children = newChildren)
            case e: Svg.RadialGradient => e.copy(children = newChildren)
            case e: Svg.Stop           => e.copy(children = newChildren)
            case e: Svg.Pattern        => e.copy(children = newChildren)
            case e: Svg.ClipPath       => e.copy(children = newChildren)
            case e: Svg.Mask           => e.copy(children = newChildren)
            case e: Svg.Image          => e.copy(children = newChildren)
            case e: Svg.ForeignObject  => e.copy(children = newChildren)
            case e: Svg.Marker         => e.copy(children = newChildren)
            case e: Svg.Title          => e.copy(children = newChildren)
            case e: Svg.Desc           => e.copy(children = newChildren)
            case e: Svg.Metadata       => e.copy(children = newChildren)
            // filter family: only Filter and FeMerge carry reactive children; the rest are leaves.
            case e: Svg.Filter            => e.copy(children = newChildren)
            case e: Svg.FeGaussianBlur    => e
            case e: Svg.FeOffset          => e
            case e: Svg.FeBlend           => e
            case e: Svg.FeColorMatrix     => e
            case e: Svg.FeFlood           => e
            case e: Svg.FeComposite       => e
            case e: Svg.FeMerge           => e.copy(children = newChildren)
            case e: Svg.FeMergeNode       => e
            case e: Svg.FeImage           => e
            case e: Svg.FeTile            => e
            case e: Svg.FeMorphology      => e
            case e: Svg.FeTurbulence      => e
            case e: Svg.FeDisplacementMap => e
            // SMIL family: all leaves.
            case e: Svg.Animate          => e
            case e: Svg.AnimateTransform => e
            case e: Svg.AnimateMotion    => e
            case e: Svg.SetAnim          => e
        end match
    end rebuildSvgElement

    /** Find the path to an element by ID in a resolved UI tree. Returns the dot-separated path string. */
    def findPathById(ui: UI, id: String, currentPath: String = ""): Maybe[String] =
        def searchChildren(children: Chunk[UI]): Maybe[String] =
            @scala.annotation.tailrec
            def loop(i: Int): Maybe[String] =
                if i >= children.size then Absent
                else
                    val childPath = if currentPath.isEmpty then i.toString else s"$currentPath.$i"
                    val child = children(i) match
                        case kc: KeyedChild[?] => kc.child
                        case c                 => c
                    findPathById(child, id, childPath) match
                        case Absent  => loop(i + 1)
                        case present => present
            loop(0)
        end searchChildren
        ui match
            case elem: Element =>
                if elem.attrs.identifier == Present(id) then Present(currentPath)
                else searchChildren(elem.children)
            case Fragment(children) => searchChildren(children)
            case _                  => Absent
        end match
    end findPathById

    /** Find all focusable element IDs in document order from a resolved UI tree. Skips disabled and hidden elements.
      */
    def findAllFocusableIds(ui: UI): Seq[String] =
        val builder = Seq.newBuilder[String]
        def walk(node: UI): Unit = node match
            case elem: Element =>
                val disabled = elem match
                    case hd: HasDisabled => hd.disabled.getOrElse(false)
                    case _               => false
                val hidden = elem.attrs.hidden.getOrElse(false)
                if !disabled && !hidden then
                    val focusable = elem.isInstanceOf[Focusable] || elem.attrs.tabIndex.nonEmpty
                    if focusable then
                        elem.attrs.identifier.foreach(id => builder += id)
                end if
                elem.children.foreach(walk)
            case Fragment(children)   => children.foreach(walk)
            case KeyedChild(_, child) => walk(child)
            case _                    => ()
        walk(ui)
        builder.result()
    end findAllFocusableIds

    /** Find element by ID in a resolved UI tree. */
    def findElementById(ui: UI, id: String): Maybe[Element] =
        def searchChildren(children: Chunk[UI]): Maybe[Element] =
            @scala.annotation.tailrec
            def loop(i: Int): Maybe[Element] =
                if i >= children.size then Absent
                else
                    findElementById(children(i), id) match
                        case Absent  => loop(i + 1)
                        case present => present
            loop(0)
        end searchChildren
        ui match
            case elem: Element =>
                if elem.attrs.identifier == Present(id) then Present(elem)
                else searchChildren(elem.children)
            case Fragment(children)   => searchChildren(children)
            case KeyedChild(_, child) => findElementById(child, id)
            case _                    => Absent
        end match
    end findElementById

    /** Find element at a dot-separated path in the UI tree. */
    @scala.annotation.tailrec
    def findAtPath(ui: UI, path: String): UI =
        if path.isEmpty then ui
        else
            val dotIdx  = path.indexOf('.')
            val segment = if dotIdx < 0 then path else path.substring(0, dotIdx)
            val rest    = if dotIdx < 0 then "" else path.substring(dotIdx + 1)
            childAt(ui, segment) match
                case Present(child) => findAtPath(child, rest)
                case Absent         => ui

    private def childAt(ui: UI, segment: String): Maybe[UI] =
        ui match
            case elem: Element =>
                Maybe.fromOption(segment.toIntOption) match
                    case Present(i) if i >= 0 && i < elem.children.size =>
                        elem.children(i) match
                            case kc: KeyedChild[?] => Present(kc.child)
                            case child             => Present(child)
                    case _ => Absent
            case Fragment(children) =>
                val keyIdx = Maybe.fromOption(children.toSeq.zipWithIndex.collectFirst {
                    case (kc: KeyedChild[?], _) if kc.key == segment => kc.child
                })
                keyIdx match
                    case Present(child) => Present(child)
                    case Absent =>
                        Maybe.fromOption(segment.toIntOption) match
                            case Present(i) if i >= 0 && i < children.size =>
                                children(i) match
                                    case kc: KeyedChild[?] => Present(kc.child)
                                    case child             => Present(child)
                            case _ => Absent
                end match
            case _ => Absent

end ReactiveUI
