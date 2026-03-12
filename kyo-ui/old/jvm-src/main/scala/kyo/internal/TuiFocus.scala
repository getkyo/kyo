package kyo.internal

import kyo.*
import kyo.Maybe.*
import scala.annotation.tailrec

/** Stateful focus manager for TUI elements.
  *
  * Scans layout for focusable elements (Button, Input, Textarea, Select, Anchor), cycles focus on Tab/Shift-Tab, applies focus style, and
  * dispatches input events to the focused element.
  */
final private[kyo] class TuiFocus:

    private var indices: Array[Int]               = new Array[Int](64)
    private var count: Int                        = 0
    private var current: Int                      = -1
    private var prevFocusedElement: Maybe[AnyRef] = Absent
    private var autoFocusOnScan: Boolean          = true

    def focusedIndex: Int =
        if current >= 0 && current < count then indices(current)
        else -1

    // ──────────────────────── Scan ────────────────────────

    def scan(layout: TuiLayout): Unit =
        // Save previous focused element for preservation
        if current >= 0 && current < count then
            prevFocusedElement = layout.element(indices(current))
        else
            prevFocusedElement = Absent
        end if

        count = 0
        val n = layout.count

        @tailrec def loop(i: Int): Unit =
            if i < n then
                val nt = layout.nodeType(i)
                val lf = layout.lFlags(i)
                if TuiLayout.isFocusable(nt) &&
                    !TuiLayout.isHidden(lf) &&
                    !TuiLayout.isDisabled(lf)
                then
                    if count >= indices.length then
                        val newArr = new Array[Int](indices.length * 2)
                        java.lang.System.arraycopy(indices, 0, newArr, 0, indices.length)
                        indices = newArr
                    end if
                    indices(count) = i
                    count += 1
                end if
                loop(i + 1)
        loop(0)

        // Preserve focus across re-scans
        if prevFocusedElement.isDefined then
            val prev = prevFocusedElement.get
            @tailrec def findPrev(j: Int): Unit =
                if j < count then
                    val elem = layout.element(indices(j))
                    if elem.isDefined && (elem.get eq prev) then
                        current = j
                    else
                        findPrev(j + 1)
                    end if
            val oldCurrent = current
            findPrev(0)
            // If not found, clamp
            if current == oldCurrent && count > 0 then
                current = math.min(current, count - 1)
                if current < 0 then current = 0
            else if count == 0 then
                current = -1
            end if
        else if count > 0 && current < 0 && autoFocusOnScan then
            current = 0
        else if count == 0 then
            current = -1
        end if
    end scan

    /** Disable auto-focus on scan. Used by TuiSimulator to start without focus. */
    def disableAutoFocus(): Unit =
        autoFocusOnScan = false

    /** Auto-focus the first focusable element if nothing is focused. Called by frame rendering. */
    def autoFocus(): Unit =
        if current < 0 && count > 0 then
            current = 0

    // ──────────────────────── Navigation ────────────────────────

    def next(): Unit =
        current =
            if count == 0 then -1
            else (current + 1) % count

    def prev(): Unit =
        current =
            if count == 0 then -1
            else (current + count - 1) % count

    // ──────────────────────── Focus Style ────────────────────────

    def applyFocusStyle(layout: TuiLayout): Unit =
        if current >= 0 && current < count then
            val idx = indices(current)
            val fs  = layout.focusStyle(idx)
            if fs.isDefined then
                TuiStyle.overlay(fs.get.asInstanceOf[Style], layout, idx)
            else
                // Default highlight: thin blue border on all sides
                val borderBits = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.lFlags(idx) = layout.lFlags(idx) | borderBits
                val blue = TuiColor.pack(122, 162, 247) // #7aa2f7
                layout.bdrClrT(idx) = blue
                layout.bdrClrR(idx) = blue
                layout.bdrClrB(idx) = blue
                layout.bdrClrL(idx) = blue
                // Set border style to thin if currently none
                if TuiLayout.borderStyle(layout.pFlags(idx)) == TuiLayout.BorderNone then
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                        (TuiLayout.BorderThin << TuiLayout.BorderStyleShift)
            end if
    end applyFocusStyle

    // ──────────────────────── Dispatch ────────────────────────

    def dispatch(event: InputEvent, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        event match
            case InputEvent.Key("Tab", false, false, shift) =>
                if shift then prev() else next()
                ()
            case m: InputEvent.Mouse =>
                m.kind match
                    case InputEvent.MouseKind.LeftPress =>
                        hitTest(m.x, m.y, layout) match
                            case found if found >= 0 =>
                                current = found
                                val idx  = indices(current)
                                val elem = layout.element(idx)
                                if elem.isDefined then
                                    val element = elem.get.asInstanceOf[UI.AST.Element]
                                    builtInHandleMouse(element)
                                else ()
                                end if
                            case _ => ()
                    case InputEvent.MouseKind.ScrollUp =>
                        // Could be used for scrolling in the future
                        ()
                    case InputEvent.MouseKind.ScrollDown =>
                        ()
                    case _ => ()
            case _ =>
                if count == 0 then ()
                else if current < 0 then
                    current = 0
                    dispatch(event, layout)
                else
                    val idx  = indices(current)
                    val elem = layout.element(idx)
                    if elem.isEmpty then ()
                    else
                        val element = elem.get.asInstanceOf[UI.AST.Element]
                        val common  = element.common
                        for
                            _ <- fireHandler(common.onKeyDown, event)
                            _ <- builtInHandle(element, event)
                            _ <- fireHandler(common.onKeyUp, event)
                        yield ()
                        end for
                    end if
    end dispatch

    /** Find which focusable element contains the given (x, y) coordinate. Returns focus index or -1. */
    private def hitTest(mx: Int, my: Int, layout: TuiLayout): Int =
        @tailrec def loop(i: Int, best: Int): Int =
            if i >= count then best
            else
                val idx = indices(i)
                val x   = layout.x(idx)
                val y   = layout.y(idx)
                val w   = layout.w(idx)
                val h   = layout.h(idx)
                if mx >= x && mx < x + w && my >= y && my < y + h then
                    loop(i + 1, i) // last match wins (deepest in tree)
                else
                    loop(i + 1, best)
                end if
        loop(0, -1)
    end hitTest

    private def builtInHandleMouse(element: UI.AST.Element)(using Frame): Unit < Sync =
        element match
            case b: UI.AST.Button => fireAction(b.common.onClick)
            case a: UI.AST.Anchor => fireAction(a.common.onClick)
            case _                => ()
    end builtInHandleMouse

    // ──────────────────────── Handler Helpers ────────────────────────

    private def fireHandler(h: Maybe[UI.AST.KeyEvent => Unit < Async], event: InputEvent)(using Frame): Unit < Sync =
        if h.isDefined then
            event match
                case InputEvent.Key(key, ctrl, alt, shift) =>
                    Fiber.initUnscoped(h.get(UI.AST.KeyEvent(key, ctrl, alt, shift))).unit
                case _ => ()
        else ()

    private def fireAction(action: Maybe[Unit < Async])(using Frame): Unit < Sync =
        if action.isDefined then Fiber.initUnscoped(action.get).unit
        else ()

    // ──────────────────────── Built-in Handling ────────────────────────

    private def builtInHandle(element: UI.AST.Element, event: InputEvent)(using Frame, AllowUnsafe): Unit < Sync =
        element match
            case b: UI.AST.Button =>
                event match
                    case InputEvent.Key("Enter", false, false, false) | InputEvent.Key(" ", false, false, false) =>
                        fireAction(b.common.onClick)
                    case _ => ()

            case inp: UI.AST.Input =>
                handleTextInput(inp.value, event, allowNewline = false)

            case ta: UI.AST.Textarea =>
                handleTextInput(ta.value, event, allowNewline = true)

            case a: UI.AST.Anchor =>
                event match
                    case InputEvent.Key("Enter", false, false, false) =>
                        fireAction(a.common.onClick)
                    case _ => ()

            case _ => ()
    end builtInHandle

    private def handleTextInput(
        value: Maybe[String | SignalRef[String]],
        event: InputEvent,
        allowNewline: Boolean
    )(using Frame, AllowUnsafe): Unit < Sync =
        value match
            case Present(ref: SignalRef[?]) =>
                val r = ref.asInstanceOf[SignalRef[String]]
                event match
                    case InputEvent.Key(key, false, false, false) if key.length == 1 && key.charAt(0) >= ' ' =>
                        val cur = Sync.Unsafe.evalOrThrow(r.get)
                        r.set(cur + key)
                    case InputEvent.Key("Backspace", false, false, false) =>
                        val cur = Sync.Unsafe.evalOrThrow(r.get)
                        if cur.nonEmpty then r.set(cur.substring(0, cur.length - 1))
                        else ()
                    case InputEvent.Key("Enter", false, false, false) =>
                        if allowNewline then
                            val cur = Sync.Unsafe.evalOrThrow(r.get)
                            r.set(cur + "\n")
                        else ()
                    case _ => ()
                end match
            case _ => ()
    end handleTextInput

end TuiFocus
