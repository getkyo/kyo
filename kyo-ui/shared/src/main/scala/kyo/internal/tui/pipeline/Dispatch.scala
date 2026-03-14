package kyo.internal.tui.pipeline

import kyo.*
import scala.annotation.tailrec

/** Routes input events to pre-composed handlers. The only pipeline step that writes `SignalRef.Unsafe` values (focusedId, hoveredId,
  * activeId) and fires handler closures.
  *
  * No tree walking for bubbling — handlers are pre-composed by Lower. hitTest returns a single node (not a path). Handler calls are direct.
  */
object Dispatch:

    private val noop: Unit < Async = ()

    /** Find the deepest node at (mx, my). Checks popups first (topmost layer). */
    def hitTest(layout: LayoutResult, mx: Int, my: Int): Maybe[Laid.Node] =
        @tailrec def checkPopups(i: Int): Maybe[Laid.Node] =
            if i < 0 then hitTestNode(layout.base, mx, my)
            else
                val hit = hitTestNode(layout.popups(i), mx, my)
                if hit.nonEmpty then hit
                else checkPopups(i - 1)
        checkPopups(layout.popups.size - 1)
    end hitTest

    private def hitTestNode(node: Laid, mx: Int, my: Int): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if !n.bounds.contains(mx, my) || !n.clip.contains(mx, my) then Absent
                else
                    findInChildren(n.children, n.children.size - 1, mx, my).orElse(Maybe(n))
            case _ => Absent

    @tailrec private def findInChildren(children: Chunk[Laid], i: Int, mx: Int, my: Int): Maybe[Laid.Node] =
        if i < 0 then Absent
        else
            val hit = hitTestNode(children(i), mx, my)
            if hit.nonEmpty then hit
            else findInChildren(children, i - 1, mx, my)

    /** Find node by WidgetKey. */
    def findByKey(node: Laid, key: WidgetKey): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if n.handlers.widgetKey.contains(key) then Maybe(n)
                else
                    @tailrec def search(i: Int): Maybe[Laid.Node] =
                        if i >= n.children.size then Absent
                        else
                            findByKey(n.children(i), key) match
                                case found @ Present(_) => found
                                case _                  => search(i + 1)
                    search(0)
            case _ => Absent

    /** Find node by user-facing id. For Label.forId redirect. */
    def findByUserId(node: Laid, id: String): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if n.handlers.id.contains(id) then Maybe(n)
                else
                    @tailrec def search(i: Int): Maybe[Laid.Node] =
                        if i >= n.children.size then Absent
                        else
                            findByUserId(n.children(i), id) match
                                case found @ Present(_) => found
                                case _                  => search(i + 1)
                    search(0)
            case _ => Absent

    /** Route an input event. Returns a computation that fires handlers and updates state. */
    def dispatch(event: InputEvent, layout: LayoutResult, state: ScreenState)(using AllowUnsafe, Frame): Unit < Async =
        event match
            case InputEvent.Key(key, ctrl, alt, shift) =>
                key match
                    case UI.Keyboard.Tab =>
                        cycleFocus(state, layout, reverse = shift)
                    case _ =>
                        findFocused(layout, state) match
                            case Present(node) if !node.handlers.disabled =>
                                val ke        = UI.KeyEvent(key, ctrl, alt, shift)
                                val keyEffect = node.handlers.onKeyDown(ke)
                                if key == UI.Keyboard.Space then
                                    keyEffect.andThen(node.handlers.onClick)
                                else keyEffect
                            case _ => noop

            case InputEvent.Mouse(MouseKind.LeftPress, mx, my) =>
                hitTest(layout, mx, my) match
                    case Present(node) if !node.handlers.disabled =>
                        val isFocusable = node.handlers.widgetKey.exists(k => state.focusableIds.exists(_ == k))
                        val focusEffect = node.handlers.forId match
                            case Present(targetId) =>
                                findByUserId(layout.base, targetId) match
                                    case Present(target) => setFocus(target, layout, state)
                                    case _               => noop
                            case _ =>
                                if isFocusable then setFocus(node, layout, state)
                                else noop
                        state.activeId.set(node.handlers.widgetKey)
                        focusEffect
                            .andThen(node.handlers.onClick)
                            .andThen(node.handlers.onClickSelf)
                    case _ => noop

            case InputEvent.Mouse(MouseKind.LeftRelease, _, _) =>
                state.activeId.set(Absent)
                noop

            case InputEvent.Mouse(MouseKind.Move, mx, my) =>
                val target = hitTest(layout, mx, my)
                state.hoveredId.set(target.flatMap(_.handlers.widgetKey))
                noop

            case InputEvent.Mouse(MouseKind.ScrollUp, mx, my) =>
                hitTest(layout, mx, my) match
                    case Present(node) => node.handlers.onScroll(-3)
                    case _             => noop

            case InputEvent.Mouse(MouseKind.ScrollDown, mx, my) =>
                hitTest(layout, mx, my) match
                    case Present(node) => node.handlers.onScroll(3)
                    case _             => noop

            case InputEvent.Paste(text) =>
                findFocused(layout, state) match
                    case Present(node) if !node.handlers.disabled =>
                        node.handlers.onInput(text)
                    case _ => noop

            case _ => noop

    private def findFocused(layout: LayoutResult, state: ScreenState)(using AllowUnsafe): Maybe[Laid.Node] =
        state.focusedId.get().flatMap(key => findByKey(layout.base, key))

    private def setFocus(node: Laid.Node, layout: LayoutResult, state: ScreenState)(using AllowUnsafe, Frame): Unit < Async =
        // Unsafe reads to set up the computation
        val oldKey = state.focusedId.get()
        val newKey = node.handlers.widgetKey
        if oldKey != newKey then
            val blurEffect = oldKey.flatMap(k => findByKey(layout.base, k)) match
                case Present(old) => old.handlers.onBlur
                case _            => noop
            state.focusedId.set(newKey)
            // Return composed computation — no more unsafe calls after this
            blurEffect.andThen(node.handlers.onFocus)
        else noop
        end if
    end setFocus

    private def cycleFocus(state: ScreenState, layout: LayoutResult, reverse: Boolean)(using AllowUnsafe, Frame): Unit < Async =
        // Unsafe reads to set up the computation
        val keys = state.focusableIds
        if keys.nonEmpty then
            val current    = state.focusedId.get()
            val currentIdx = current.map(k => keys.indexOf(k)).getOrElse(-1)
            val nextIdx =
                if reverse then
                    if currentIdx <= 0 then keys.size - 1 else currentIdx - 1
                else if currentIdx < 0 || currentIdx >= keys.size - 1 then 0
                else currentIdx + 1
            val newKey = keys(nextIdx)
            val blurEffect = current.flatMap(k => findByKey(layout.base, k)) match
                case Present(old) => old.handlers.onBlur
                case _            => noop
            val focusEffect = findByKey(layout.base, newKey) match
                case Present(node) => node.handlers.onFocus
                case _             => noop
            state.focusedId.set(Maybe(newKey))
            // Return composed computation — no more unsafe calls after this
            blurEffect.andThen(focusEffect)
        else noop
        end if
    end cycleFocus

end Dispatch
