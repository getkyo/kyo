package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import scala.annotation.tailrec

/** Transforms UI → Resolved. The only pipeline step that reads reactive state.
  *
  * Evaluates all Signal/SignalRef values, expands widgets into primitive elements, pre-composes event handlers for bubbling, merges
  * pseudo-state styles, and collects focusable element keys. Produces an immutable Resolved tree.
  */
object Lower:

    case class LowerResult(tree: Resolved, focusableIds: Chunk[WidgetKey])

    /** Context threaded through the recursive walk. Handler fields are updated when recursing into children — each child sees its parent's
      * composed handler chain. `state` and `focusables` are shared (not copied per-node).
      */
    private case class Ctx(
        state: ScreenState,
        focusables: ChunkBuilder[WidgetKey],
        parentOnClick: Unit < Async,
        parentOnKeyDown: UI.KeyEvent => Unit < Async,
        parentOnKeyUp: UI.KeyEvent => Unit < Async,
        parentOnScroll: Int => Unit < Async,
        parentOnSubmit: Unit < Async
    )

    private val noop: Unit < Async                   = ()
    private val noopKey: UI.KeyEvent => Unit < Async = _ => noop
    private val noopInt: Int => Unit < Async         = _ => noop

    def lower(ui: UI, state: ScreenState)(using AllowUnsafe, Frame): LowerResult < (Async & Scope) =
        // Phase 1: materialize all Signals into cached SignalRef.Unsafe (creates piping fibers)
        materialize(ui, state, Chunk.empty).andThen {
            // Phase 2: side-effectful lowering under AllowUnsafe
            // Reads cached SignalRef.Unsafe.get(), mutates ChunkBuilder
            val focusables = ChunkBuilder.init[WidgetKey]
            val ctx        = Ctx(state, focusables, noop, noopKey, noopKey, noopInt, noop)
            val tree       = walk(ui, Chunk.empty, ctx)
            LowerResult(tree, focusables.result())
        }

    // ---- Phase 1: Signal materialization ----

    /** Walk the UI tree and materialize every Signal into a cached SignalRef.Unsafe. Effectful — creates piping fibers via Signal.asRef on
      * first encounter. Cached refs are reused on subsequent frames.
      */
    private def materialize(ui: UI, state: ScreenState, dynamicPath: Chunk[String])(using Frame): Unit < (Async & Scope) =
        ui match
            case _: UI.internal.Text     => ()
            case _: UI.internal.Fragment => ()

            case r: UI.internal.Reactive =>
                val key = WidgetKey(r.frame, dynamicPath)
                materializeSignal[UI](r.signal, key, "reactive", state).map { ref =>
                    Sync.Unsafe.defer(materialize(ref.get(), state, dynamicPath))
                }

            case fe: UI.internal.Foreach[?] =>
                val key = WidgetKey(fe.frame, dynamicPath)
                materializeSignal[Chunk[Any]](fe.signal.asInstanceOf[Signal[Chunk[Any]]], key, "items", state).unit

            case elem: UI.Element =>
                val key = WidgetKey(elem.frame, dynamicPath)
                materializeElement(elem, key, state).andThen(
                    materializeChildSignals(elem.children, state, dynamicPath)
                )

    private def materializeElement(elem: UI.Element, key: WidgetKey, state: ScreenState)(using Frame): Unit < (Async & Scope) =
        val styleEffect: Unit < (Async & Scope) = elem.attrs.uiStyle match
            case _: Style       => ()
            case sig: Signal[?] => materializeSignal[Style](sig.asInstanceOf[Signal[Style]], key, "style", state).unit

        val hiddenEffect = materializeMaybeBoolSignal(elem.attrs.hidden, key, "hidden", state)

        val disabledEffect: Unit < (Async & Scope) = elem match
            case hd: UI.HasDisabled => materializeMaybeBoolSignal(hd.disabled, key, "disabled", state)
            case _                  => ()

        val checkedEffect: Unit < (Async & Scope) = elem match
            case bi: UI.BooleanInput => materializeMaybeBoolSignal(bi.checked, key, "checked", state)
            case _                   => ()

        val valueEffect: Unit < (Async & Scope) = elem match
            case ti: UI.TextInput   => materializeMaybeStringSignal(ti.value, key, "value", state)
            case pi: UI.PickerInput => materializeMaybeStringSignal(pi.value, key, "value", state)
            case _                  => ()

        styleEffect.andThen(hiddenEffect).andThen(disabledEffect).andThen(checkedEffect).andThen(valueEffect)
    end materializeElement

    private def materializeChildSignals(children: kyo.Span[UI], state: ScreenState, dynamicPath: Chunk[String])(using
        Frame
    ): Unit < (Async & Scope) =
        @tailrec def loop(i: Int, acc: Unit < (Async & Scope)): Unit < (Async & Scope) =
            if i >= children.size then acc
            else loop(i + 1, acc.andThen(materialize(children(i), state, dynamicPath)))
        loop(0, ())
    end materializeChildSignals

    private def materializeSignal[A](
        signal: Signal[A],
        key: WidgetKey,
        suffix: String,
        state: ScreenState
    )(using Frame): SignalRef.Unsafe[A] < (Async & Scope) =
        val cacheKey = key.child(suffix)
        Sync.Unsafe.defer {
            state.widgetState.get[SignalRef.Unsafe[A]](cacheKey)
        }.map {
            case Present(ref) => ref
            case _ =>
                signal.asRef.map { ref =>
                    Sync.Unsafe.defer {
                        val unsafe = ref.unsafe
                        discard(state.widgetState.getOrCreate(cacheKey, unsafe))
                        unsafe
                    }
                }
        }
    end materializeSignal

    private def materializeMaybeBoolSignal(
        value: Maybe[Boolean | Signal[Boolean]],
        key: WidgetKey,
        suffix: String,
        state: ScreenState
    )(using Frame): Unit < (Async & Scope) =
        if value.isEmpty then ()
        else
            value.get match
                case _: Boolean     => ()
                case sig: Signal[?] => materializeSignal[Boolean](sig.asInstanceOf[Signal[Boolean]], key, suffix, state).unit

    private def materializeMaybeStringSignal(
        value: Maybe[String | SignalRef[String]],
        key: WidgetKey,
        suffix: String,
        state: ScreenState
    )(using Frame): Unit < (Async & Scope) =
        if value.isEmpty then ()
        else
            value.get match
                case _: String         => ()
                case ref: SignalRef[?] =>
                    // Cache the ORIGINAL ref's unsafe version for two-way binding
                    Sync.Unsafe.defer {
                        val cacheKey = key.child(suffix)
                        discard(state.widgetState.getOrCreate(cacheKey, ref.unsafe))
                    }
                case sig: Signal[?] =>
                    materializeSignal[String](sig.asInstanceOf[Signal[String]], key, suffix, state).unit

    // ---- Phase 2: Core recursive walk (side-effectful under AllowUnsafe, no Async & Scope) ----

    private def walk(ui: UI, dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        ui match
            case UI.internal.Text(value) =>
                Resolved.Text(value)

            case r: UI.internal.Reactive =>
                val key = WidgetKey(r.frame, dynamicPath)
                val current = ctx.state.widgetState.get[SignalRef.Unsafe[UI]](key.child("reactive")) match
                    case Present(ref) => ref.get()
                    case _            => UI.empty
                walk(current, dynamicPath, ctx)

            case fe: UI.internal.Foreach[?] =>
                walkForeach(fe, dynamicPath, ctx)

            case UI.internal.Fragment(children) =>
                val resolved = walkChildren(children, dynamicPath, ctx)
                wrapChildren(resolved)

            case elem: UI.Element =>
                lowerElement(elem, dynamicPath, ctx)

    // ---- Foreach expansion ----

    private def walkForeach(fe: UI.internal.Foreach[?], dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        val key = WidgetKey(fe.frame, dynamicPath)
        val items = ctx.state.widgetState.get[SignalRef.Unsafe[Chunk[Any]]](key.child("items")) match
            case Present(ref) => ref.get()
            case _            => Chunk.empty
        val keyFn  = fe.key
        val render = fe.render.asInstanceOf[(Int, Any) => UI]

        @tailrec def eachItem(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
            if i >= items.size then acc
            else
                val item = items(i)
                val childPath = keyFn match
                    case Present(fn) => dynamicPath.append(fn.asInstanceOf[Any => String](item))
                    case Absent      => dynamicPath.append(i.toString)
                val childUI  = render(i, item)
                val resolved = walk(childUI, childPath, ctx)
                eachItem(i + 1, acc.append(resolved))
        val children = eachItem(0, Chunk.empty)
        wrapChildren(children)
    end walkForeach

    // ---- Element lowering ----

    private def lowerElement(elem: UI.Element, dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        val key = WidgetKey(elem.frame, dynamicPath)

        // Check hidden
        val hidden = readBooleanOrSignal(elem.attrs.hidden, key, "hidden", ctx.state)
        if hidden then Resolved.Text("")
        else
            // Dispatch to widget-specific or passthrough lowering
            elem match
                case ti: UI.TextInput    => lowerTextInput(ti, key, dynamicPath, ctx)
                case bi: UI.BooleanInput => lowerBooleanInput(bi, key, dynamicPath, ctx)
                case sel: UI.Select      => lowerSelect(sel, key, dynamicPath, ctx)
                case ri: UI.RangeInput   => lowerRangeInput(ri, key, dynamicPath, ctx)
                case _: UI.HiddenInput   => Resolved.Text("")
                case _: UI.Br            => Resolved.Text("\n")
                case img: UI.Img         => lowerImg(img, key, dynamicPath, ctx)
                case _                   => lowerPassthrough(elem, key, dynamicPath, ctx)
        end if
    end lowerElement

    // ---- Passthrough elements (Div, Span, H1-H6, P, etc.) ----

    private def lowerPassthrough(elem: UI.Element, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val style    = resolveStyle(elem, key, ctx.state)
        val disabled = isDisabled(elem, ctx.state)
        val tag      = resolveTag(elem)
        val handlers = buildHandlers(elem, key, disabled, ctx)

        // Register focusable — Focusable elements (Button, Anchor, etc.) are focusable by default
        val isFocusable =
            if disabled then false
            else
                elem match
                    case _: UI.Focusable =>
                        elem.attrs.tabIndex match
                            case Present(idx) => idx >= 0
                            case Absent       => true
                    case _ =>
                        elem.attrs.tabIndex.exists(_ >= 0)
        if isFocusable then ctx.focusables.addOne(key)

        // Recurse children with this node's composed handlers
        val childCtx = ctx.copy(
            parentOnClick = handlers.onClick,
            parentOnKeyDown = handlers.onKeyDown,
            parentOnKeyUp = handlers.onKeyUp,
            parentOnSubmit = elem match
                case form: UI.Form => form.onSubmit.getOrElse(ctx.parentOnSubmit)
                case _             => ctx.parentOnSubmit
        )

        val children = walkChildren(elem.children, dynamicPath, childCtx)
        Resolved.Node(tag, style, handlers, children)
    end lowerPassthrough

    // ---- TextInput widget expansion ----

    private def lowerTextInput(ti: UI.TextInput, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        val cursorPos = ctx.state.widgetState.getOrCreate(
            key.child("cursor"),
            SignalRef.Unsafe.init(0)
        )

        val currentValue = readStringOrRef(ti.value, key, ctx.state, "")
        val disabled     = readBooleanOrSignal(ti.disabled, key, "disabled", ctx.state)
        val readOnly     = ti.readOnly.getOrElse(false)

        // Display text — password masks with •
        val displayText = ti match
            case _: UI.Password => "•" * currentValue.length
            case _              => currentValue

        val cursor = math.min(cursorPos.get(), displayText.length)
        val before = displayText.substring(0, cursor)
        val after  = displayText.substring(cursor)

        // Build widget onKeyDown — captures refs for deferred mutation
        val isTextarea = ti.isInstanceOf[UI.Textarea]

        /** Insert a character at cursor, update refs, fire callbacks */
        def insertChar(ch: String): Unit < Async =
            val pos    = cursorPos.get()
            val newVal = currentValue.substring(0, pos) + ch + currentValue.substring(pos)
            writeStringRef(ti.value, key, ctx.state, newVal)
                .andThen(cursorPos.set(pos + 1))
                .andThen(fireStringCallback(ti.onInput, newVal))
                .andThen(fireStringCallback(ti.onChange, newVal))
        end insertChar

        val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
            if !readOnly && !disabled then
                ke.key match
                    case UI.Keyboard.Char(c) => insertChar(c.toString)
                    case UI.Keyboard.Space   => insertChar(" ")
                    case UI.Keyboard.Backspace =>
                        val pos = cursorPos.get()
                        if pos > 0 then
                            val newVal = currentValue.substring(0, pos - 1) + currentValue.substring(pos)
                            writeStringRef(ti.value, key, ctx.state, newVal)
                                .andThen(cursorPos.set(pos - 1))
                                .andThen(fireStringCallback(ti.onInput, newVal))
                                .andThen(fireStringCallback(ti.onChange, newVal))
                        else noop
                        end if
                    case UI.Keyboard.Delete =>
                        val pos = cursorPos.get()
                        if pos < currentValue.length then
                            val newVal = currentValue.substring(0, pos) + currentValue.substring(pos + 1)
                            writeStringRef(ti.value, key, ctx.state, newVal)
                                .andThen(fireStringCallback(ti.onInput, newVal))
                                .andThen(fireStringCallback(ti.onChange, newVal))
                        else noop
                        end if
                    case UI.Keyboard.ArrowLeft =>
                        val pos = cursorPos.get()
                        if pos > 0 then cursorPos.set(pos - 1) else noop
                    case UI.Keyboard.ArrowRight =>
                        val pos = cursorPos.get()
                        if pos < currentValue.length then cursorPos.set(pos + 1) else noop
                    case UI.Keyboard.Home => cursorPos.set(0)
                    case UI.Keyboard.End  => cursorPos.set(currentValue.length)
                    case UI.Keyboard.Enter =>
                        if isTextarea then insertChar("\n")
                        else ctx.parentOnSubmit // fire form onSubmit
                    case _ => noop
            else noop

        val style    = resolveStyle(ti, key, ctx.state)
        val userOnKD = ti.attrs.onKeyDown.getOrElse(noopKey)

        // Compose: widget → user → parent
        val composedOnKeyDown = composeKeyed(widgetOnKeyDown, composeKeyed(userOnKD, ctx.parentOnKeyDown))

        val handlers = Handlers(
            widgetKey = Maybe(key),
            id = ti.attrs.identifier,
            forId = Absent,
            tabIndex = ti.attrs.tabIndex,
            disabled = disabled,
            onClick = composeUnit(ti.attrs.onClick.getOrElse(noop), ctx.parentOnClick),
            onClickSelf = ti.attrs.onClickSelf.getOrElse(noop),
            onKeyDown = composedOnKeyDown,
            onKeyUp = composeKeyed(ti.attrs.onKeyUp.getOrElse(noopKey), ctx.parentOnKeyUp),
            onInput = ti.onInput.getOrElse(_ => noop),
            onChange = ti.onChange.map(f => (v: Any) => f(v.asInstanceOf[String])).getOrElse(_ => noop),
            onSubmit = noop,
            onFocus = ti.attrs.onFocus.getOrElse(noop),
            onBlur = ti.attrs.onBlur.getOrElse(noop),
            onScroll = ctx.parentOnScroll,
            colspan = 1,
            rowspan = 1,
            imageData = Absent
        )

        if !disabled then
            ti.attrs.tabIndex match
                case Present(idx) if idx >= 0 => ctx.focusables.addOne(key)
                case Absent                   => ctx.focusables.addOne(key) // text inputs are focusable by default
                case _                        =>
        end if

        Resolved.Node(
            ElemTag.Div,
            style,
            handlers,
            Chunk(
                Resolved.Text(before),
                Resolved.Cursor(cursor),
                Resolved.Text(after)
            )
        )
    end lowerTextInput

    // ---- BooleanInput (Checkbox / Radio) ----

    private def lowerBooleanInput(bi: UI.BooleanInput, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        // Internal checked state — persists across re-renders
        val checkedRef = ctx.state.widgetState.getOrCreate(
            key.child("_checked"),
            SignalRef.Unsafe.init(bi.checked match
                case Present(b: Boolean) => b
                case _                   => false)
        )
        // If checked is a Signal, use the materialized ref; otherwise use internal ref
        val checked = bi.checked match
            case Present(_: Signal[?]) => readBooleanOrSignal(bi.checked, key, "checked", ctx.state)
            case _                     => checkedRef.get()
        val disabled = readBooleanOrSignal(bi.disabled, key, "disabled", ctx.state)
        val style    = resolveStyle(bi, key, ctx.state)

        val display = bi match
            case _: UI.Checkbox => if checked then "[x]" else "[ ]"
            case _: UI.Radio    => if checked then "(•)" else "( )"
            case _              => ""

        val widgetOnClick: Unit < Async =
            if !disabled then
                Sync.Unsafe.defer {
                    val newVal = !checkedRef.get()
                    checkedRef.set(newVal)
                    bi.onChange match
                        case Present(f) => f(newVal)
                        case Absent     => noop
                }
            else noop

        val handlers = Handlers(
            widgetKey = Maybe(key),
            id = bi.attrs.identifier,
            forId = Absent,
            tabIndex = bi.attrs.tabIndex,
            disabled = disabled,
            onClick = composeUnit(widgetOnClick, composeUnit(bi.attrs.onClick.getOrElse(noop), ctx.parentOnClick)),
            onClickSelf = bi.attrs.onClickSelf.getOrElse(noop),
            onKeyDown = composeKeyed(bi.attrs.onKeyDown.getOrElse(noopKey), ctx.parentOnKeyDown),
            onKeyUp = composeKeyed(bi.attrs.onKeyUp.getOrElse(noopKey), ctx.parentOnKeyUp),
            onInput = _ => noop,
            onChange = _ => noop,
            onSubmit = noop,
            onFocus = bi.attrs.onFocus.getOrElse(noop),
            onBlur = bi.attrs.onBlur.getOrElse(noop),
            onScroll = ctx.parentOnScroll,
            colspan = 1,
            rowspan = 1,
            imageData = Absent
        )

        if !disabled then
            bi.attrs.tabIndex match
                case Present(idx) if idx >= 0 => ctx.focusables.addOne(key)
                case Absent                   => ctx.focusables.addOne(key)
                case _                        =>
        end if

        Resolved.Node(ElemTag.Span, style, handlers, Chunk(Resolved.Text(display)))
    end lowerBooleanInput

    // ---- Select dropdown ----

    private def lowerSelect(sel: UI.Select, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        val expanded  = ctx.state.widgetState.getOrCreate(key.child("expanded"), SignalRef.Unsafe.init(false))
        val highlight = ctx.state.widgetState.getOrCreate(key.child("highlight"), SignalRef.Unsafe.init(0))

        val currentValue = readStringOrRef(sel.value, key, ctx.state, "")
        val disabled     = readBooleanOrSignal(sel.disabled, key, "disabled", ctx.state)
        val style        = resolveStyle(sel, key, ctx.state)
        val isExpanded   = expanded.get()

        // Collect option texts/values
        val options = collectOptions(sel.children, ctx)

        val selectedText = options.find(_._1 == currentValue).map(_._2).getOrElse(currentValue)

        // Toggle on self-click
        val toggleClick: Unit < Async =
            if !disabled then expanded.set(!isExpanded)
            else noop

        val handlers = Handlers(
            widgetKey = Maybe(key),
            id = sel.attrs.identifier,
            forId = Absent,
            tabIndex = sel.attrs.tabIndex,
            disabled = disabled,
            onClick = composeUnit(sel.attrs.onClick.getOrElse(noop), ctx.parentOnClick),
            onClickSelf = toggleClick,
            onKeyDown = composeKeyed(sel.attrs.onKeyDown.getOrElse(noopKey), ctx.parentOnKeyDown),
            onKeyUp = composeKeyed(sel.attrs.onKeyUp.getOrElse(noopKey), ctx.parentOnKeyUp),
            onInput = _ => noop,
            onChange = _ => noop,
            onSubmit = noop,
            onFocus = sel.attrs.onFocus.getOrElse(noop),
            onBlur = sel.attrs.onBlur.getOrElse(noop),
            onScroll = ctx.parentOnScroll,
            colspan = 1,
            rowspan = 1,
            imageData = Absent
        )

        if !disabled then
            sel.attrs.tabIndex match
                case Present(idx) if idx >= 0 => ctx.focusables.addOne(key)
                case Absent                   => ctx.focusables.addOne(key)
                case _                        =>
        end if

        val displayChildren = Chunk(Resolved.Text(selectedText), Resolved.Text(" ▼"))

        if isExpanded then
            // Build popup with option nodes
            val optionNodes = buildOptionNodes(options, sel, key, expanded, ctx)
            val popup       = Resolved.Node(ElemTag.Popup, Style.empty, Handlers.empty, optionNodes)
            Resolved.Node(ElemTag.Div, style, handlers, displayChildren.append(popup))
        else
            Resolved.Node(ElemTag.Div, style, handlers, displayChildren)
        end if
    end lowerSelect

    // ---- RangeInput ----

    private def lowerRangeInput(ri: UI.RangeInput, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val disabled = readBooleanOrSignal(ri.disabled, key, "disabled", ctx.state)
        val style    = resolveStyle(ri, key, ctx.state)
        val minVal   = ri.min.getOrElse(0.0)
        val maxVal   = ri.max.getOrElse(100.0)
        val step     = ri.step.getOrElse(1.0)

        val currentValue = ri.value match
            case Present(v: Double)         => v
            case Present(ref: SignalRef[?]) => ref.asInstanceOf[SignalRef.Unsafe[Double]].get()
            case _                          => minVal

        val display = f"$currentValue%.1f"

        val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
            if !disabled then
                val delta = ke.key match
                    case UI.Keyboard.ArrowRight | UI.Keyboard.ArrowUp  => step
                    case UI.Keyboard.ArrowLeft | UI.Keyboard.ArrowDown => -step
                    case _                                             => 0.0
                if delta != 0.0 then
                    val newVal = math.max(minVal, math.min(maxVal, currentValue + delta))
                    ri.value match
                        case Present(ref: SignalRef[?]) => ref.asInstanceOf[SignalRef.Unsafe[Double]].set(newVal)
                        case _                          =>
                    ri.onChange match
                        case Present(f) => f(newVal)
                        case Absent     => noop
                else noop
                end if
            else noop

        val handlers = Handlers(
            widgetKey = Maybe(key),
            id = ri.attrs.identifier,
            forId = Absent,
            tabIndex = ri.attrs.tabIndex,
            disabled = disabled,
            onClick = composeUnit(ri.attrs.onClick.getOrElse(noop), ctx.parentOnClick),
            onClickSelf = ri.attrs.onClickSelf.getOrElse(noop),
            onKeyDown = composeKeyed(widgetOnKeyDown, composeKeyed(ri.attrs.onKeyDown.getOrElse(noopKey), ctx.parentOnKeyDown)),
            onKeyUp = composeKeyed(ri.attrs.onKeyUp.getOrElse(noopKey), ctx.parentOnKeyUp),
            onInput = _ => noop,
            onChange = _ => noop,
            onSubmit = noop,
            onFocus = ri.attrs.onFocus.getOrElse(noop),
            onBlur = ri.attrs.onBlur.getOrElse(noop),
            onScroll = ctx.parentOnScroll,
            colspan = 1,
            rowspan = 1,
            imageData = Absent
        )

        if !disabled then
            ri.attrs.tabIndex match
                case Present(idx) if idx >= 0 => ctx.focusables.addOne(key)
                case Absent                   => ctx.focusables.addOne(key)
                case _                        =>
        end if

        Resolved.Node(ElemTag.Div, style, handlers, Chunk(Resolved.Text(display)))
    end lowerRangeInput

    // ---- Img ----

    private def lowerImg(img: UI.Img, key: WidgetKey, dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Resolved =
        val style = resolveStyle(img, key, ctx.state)
        val alt   = img.alt.getOrElse("")
        val handlers = Handlers.empty.copy(
            widgetKey = Maybe(key),
            id = img.attrs.identifier
        )
        Resolved.Node(ElemTag.Div, style, handlers, Chunk(Resolved.Text(alt)))
    end lowerImg

    // ---- Style resolution ----

    private def resolveStyle(elem: UI.Element, key: WidgetKey, state: ScreenState)(using AllowUnsafe, Frame): Style =
        val userStyle = elem.attrs.uiStyle match
            case s: Style     => s
            case _: Signal[?] =>
                // Already materialized in phase 1
                state.widgetState.get[SignalRef.Unsafe[Style]](key.child("style"))
                    .map(_.get())
                    .getOrElse(Style.empty)

        val theme   = themeStyle(elem, state.theme)
        val base    = theme ++ userStyle
        val focused = state.focusedId.get()
        val hovered = state.hoveredId.get()
        val active  = state.activeId.get()

        mergePseudoStates(base, key, focused, hovered, active, isDisabled(elem, state))
    end resolveStyle

    private def mergePseudoStates(
        base: Style,
        key: WidgetKey,
        focused: Maybe[WidgetKey],
        hovered: Maybe[WidgetKey],
        active: Maybe[WidgetKey],
        disabled: Boolean
    ): Style =
        var result = base
        if focused.contains(key) then
            result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.FocusProp])
        if hovered.contains(key) then
            result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.HoverProp])
        if active.contains(key) then
            result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.ActiveProp])
        if disabled then
            result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.DisabledProp])
        result
    end mergePseudoStates

    private def extractPseudo(style: Style, pred: Style.Prop => Boolean): Style =
        @tailrec def loop(i: Int, acc: Style): Style =
            if i >= style.props.size then acc
            else
                val prop = style.props(i)
                val extracted = prop match
                    case Style.Prop.HoverProp(s) if pred(prop)    => s
                    case Style.Prop.FocusProp(s) if pred(prop)    => s
                    case Style.Prop.ActiveProp(s) if pred(prop)   => s
                    case Style.Prop.DisabledProp(s) if pred(prop) => s
                    case _                                        => Style.empty
                loop(i + 1, acc ++ extracted)
        loop(0, Style.empty)
    end extractPseudo

    // ---- Theme styles ----

    private def themeStyle(elem: UI.Element, theme: ResolvedTheme): Style =
        theme.variant match
            case Theme.Plain => Style.empty
            case Theme.Minimal =>
                elem match
                    case _: UI.H1 => Style.bold.padding(1.px, 0.px)
                    case _: UI.H2 => Style.bold
                    case _: UI.Hr => Style.border(1.px, theme.borderColor).width(100.pct)
                    case _        => Style.empty
            case Theme.Default =>
                elem match
                    case _: UI.H1     => Style.bold.padding(1.px, 0.px)
                    case _: UI.H2     => Style.bold
                    case _: UI.Button => Style.border(1.px, theme.borderColor).padding(0.px, 1.px)
                    case _: UI.Hr     => Style.border(1.px, theme.borderColor).width(100.pct)
                    case _            => Style.empty

    // ---- Tag resolution ----

    private def resolveTag(elem: UI.Element): ElemTag = elem match
        case _: UI.Table => ElemTag.Table
        case _: UI.Span | _: UI.Nav | _: UI.Li | _: UI.Tr | _: UI.Button |
            _: UI.Anchor => ElemTag.Span
        case _ => ElemTag.Div

    // ---- Handler building ----

    private def buildHandlers(elem: UI.Element, key: WidgetKey, disabled: Boolean, ctx: Ctx)(using AllowUnsafe, Frame): Handlers =
        val attrs = elem.attrs
        Handlers(
            widgetKey = Maybe(key),
            id = attrs.identifier,
            forId = elem match
                case lbl: UI.Label => lbl.forId
                case _             => Absent
            ,
            tabIndex = attrs.tabIndex,
            disabled = disabled,
            onClick = composeUnit(attrs.onClick.getOrElse(noop), ctx.parentOnClick),
            onClickSelf = attrs.onClickSelf.getOrElse(noop),
            onKeyDown = composeKeyed(attrs.onKeyDown.getOrElse(noopKey), ctx.parentOnKeyDown),
            onKeyUp = composeKeyed(attrs.onKeyUp.getOrElse(noopKey), ctx.parentOnKeyUp),
            onInput = _ => noop,
            onChange = _ => noop,
            onSubmit = noop,
            onFocus = attrs.onFocus.getOrElse(noop),
            onBlur = attrs.onBlur.getOrElse(noop),
            onScroll = ctx.parentOnScroll,
            colspan = elem match
                case td: UI.Td => td.colspan.getOrElse(1)
                case th: UI.Th => th.colspan.getOrElse(1)
                case _         => 1
            ,
            rowspan = elem match
                case td: UI.Td => td.rowspan.getOrElse(1)
                case th: UI.Th => th.rowspan.getOrElse(1)
                case _         => 1
            ,
            imageData = Absent
        )
    end buildHandlers

    // ---- Handler composition ----

    /** Compose two Unit < Async: child fires first, then parent. */
    private def composeUnit(child: Unit < Async, parent: Unit < Async)(using Frame): Unit < Async =
        child.andThen(parent)

    /** Compose two keyed handlers: child fires first, then parent (same event). */
    private def composeKeyed(
        child: UI.KeyEvent => Unit < Async,
        parent: UI.KeyEvent => Unit < Async
    )(using Frame): UI.KeyEvent => Unit < Async =
        e => child(e).andThen(parent(e))

    // ---- Child walking ----

    private def walkChildren(children: kyo.Span[UI], dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Chunk[Resolved] =
        @tailrec def loop(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
            if i >= children.size then acc
            else loop(i + 1, acc.append(walk(children(i), dynamicPath, ctx)))
        loop(0, Chunk.empty)
    end walkChildren

    /** Wrap multiple children — single child unwrapped, multiple wrapped in a transparent Div. */
    private def wrapChildren(children: Chunk[Resolved]): Resolved =
        if children.size == 1 then children(0)
        else Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, children)

    // ---- Select helpers ----

    private def collectOptions(children: kyo.Span[UI], ctx: Ctx)(using AllowUnsafe, Frame): Chunk[(String, String)] =
        @tailrec def loop(i: Int, acc: Chunk[(String, String)]): Chunk[(String, String)] =
            if i >= children.size then acc
            else
                children(i) match
                    case opt: UI.Opt =>
                        val value = opt.value.getOrElse("")
                        val text  = optionText(opt.children)
                        loop(i + 1, acc.append((value, text)))
                    case _ =>
                        loop(i + 1, acc)
        loop(0, Chunk.empty)
    end collectOptions

    private def optionText(children: kyo.Span[UI]): String =
        @tailrec def loop(i: Int, acc: String): String =
            if i >= children.size then acc
            else
                children(i) match
                    case UI.internal.Text(v) => loop(i + 1, acc + v)
                    case _                   => loop(i + 1, acc)
        loop(0, "")
    end optionText

    private def buildOptionNodes(
        options: Chunk[(String, String)],
        sel: UI.Select,
        key: WidgetKey,
        expanded: SignalRef.Unsafe[Boolean],
        ctx: Ctx
    )(using AllowUnsafe, Frame): Chunk[Resolved] =
        @tailrec def loop(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
            if i >= options.size then acc
            else
                val (value, text) = options(i)
                val optClick: Unit < Async =
                    writeStringRef(sel.value, key, ctx.state, value)
                        .andThen(expanded.set(false))
                        .andThen(sel.onChange match
                            case Present(f) => f(value)
                            case Absent     => noop)
                val optHandlers = Handlers.empty.copy(
                    onClick = composeUnit(optClick, ctx.parentOnClick)
                )
                loop(i + 1, acc.append(Resolved.Node(ElemTag.Div, Style.empty, optHandlers, Chunk(Resolved.Text(text)))))
        loop(0, Chunk.empty)
    end buildOptionNodes

    // ---- Value reading helpers ----

    private def readBooleanOrSignal(value: Maybe[Boolean | Signal[Boolean]], key: WidgetKey, suffix: String, state: ScreenState)(using
        AllowUnsafe
    ): Boolean =
        if value.isEmpty then false
        else
            value.get match
                case b: Boolean => b
                case _: Signal[?] =>
                    state.widgetState.get[SignalRef.Unsafe[Boolean]](key.child(suffix))
                        .map(_.get())
                        .getOrElse(false)

    private def readStringOrRef(value: Maybe[String | SignalRef[String]], key: WidgetKey, state: ScreenState, default: String)(using
        AllowUnsafe
    ): String =
        if value.isEmpty then default
        else
            value.get match
                case s: String => s
                case _: SignalRef[?] =>
                    state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")) match
                        case Present(ref) => ref.get()
                        case _            => default

    private def writeStringRef(value: Maybe[String | SignalRef[String]], key: WidgetKey, state: ScreenState, newVal: String)(using
        AllowUnsafe
    ): Unit < Async =
        if value.isEmpty then noop
        else
            value.get match
                case _: SignalRef[?] =>
                    state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")) match
                        case Present(ref) =>
                            ref.set(newVal)
                            noop
                        case _ => noop
                case _ => noop

    private def fireStringCallback(cb: Maybe[String => Unit < Async], value: String): Unit < Async =
        cb match
            case Present(f) => f(value)
            case Absent     => noop

    private def isDisabled(elem: UI.Element, state: ScreenState)(using AllowUnsafe): Boolean =
        elem match
            case hd: UI.HasDisabled =>
                val key = WidgetKey(elem.frame, Chunk.empty)
                readBooleanOrSignal(hd.disabled, key, "disabled", state)
            case _ => false

end Lower
