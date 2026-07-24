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
    svgContext: Boolean = false,
    mountedSpec: Maybe[MountedSpec] = Absent,
    mountDispatch: Maybe[MountDispatch] = Absent // set on the ROOT normalize product only; see subscribe
)

/** Session-level dispatch table for mounted nodes: path → live content cell.
  *
  * Event dispatch re-derives handler chains from `signal.current` on every event, re-running any `Signal.map`
  * lambdas on the way, so the Mounted node value (and any state carried on it or its normalize product) is FRESH
  * per dispatch and cannot hold the subscribe-time wiring. The table is the stable meeting point: created once per
  * normalize root (one per mount session), closed over by every handler the normalization tree builds, written by
  * subscribeMounted (registration is Scope-bound: the per-value scope that subscribed a node unregisters it, and
  * observe's closed-and-awaited ordering makes unregister-then-reregister safe across region rebuilds), and read by
  * the dispatch-time handler of whatever fresh Mounted node value occupies the same path.
  */
final private[kyo] class MountDispatch(cells: AtomicRef[Dict[Seq[String], Signal[UI]]]):
    def register(path: Seq[String], cell: Signal[UI])(using Frame): Unit < Sync =
        cells.getAndUpdate(_.update(path, cell)).unit

    /** Unregister only if `cell` is still the registered one: a successor that already re-registered wins. */
    def unregister(path: Seq[String], cell: Signal[UI])(using Frame): Unit < Sync =
        cells.getAndUpdate(m => if m.get(path).exists(_ eq cell) then m.remove(path) else m).unit

    def lookup(path: Seq[String])(using Frame): Maybe[Signal[UI]] < Sync =
        cells.get.map(_.get(path))
end MountDispatch

private[kyo] object MountDispatch:
    def init(using Frame): MountDispatch < Sync =
        AtomicRef.init(Dict.empty[Seq[String], Signal[UI]]).map(new MountDispatch(_))

/** Normalization-time companion of a [[kyo.UI.Ast.Mounted]] node: the node, the render path it was normalized at, and its
  * rendering defaults.
  */
final private[kyo] case class MountedSpec(node: UI.Ast.Mounted, path: Seq[String]):
    def placeholderUI(using Frame): UI = node.placeholderUI.getOrElse(UI.empty)

    def errorUI(err: UI.MountError): UI =
        node.errorRender match
            case Present(f) => f(err)
            case Absent =>
                given Frame = node.frame
                UI.span(UI.Ast.Text(err.message)).cssClass("kyo-mount-error")

    def mountError(t: Throwable, source: UI.MountErrorSource): UI.MountError =
        UI.MountError(t, source, Maybe(t.getMessage).getOrElse(t.toString), node.frame, path, node.key)
