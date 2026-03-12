package kyo.internal.tui2

import kyo.*
import kyo.Maybe.*
import kyo.discard
import kyo.internal.tui2.widget.*
import scala.annotation.tailrec

/** Event routing — dispatches input events to focus, widgets, and click handlers. */
private[kyo] object EventDispatch:

    def dispatch(
        ui: UI,
        event: InputEvent,
        w: Int,
        h: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        event match
            case ke: InputEvent.Key =>
                if ke.key == UI.Keyboard.Tab then
                    changeFocus(ctx) { if ke.shift then ctx.focus.prev() else ctx.focus.next() }
                else if ke.ctrl && ke.key == UI.Keyboard.Char('c') then
                    () // Ctrl-C handled at backend level
                else if ke.ctrl && ke.key == UI.Keyboard.Char('v') then
                    // Ctrl+V: read OS clipboard for rich paste
                    ctx.terminal.foreach { _ =>
                        val items = PlatformCmd.clipboardReadAll()
                        if items.nonEmpty then
                            ctx.focus.focused.foreach { elem =>
                                findClipboardText(items, 0).foreach { text =>
                                    discard(WidgetRegistry.lookup(elem).handlePaste(elem, text, ctx))
                                }
                            }
                        end if
                    }
                else
                    ctx.focus.focused.foreach { elem =>
                        // Fire onKeyDown
                        elem.attrs.onKeyDown.foreach { f =>
                            val keyEvent = toKeyEvent(ke)
                            ValueResolver.runHandler(f(keyEvent))
                        }
                        // Normalize Space to Char(' ') for text-input widgets
                        val widget = WidgetRegistry.lookup(elem)
                        val normalizedKe =
                            if ke.key == UI.Keyboard.Space && widget.acceptsTextInput then
                                InputEvent.Key(UI.Keyboard.Char(' '), ke.ctrl, ke.alt, ke.shift)
                            else ke
                        // Built-in widget handling
                        val handled = widget.handleKey(elem, normalizedKe, ctx)
                        // Form submit: Enter on non-textarea input that didn't consume the key
                        if !handled && ke.key == UI.Keyboard.Enter then
                            ctx.focus.formForFocused.foreach { form =>
                                form.onSubmit.foreach(h => ValueResolver.runHandler(h))
                            }
                        end if
                        // Fire onKeyUp
                        elem.attrs.onKeyUp.foreach { f =>
                            val keyEvent = toKeyEvent(ke)
                            ValueResolver.runHandler(f(keyEvent))
                        }
                    }

            case me: InputEvent.Mouse =>
                me.kind match
                    case InputEvent.MouseKind.LeftPress =>
                        DebugLog(s"LeftPress at (${me.x}, ${me.y})")
                        // Check if click is inside an open dropdown
                        if ctx.hasDropdown && ctx.dropdownOpen then
                            val ddX    = ctx.dropdownX
                            val ddY    = ctx.dropdownY + 2 // matches renderDropdown
                            val ddW    = ctx.dropdownW
                            val ddH    = ctx.dropdownOptionCount
                            val clickY = me.y - ddY
                            if me.x >= ddX && me.x < ddX + ddW && clickY >= 0 && clickY < ddH then
                                // Click on a dropdown option — select it and close
                                ctx.focus.focused.foreach {
                                    case sel: UI.Select => PickerW.selectByIndex(sel, clickY, ctx)
                                    case _              => ()
                                }
                                ctx.dropdownOpen = false
                                return
                            else
                                // Click outside dropdown — close it
                                ctx.dropdownOpen = false
                            end if
                        end if
                        hitTest(ui, 0, 0, w, h, me.x, me.y, ctx).foreach { hit =>
                            DebugLog(
                                s"[DBG] hitTest result: ${hit.getClass.getSimpleName}, focusable=${hit.isInstanceOf[UI.Focusable]}"
                            )
                            // Fire onClickSelf on the raw hit element
                            hit.attrs.onClickSelf.foreach(h => ValueResolver.runHandler(h))
                            // Label.forId: click on label focuses the target element
                            hit match
                                case label: UI.Label =>
                                    label.forId.foreach { id =>
                                        ctx.identifiers.lookup(id).foreach { target =>
                                            changeFocus(ctx) { ctx.focus.focusOn(target) }
                                        }
                                    }
                                case _ => ()
                            end match
                            // Find the focusable element to activate:
                            // if the hit is itself focusable, use it directly;
                            // otherwise find the enclosing focusable at the click position.
                            val focusTarget: Maybe[UI.Element] = hit match
                                case _: UI.Focusable => Present(hit)
                                case _               => findFocusableAt(me.x, me.y, ctx)
                            focusTarget match
                                case Present(ft) =>
                                    val widget = WidgetRegistry.lookup(ft)
                                    DebugLog(
                                        s"[DBG] focusTarget: ${ft.getClass.getSimpleName}, widget: ${widget.getClass.getSimpleName}"
                                    )
                                    ctx.activeTarget = Present(ft)
                                    changeFocus(ctx) { ctx.focus.focusOn(ft) }
                                    // Widget dispatch handles activation (handleClick fires onClick for containers)
                                    discard(widget.handleMouse(ft, me.kind, me.x, me.y, ctx))
                                case _ =>
                                    DebugLog(s"[DBG] no focusTarget found")
                                    ctx.activeTarget = Present(hit)
                                    // No focusable target — fire onClick directly on the hit element
                                    hit.attrs.onClick.foreach(h => ValueResolver.runHandler(h))
                            end match
                        }
                    case InputEvent.MouseKind.LeftDrag =>
                        // Drag goes to the element that received the press
                        ctx.activeTarget.foreach { target =>
                            discard(WidgetRegistry.lookup(target).handleMouse(target, me.kind, me.x, me.y, ctx))
                        }
                    case InputEvent.MouseKind.LeftRelease =>
                        ctx.activeTarget.foreach { target =>
                            discard(WidgetRegistry.lookup(target).handleMouse(target, me.kind, me.x, me.y, ctx))
                        }
                        ctx.activeTarget = Absent
                    case InputEvent.MouseKind.Move =>
                        ctx.hoverTarget = hitTest(ui, 0, 0, w, h, me.x, me.y, ctx)
                    case InputEvent.MouseKind.ScrollUp | InputEvent.MouseKind.ScrollDown =>
                        // Try widget first, fall back to scrollable container
                        val consumed = hitTest(ui, 0, 0, w, h, me.x, me.y, ctx).exists { hit =>
                            WidgetRegistry.lookup(hit).handleMouse(hit, me.kind, me.x, me.y, ctx)
                        }
                        if !consumed then
                            ctx.findScrollableAt(me.x, me.y).foreach { elem =>
                                val sy    = ctx.getScrollY(elem)
                                val delta = if me.kind == InputEvent.MouseKind.ScrollUp then -1 else 1
                                ctx.setScrollY(elem, math.max(0, sy + delta))
                            }
                        end if
                    case _ =>
                        // RightPress, MiddlePress, etc. — delegate to hit-tested widget
                        hitTest(ui, 0, 0, w, h, me.x, me.y, ctx).foreach { hit =>
                            discard(WidgetRegistry.lookup(hit).handleMouse(hit, me.kind, me.x, me.y, ctx))
                        }

            case InputEvent.Paste(text) =>
                ctx.focus.focused.foreach { elem =>
                    discard(WidgetRegistry.lookup(elem).handlePaste(elem, text, ctx))
                }

            case InputEvent.ClipboardPaste(items) =>
                ctx.focus.focused.foreach { elem =>
                    findClipboardText(items, 0).foreach { text =>
                        discard(WidgetRegistry.lookup(elem).handlePaste(elem, text, ctx))
                    }
                }

    // ---- Hit testing ----

    /** Find the deepest element containing (mx, my) using cached render positions. */
    private def hitTest(
        ui: UI,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mx: Int,
        my: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Maybe[UI.Element] =
        if mx < x || mx >= x + w || my < y || my >= y + h then Absent
        else
            ui match
                case elem: UI.Element =>
                    // Use cached position if available
                    val packed = ctx.getPosition(elem)
                    val (ex, ey, ew, eh) =
                        if packed != -1L then (ctx.posX(packed), ctx.posY(packed), ctx.posW(packed), ctx.posH(packed))
                        else (x, y, w, h)
                    if mx < ex || mx >= ex + ew || my < ey || my >= ey + eh then Absent
                    else
                        // Check children in reverse order (topmost = last)
                        val children = elem.children
                        hitTestChildren(children, ex, ey, ew, eh, mx, my, ctx) match
                            case Present(hit) => Present(hit)
                            case _            => Present(elem)
                    end if
                case UI.Fragment(children) =>
                    hitTestChildren(children, x, y, w, h, mx, my, ctx)
                case UI.Reactive(signal) =>
                    hitTest(ctx.resolve(signal), x, y, w, h, mx, my, ctx)
                case fe: UI.Foreach[?] =>
                    val seq  = ctx.resolve(fe.signal)
                    val size = seq.size
                    @tailrec def hitForeach(i: Int): Maybe[UI.Element] =
                        if i < 0 then Absent
                        else
                            val child = ValueResolver.foreachApply(fe, i, seq(i))
                            hitTest(child, x, y, w, h, mx, my, ctx) match
                                case Present(hit) => Present(hit)
                                case _            => hitForeach(i - 1)
                    hitForeach(size - 1)
                case _ => Absent

    private def hitTestChildren(
        children: Span[UI],
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mx: Int,
        my: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Maybe[UI.Element] =
        @tailrec def loop(i: Int): Maybe[UI.Element] =
            if i < 0 then Absent
            else
                hitTest(children(i), x, y, w, h, mx, my, ctx) match
                    case Present(hit) => Present(hit)
                    case _            => loop(i - 1)
        loop(children.size - 1)
    end hitTestChildren

    // ---- Focusable lookup by position ----

    /** Find the focusable element whose rendered bounds contain (mx, my). Iterates the focus ring and checks cached positions — returns the
      * last match (deepest in tree order).
      */
    private def findFocusableAt(mx: Int, my: Int, ctx: RenderCtx): Maybe[UI.Element] =
        var result: UI.Element = null
        ctx.focus.forEachFocusable { elem =>
            val packed = ctx.getPosition(elem)
            if packed != -1L then
                val ex = ctx.posX(packed)
                val ey = ctx.posY(packed)
                val ew = ctx.posW(packed)
                val eh = ctx.posH(packed)
                if mx >= ex && mx < ex + ew && my >= ey && my < ey + eh then
                    result = elem
            end if
        }
        Maybe(result)
    end findFocusableAt

    // ---- Focus change with onBlur/onFocus ----

    private def changeFocus(ctx: RenderCtx)(action: => Unit)(using Frame, AllowUnsafe): Unit =
        val oldFocused = ctx.focus.focused
        action
        val newFocused = ctx.focus.focused
        val changed = oldFocused match
            case Present(o) =>
                newFocused match
                    case Present(n) => !(o eq n)
                    case _          => true
            case _ =>
                newFocused.nonEmpty
        if changed then
            // Close any open dropdown when focus changes
            ctx.dropdownOpen = false
            oldFocused.foreach(_.attrs.onBlur.foreach(h => ValueResolver.runHandler(h)))
            newFocused.foreach(_.attrs.onFocus.foreach(h => ValueResolver.runHandler(h)))
        end if
    end changeFocus

    // bubbleClick removed — onClick now handled by Widget.handleClick (for focusable elements)
    // or directly in the LeftPress handler (for non-focusable elements).
    // onClickSelf and Label.forId are handled inline in the LeftPress handler.

    // ---- Clipboard text extraction ----

    @tailrec
    private def findClipboardText(items: Chunk[InputEvent.ClipboardItem], i: Int): Maybe[String] =
        if i >= items.size then Absent
        else if items(i).mimeType == "text/plain" || items(i).mimeType.startsWith("text/") then
            Present(new String(items(i).data, "UTF-8"))
        else findClipboardText(items, i + 1)

    // ---- Event conversion ----

    private def toKeyEvent(ke: InputEvent.Key): UI.KeyEvent =
        UI.KeyEvent(ke.key, ke.ctrl, ke.alt, ke.shift, meta = false)

end EventDispatch
