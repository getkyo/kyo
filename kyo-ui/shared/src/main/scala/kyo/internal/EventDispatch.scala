package kyo.internal

import MountRegions.*
import ReactiveUI.*
import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*

private[kyo] object EventDispatch:

    private[internal] type Handler = (Seq[String], UIEvent) => Boolean < Async

    private[internal] type ValidatedHandler = (Seq[String], DragProtocol.ValidatedEvent) => Boolean < Async

    /** Dispatch an event through an element. */
    private[internal] def dispatch(
        elem: Element,
        myPath: Seq[String],
        targetPath: Seq[String],
        event: UIEvent,
        reactiveChildren: Seq[ReactiveUI],
        staticHandlers: Seq[(Int, Handler)],
        mounts: MountDispatch
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
                    case _: UIEvent.Click => isTargetDisabled(elem, myPath, targetPath, mounts)
                    case _                => Kyo.lift(false)
                targetIsButton <- event match
                    case _: UIEvent.Click => isTargetButton(elem, myPath, targetPath, mounts)
                    case _                => Kyo.lift(false)
                targetIsSelect <- event match
                    case _: UIEvent.KeyDown => isTargetSelect(elem, myPath, targetPath, mounts)
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
                            .fold(Kyo.lift(false))(childHandle =>
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
    private[internal] def safeDispatch(handle: (Seq[String], UIEvent) => Boolean < Async, path: Seq[String], event: UIEvent)(using
        Frame
    ): Boolean < Async =
        DragCommands.current.use { context =>
            val decisions = context.flatMap(_.decisions)
            Abort.recover[Throwable](
                onFail = err =>
                    Log.error(s"Handler error during ${event.getClass.getSimpleName} at ${path.mkString(".")}: ${err.getMessage}")
                        .andThen(markDecisionFailure(event, decisions))
                        .andThen(true), // continue bubbling
                onPanic = thr =>
                    Log.error(s"Handler panic during ${event.getClass.getSimpleName} at ${path.mkString(".")}: ${thr.getMessage}")
                        .andThen(markDecisionFailure(event, decisions))
                        .andThen(true) // continue bubbling
            )(handle(path, event))
        }

    private def markDecisionFailure(event: UIEvent, decisions: Maybe[AtomicRef[DragCommands.DecisionState]])(using
        Frame
    ): Unit < Async =
        event match
            case _: UIEvent.Drop     => failDecision(decisions, "The drop handler failed.")
            case _: UIEvent.SortMove => failDecision(decisions, "The sort handler failed.")
            case _                   => ()

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
                nextIdx    =
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
    private def targetSatisfies(
        node: UI,
        nodePath: Seq[String],
        targetPath: Seq[String],
        predicate: Element => Boolean,
        mounts: MountDispatch
    )(using
        Frame
    ): Boolean < Sync =
        node match
            case kc: KeyedChild[?] =>
                targetSatisfies(kc.child, nodePath, targetPath, predicate, mounts)
            case r: Reactive[?] =>
                // A Reactive's rendered content occupies the same path as the boundary, so re-test at nodePath.
                r.signal.current(using r.frame).map(cur => targetSatisfies(cur, nodePath, targetPath, predicate, mounts))
            case Fragment(children) =>
                if targetPath.size <= nodePath.size then false
                else
                    val seg = targetPath(nodePath.size)
                    Maybe.fromOption(seg.toIntOption) match
                        case Present(i) if i >= 0 && i < children.size =>
                            targetSatisfies(children(i), nodePath :+ seg, targetPath, predicate, mounts)
                        case _ => false
                    end match
            case fe: Foreach[?, ?] @unchecked =>
                if targetPath.size <= nodePath.size then false
                else
                    val seg = targetPath(nodePath.size)
                    fe.applyTyped {
                        [T] => (signal, keyFn, renderFn) =>
                            signal.current(using fe.frame).map { items =>
                                val idx = keyFn match
                                    case Present(f) => items.indexWhere(it => f(it) == seg)
                                    case Absent     => Maybe.fromOption(seg.toIntOption).getOrElse(-1)
                                if idx >= 0 && idx < items.size then
                                    targetSatisfies(renderFn(idx, items(idx)), nodePath :+ seg, targetPath, predicate, mounts)
                                else false
                            }
                    }
            case e: Element =>
                if targetPath.size <= nodePath.size then predicate(e)
                else
                    val seg = targetPath(nodePath.size)
                    Maybe.fromOption(seg.toIntOption) match
                        case Present(i) if i >= 0 && i < e.children.size =>
                            targetSatisfies(e.children(i), nodePath :+ seg, targetPath, predicate, mounts)
                        case _ => false
                    end match
            // Text, RawHtml and StyleElement are leaf content with no kyo-addressable Element children, so no event
            // target can resolve through them: none can satisfy an Element predicate.
            case _: Text | _: RawHtml | _: StyleElement => false
            // A mount's content lives with its instance, found through the dispatch table like the node's handler finds it, and
            // occupies the node's path. An inert snapshot satisfies nothing, since no event reaches it.
            case m: Mounted =>
                mounts.lookup(nodePath).map {
                    case Absent        => false
                    case Present(cell) =>
                        cell.current.map {
                            case MountState.Pending(Present(_)) => false
                            case state                          =>
                                targetSatisfies(
                                    state.content(MountedSpec(m, nodePath, Chunk.empty)),
                                    nodePath,
                                    targetPath,
                                    predicate,
                                    mounts
                                )
                        }
                }

    private def isTargetDisabled(elem: Element, myPath: Seq[String], targetPath: Seq[String], mounts: MountDispatch)(using
        Frame
    ): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, isDisabled, mounts)

    private def isTargetButton(elem: Element, myPath: Seq[String], targetPath: Seq[String], mounts: MountDispatch)(using
        Frame
    ): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Button], mounts)

    private def isTargetSelect(elem: Element, myPath: Seq[String], targetPath: Seq[String], mounts: MountDispatch)(using
        Frame
    ): Boolean < Sync =
        targetSatisfies(elem, myPath, targetPath, _.isInstanceOf[Select], mounts)

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
                    val self  = if isTarget then
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
                        if isTarget &&
                            (e.keyboard.key == "ArrowDown" || e.keyboard.key == "ArrowUp" || e.keyboard.key == " " ||
                                e.keyboard.key == "Enter")
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
            case _: UIEvent.DragStart =>
                if !isTarget then true
                else
                    DragCommands.current.use {
                        case Present(DragCommands.Dispatch(DragCommands.Payload.Event(value), _)) =>
                            invokeDrag(attrs.onDragStart, attrs.onDragStartEvt.map(_(value)), "drag start")
                                .andThen(keepBubbling(elem, attrs.onDragStart.nonEmpty || attrs.onDragStartEvt.nonEmpty))
                        case _ => true
                    }
            case _: UIEvent.DragEnd =>
                if !isTarget then true
                else
                    DragCommands.current.use {
                        case Present(DragCommands.Dispatch(DragCommands.Payload.End(value), _)) =>
                            invokeDrag(attrs.onDragEnd, attrs.onDragEndEvt.map(_(value)), "drag end")
                                .andThen(keepBubbling(elem, attrs.onDragEnd.nonEmpty || attrs.onDragEndEvt.nonEmpty))
                        case _ => true
                    }
            case _: UIEvent.DragEnter => dispatchDragEvent(elem, attrs.onDragEnter, attrs.onDragEnterEvt, "drag enter")
            case _: UIEvent.DragLeave => dispatchDragEvent(elem, attrs.onDragLeave, attrs.onDragLeaveEvt, "drag leave")
            case _: UIEvent.DragOver  => dispatchDragEvent(elem, attrs.onDragOver, attrs.onDragOverEvt, "drag over")
            case _: UIEvent.Drop      =>
                DragCommands.current.use {
                    case Present(DragCommands.Dispatch(DragCommands.Payload.Event(value), decisions)) =>
                        invokeDecision(attrs.onDrop, decisions, "The drop handler failed.")
                            .andThen(invokeDecision(attrs.onDropEvt.map(_(value)), decisions, "The drop handler failed."))
                            .andThen(keepBubbling(elem, attrs.onDrop.nonEmpty || attrs.onDropEvt.nonEmpty))
                    case _ => true
                }
            case _: UIEvent.SortMove =>
                DragCommands.current.use {
                    case Present(DragCommands.Dispatch(DragCommands.Payload.Move(value), decisions)) =>
                        invokeDecision(attrs.onSortMove, decisions, "The sort handler failed.")
                            .andThen(invokeDecision(attrs.onSortMoveEvt.map(_(value)), decisions, "The sort handler failed."))
                            .andThen(keepBubbling(elem, attrs.onSortMove.nonEmpty || attrs.onSortMoveEvt.nonEmpty))
                    case _ => true
                }
            case _ => true
        end match
    end dispatchToElement

    private def invokeDrag(action: Maybe[Any < Async], typed: Maybe[Any < Async], label: String)(using Frame): Unit < Async =
        def safe(handler: Maybe[Any < Async]): Unit < Async =
            handler.fold((): Unit < Async) { effect =>
                Abort.recover[Throwable](
                    err => Log.error(s"Handler error during $label: ${err.getMessage}"),
                    panic => Log.error(s"Handler panic during $label: ${panic.getMessage}")
                )(effect.unit)
            }
        safe(action).andThen(safe(typed))
    end invokeDrag

    private def dispatchDragEvent(
        elem: Element,
        action: Maybe[Any < Async],
        typed: Maybe[Drag.Event => Any < Async],
        label: String
    )(using Frame): Boolean < Async =
        DragCommands.current.use {
            case Present(DragCommands.Dispatch(DragCommands.Payload.Event(value), _)) =>
                invokeDrag(action, typed.map(_(value)), label)
                    .andThen(keepBubbling(elem, action.nonEmpty || typed.nonEmpty))
            case _ => true
        }

    private def invokeDecision(
        handler: Maybe[Drag.Decision < Async],
        decisions: Maybe[AtomicRef[DragCommands.DecisionState]],
        failure: String
    )(using Frame): Unit < Async =
        handler.fold((): Unit < Async) { effect =>
            for
                result <- Abort.run[Any](effect)
                _      <- result match
                    case Result.Success(decision) => recordDecision(decisions, decision)
                    case Result.Failure(err)      =>
                        Log.error(s"Drag decision handler error: $err").andThen(failDecision(decisions, failure))
                    case Result.Panic(panic) =>
                        Log.error(s"Drag decision handler panic: ${panic.getMessage}").andThen(failDecision(decisions, failure))
            yield ()
        }

    private def recordDecision(ref: Maybe[AtomicRef[DragCommands.DecisionState]], decision: Drag.Decision)(using
        Frame
    ): Unit < Sync =
        ref.fold((): Unit < Sync)(_.getAndUpdate {
            case failed: DragCommands.DecisionState.Failed     => failed
            case rejected: DragCommands.DecisionState.Rejected => rejected
            case _                                             =>
                decision match
                    case Drag.Decision.Accept         => DragCommands.DecisionState.Accepted
                    case reject: Drag.Decision.Reject => DragCommands.DecisionState.Rejected(reject)
        }.unit)

    private def failDecision(ref: Maybe[AtomicRef[DragCommands.DecisionState]], reason: String)(using Frame): Unit < Sync =
        ref.fold((): Unit < Sync)(_.getAndUpdate(_ =>
            DragCommands.DecisionState.Failed(Drag.Decision.Reject(Drag.Rejection.Application(reason)))
        ).unit)

end EventDispatch
