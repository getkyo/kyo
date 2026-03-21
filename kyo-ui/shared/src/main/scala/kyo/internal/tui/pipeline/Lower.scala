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

    /** Context threaded through the recursive walk. `parentHandlers` carries the accumulated parent handler chain for bubbling.
      * `parentOnSubmit` stays separate because it's woven specially into TextInput onKeyDown (not composed via the normal bubbling
      * pattern). `state` and `focusables` are shared (not copied per-node).
      */
    private case class Ctx(
        state: ScreenState,
        focusables: ChunkBuilder[WidgetKey],
        parentHandlers: Handlers,
        parentOnSubmit: () => Unit < Async
    )

    private val noopSubmit: () => Unit < Async = () => ()

    def lower(ui: UI, state: ScreenState)(using Frame): LowerResult < (Async & Scope) =
        // Phase 1: materialize all Signals into cached SignalRef.Unsafe (creates piping fibers)
        materialize(ui, state, Chunk.empty).andThen {
            // Phase 2: side-effectful lowering under AllowUnsafe
            Sync.Unsafe.defer {
                val focusables = ChunkBuilder.init[WidgetKey]
                val ctx        = Ctx(state, focusables, Handlers.empty, noopSubmit)
                val tree       = walk(ui, Chunk.empty, ctx)
                LowerResult(tree, focusables.result())
            }
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
            case ri: UI.RangeInput  => materializeMaybeDoubleSignal(ri.value, key, "value", state)
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
                case _: Boolean        => ()
                case ref: SignalRef[?] =>
                    // Cache the ORIGINAL ref directly for two-way binding (same as string refs)
                    Sync.Unsafe.defer {
                        val cacheKey = key.child(suffix)
                        discard(state.widgetState.getOrCreate(cacheKey, ref.unsafe))
                    }
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

    private def materializeMaybeDoubleSignal(
        value: Maybe[Double | SignalRef[Double]],
        key: WidgetKey,
        suffix: String,
        state: ScreenState
    )(using Frame): Unit < (Async & Scope) =
        if value.isEmpty then ()
        else
            value.get match
                case _: Double => ()
                case ref: SignalRef[?] =>
                    Sync.Unsafe.defer {
                        val cacheKey = key.child(suffix)
                        discard(state.widgetState.getOrCreate(cacheKey, ref.unsafe))
                    }

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
        if hidden then Resolved.Empty
        else
            // Compute interaction state once — used by both style merging and widget expansion
            val ws = WidgetState(
                focused = ctx.state.focusedId.get().contains(key),
                hovered = ctx.state.hoveredId.get().contains(key),
                active = ctx.state.activeId.get().contains(key),
                disabled = isDisabled(elem, ctx.state)
            )
            // Dispatch to widget-specific or passthrough lowering
            elem match
                case ti: UI.TextInput    => lowerTextInput(ti, key, ws, dynamicPath, ctx)
                case bi: UI.BooleanInput => lowerBooleanInput(bi, key, ws, dynamicPath, ctx)
                case sel: UI.Select      => lowerSelect(sel, key, ws, dynamicPath, ctx)
                case ri: UI.RangeInput   => lowerRangeInput(ri, key, ws, dynamicPath, ctx)
                case _: UI.HiddenInput   => Resolved.Text("")
                case _: UI.Br            => Resolved.Break
                case hr: UI.Hr           => Resolved.Rule(resolveStyle(hr, key, ws, ctx.state))
                case img: UI.Img         => lowerImg(img, key, ws, dynamicPath, ctx)
                case _                   => lowerPassthrough(elem, key, ws, dynamicPath, ctx)
            end match
        end if
    end lowerElement

    // ---- Passthrough elements (Div, Span, H1-H6, P, etc.) ----

    private def lowerPassthrough(elem: UI.Element, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val style        = resolveStyle(elem, key, ws, ctx.state)
        val disabled     = ws.disabled
        val tag          = resolveTag(elem)
        val baseHandlers = buildHandlers(elem, key, disabled, ctx)
        // Button inside form: clicking fires form onSubmit (like HTML submit button)
        val handlers = elem match
            case _: UI.Button if ctx.parentOnSubmit ne noopSubmit =>
                baseHandlers.composeOnClick(ctx.parentOnSubmit())
            case _ => baseHandlers

        registerFocusable(elem, key, disabled, ctx)

        // Recurse children with this node's composed handlers
        val childCtx = ctx.copy(
            parentHandlers = handlers,
            parentOnSubmit = elem match
                case form: UI.Form => () => form.onSubmit.getOrElse(())
                case _             => ctx.parentOnSubmit
        )

        val children = walkChildren(elem.children, dynamicPath, childCtx)
        Resolved.Node(tag, style, handlers, children)
    end lowerPassthrough

    // ---- TextInput widget expansion ----

    private def lowerTextInput(ti: UI.TextInput, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val currentValue = readStringOrRef(ti.value, key, ctx.state, "")

        // Initialize cursor at end of current value, not at 0
        val cursorPos = ctx.state.widgetState.getOrCreate(
            key.child("cursor"),
            SignalRef.Unsafe.init(currentValue.length)
        )
        val disabled = ws.disabled
        val readOnly = ti.readOnly.getOrElse(false)

        // Display text:
        // - Focused + empty: show nothing (placeholder hidden while editing)
        // - Unfocused + empty: show placeholder (dimmed in style below)
        // - Has value + password: show dots
        // - Has value: show value
        val isShowingPlaceholder = currentValue.isEmpty && !ws.focused && ti.placeholder.nonEmpty
        val displayText =
            if currentValue.isEmpty then
                if ws.focused then ""
                else ti.placeholder.getOrElse("")
            else
                ti match
                    case _: UI.Password => "•" * currentValue.length
                    case _              => currentValue

        val cursor = math.min(cursorPos.get(), displayText.length)

        val isTextarea = ti.isInstanceOf[UI.Textarea]

        // Safe refs captured at construction time (Rule 2 context) for handler computation chains
        val cursorRef = cursorPos.safe
        val valueRef: Maybe[SignalRef[String]] = ti.value match
            case Present(_: SignalRef[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")).map(_.safe)
            case _ => Absent
        val disabledRef: Maybe[SignalRef[Boolean]] = ti.disabled match
            case Present(_: Signal[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[Boolean]](key.child("disabled")).map(_.safe)
            case _ => Absent

        // Handler: pure computation code — no Sync.Unsafe.defer, only safe refs
        // Cursor movement is allowed in readonly mode; only editing is blocked.
        val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
            // Read disabled state (safe API returns < IO)
            val disabledCheck = disabledRef match
                case Present(ref) => ref.get
                case _            => Kyo.lift(ti.disabled.map { case b: Boolean => b; case _ => false }.getOrElse(false))
            disabledCheck.map { dis =>
                if dis then ()
                else
                    // Cursor movement — allowed even in readonly
                    ke.key match
                        case UI.Keyboard.ArrowLeft =>
                            cursorRef.get.map { pos =>
                                if pos > 0 then cursorRef.set(pos - 1) else ()
                            }
                        case UI.Keyboard.ArrowRight =>
                            val valLen = valueRef match
                                case Present(ref) => ref.get.map(_.length)
                                case _            => Kyo.lift(currentValue.length)
                            valLen.map { len =>
                                cursorRef.get.map { pos =>
                                    if pos < len then cursorRef.set(pos + 1) else ()
                                }
                            }
                        case UI.Keyboard.Home => cursorRef.set(0)
                        case UI.Keyboard.End =>
                            val valLen = valueRef match
                                case Present(ref) => ref.get.map(_.length)
                                case _            => Kyo.lift(currentValue.length)
                            valLen.map(len => cursorRef.set(len))
                        // Editing — blocked by readonly
                        case _ =>
                            val ro = ti.readOnly.getOrElse(false)
                            if ro then ()
                            else
                                val valueRead = valueRef match
                                    case Present(ref) => ref.get
                                    case _            => Kyo.lift(ti.value.map { case s: String => s; case _ => "" }.getOrElse(""))
                                valueRead.map { curValue =>
                                    def writeValue(newVal: String): Unit < Async =
                                        valueRef match
                                            case Present(ref) => ref.set(newVal)
                                            case _            => ()
                                    def insert(ch: String): Unit < Async =
                                        cursorRef.get.map { pos =>
                                            val safePos = math.min(pos, curValue.length)
                                            val newVal  = curValue.substring(0, safePos) + ch + curValue.substring(safePos)
                                            writeValue(newVal)
                                                .andThen(cursorRef.set(safePos + 1))
                                                .andThen(fireStringCallback(ti.onInput, newVal))
                                                .andThen(fireStringCallback(ti.onChange, newVal))
                                        }
                                    ke.key match
                                        case UI.Keyboard.Char(c) => insert(c.toString)
                                        case UI.Keyboard.Space   => insert(" ")
                                        case UI.Keyboard.Backspace =>
                                            cursorRef.get.map { pos =>
                                                val safePos = math.min(pos, curValue.length)
                                                if safePos > 0 then
                                                    val newVal = curValue.substring(0, safePos - 1) + curValue.substring(safePos)
                                                    writeValue(newVal)
                                                        .andThen(cursorRef.set(safePos - 1))
                                                        .andThen(fireStringCallback(ti.onInput, newVal))
                                                        .andThen(fireStringCallback(ti.onChange, newVal))
                                                else ()
                                                end if
                                            }
                                        case UI.Keyboard.Delete =>
                                            cursorRef.get.map { pos =>
                                                val safePos = math.min(pos, curValue.length)
                                                if safePos < curValue.length then
                                                    val newVal = curValue.substring(0, safePos) + curValue.substring(safePos + 1)
                                                    writeValue(newVal)
                                                        .andThen(fireStringCallback(ti.onInput, newVal))
                                                        .andThen(fireStringCallback(ti.onChange, newVal))
                                                else ()
                                                end if
                                            }
                                        case UI.Keyboard.Enter =>
                                            if isTextarea then insert("\n")
                                            else ctx.parentOnSubmit()
                                        case _ => ()
                                    end match
                                }
                            end if
                    end match
            }

        val style = resolveStyle(ti, key, ws, ctx.state)

        // Build handlers: start from parentHandlers (carries parent bubbling chain),
        // then compose widget's own onKeyDown, user's onKeyDown, and other handlers
        val handlers = ctx.parentHandlers
            .withWidgetKey(key)
            .withId(ti.attrs.identifier)
            .withTabIndex(ti.attrs.tabIndex)
            .withDisabled(disabled)
            .composeOnClick(ti.attrs.onClick.getOrElse(()))
            .withOnClickSelf(ti.attrs.onClickSelf.getOrElse(()))
            .composeOnKeyDown(widgetOnKeyDown)
            .composeOnKeyDown(ti.attrs.onKeyDown.getOrElse(_ => ()))
            .composeOnKeyUp(ti.attrs.onKeyUp.getOrElse(_ => ()))
            .withOnInput(ti.onInput.getOrElse(_ => ()))
            .withOnChange(ti.onChange.map(f => (v: Any) => f(v.asInstanceOf[String])).getOrElse(_ => ()))
            .withOnFocus(ti.attrs.onFocus.getOrElse(()))
            .withOnBlur(ti.attrs.onBlur.getOrElse(()))

        registerFocusable(ti, key, disabled, ctx)

        // Placeholder gets dimmed color on the input div — scoped to this div only, not siblings
        val placeholderDim = if isShowingPlaceholder then Style.color(Style.Color.rgb(128, 128, 128)) else Style.empty
        // Single-line inputs: noWrap + overflow hidden (like web <input>)
        // Textarea: wraps normally
        val wrapStyle =
            if isTextarea then Style.empty
            else Style.textWrap(Style.TextWrap.noWrap).overflow(Style.Overflow.hidden)
        // No Style.row — single text child fills parent width in column layout
        val effectiveStyle = style ++ wrapStyle ++ placeholderDim

        // Horizontal scroll for single-line inputs: emit visible window of text
        val (visibleText, scrollOffset) =
            if isTextarea || displayText.isEmpty then (displayText, 0)
            else
                // Read previous frame's visible width from layout tree
                val prevWidth = ctx.state.prevLayout.flatMap { layout =>
                    Dispatch.findByKey(layout.base, key).map(_.content.w)
                }.getOrElse(Int.MaxValue)

                // Read/update scroll offset to keep cursor in visible window
                val scrollRef = ctx.state.widgetState.getOrCreate(
                    key.child("scrollX"),
                    SignalRef.Unsafe.init(0)
                )
                val prevOffset = scrollRef.get()
                // Adjust scroll to keep cursor visible
                val adjusted =
                    if !ws.focused then 0
                    else if cursor >= prevOffset + prevWidth then cursor - prevWidth + 1
                    else if cursor < prevOffset then cursor
                    else prevOffset
                val offset = math.max(0, math.min(adjusted, math.max(0, displayText.length - prevWidth)))
                scrollRef.set(offset)
                // Extract visible window
                val endIdx = math.min(offset + prevWidth, displayText.length)
                (displayText.substring(offset, endIdx), offset)
            end if
        end val

        // Cursor position is relative to visible window
        val adjustedHandlers =
            if ws.focused then
                val visibleCursorCol = cursor - scrollOffset
                val beforeCursor     = visibleText.substring(0, math.max(0, math.min(visibleCursorCol, visibleText.length)))
                val lines            = beforeCursor.split("\n", -1)
                val cursorRow        = lines.length - 1
                val cursorCol        = lines.last.length
                handlers.withCursorPosition(Maybe((cursorCol, cursorRow)))
            else handlers.withCursorPosition(Absent)

        Resolved.Node(
            ElemTag.Div,
            effectiveStyle,
            adjustedHandlers,
            Chunk(Resolved.Text(visibleText))
        )
    end lowerTextInput

    // ---- BooleanInput (Checkbox / Radio) ----

    private def lowerBooleanInput(bi: UI.BooleanInput, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        // Internal checked state — persists across re-renders
        val internalRef = ctx.state.widgetState.getOrCreate(
            key.child("_checked"),
            SignalRef.Unsafe.init(bi.checked match
                case Present(b: Boolean) => b
                case _                   => false)
        )
        // Determine the effective ref: user's SignalRef for two-way binding, internal ref otherwise
        val effectiveRef = bi.checked match
            case Present(_: SignalRef[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[Boolean]](key.child("checked"))
                    .getOrElse(internalRef)
            case _ => internalRef
        val checked = bi.checked match
            case Present(_: Signal[?]) => readBooleanOrSignal(bi.checked, key, "checked", ctx.state)
            case _                     => effectiveRef.get()
        val disabled = ws.disabled
        val style    = resolveStyle(bi, key, ws, ctx.state)

        val display = bi match
            case _: UI.Checkbox => if checked then "[x]" else "[ ]"
            case _: UI.Radio    => if checked then "(•)" else "( )"

        val checkedSafe = effectiveRef.safe
        val widgetOnClick: Unit < Async =
            checkedSafe.get.map { curr =>
                val newVal = !curr
                checkedSafe.set(newVal).andThen(
                    bi.onChange match
                        case Present(f) => f(newVal)
                        case Absent     => ()
                )
            }

        val handlers = ctx.parentHandlers
            .withWidgetKey(key)
            .withId(bi.attrs.identifier)
            .withTabIndex(bi.attrs.tabIndex)
            .withDisabled(disabled)
            .composeOnClick(widgetOnClick)
            .composeOnClick(bi.attrs.onClick.getOrElse(()))
            .withOnClickSelf(bi.attrs.onClickSelf.getOrElse(()))
            .composeOnKeyDown(bi.attrs.onKeyDown.getOrElse(_ => ()))
            .composeOnKeyUp(bi.attrs.onKeyUp.getOrElse(_ => ()))
            .withOnFocus(bi.attrs.onFocus.getOrElse(()))
            .withOnBlur(bi.attrs.onBlur.getOrElse(()))

        if !disabled then
            registerFocusable(bi, key, disabled, ctx)

        Resolved.Node(ElemTag.Span, style, handlers, Chunk(Resolved.Text(display)))
    end lowerBooleanInput

    // ---- Select dropdown ----

    private def lowerSelect(sel: UI.Select, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val expanded  = ctx.state.widgetState.getOrCreate(key.child("expanded"), SignalRef.Unsafe.init(false))
        val highlight = ctx.state.widgetState.getOrCreate(key.child("highlight"), SignalRef.Unsafe.init(0))

        val currentValue = readStringOrRef(sel.value, key, ctx.state, "")
        val disabled     = ws.disabled
        val style        = resolveStyle(sel, key, ws, ctx.state)
        val isExpanded   = expanded.get()

        // Collect option texts/values
        val options = collectOptions(sel.children, ctx)

        val selectedText = options.find(_._1 == currentValue).map(_._2).getOrElse(currentValue)

        // Safe refs for handler computations
        val expandedRef  = expanded.safe
        val highlightRef = highlight.safe
        val selectValueRef: Maybe[SignalRef[String]] = sel.value match
            case Present(_: SignalRef[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")).map(_.safe)
            case _ => Absent

        val toggleClick: Unit < Async =
            expandedRef.get.map(curr => expandedRef.set(!curr))

        val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
            ke.key match
                case UI.Keyboard.Escape =>
                    expandedRef.set(false)
                case UI.Keyboard.ArrowDown =>
                    highlightRef.get.map(h => highlightRef.set(math.min(h + 1, options.size - 1)))
                case UI.Keyboard.ArrowUp =>
                    highlightRef.get.map(h => highlightRef.set(math.max(h - 1, 0)))
                case UI.Keyboard.Enter =>
                    highlightRef.get.map { idx =>
                        if idx >= 0 && idx < options.size then
                            val (value, _) = options(idx)
                            val writeEffect: Unit < Async = selectValueRef match
                                case Present(ref) => ref.set(value)
                                case _            => ()
                            writeEffect
                                .andThen(expandedRef.set(false))
                                .andThen(sel.onChange match
                                    case Present(f) => f(value)
                                    case _          => ())
                        else ()
                    }
                case _ => ()

        val handlers = ctx.parentHandlers
            .withWidgetKey(key)
            .withId(sel.attrs.identifier)
            .withTabIndex(sel.attrs.tabIndex)
            .withDisabled(disabled)
            .composeOnClick(sel.attrs.onClick.getOrElse(()))
            .withOnClickSelf(toggleClick)
            .composeOnKeyDown(widgetOnKeyDown)
            .composeOnKeyDown(sel.attrs.onKeyDown.getOrElse(_ => ()))
            .composeOnKeyUp(sel.attrs.onKeyUp.getOrElse(_ => ()))
            .withOnFocus(sel.attrs.onFocus.getOrElse(()))
            .withOnBlur(sel.attrs.onBlur.getOrElse(()))

        registerFocusable(sel, key, disabled, ctx)

        val displayChildren = Chunk(Resolved.Text(selectedText), Resolved.Text(" ▼"))

        // Select children (label + arrow) flow horizontally
        val rowStyle = style ++ Style.row
        if isExpanded then
            val optionNodes = buildOptionNodes(options, sel, selectValueRef, expandedRef, ctx)
            val popupStyle  = Style.border(1.px, ctx.state.theme.borderColor).bg(Style.Color.rgb(0, 0, 0))
            val popup       = Resolved.Node(ElemTag.Popup, popupStyle, Handlers.empty, optionNodes)
            Resolved.Node(ElemTag.Div, rowStyle, handlers, displayChildren.append(popup))
        else
            Resolved.Node(ElemTag.Div, rowStyle, handlers, displayChildren)
        end if
    end lowerSelect

    // ---- RangeInput ----

    private def lowerRangeInput(ri: UI.RangeInput, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val disabled = ws.disabled
        val style    = resolveStyle(ri, key, ws, ctx.state)
        val minVal   = ri.min.getOrElse(0.0)
        val maxVal   = ri.max.getOrElse(100.0)
        val step     = ri.step.getOrElse(1.0)

        val currentValue = readDoubleOrRef(ri.value, key, ctx.state, minVal)

        val display = f"$currentValue%.1f"

        // Safe refs for handler computation
        val rangeValueRef: Maybe[SignalRef[Double]] = ri.value match
            case Present(_: SignalRef[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[Double]](key.child("value")).map(_.safe)
            case _ => Absent
        val rangeDisabledRef: Maybe[SignalRef[Boolean]] = ri.disabled match
            case Present(_: Signal[?]) =>
                ctx.state.widgetState.get[SignalRef.Unsafe[Boolean]](key.child("disabled")).map(_.safe)
            case _ => Absent

        val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
            val disCheck = rangeDisabledRef match
                case Present(ref) => ref.get
                case _            => Kyo.lift(ri.disabled.map { case b: Boolean => b; case _ => false }.getOrElse(false))
            disCheck.map { dis =>
                if !dis then
                    val valRead = rangeValueRef match
                        case Present(ref) => ref.get
                        case _            => Kyo.lift(ri.value.map { case d: Double => d; case _ => minVal }.getOrElse(minVal))
                    valRead.map { curVal =>
                        val delta = ke.key match
                            case UI.Keyboard.ArrowRight | UI.Keyboard.ArrowUp  => step
                            case UI.Keyboard.ArrowLeft | UI.Keyboard.ArrowDown => -step
                            case _                                             => 0.0
                        if delta != 0.0 then
                            val newVal = math.max(minVal, math.min(maxVal, curVal + delta))
                            val writeEffect: Unit < Async = rangeValueRef match
                                case Present(ref) => ref.set(newVal)
                                case _            => ()
                            writeEffect.andThen(ri.onChange match
                                case Present(f) => f(newVal)
                                case Absent     => ())
                        else ()
                        end if
                    }
                else ()
            }

        val handlers = ctx.parentHandlers
            .withWidgetKey(key)
            .withId(ri.attrs.identifier)
            .withTabIndex(ri.attrs.tabIndex)
            .withDisabled(disabled)
            .composeOnClick(ri.attrs.onClick.getOrElse(()))
            .withOnClickSelf(ri.attrs.onClickSelf.getOrElse(()))
            .composeOnKeyDown(widgetOnKeyDown)
            .composeOnKeyDown(ri.attrs.onKeyDown.getOrElse(_ => ()))
            .composeOnKeyUp(ri.attrs.onKeyUp.getOrElse(_ => ()))
            .withOnFocus(ri.attrs.onFocus.getOrElse(()))
            .withOnBlur(ri.attrs.onBlur.getOrElse(()))

        if !disabled then
            ri.attrs.tabIndex match
                case Present(idx) if idx >= 0 => ctx.focusables.addOne(key)
                case Absent                   => ctx.focusables.addOne(key)
                case _                        =>
        end if

        Resolved.Node(ElemTag.Div, style, handlers, Chunk(Resolved.Text(display)))
    end lowerRangeInput

    // ---- Img ----

    private def lowerImg(img: UI.Img, key: WidgetKey, ws: WidgetState, dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Resolved =
        val style = resolveStyle(img, key, ws, ctx.state)
        val alt   = img.alt.getOrElse("")
        val handlers = Handlers.empty
            .withWidgetKey(key)
            .withId(img.attrs.identifier)
        Resolved.Node(ElemTag.Div, style, handlers, Chunk(Resolved.Text(alt)))
    end lowerImg

    // ---- Style resolution ----

    private def resolveStyle(elem: UI.Element, key: WidgetKey, ws: WidgetState, state: ScreenState)(using AllowUnsafe, Frame): Style =
        val userStyle = elem.attrs.uiStyle match
            case s: Style     => s
            case _: Signal[?] =>
                // Already materialized in phase 1
                state.widgetState.get[SignalRef.Unsafe[Style]](key.child("style"))
                    .map(_.get())
                    .getOrElse(Style.empty)

        val theme = themeStyle(elem, state.theme)
        val base  = theme ++ userStyle

        mergePseudoStates(base, ws)
    end resolveStyle

    private def mergePseudoStates(
        base: Style,
        ws: WidgetState
    ): Style =
        val conditions = Chunk(
            (
                ws.focused,
                (p: Style.Prop) =>
                    p match
                        case _: Style.Prop.FocusProp => true;
                        case _                       => false
            ),
            (
                ws.hovered,
                (p: Style.Prop) =>
                    p match
                        case _: Style.Prop.HoverProp => true;
                        case _                       => false
            ),
            (
                ws.active,
                (p: Style.Prop) =>
                    p match
                        case _: Style.Prop.ActiveProp => true;
                        case _                        => false
            ),
            (
                ws.disabled,
                (p: Style.Prop) =>
                    p match
                        case _: Style.Prop.DisabledProp => true;
                        case _                          => false
            )
        )
        @tailrec def loop(i: Int, acc: Style): Style =
            if i >= conditions.size then acc
            else
                val (isActive, pred) = conditions(i)
                val next             = if isActive then acc ++ extractPseudo(base, pred) else acc
                loop(i + 1, next)
        loop(0, base)
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

    /** TUI "user agent stylesheet" — default styles per element type, using theme color tokens. This is the TUI equivalent of browser
      * default styles. Each entry mirrors what a web browser would show for that HTML element.
      */
    private def themeStyle(elem: UI.Element, theme: ResolvedTheme): Style =
        theme.variant match
            case Theme.Plain => Style.empty
            case Theme.Minimal =>
                elem match
                    case _: UI.H1 => Style.bold.padding(1.px, 0.px)
                    case _: UI.H2 => Style.bold
                    case _: UI.Hr => Style.borderBottom(1.px, theme.borderColor).width(100.pct).height(1.px)
                    case _        => Style.empty
            case Theme.Default =>
                elem match
                    // Headings
                    case _: UI.H1 => Style.bold.padding(1.px, 0.px)
                    case _: UI.H2 => Style.bold
                    // Form controls — bordered like web browser defaults
                    case _: UI.TextInput => Style.border(1.px, theme.borderColor).padding(0.px, 1.px).width(100.pct)
                    case _: UI.Select    => Style.border(1.px, theme.borderColor).padding(0.px, 1.px).width(100.pct)
                    case _: UI.Button    => Style.border(1.px, theme.borderColor).padding(0.px, 1.px)
                    // Table cells — padding for column spacing
                    case _: UI.Th => Style.bold.padding(0.px, 1.px)
                    case _: UI.Td => Style.padding(0.px, 1.px)
                    // Separators
                    case _: UI.Hr => Style.borderBottom(1.px, theme.borderColor).width(100.pct).height(1.px)
                    case _        => Style.empty

    // ---- Tag resolution ----

    private def resolveTag(elem: UI.Element): ElemTag = elem match
        case _: UI.Table => ElemTag.Table
        case _: UI.Span | _: UI.Nav | _: UI.Li | _: UI.Tr | _: UI.Button |
            _: UI.Anchor => ElemTag.Span
        case _ => ElemTag.Div

    // ---- Handler building ----

    private def buildHandlers(elem: UI.Element, key: WidgetKey, disabled: Boolean, ctx: Ctx)(using AllowUnsafe, Frame): Handlers =
        val attrs = elem.attrs
        ctx.parentHandlers
            .withWidgetKey(key)
            .withId(attrs.identifier)
            .withForId(elem match
                case lbl: UI.Label => lbl.forId;
                case _             => Absent)
            .withTabIndex(attrs.tabIndex)
            .withDisabled(disabled)
            .withColspan(elem match
                case td: UI.Td => td.colspan.getOrElse(1)
                case th: UI.Th => th.colspan.getOrElse(1)
                case _         => 1)
            .withRowspan(elem match
                case td: UI.Td => td.rowspan.getOrElse(1)
                case th: UI.Th => th.rowspan.getOrElse(1)
                case _         => 1)
            .composeOnClick(attrs.onClick.getOrElse(()))
            .withOnClickSelf(attrs.onClickSelf.getOrElse(()))
            .composeOnKeyDown(attrs.onKeyDown.getOrElse(_ => ()))
            .composeOnKeyUp(attrs.onKeyUp.getOrElse(_ => ()))
            .withOnFocus(attrs.onFocus.getOrElse(()))
            .withOnBlur(attrs.onBlur.getOrElse(()))
    end buildHandlers

    // ---- Child walking ----

    private def walkChildren(children: kyo.Span[UI], dynamicPath: Chunk[String], ctx: Ctx)(using AllowUnsafe, Frame): Chunk[Resolved] =
        @tailrec def loop(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
            if i >= children.size then acc
            else
                children(i) match
                    // Flatten Foreach/Fragment results into parent's children list
                    // (prevents wrapping in a Div which breaks table layout)
                    case fe: UI.internal.Foreach[?] =>
                        loop(i + 1, acc.concat(walkForeachFlat(fe, dynamicPath, ctx)))
                    case UI.internal.Fragment(fcs) =>
                        loop(i + 1, acc.concat(walkChildren(fcs, dynamicPath, ctx)))
                    case other =>
                        loop(i + 1, acc.append(walk(other, dynamicPath, ctx)))
        loop(0, Chunk.empty)
    end walkChildren

    /** Walk Foreach and return flat list of resolved items (no wrapping). */
    private def walkForeachFlat(fe: UI.internal.Foreach[?], dynamicPath: Chunk[String], ctx: Ctx)(using
        AllowUnsafe,
        Frame
    ): Chunk[Resolved] =
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
        eachItem(0, Chunk.empty)
    end walkForeachFlat

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
        selectValueRef: Maybe[SignalRef[String]],
        expandedRef: SignalRef[Boolean],
        ctx: Ctx
    )(using AllowUnsafe, Frame): Chunk[Resolved] =
        @tailrec def loop(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
            if i >= options.size then acc
            else
                val (value, text) = options(i)
                val optClick: Unit < Async =
                    val writeEffect: Unit < Async = selectValueRef match
                        case Present(ref) => ref.set(value)
                        case _            => ()
                    writeEffect
                        .andThen(expandedRef.set(false))
                        .andThen(sel.onChange match
                            case Present(f) => f(value)
                            case Absent     => ())
                end optClick
                val optHandlers = Handlers.empty
                    .withOnClick(optClick)
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
        if value.isEmpty then ()
        else
            value.get match
                case _: SignalRef[?] =>
                    state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")) match
                        case Present(ref) =>
                            ref.set(newVal)
                            ()
                        case _ => ()
                case _ => ()

    private def readDoubleOrRef(value: Maybe[Double | SignalRef[Double]], key: WidgetKey, state: ScreenState, default: Double)(using
        AllowUnsafe
    ): Double =
        if value.isEmpty then default
        else
            value.get match
                case d: Double => d
                case _: SignalRef[?] =>
                    state.widgetState.get[SignalRef.Unsafe[Double]](key.child("value")) match
                        case Present(ref) => ref.get()
                        case _            => default

    private def writeDoubleRef(value: Maybe[Double | SignalRef[Double]], key: WidgetKey, state: ScreenState, newVal: Double)(using
        AllowUnsafe
    ): Unit < Async =
        if value.isEmpty then ()
        else
            value.get match
                case _: SignalRef[?] =>
                    state.widgetState.get[SignalRef.Unsafe[Double]](key.child("value")) match
                        case Present(ref) =>
                            ref.set(newVal)
                            ()
                        case _ => ()
                case _ => ()

    private def fireStringCallback(cb: Maybe[String => Unit < Async], value: String): Unit < Async =
        cb match
            case Present(f) => f(value)
            case Absent     => ()

    /** Register element as focusable if eligible. Single source of truth for focusable rules. */
    private def registerFocusable(elem: UI.Element, key: WidgetKey, disabled: Boolean, ctx: Ctx): Unit =
        if !disabled then
            val isFocusable = elem match
                case _: UI.Focusable => true
                case _               => false
            val tabAllowed = elem.attrs.tabIndex match
                case Present(idx) => idx >= 0
                case Absent       => isFocusable
            if tabAllowed then ctx.focusables.addOne(key)

    private def isDisabled(elem: UI.Element, state: ScreenState)(using AllowUnsafe): Boolean =
        elem match
            case hd: UI.HasDisabled =>
                val key = WidgetKey(elem.frame, Chunk.empty)
                readBooleanOrSignal(hd.disabled, key, "disabled", state)
            case _ => false

end Lower