end MountedSpec

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

    /** Root entry point: creates the session's MountDispatch table and stamps it on the returned root node
      * (subscribe picks it up from there). All recursion goes through normalizeWith so every handler closure
      * shares the one table.
      */
    def normalize(ui: UI, path: Seq[String], svg: Boolean = false): ReactiveUI < Sync =
        given Frame = ui.frame
        for
            mountDispatch <- MountDispatch.init
            root          <- normalizeWith(ui, path, svg, mountDispatch)
        yield root.copy(mountDispatch = Present(mountDispatch))
        end for
    end normalize

    private def normalizeWith(ui: UI, path: Seq[String], svg: Boolean, mountDispatch: MountDispatch): ReactiveUI < Sync =
        given Frame = ui.frame
        ui match
            case ui: Reactive[?] =>
                for
                    current   <- ui.signal.current
                    (kids, _) <- walkStatic(current, path, svg, mountDispatch)
                yield init(path, ui.signal, isConst = false, kids, svgContext = svg) {
                    (targetPath, event) =>
                        for
                            currentUI     <- ui.signal.current
                            (_, freshHdl) <- walkStatic(currentUI, path, svg, mountDispatch)
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
                    (kids, _) <- walkStatic(current, path, svg, mountDispatch)
                yield init(path, sig, isConst = false, kids, svgContext = svg) {
                    (targetPath, event) =>
                        for
                            currentUI     <- sig.current
                            (_, freshHdl) <- walkStatic(currentUI, path, svg, mountDispatch)
                            result        <- freshHdl(targetPath, event)
                        yield result
                }
                end for

            case ui: Element =>
                // An element with a SignalRef-bound attribute (`.value(ref)` xor `.checked(ref)`; an element binds at most
                // one, see collectSignalRef) must re-render when that signal changes. The change is carried by the ref
                // (read afresh at render time), not by the rendered value's identity: the value is always the same `ui`
                // object, kept with its `Bound.Ref` attributes so the rendered HTML carries the auto-binding event markers
                // `data-kyo-ev="input"/"change"` the client needs and the dispatch handler resolves the ref. The region's
                // signal is that ref mapped to the constant `ui`; mapping the leaf `SignalRef` keeps the signal exact, so
                // subscribeScoped's `observe` delegates to the ref's register-before-read leaf loop (lossless, no
                // deferred next-capture, no repair timer / idle churn), and each ref edit is a distinct ref VALUE that
                // re-renders without the value-dedup ever suppressing a real edit. An element with no bound ref is const.
                val (elementSignal, isConstNode) =
                    collectSignalRef(ui).fold((Signal.initConst(ui: UI), true))(ref => (ref.map(_ => ui: UI), false))
                for (kids, hdl) <- walkStatic(ui, path, svg, mountDispatch)
                yield ReactiveUI(path, elementSignal, isConst = isConstNode, kids, hdl, svgContext = svg)

            case ui: Mounted =>
                // The handler resolves through the MountDispatch TABLE (by path), not the node: dispatch re-normalizes
                // a fresh node value per event, so no live wiring can ride the node. The stored signal is a placeholder
                // constant the subscribe path never observes (subscribeScoped branches on mountedSpec first).
                val spec = MountedSpec(ui, path)
                val handle: Handler = (targetPath, event) =>
                    mountDispatch.lookup(path).map {
                        case Present(cell) =>
                            for
                                currentUI     <- cell.current
                                (_, freshHdl) <- walkStatic(currentUI, path, svg, mountDispatch)
                                result        <- freshHdl(targetPath, event)
                            yield result
                        case Absent => (true: Boolean) // not (or no longer) wired: bubble
                    }
                ReactiveUI(
                    path,
                    Signal.initConst(spec.placeholderUI),
                    isConst = false,
                    Seq.empty,
                    handle,
                    svgContext = svg,
                    mountedSpec = Present(spec)
                )

            case ui =>
                // Catch-all for static leaf nodes: Text, Fragment, KeyedChild.
                // All interactive types extend Element (handled above) and all reactive types are
                // Reactive, Foreach, or Mounted (also handled above). If a new UI subtype is added,
                // the exhaustiveness checker will NOT warn here; any new type that is interactive must
                // be added as an explicit case above before this catch-all.
                ReactiveUI(path, Signal.initConst(ui), isConst = true, Seq.empty, (_, _) => true, svgContext = svg)
        end match
    end normalizeWith

    /** Returns the element's single SignalRef-bound attribute (its `.value` or `.checked`), if any, so the element can be made reactive over
      * it (its HTML re-renders when that signal changes). An element binds at most one such ref.
      */
    private def collectSignalRef(elem: Element): Maybe[Signal[?]] =
        def refOf(v: Maybe[Bound[?]]): Maybe[Signal[?]] = v match
            case Present(Bound.Ref(ref)) => Present(ref)
            case _                       => Absent
        elem match
            case ti: TextInput    => refOf(ti.value)
            case ri: RangeInput   => refOf(ri.value)
            case pi: PickerInput  => refOf(pi.value)
            case bi: BooleanInput => refOf(bi.checked)
            case h: HiddenInput   => refOf(h.value)
            case _                => Absent
        end match
    end collectSignalRef

    private type Handler = (Seq[String], UIEvent) => Boolean < Async

    /** Walk a static UI tree. Collect reactive children, build handle. */
    private def walkStatic(ui: UI, basePath: Seq[String], svg: Boolean, mountDispatch: MountDispatch)(using
        Frame
    ): (Seq[ReactiveUI], Handler) < Sync =
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
                            case _: Reactive[?] | _: Foreach[?, ?] | _: Mounted =>
                                for rui <- normalizeWith(child, childPath, childSvg, mountDispatch)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case childElem: Element if collectSignalRef(childElem).nonEmpty =>
                                // Element with SignalRef-bound attributes is reactive over those signals;
                                // normalize it so subscribeNode wires updates.
                                for rui <- normalizeWith(childElem, childPath, childSvg, mountDispatch)
                                yield (Seq(rui), Seq.empty[(Int, Handler)])
                            case _ =>
                                for (innerKids, innerHandle) <- walkStatic(child, childPath, childSvg, mountDispatch)
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
                        walkStatic(inner, childPath, svg, mountDispatch)
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

            case _: Reactive[?] | _: Foreach[?, ?] | _: Mounted =>
                // When walkStatic is called with a Reactive, Foreach, or Mounted as the top-level node
                // (not as a child of an Element), normalize it at basePath so subscribeNode
                // sets up a subscription for it. This handles the case where an outer reactive's
                // signal value is itself a Reactive or Foreach (e.g. outer.map { _ => inner.map(UI.span(_)) }).
                for rui <- normalizeWith(ui, basePath, svg, mountDispatch)
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
            val childIdx     = Maybe.fromOption(childSegment.toIntOption)

            // Find the reactive child whose path is a prefix of (or equals) targetPath. Matching by
            // `lastOption.contains(childSegment)` is incorrect when reactive children are nested
            // multiple levels deep; their last segment may collide with a sibling's index at the
            // current level. Using prefix-match correctly routes only when the target lies inside.
            val reactiveChild = Maybe.fromOption(reactiveChildren.find(rc => targetPath.startsWith(rc.path)))
            // STATIC descent takes precedence: route through the static child's handler chain so intermediate bubble
            // handlers run. Jumping straight to a DEEP reactive child would skip them: a click on a reactive label
            // inside a button (`button(labelSignal)`) would bypass the button's own onClick. Only a DIRECT reactive
            // child (no staticHandlers entry at its index) takes the reactive jump.
            val staticChild = childIdx.flatMap(i => Maybe.fromOption(staticHandlers.find(_._1 == i)).map(_._2))

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
                result <- staticChild match
                    case Present(childHandle) =>
                        safeDispatch(childHandle, targetPath, event).map(keep => if keep then bubble else false)
                    case Absent =>
                        reactiveChild.fold(bubble)(child =>
                            safeDispatch(child.handle, targetPath, event).map(keep => if keep then bubble else false)
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
            // Text and RawHtml are leaf content with no kyo-addressable Element children, so no event
            // target can resolve through them: neither can satisfy an Element predicate.
            case _: Text | _: RawHtml => false
            // Predicates do not resolve THROUGH a mount boundary: the content lives behind a subscribe-time cell this
            // static resolver cannot reach (v1 limitation: a Button in a mounted subtree does not trigger an OUTER
            // Form's submit-on-bubble refinement). Event dispatch still resolves via the node's handler indirection.
            case _: Mounted => false

    private def isTargetDisabled(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, isDisabled)

    private def isTargetButton(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Button])

    private def isTargetSelect(elem: Element, myPath: Seq[String], targetPath: Seq[String])(using Frame): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Select])

    /** Bubble-continue value after an element handled `event`: `false` (consume) only when the element set
      * `stopPropagation(true)` AND actually declared a handler for this event's type (`declared`). The result flows up the
      * dispatch recursion, so a `false` makes every element ABOVE this one skip its own handler.
      */
    private def keepBubbling(elem: Element, declared: Boolean): Boolean =
        !(declared && elem.attrs.stopPropagation.getOrElse(false))

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
                    // Self handlers only count as "declared" when they actually fired (isTarget).
                    val declared = attrs.onClick.nonEmpty || attrs.onClickEvt.nonEmpty ||
                        (isTarget && (attrs.onClickSelf.nonEmpty || attrs.onClickSelfEvt.nonEmpty))
                    self
                        .andThen(invokeWith(attrs.onClickEvt, mouse))
                        .andThen(invoke(attrs.onClick))
                        .andThen(activateToggle)
                        .andThen(formSubmit)
                        .andThen(keepBubbling(elem, declared))
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
                    keyHandler.andThen(activateClick).andThen(activateToggle).andThen(selectCycle).andThen(formSubmit)
                        .andThen(keepBubbling(elem, attrs.onKeyDown.nonEmpty))
            case e: UIEvent.KeyUp =>
                val kbEvent = UI.KeyboardEvent(
                    key = Keyboard.fromString(e.keyboard.key),
                    modifiers = e.keyboard.modifiers,
                    targetId = e.keyboard.targetId
                )
                invokeWith(attrs.onKeyUp, kbEvent).andThen(keepBubbling(elem, attrs.onKeyUp.nonEmpty))
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
                invoke(attrs.onHover).andThen(invokeWith(attrs.onHoverEvt, mouse))
                    .andThen(keepBubbling(elem, attrs.onHover.nonEmpty || attrs.onHoverEvt.nonEmpty))
            case ev: UIEvent.Unhover =>
                val mouse = UI.MouseEvent(ev.mouse.targetId, ev.mouse.modifiers)
                invoke(attrs.onUnhover).andThen(invokeWith(attrs.onUnhoverEvt, mouse))
                    .andThen(keepBubbling(elem, attrs.onUnhover.nonEmpty || attrs.onUnhoverEvt.nonEmpty))
            case ev: UIEvent.Scroll =>
                val wheel = UI.WheelEvent(ev.deltaX, ev.deltaY, ev.targetId, ev.modifiers)
                invoke(attrs.onScroll).andThen(invokeWith(attrs.onScrollEvt, wheel))
                    .andThen(keepBubbling(elem, attrs.onScroll.nonEmpty || attrs.onScrollEvt.nonEmpty))
            case _ => true
        end match
    end dispatchToElement

    // ---- Subscribe ----

    /** Result of subscribe: dispatch handler + signal change tracking. The enclosing Scope owns every
      * region fiber's lifecycle; the per-value Scope (opened by observe) owns each region's children,
      * so closing the root scope cascade-tears-down the tree.
      */
    case class Subscription(handle: Handler, lastSignalChangeTime: AtomicRef[Instant])

    /** Subscribe all reactive boundaries under the caller's Scope. Returns the dispatch handle + change
      * time; lifecycle is owned by the enclosing Scope (closing it interrupts every region fiber).
      */
    def subscribe(rui: ReactiveUI, exchange: UIExchange)(using Frame): Subscription < (Async & Scope) =
        for
            signalChangeTime <- AtomicRef.init(Instant.Epoch)
            rootMounts       <- MountRegistry.init
            _                <- Scope.ensure(rootMounts.evictAll)
            // Absent only for hand-built ReactiveUIs in tests; a normalize-produced root always carries the table.
            mountDispatch <- rui.mountDispatch match
                case Present(d) => Kyo.lift(d)
                case Absent     => MountDispatch.init
            _ <- subscribeScoped(rui, exchange, signalChangeTime, rootMounts, mountDispatch)
        yield Subscription(rui.handle, signalChangeTime)

    // Each reactive region forks a Fiber.init (scoped to the enclosing Scope) running observe. Each
    // value opens a fresh per-value Scope; the region renders, re-walks, and forks its children INTO that
    // per-value Scope via the recursive subscribeScoped, so the next value (or an interrupt) closes them by
    // cascade. A const node has no signal: it subscribes its children in the enclosing scope. The prior manual
    // interrupt-old bookkeeping (a ref of live child fibers, interrupted one level per change) is gone; the
    // per-value Scope does interrupt-old transitively (every descendant), fixing the climbing-waiters leak the
    // one-level interrupt left.
    //
    // `mounts` is the MountRegistry of the nearest enclosing region (or the subscribe root): keyed Mounted instances
    // escape the per-value cascade by living on fibers the registry owns, so keyed continuity spans re-renders of the
    // immediately enclosing region and ends with that region.
    private def subscribeScoped(
        rui: ReactiveUI,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        mounts: MountRegistry,
        mountDispatch: MountDispatch
    )(using
        Frame
    ): Unit < (Async & Scope) =
        rui.mountedSpec match
            case Present(spec) =>
                subscribeMounted(rui, spec, exchange, signalChangeTime, mounts, mountDispatch)
            case Absent =>
                if rui.isConst then
                    Kyo.foreachDiscard(rui.children)(
                        subscribeScoped(_, exchange, signalChangeTime, mounts, mountDispatch)
                    )
                else
                    subscribeRegion(
                        rui.path,
                        rui.signal,
                        rui.svgContext,
                        exchange,
                        signalChangeTime,
                        Absent,
                        mountDispatch
                    )

    /** Fork one region fiber observing `signal`, with a per-value Scope per emission (see subscribeScoped's
      * contract note). `presetMounts` supplies the region's MountRegistry when the caller owns it already (the
      * content region of a KEYED mounted instance reuses the instance's registry so nested keyed mounts survive
      * re-subscription); `Absent` creates a fresh registry owned by the current scope: the same scope that owns
      * the region fiber, so registry and region die together.
      *
      * renderValue ordering is walk → evict → paint → subscribe: mount keys of the new value are known after the
      * walk, so instances whose key vanished (or was swapped) are closed AND awaited BEFORE the new HTML paints
      * and before any successor effect runs (the region-level closed-and-awaited guarantee, extended to the
      * keyed instances that deliberately escape the per-value cascade).
      */
    private def subscribeRegion(
        path: Seq[String],
        signal: Signal[UI],
        svg: Boolean,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        presetMounts: Maybe[MountRegistry],
        mountDispatch: MountDispatch
    )(using Frame): Unit < (Async & Scope) =
        for
            regionMounts <- presetMounts match
                case Present(r) => Kyo.lift(r)
                case Absent     => MountRegistry.init.map(r => Scope.ensure(r.evictAll).andThen(r))
            _ <- Fiber.init {
                def renderValue(current: UI): Unit < (Async & Scope) =
                    for
                        now          <- Clock.now
                        _            <- signalChangeTime.set(now)
                        (newKids, _) <- walkStatic(current, path, svg, mountDispatch)
                        _            <- regionMounts.evictExcept(collectMountKeys(newKids))
                        _            <- exchange.onChange(path, current)
                        _ <- Kyo.foreachDiscard(newKids)(
                            subscribeScoped(_, exchange, signalChangeTime, regionMounts, mountDispatch)
                        )
                    yield ()
                Abort.run[Throwable] {
                    signal.observe(renderValue)
                }.map { result =>
                    result.fold(
                        _ => (),
                        err => Log.error(s"Reactive subscription fiber failed at path=${path.mkString(".")}", err),
                        panic =>
                            if panic.isInstanceOf[Interrupted] then ()
                            else Log.error(s"Reactive subscription fiber failed at path=${path.mkString(".")}", panic)
                    )
                }
            }
        yield ()

    /** The keys of the Mounted nodes among a walk's reactive children: the claims of the upcoming subscribe
      * pass, computed up front so evictExcept can run before paint.
      */
    private def collectMountKeys(kids: Seq[ReactiveUI]): Set[Any] =
        kids.flatMap(_.mountedSpec.flatMap(_.node.key).toChunk).toSet

    /** Subscribe one Mounted node.
      *
      * KEYED: claim (or adopt) the live instance from the enclosing region's registry. The instance runner is an
      * UNSCOPED fiber (it survives the per-value cascade; the registry's owner scope ends it) holding the node
      * Scope open; then push one synchronous paint of the cell's current content (so an adopted instance's content
      * replaces the placeholder the parent's wholesale re-render just painted, within the same task, with no visible
      * placeholder blink), and finally subscribe the content region over the cell, reusing the instance's child
      * registry so keyed mounts NESTED in the content survive as long as this instance does.
      *
      * KEYLESS: the instance lifetime IS the current scope (the enclosing region's per-value scope, or the
      * session scope for a static position): the effect runs on a normally-scoped fiber and the next parent
      * emission tears it down by cascade (remount semantics). A thrash note counts remounts per path and logs a
      * dev hint when a keyless node keeps remounting inside a hot region.
      */
    private def subscribeMounted(
        rui: ReactiveUI,
        spec: MountedSpec,
        exchange: UIExchange,
        signalChangeTime: AtomicRef[Instant],
        mounts: MountRegistry,
        mountDispatch: MountDispatch
    )(using Frame): Unit < (Async & Scope) =
        spec.node.key match
            case Present(key) =>
                mounts.claim(key, rui.path, spec).map {
                    case Present(inst) =>
                        for
                            _       <- mountDispatch.register(rui.path, inst.cell)
                            _       <- Scope.ensure(mountDispatch.unregister(rui.path, inst.cell))
                            current <- inst.cell.current
                            _       <- exchange.onChange(rui.path, current)
                            _ <- subscribeRegion(
                                rui.path,
                                inst.cell,
                                rui.svgContext,
                                exchange,
                                signalChangeTime,
                                Present(inst.childMounts),
                                mountDispatch
                            )
                        yield ()
                    case Absent =>
                        // Duplicate key this render pass (claim already warned): paint a loud inline error instead of
                        // silently sharing the first slot's instance. No cell or dispatch entry: a dead slot until the
                        // caller gives distinct keys.
                        given Frame = spec.node.frame
                        exchange
                            .onChange(
                                rui.path,
                                UI.span(UI.Ast.Text(s"kyo-ui: duplicate mounted key '$key'")).cssClass("kyo-mount-error")
                            )
                            .unit
                }
            case Absent =>
                for
                    _           <- mounts.noteKeylessRemount(rui.path)
                    seed        <- mounts.keepLatestSeed(rui.path, spec)
                    cell        <- Signal.initRef[UI](seed)
                    _           <- mountDispatch.register(rui.path, cell)
                    _           <- Scope.ensure(mountDispatch.unregister(rui.path, cell))
                    supervision <- MountSupervision.init
                    _ <- Fiber.init(runMountEffect(
                        spec,
                        cell,
                        ui => mounts.recordContent(rui.path, ui),
                        supervision
                    ))
                    _ <- subscribeRegion(rui.path, cell, rui.svgContext, exchange, signalChangeTime, Absent, mountDispatch)
                yield ()

    /** A supervised background fiber of a mounted node failed with a non-Throwable `Abort` value; the node's
      * error rendering needs a Throwable, so the value is carried in this wrapper.
      */
    final private[kyo] case class MountFiberFailure(value: Any)(using Frame)
        extends KyoException(s"Mounted background fiber failed: $value")

    /** The node-scope supervision seam of one mount instance: collects the FIRST non-interrupt failure of any
      * supervised background fiber (see [[kyo.UI.fork]]) and lets the node runner park on it AFTER the effect
      * outcome, flipping an already-published node into its error rendering retroactively.
      *
      * Publish then flip is serialized on the node's own fiber (the park runs after [[runMountEffect]]'s
      * outcome handling), so a supervised failure can never be overwritten by a late `cell.set(ui)` of the
      * success path. The `routed` once-guard is shared between the effect-failure path and the park, so an
      * effect failure racing a supervised failure routes exactly once.
      */
    final private[kyo] class MountSupervision(
        firstFailure: Fiber.Promise.Unsafe[Throwable, Any],
        routed: AtomicRef[Boolean]
    ):
        /** Fiber-completion callback registered by [[kyo.UI.fork]]: maps a non-interrupt terminal error to a
          * Throwable and completes the once-only failure promise; later failures are dropped by the promise.
          * Runs on the completing fiber's thread, so it must stay allocation-light and must not block.
          */
        def report[E, A](result: Result[E, A])(using Frame): Unit < Sync =
            result match
                case Result.Panic(_: Interrupted)   => Kyo.lift(())
                case Result.Failure(_: Interrupted) => Kyo.lift(())
                case Result.Panic(exception)        => recordFailure(exception)
                case Result.Failure(failure) =>
                    failure match
                        case t: Throwable => recordFailure(t)
                        case other        => recordFailure(MountFiberFailure(other))
                case _ => Kyo.lift(())

        private def recordFailure(t: Throwable)(using Frame): Unit < Sync =
            Sync.Unsafe.defer(discard(firstFailure.complete(Result.succeed(t))))

        /** Route a failure through `route` at most once across effect-fail and supervised-fail paths. */
        def routeOnce(t: Throwable)(route: Throwable => Unit < Async)(using Frame): Unit < Async =
            routed.getAndSet(true).map(was => if was then () else route(t))

        /** Park the node fiber until a supervised failure arrives, then log + route it. Never returns: the
          * park (interrupted on teardown) is what holds the node Scope open for the instance's lifetime.
          */
        def park(spec: MountedSpec, route: Throwable => Unit < Async)(using Frame): Nothing < Async =
            firstFailure.safe.get.map { t =>
                Log.error(s"Mounted background fiber failed (frame=${spec.node.frame.position.show})", t)
                    .andThen(routeOnce(t)(route))
            }.andThen(Async.never)
    end MountSupervision

    private[kyo] object MountSupervision:
        def init(using Frame): MountSupervision < Sync =
            AtomicRef.init(false).map { routed =>
                Sync.Unsafe.defer {
                    new MountSupervision(Fiber.Promise.Unsafe.init[Throwable, Any](), routed)
                }
            }

        /** The mount instance a computation runs under, read by [[kyo.UI.fork]]. Inheritable, so a `UI.fork`
          * inside an already forked fiber still reports to the node that started the chain. Installed around
          * the USER effect only: engine fibers run outside it and are never supervised.
          */
        private[kyo] val local: Local[Maybe[MountSupervision]] = Local.init(Maybe.empty[MountSupervision])

        private[kyo] def let[A, S](sup: MountSupervision)(v: A < S)(using Frame): A < S =
            local.let(Present(sup))(v)
    end MountSupervision

    /** Run a Mounted node's effect, publish the outcome into its content cell, then PARK on `supervision` for
      * the instance's lifetime (this method never returns; the park is interrupted by the node's teardown).
      * Failure routing (shared by the effect outcome and a later supervised background-fiber failure, at most
      * once per instance): a node-local `.onError` wins, otherwise the node's default error rendering applies.
      * The enclosing region stays alive either way (a flip keeps the node Scope open, like a pending-phase
      * failure always has); failures are additionally logged out-of-band.
      */
    private def runMountEffect(
        spec: MountedSpec,
        cell: SignalRef[UI],
        record: UI => Unit < Sync,
        supervision: MountSupervision
    )(using Frame): Nothing < (Async & Scope) =
        def failed(source: UI.MountErrorSource)(t: Throwable): Unit < Async =
            cell.set(spec.errorUI(spec.mountError(t, source))).unit
        Abort.run[Throwable](MountSupervision.let(supervision)(spec.node.effect)).map {
            case Result.Success(ui) => cell.set(ui).andThen(record(ui))
            case Result.Failure(t) =>
                Log.error(s"Mounted effect failed (frame=${spec.node.frame.position.show})", t)
                    .andThen(supervision.routeOnce(t)(failed(UI.MountErrorSource.Effect)))
            case Result.Panic(t) =>
                if t.isInstanceOf[Interrupted] then ()
                else
                    Log.error(s"Mounted effect panicked (frame=${spec.node.frame.position.show})", t)
                        .andThen(supervision.routeOnce(t)(failed(UI.MountErrorSource.Panic)))
        }.map(_ => supervision.park(spec, failed(UI.MountErrorSource.Fork)))
    end runMountEffect

    /** A live keyed mount: the content cell, the unscoped runner fiber that owns the node Scope, and the child
      * registry that carries keyed mounts nested in this instance's content across re-subscriptions.
      */
    final private[kyo] class MountInstance(
        val key: Any,
        val cell: SignalRef[UI],
        val fiber: Fiber[Nothing, Any],
        val childMounts: MountRegistry
    )

    /** Per-region ownership of keyed mount instances (plus keep-latest seeds and the keyless thrash counter).
      * All mutation happens from the owning region's sequential observe loop (or once, at static subscribe), so
      * the AtomicRefs guard memory visibility across fiber steps, not concurrent writers.
      */
    final private[kyo] class MountRegistry(
        instances: AtomicRef[Dict[Any, MountInstance]],
        lastContent: AtomicRef[Dict[Seq[String], UI]],
        keylessRemounts: AtomicRef[Dict[Seq[String], Int]],
        claimedThisWalk: AtomicRef[Set[Any]],
        lastKeyByPath: AtomicRef[Dict[Seq[String], (Any, Int)]]
    ):

        /** Content last published at a path, the KeepLatest seed: a re-mounting node at this slot starts from the
          * previous content instead of the placeholder (frozen snapshot; its regions re-attach when the new effect
          * publishes). Placeholder policy, or a slot that never published, starts from the placeholder.
          */
        def keepLatestSeed(path: Seq[String], spec: MountedSpec)(using Frame): UI < Sync =
            spec.node.pendingPolicy match
                case UI.PendingPolicy.Placeholder => spec.placeholderUI
                case UI.PendingPolicy.KeepLatest =>
                    lastContent.get.map(_.getOrElse(path, spec.placeholderUI))

        def recordContent(path: Seq[String], ui: UI)(using Frame): Unit < Sync =
            lastContent.getAndUpdate(_.update(path, ui)).unit

        /** Reuse the live instance under `key`, or create one: seed the cell (keep-latest by slot), fork the
          * UNSCOPED runner (it must survive the caller's per-value scope; this registry's owner ends it), which
          * holds the node Scope open until interrupted and evicts nested keyed mounts on the way out. Two claims
          * of one key within a single walk (evictExcept resets the claim set per walk) are a caller bug: the
          * duplicate claim returns `Absent` (the caller paints a loud inline error card in that slot instead of
          * silently sharing the first slot's instance), and the warning names key and path (Laminar's
          * duplicate-split-key discipline, made visible in the page).
          */
        def claim(key: Any, path: Seq[String], spec: MountedSpec)(using
            Frame
        ): Maybe[MountInstance] < (Async & Scope) =
            claimedThisWalk.getAndUpdate(_ + key).map(_.contains(key)).map {
                case true =>
                    Log.warn(
                        s"kyo-ui: duplicate mounted key '$key' claimed at ${path.mkString(".")} within one render pass: " +
                            "the duplicate slot renders an inline error card; use distinct keys (e.g. .keyed(Component -> id))"
                    ).andThen(Absent: Maybe[MountInstance])
                case false =>
                    for
                        _ <- noteKeyAt(path, key)
                        m <- instances.get
                        inst <- m.get(key) match
                            case Present(inst) => Kyo.lift(inst)
                            case Absent =>
                                for
                                    seed        <- keepLatestSeed(path, spec)
                                    cell        <- Signal.initRef[UI](seed)
                                    childMounts <- MountRegistry.init
                                    supervision <- MountSupervision.init
                                    fiber <- Fiber.initUnscoped {
                                        Scope.run {
                                            // runMountEffect never returns (it parks on `supervision`), which holds this
                                            // node Scope open until teardown.
                                            Scope.ensure(childMounts.evictAll)
                                                .andThen(runMountEffect(
                                                    spec,
                                                    cell,
                                                    ui => recordContent(path, ui),
                                                    supervision
                                                ))
                                        }
                                    }
                                    inst = new MountInstance(key, cell, fiber, childMounts)
                                    _ <- instances.getAndUpdate(_.update(key, inst))
                                yield inst
                    yield Present(inst)
            }

        /** Dev hint against the unstable-key anti-pattern: a key that is REBUILT per render (a fresh tuple or
          * object identity, an inline-derived `Signal` inside the key) evicts and re-creates the instance on
          * every enclosing re-render: keyed continuity silently degrades to remount semantics. A key that
          * changes ONCE (`.keyed(EraPanel -> era)` on an era switch) is the intended re-creation and resets the
          * streak; only consecutive-change streaks of COMPOSITE keys are flagged: simple value keys
          * (String/primitive) are exempt, since a location-driven region (route node keyed by path) re-renders
          * exclusively on key changes and would otherwise be flagged after three navigations.
          */
        private def noteKeyAt(path: Seq[String], key: Any)(using Frame): Unit < Sync =
            if simpleValueKey(key) then lastKeyByPath.getAndUpdate(_.remove(path)).unit
            else noteCompositeKeyAt(path, key)

        /** String / primitive / boxed-primitive keys: value equality, no identity risk. */
        private def simpleValueKey(key: Any): Boolean = key match
            case _: String | _: Int | _: Long | _: Boolean | _: Double | _: Float | _: Short |
                _: Byte | _: Char =>
                true
            case _ => false

        private def noteCompositeKeyAt(path: Seq[String], key: Any)(using Frame): Unit < Sync =
            lastKeyByPath.getAndUpdate { m =>
                m.get(path) match
                    case Present((prev, streak)) if !prev.equals(key) => m.update(path, (key, streak + 1))
                    case _                                            => m.update(path, (key, 0))
            }.map { prev =>
                prev.get(path) match
                    case Present((prevKey, streak)) if !prevKey.equals(key) =>
                        val n = streak + 1
                        if n == 3 || n == 10 || n == 100 then
                            Log.warn(
                                s"kyo-ui: mounted key at ${path.mkString(".")} changed in $n consecutive render passes " +
                                    s"(now '$key'): the key value is likely rebuilt per render (unstable tuple/object/" +
                                    "Signal identity), so the instance is evicted and re-created every pass. Derive keys " +
                                    "from stable data if the instance should live across re-renders."
                            )
                        else Kyo.unit
                        end if
                    case _ => Kyo.unit
            }.unit

        /** Close-and-await every instance whose key is not in `keep`: run BEFORE the new value paints, so a
          * vanished or swapped key's resources are fully released while the OLD content is still on screen
          * (break-before-make; under KeepLatest the successor then seeds from the recorded content). Also opens
          * the next claim generation for duplicate detection.
          */
        def evictExcept(keep: Set[Any])(using Frame): Unit < Async =
            for
                _   <- claimedThisWalk.set(Set.empty)
                old <- instances.getAndUpdate(_.filter((k, _) => keep.contains(k)))
                evicted = old.foldLeft(Chunk.empty[MountInstance])((acc, k, inst) =>
                    if keep.contains(k) then acc else acc.append(inst)
                )
                _ <- Kyo.foreachDiscard(evicted)(teardown)
            yield ()

        def evictAll(using Frame): Unit < Async =
            evictExcept(Set.empty)

        private def teardown(inst: MountInstance)(using Frame): Unit < Async =
            inst.fiber.interrupt.andThen(inst.fiber.getResult.unit)

        /** Dev hint against the keyless-node-in-hot-region anti-pattern: a keyless Mounted re-runs its effect on
          * every enclosing region re-render; keys are the opt-in continuity mechanism.
          */
        def noteKeylessRemount(path: Seq[String])(using Frame): Unit < Sync =
            keylessRemounts.getAndUpdate(m => m.update(path, m.getOrElse(path, 0) + 1)).map { prev =>
                val n = prev.getOrElse(path, 0) + 1
                if n == 10 || n == 100 || n == 1000 then
                    Log.warn(
                        s"kyo-ui: keyless UI.mounted at ${path.mkString(".")} remounted $n times: its effect re-runs on " +
                            "every enclosing region re-render. Give it a stable identity with .keyed(...) if the instance " +
                            "should live across re-renders, or move it out of the hot region."
                    )
                else Kyo.unit
                end if
            }
    end MountRegistry

    private[kyo] object MountRegistry:
        def init(using Frame): MountRegistry < Sync =
            for
                instances       <- AtomicRef.init(Dict.empty[Any, MountInstance])
                lastContent     <- AtomicRef.init(Dict.empty[Seq[String], UI])
                keylessRemounts <- AtomicRef.init(Dict.empty[Seq[String], Int])
                claimedThisWalk <- AtomicRef.init(Set.empty[Any])
                lastKeyByPath   <- AtomicRef.init(Dict.empty[Seq[String], (Any, Int)])
            yield new MountRegistry(instances, lastContent, keylessRemounts, claimedThisWalk, lastKeyByPath)
    end MountRegistry

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
            case m: Mounted =>
                // A static snapshot cannot run the mount effect; the node's static projection is its placeholder.
                m.placeholderUI.getOrElse(UI.empty(using m.frame))
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
