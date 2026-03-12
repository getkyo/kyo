package kyo.internal

import kyo.*
import kyo.Maybe.*
import scala.annotation.tailrec

/** Stateful focus manager for TUI elements.
  *
  * Flag-driven dispatch: uses Tag.isFocusable, Tag.isClickable, Tag.isActivatable, Tag.isTextInput instead of class-type matching. Scans
  * layout for focusable elements, cycles focus on Tab/Shift-Tab, applies focus style, and dispatches input events to the focused element.
  *
  * Handler-driven mouse dispatch: hitTest scans ALL layout nodes in reverse order (deeper/later = on top). Event bubbling walks parent
  * chain to find nearest handler. Any element with onClick responds to mouse clicks, not just focusable ones.
  *
  * Text cursor: cursorPos tracks insertion point within focused text input. -1 = end of text.
  */
final private[kyo] class TuiFocus:

    private var indices: Array[Int]               = new Array[Int](64)
    private var count: Int                        = 0
    private var current: Int                      = -1
    private var prevFocusedElement: Maybe[AnyRef] = Absent
    private var autoFocusOnScan: Boolean          = true

    /** Cursor position within the focused text input's value. -1 = end of text. */
    private var _cursorPos: Int = -1

    /** Layout index of the element under the mouse cursor. -1 = none. */
    private var _hoverIdx: Int = -1

    /** Layout index of the element currently being mouse-pressed. -1 = none. */
    private var _activeIdx: Int = -1

    /** Horizontal scroll offset for the focused text input. Persists across renders. */
    private var _scrollOffset: Int = 0

    /** Tracks whether cursor position changed since last scroll adjustment. */
    private var _prevCursorPos: Int = -2 // sentinel: different from initial _cursorPos (-1)

    def focusedIndex: Int =
        if current >= 0 && current < count then indices(current)
        else -1

    def hoverIdx: Int  = _hoverIdx
    def activeIdx: Int = _activeIdx

    def cursorPos: Int = _cursorPos

    /** Resolve the effective cursor position given the text length. Clamps to [0, len]. */
    private def effectiveCursorPos(textLen: Int): Int =
        if _cursorPos < 0 || _cursorPos > textLen then textLen
        else _cursorPos

    // ---- Scan ----

    def scan(layout: TuiLayout): Unit =
        // Save previous focused element for preservation
        if current >= 0 && current < count then
            prevFocusedElement = layout.element(indices(current))
        else
            prevFocusedElement = Absent
        end if

        // Phase 1: collect all focusable elements, separating tabIndex > 0 from default order
        var defaultCount    = 0
        var tabIndexedCount = 0
        val n               = layout.count

        @tailrec def collectLoop(i: Int): Unit =
            if i < n then
                val lf      = layout.lFlags(i)
                val elemRef = layout.element(i)
                if elemRef.isDefined then
                    val element = elemRef.get
                    if (element.isInstanceOf[UI.Focusable] ||
                            element.attrs.tabIndex.isDefined ||
                            element.attrs.onKeyDown.isDefined ||
                            element.attrs.onKeyUp.isDefined) &&
                        !TuiLayout.isHidden(lf) &&
                        !TuiLayout.isDisabled(lf)
                    then
                        val ti     = element.attrs.tabIndex
                        val tabIdx = if ti.isDefined then ti.get else 0
                        if tabIdx >= 0 then
                            val total = defaultCount + tabIndexedCount
                            if total >= indices.length then
                                val newArr = new Array[Int](indices.length * 2)
                                java.lang.System.arraycopy(indices, 0, newArr, 0, indices.length)
                                indices = newArr
                            end if
                            if tabIdx > 0 then
                                // Store at end for later sorting
                                indices(total) = i
                                tabIndexedCount += 1
                            else
                                // Default order: shift any tabIndexed entries right, insert at defaultCount
                                if tabIndexedCount > 0 then
                                    java.lang.System.arraycopy(indices, defaultCount, indices, defaultCount + 1, tabIndexedCount)
                                indices(defaultCount) = i
                                defaultCount += 1
                            end if
                        // tabIdx < 0: excluded from focus order
                        end if
                    end if
                end if
                collectLoop(i + 1)
        collectLoop(0)

        // Phase 2: sort the tabIndexed entries (indices[defaultCount..defaultCount+tabIndexedCount))
        // by their tabIndex value using insertion sort (small counts expected)
        if tabIndexedCount > 1 then
            sortTabIndexed(layout, defaultCount, tabIndexedCount)

        // Phase 3: merge — positive tabIndex elements come FIRST, then default-order elements
        // Currently: indices = [default0..defaultN | tabIdx1..tabIdxM]
        // Target:    indices = [tabIdx1..tabIdxM | default0..defaultN]
        if tabIndexedCount > 0 && defaultCount > 0 then
            rotateArray(defaultCount, tabIndexedCount)

        count = defaultCount + tabIndexedCount

        // Focus trapping: when an overlay is active, restrict focus cycling to its descendants
        val topmostOverlay = findTopmostOverlay(layout)
        if topmostOverlay >= 0 then
            filterToOverlayDescendants(topmostOverlay, layout)

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

    /** Insertion sort tabIndexed entries in indices[start..start+len) by their tabIndex value. */
    private def sortTabIndexed(layout: TuiLayout, start: Int, len: Int): Unit =
        // unsafe: while loop for insertion sort performance
        var i = 1
        while i < len do
            val key    = indices(start + i)
            val keyVal = getTabIndex(layout, key)
            var j      = i - 1
            while j >= 0 && getTabIndex(layout, indices(start + j)) > keyVal do
                indices(start + j + 1) = indices(start + j)
                j -= 1
            end while
            indices(start + j + 1) = key
            i += 1
        end while
    end sortTabIndexed

    /** Get the tabIndex value for a layout node, defaulting to 0. */
    private def getTabIndex(layout: TuiLayout, layoutIdx: Int): Int =
        val elem = layout.element(layoutIdx)
        if elem.isDefined then

            val ti = elem.get.attrs.tabIndex
            if ti.isDefined then ti.get else 0
        else 0
        end if
    end getTabIndex

    /** Rotate indices[0..a+b) so that indices[a..a+b) comes first. In-place via triple reversal. */
    private def rotateArray(a: Int, b: Int): Unit =
        reverseArray(0, a)
        reverseArray(a, a + b)
        reverseArray(0, a + b)
    end rotateArray

    private def reverseArray(from: Int, to: Int): Unit =
        // unsafe: while loop for performance
        var lo = from
        var hi = to - 1
        while lo < hi do
            val tmp = indices(lo)
            indices(lo) = indices(hi)
            indices(hi) = tmp
            lo += 1
            hi -= 1
        end while
    end reverseArray

    /** Disable auto-focus on scan. Used by TuiSimulator to start without focus. */
    def disableAutoFocus(): Unit =
        autoFocusOnScan = false

    /** Auto-focus the first focusable element if nothing is focused. Called by frame rendering. */
    def autoFocus(): Unit =
        if current < 0 && count > 0 then
            current = 0

    // ---- Navigation ----

    def next(): Unit =
        current =
            if count == 0 then -1
            else (current + 1) % count
        _cursorPos = -1   // Reset cursor on focus change
        _scrollOffset = 0 // Reset scroll on focus change
    end next

    def prev(): Unit =
        current =
            if count == 0 then -1
            else (current + count - 1) % count
        _cursorPos = -1   // Reset cursor on focus change
        _scrollOffset = 0 // Reset scroll on focus change
    end prev

    // ---- Cursor position for TuiBackend ----

    /** Get the (x, y) cursor position in terminal coordinates for the focused text input. Uses CPS to avoid tuple allocation. Calls f(cx,
      * cy) if cursor should be shown, else calls f(-1, -1).
      */
    inline def getCursorPosition[A](layout: TuiLayout)(inline f: (Int, Int) => A)(using Frame, AllowUnsafe): A =
        val idx = focusedIndex
        if idx < 0 then f(-1, -1)
        else
            val elem = layout.element(idx)
            if elem.isEmpty then f(-1, -1)
            else

                val element = elem.get
                if !element.isInstanceOf[UI.TextInput] then f(-1, -1)
                else
                    val ti = element.asInstanceOf[UI.TextInput]
                    val text = ti.value match
                        case Present(ref: SignalRef[?]) =>
                            // unsafe: asInstanceOf for union type resolution
                            Sync.Unsafe.evalOrThrow(ref.asInstanceOf[Signal[String]].current)
                        case Present(s: String) => s
                        case _                  => ""
                    val pos = effectiveCursorPos(text.length)
                    // Text child is the first child of the input element
                    val childIdx = layout.firstChild(idx)
                    if childIdx < 0 then f(-1, -1)
                    else if element.isInstanceOf[UI.Textarea] then
                        // Textarea: compute cursor row/col in wrapped text
                        val lf = layout.lFlags(idx)
                        val contentW = layout.w(idx) - layout.padL(idx) - layout.padR(idx) -
                            (if TuiLayout.hasBorderL(lf) then 1 else 0) - (if TuiLayout.hasBorderR(lf) then 1 else 0)
                        if contentW <= 0 then f(-1, -1)
                        else
                            // Walk wrapped lines to find cursor position
                            var cursorRow = 0
                            var cursorCol = 0
                            var found     = false
                            val _ = TuiText.forEachLine(text, contentW, wrap = true) { (start, end) =>
                                if !found then
                                    if pos >= start && pos <= end then
                                        cursorCol = pos - start
                                        found = true
                                    else
                                        cursorRow += 1
                                    end if
                                end if
                            }
                            val cursorX = layout.x(childIdx) + cursorCol
                            val cursorY = layout.y(childIdx) + cursorRow
                            // Clamp: hide cursor if outside textarea content area
                            val contentH = layout.h(idx) - layout.padT(idx) - layout.padB(idx) -
                                (if TuiLayout.hasBorderT(lf) then 1 else 0) - (if TuiLayout.hasBorderB(lf) then 1 else 0)
                            val contentTop = layout.y(idx) + layout.padT(idx) +
                                (if TuiLayout.hasBorderT(lf) then 1 else 0)
                            if cursorY < contentTop || cursorY >= contentTop + contentH then f(-1, -1)
                            else f(cursorX, cursorY)
                        end if
                    else
                        f(layout.x(childIdx) + pos - _scrollOffset, layout.y(childIdx))
                    end if
                end if
            end if
        end if
    end getCursorPosition

    /** Adjust the focused text input's displayed text to a visible window based on scroll offset and cursor position. Called after
      * focus.scan when layout dimensions are known. Like web `<input>`: fixed width, text scrolls to keep cursor visible.
      */
    def adjustTextScroll(layout: TuiLayout)(using Frame, AllowUnsafe): Unit =
        val idx = focusedIndex
        if idx < 0 then return // unsafe: early return
        val elem = layout.element(idx)
        if elem.isEmpty then return // unsafe: early return
        val element = elem.get
        if !element.isInstanceOf[UI.TextInput] then return // unsafe: early return
        // Textarea: vertical scroll to keep cursor line visible
        if element.isInstanceOf[UI.Textarea] then
            adjustTextareaScroll(layout, idx, element)
            return // unsafe: early return
        end if
        val childIdx = layout.firstChild(idx)
        if childIdx < 0 || layout.text(childIdx).isEmpty then return // unsafe: early return
        // Read raw text to compute cursor position
        val rawText = element.asInstanceOf[UI.TextInput].value match
            case Present(ref: SignalRef[?]) =>
                // unsafe: asInstanceOf for union type resolution
                Sync.Unsafe.evalOrThrow(ref.asInstanceOf[Signal[String]].current)
            case Present(s: String) => s
            case _                  => ""
        // Placeholder: no scrolling
        if rawText.isEmpty then
            _scrollOffset = 0
            return // unsafe: early return
        end if
        // Compute content width from laid-out dimensions
        val lf = layout.lFlags(idx)
        val contentW = layout.w(idx) - layout.padL(idx) - layout.padR(idx) -
            (if TuiLayout.hasBorderL(lf) then 1 else 0) - (if TuiLayout.hasBorderR(lf) then 1 else 0)
        if contentW <= 0 then return // unsafe: early return
        val displayText = layout.text(childIdx).get
        if displayText.length <= contentW then
            _scrollOffset = 0
            return // unsafe: early return
        end if
        // Adjust scroll offset to keep cursor visible
        val pos = effectiveCursorPos(rawText.length)
        if pos < _scrollOffset then _scrollOffset = pos
        else if pos >= _scrollOffset + contentW then _scrollOffset = pos - contentW + 1
        // Clamp scroll offset
        _scrollOffset = math.max(0, math.min(_scrollOffset, displayText.length - contentW))
        // Trim display text to visible window
        val endIdx = math.min(_scrollOffset + contentW, displayText.length)
        layout.text(childIdx) = Present(displayText.substring(_scrollOffset, endIdx))
    end adjustTextScroll

    /** Adjust textarea vertical scroll to keep the cursor line visible. Only adjusts when cursor moved (allows mouse scroll). */
    private def adjustTextareaScroll(layout: TuiLayout, idx: Int, element: UI.Element)(using Frame, AllowUnsafe): Unit =
        // Only auto-scroll when cursor position changed — mouse scroll should not be overridden
        val cursorMoved = _cursorPos != _prevCursorPos
        _prevCursorPos = _cursorPos
        val rawText = element.asInstanceOf[UI.TextInput].value match
            case Present(ref: SignalRef[?]) =>
                // unsafe: asInstanceOf for union type resolution
                Sync.Unsafe.evalOrThrow(ref.asInstanceOf[Signal[String]].current)
            case Present(s: String) => s
            case _                  => ""
        if rawText.isEmpty then
            layout.scrollY(idx) = 0
            return // unsafe: early return
        end if
        if !cursorMoved then return // unsafe: early return — preserve mouse scroll position
        val lf = layout.lFlags(idx)
        val contentW = layout.w(idx) - layout.padL(idx) - layout.padR(idx) -
            (if TuiLayout.hasBorderL(lf) then 1 else 0) - (if TuiLayout.hasBorderR(lf) then 1 else 0)
        val contentH = layout.h(idx) - layout.padT(idx) - layout.padB(idx) -
            (if TuiLayout.hasBorderT(lf) then 1 else 0) - (if TuiLayout.hasBorderB(lf) then 1 else 0)
        if contentW <= 0 || contentH <= 0 then return // unsafe: early return
        // Find which wrapped line the cursor is on
        val pos       = effectiveCursorPos(rawText.length)
        var cursorRow = 0
        var found     = false
        val _ = TuiText.forEachLine(rawText, contentW, wrap = true) { (start, end) =>
            if !found then
                if pos >= start && pos <= end then found = true
                else cursorRow += 1
            end if
        }
        // Adjust scrollY to keep cursor line visible
        val oldScrollY = layout.scrollY(idx)
        if cursorRow < oldScrollY then
            layout.scrollY(idx) = cursorRow
        else if cursorRow >= oldScrollY + contentH then
            layout.scrollY(idx) = cursorRow - contentH + 1
        end if
        // Apply scroll delta to child positions immediately (avoids 1-frame lag)
        val delta = layout.scrollY(idx) - oldScrollY
        if delta != 0 then
            TuiFlexLayout.applyScrollDelta(layout, layout.firstChild(idx), delta)
    end adjustTextareaScroll

    // ---- Focus change with onBlur/onFocus ----

    /** Change focus to a new focusable index, firing onBlur on old and onFocus on new element. */
    private def changeFocus(newCurrent: Int, layout: TuiLayout)(using Frame): Unit < Sync =
        val oldIdx = focusedIndex
        current = newCurrent
        _cursorPos = -1   // Reset cursor on focus change
        _scrollOffset = 0 // Reset scroll on focus change
        val newIdx = focusedIndex
        for
            _ <- fireLifecycle(oldIdx, layout, _.onBlur)
            _ <- fireLifecycle(newIdx, layout, _.onFocus)
        yield ()
        end for
    end changeFocus

    /** Fire a lifecycle callback (onBlur/onFocus) for an element at the given layout index. */
    private def fireLifecycle(idx: Int, layout: TuiLayout, get: UI.Attrs => Maybe[Unit < Async])(using Frame): Unit < Sync =
        if idx >= 0 then
            val elem = layout.element(idx)
            if elem.isDefined then

                fireAction(get(elem.get.attrs))
            else ()
            end if
        else ()

    // ---- Dispatch ----

    def dispatch(event: InputEvent, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        event match
            case InputEvent.Key(UI.Keyboard.Tab, false, false, shift) =>
                val oldCurrent = current
                if shift then prev() else next()
                if current != oldCurrent then
                    // Restore old current temporarily for changeFocus
                    val newC = current
                    current = oldCurrent
                    changeFocus(newC, layout)
                else ()
                end if
            case InputEvent.Paste(text) =>
                handlePaste(text, layout)
            case m: InputEvent.Mouse =>
                m.kind match
                    case InputEvent.MouseKind.LeftPress =>
                        _activeIdx = hitTestAll(m.x, m.y, layout)
                        handleMouseClick(m.x, m.y, layout)
                    case InputEvent.MouseKind.LeftRelease =>
                        _activeIdx = -1
                        ()
                    case InputEvent.MouseKind.Move =>
                        _hoverIdx = hitTestAll(m.x, m.y, layout)
                        ()
                    case InputEvent.MouseKind.ScrollUp   => handleScroll(m.x, m.y, -3, layout)
                    case InputEvent.MouseKind.ScrollDown => handleScroll(m.x, m.y, 3, layout)
                    case _                               => ()
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

                        val element = elem.get
                        for
                            _ <- fireHandler(element.attrs.onKeyDown, event)
                            _ <- builtInHandle(element, event, layout)
                            _ <- fireHandler(element.attrs.onKeyUp, event)
                        yield ()
                        end for
                    end if
    end dispatch

    /** Handle scroll event: find the nearest scrollable container under the mouse and adjust its scrollY. */
    private def handleScroll(mx: Int, my: Int, delta: Int, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        val hitIdx = hitTestAll(mx, my, layout)
        if hitIdx < 0 then ()
        else
            val containerIdx = findScrollableAncestor(hitIdx, layout)
            if containerIdx < 0 then ()
            else
                val lf = layout.lFlags(containerIdx)
                val contentH = layout.h(containerIdx) - layout.padT(containerIdx) - layout.padB(containerIdx) -
                    (if TuiLayout.hasBorderT(lf) then 1 else 0) - (if TuiLayout.hasBorderB(lf) then 1 else 0)
                // Compute total child height (undo current scroll offset to get natural positions)
                val curScroll   = layout.scrollY(containerIdx)
                val totalChildH = computeChildHeight(layout, containerIdx, curScroll)
                val maxScroll   = math.max(0, totalChildH - contentH)
                val newScroll   = layout.scrollY(containerIdx) + delta
                layout.scrollY(containerIdx) = math.max(0, math.min(newScroll, maxScroll))
                ()
            end if
        end if
    end handleScroll

    /** Compute total height of children, accounting for current scroll offset. */
    private def computeChildHeight(layout: TuiLayout, parentIdx: Int, curScroll: Int): Int =
        val lf = layout.lFlags(parentIdx)
        val contentTop = layout.y(parentIdx) +
            (if TuiLayout.hasBorderT(lf) then 1 else 0) + layout.padT(parentIdx)
        @tailrec def loop(c: Int, maxBottom: Int): Int =
            if c == -1 then math.max(0, maxBottom - contentTop)
            else
                // Children are offset by scroll — undo to get natural position
                val childBottom = layout.y(c) + curScroll + layout.h(c)
                loop(layout.nextSibling(c), math.max(maxBottom, childBottom))
        loop(layout.firstChild(parentIdx), contentTop)
    end computeChildHeight

    /** Walk parent chain from idx to find nearest scrollable container (overflow==2). */
    private def findScrollableAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else if TuiLayout.isScrollable(layout.lFlags(i)) then i
            else walk(layout.parent(i))
        walk(idx)
    end findScrollableAncestor

    /** Handle mouse click: hitTest ALL nodes, find click handler via bubbling, update focus. Also handles Label+forId and Select/Option. */
    private def handleMouseClick(mx: Int, my: Int, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        val hitIdx = hitTestAll(mx, my, layout)
        if hitIdx < 0 then ()
        else
            for
                // 1. Fire onClick via event bubbling (walks parent chain)
                _ <- fireClickHandler(hitIdx, layout)
                // 2. Check for Label+forId: clicking label focuses associated input
                _ <- handleLabelForId(hitIdx, layout)
                // 3. Handle Select/Option: clicking an Option toggles selected + fires onChange
                _ <- handleSelectOptionClick(hitIdx, layout)
                // 4. Update focus to nearest focusable element at/above hit point
                _ <- updateFocusFromHit(hitIdx, layout)
            yield ()
            end for
        end if
    end handleMouseClick

    /** If the hit element (or ancestor) is a Label with forId, move focus to the target element. */
    private def handleLabelForId(hitIdx: Int, layout: TuiLayout)(using Frame): Unit < Sync =
        val labelIdx = findLabelAncestor(hitIdx, layout)
        if labelIdx >= 0 then
            val elem = layout.element(labelIdx)
            if elem.isDefined then

                val label = elem.get.asInstanceOf[UI.Label]
                val fid   = label.forId
                if fid.isDefined then
                    val targetIdx = findElementById(fid.get, layout)
                    if targetIdx >= 0 then
                        val focusPos = findFocusPosition(targetIdx)
                        if focusPos >= 0 && focusPos != current then
                            changeFocus(focusPos, layout)
                        else ()
                    else ()
                    end if
                else ()
                end if
            else ()
            end if
        else ()
        end if
    end handleLabelForId

    /** Walk parent chain looking for Tag.Label. */
    private def findLabelAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    if elem.get.isInstanceOf[UI.Label] then i
                    else walk(layout.parent(i))
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findLabelAncestor

    /** Find a layout element by its identifier (id). Returns layout index, or -1. */
    private def findElementById(id: String, layout: TuiLayout): Int =
        val n = layout.count
        @tailrec def loop(i: Int): Int =
            if i >= n then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    val element = elem.get
                    if element.attrs.identifier.exists(_ == id) then i
                    else loop(i + 1)
                else loop(i + 1)
                end if
        loop(0)
    end findElementById

    /** Find and fire the nearest onClick handler by walking the parent chain from hitIdx. */
    private def fireClickHandler(hitIdx: Int, layout: TuiLayout)(using Frame): Unit < Sync =
        val found = findClickHandler(hitIdx, layout)
        if found >= 0 then
            val elem = layout.element(found)
            if elem.isDefined then

                fireAction(elem.get.attrs.onClick)
            else ()
            end if
        else ()
        end if
    end fireClickHandler

    /** Hit test ALL elements. Overlay descendants checked first (they paint on top), then flow elements. Single reverse pass tracking both
      * overlay and flow hits.
      */
    private def hitTestAll(mx: Int, my: Int, layout: TuiLayout): Int =
        val n = layout.count
        // unsafe: while loop for performance — single pass tracking both overlay and flow hits
        var i           = n - 1
        var flowBest    = -1
        var overlayBest = -1
        while i >= 0 && (overlayBest < 0 || flowBest < 0) do
            val lf = layout.lFlags(i)
            if !TuiLayout.isHidden(lf) then
                val nx = layout.x(i)
                val ny = layout.y(i)
                val nw = layout.w(i)
                val nh = layout.h(i)
                if mx >= nx && mx < nx + nw && my >= ny && my < ny + nh then
                    if isInOverlay(i, layout) then
                        if overlayBest < 0 then overlayBest = i
                    else if flowBest < 0 then flowBest = i
                end if
            end if
            i -= 1
        end while
        if overlayBest >= 0 then overlayBest else flowBest
    end hitTestAll

    /** Check if a node is inside an overlay subtree (i.e., has an overlay ancestor, or is itself an overlay). */
    private def isInOverlay(idx: Int, layout: TuiLayout): Boolean =
        @tailrec def walk(i: Int): Boolean =
            if i < 0 then false
            else if TuiLayout.isOverlay(layout.lFlags(i)) then true
            else walk(layout.parent(i))
        walk(idx)
    end isInOverlay

    /** Find the topmost (last in tree order) visible overlay. Returns layout index or -1. */
    private def findTopmostOverlay(layout: TuiLayout): Int =
        val n = layout.count
        // unsafe: while for array iteration
        var highest = -1
        var i       = 0
        while i < n do
            if TuiLayout.isOverlay(layout.lFlags(i)) && !TuiLayout.isHidden(layout.lFlags(i)) then
                highest = i
            i += 1
        end while
        highest
    end findTopmostOverlay

    /** Filter the focusable indices array to only include descendants of the given overlay. */
    private def filterToOverlayDescendants(overlayIdx: Int, layout: TuiLayout): Unit =
        // unsafe: while for array iteration
        var writePos = 0
        var readPos  = 0
        while readPos < count do
            val layoutIdx = indices(readPos)
            if isDescendantOf(layoutIdx, overlayIdx, layout) then
                indices(writePos) = layoutIdx
                writePos += 1
            readPos += 1
        end while
        count = writePos
    end filterToOverlayDescendants

    /** Check if idx is a descendant of ancestorIdx (or is ancestorIdx itself). */
    private def isDescendantOf(idx: Int, ancestorIdx: Int, layout: TuiLayout): Boolean =
        @tailrec def walk(i: Int): Boolean =
            if i < 0 then false
            else if i == ancestorIdx then true
            else walk(layout.parent(i))
        walk(idx)
    end isDescendantOf

    /** Walk parent chain from idx upward to find nearest element with an onClick handler. Returns layout index of the handler element, or
      * -1 if none found.
      */
    private def findClickHandler(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    val element = elem.get
                    if element.attrs.onClick.isDefined then i
                    else walk(layout.parent(i))
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findClickHandler

    /** Find the nearest focusable element at or above hitIdx and update focus. */
    private def updateFocusFromHit(hitIdx: Int, layout: TuiLayout)(using Frame): Unit < Sync =
        val focusableIdx = findFocusableAncestor(hitIdx, layout)
        if focusableIdx >= 0 then
            // Find which focus index this layout index corresponds to
            val focusPos = findFocusPosition(focusableIdx)
            if focusPos >= 0 && focusPos != current then
                changeFocus(focusPos, layout)
            else ()
        else ()
        end if
    end updateFocusFromHit

    /** Walk parent chain from idx to find nearest focusable element. */
    private def findFocusableAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    val element = elem.get
                    if (element.isInstanceOf[UI.Focusable] ||
                            element.attrs.tabIndex.isDefined ||
                            element.attrs.onKeyDown.isDefined ||
                            element.attrs.onKeyUp.isDefined) &&
                        !TuiLayout.isHidden(layout.lFlags(i)) &&
                        !TuiLayout.isDisabled(layout.lFlags(i))
                    then i
                    else walk(layout.parent(i))
                    end if
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findFocusableAncestor

    /** Find the position in the focusable indices array for a given layout index. */
    private def findFocusPosition(layoutIdx: Int): Int =
        @tailrec def loop(i: Int): Int =
            if i >= count then -1
            else if indices(i) == layoutIdx then i
            else loop(i + 1)
        loop(0)
    end findFocusPosition

    // ---- Select/Option Handling ----

    /** If the hit element is inside a Select's Option, toggle selected and fire onChange. */
    private def handleSelectOptionClick(hitIdx: Int, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        val optionIdx = findOptionAncestor(hitIdx, layout)
        if optionIdx < 0 then ()
        else
            val selectIdx = findSelectAncestor(layout.parent(optionIdx), layout)
            if selectIdx < 0 then ()
            else
                val optionElem = layout.element(optionIdx)
                val selectElem = layout.element(selectIdx)
                if optionElem.isEmpty || selectElem.isEmpty then ()
                else

                    val opt = optionElem.get.asInstanceOf[UI.Opt]
                    val sel = selectElem.get.asInstanceOf[UI.Select]
                    for
                        _ <- toggleSelected(opt.selected)
                        _ <- fireOnChange(sel.onChange, getOptionText(optionIdx, layout))
                    yield ()
                    end for
                end if
            end if
        end if
    end handleSelectOptionClick

    /** Walk parent chain looking for Tag.Option. */
    private def findOptionAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    if elem.get.isInstanceOf[UI.Opt] then i
                    else walk(layout.parent(i))
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findOptionAncestor

    /** Walk parent chain looking for Tag.Select. */
    private def findSelectAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then

                    if elem.get.isInstanceOf[UI.Select] then i
                    else walk(layout.parent(i))
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findSelectAncestor

    /** Toggle selected state for an Option element. */
    private def toggleSelected(selected: Maybe[Boolean | Signal[Boolean]])(using Frame, AllowUnsafe): Unit < Sync =
        if selected.isEmpty then ()
        else
            selected.get match
                case sig: Signal[?] =>
                    sig match
                        case ref: SignalRef[?] =>
                            // unsafe: asInstanceOf for union type resolution
                            val typedRef = ref.asInstanceOf[SignalRef[Boolean]]
                            val cur      = Sync.Unsafe.evalOrThrow(typedRef.get)
                            typedRef.set(!cur).unit
                        case _ => ()
                case _ => ()
    end toggleSelected

    /** Get the text content of an Option element from its first text child. */
    private def getOptionText(optionIdx: Int, layout: TuiLayout): String =
        val childIdx = layout.firstChild(optionIdx)
        if childIdx >= 0 && layout.text(childIdx).isDefined then
            val t = layout.text(childIdx).get
            // Strip the "▶ " prefix if present
            if t.startsWith("\u25b6 ") then t.substring(2) else t
        else ""
        end if
    end getOptionText

    // ---- Handler Helpers ----

    private def fireHandler(h: Maybe[UI.KeyEvent => Unit < Async], event: InputEvent)(using Frame): Unit < Sync =
        if h.isDefined then
            event match
                case InputEvent.Key(key, ctrl, alt, shift) =>
                    Fiber.initUnscoped(h.get(UI.KeyEvent(key, ctrl, alt, shift))).unit
                case _ => ()
        else ()

    private def fireAction(action: Maybe[Unit < Async])(using Frame): Unit < Sync =
        if action.isDefined then Fiber.initUnscoped(action.get).unit
        else ()

    // ---- Built-in Handling ----

    /** Flag-driven keyboard dispatch:
      *   - Tag.isActivatable: Enter/Space -> fire onClick + handle href for Anchor
      *   - Tag.isTextInput: character input -> modify value with cursor tracking, or checkbox toggle
      */
    private def builtInHandle(element: UI.Element, event: InputEvent, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        if element.isInstanceOf[UI.Activatable] then
            event match
                case InputEvent.Key(UI.Keyboard.Enter, false, false, false) | InputEvent.Key(UI.Keyboard.Space, false, false, false) =>
                    for
                        _ <- fireAction(element.attrs.onClick)
                        _ <- handleHrefActivation(element)
                    yield ()
                    end for
                case _ => ()
        else if element.isInstanceOf[UI.BooleanInput] then
            event match
                case InputEvent.Key(UI.Keyboard.Enter, false, false, false) | InputEvent.Key(UI.Keyboard.Space, false, false, false) =>
                    handleBooleanToggle(element.asInstanceOf[UI.BooleanInput])
                case _ => ()
        else if element.isInstanceOf[UI.TextInput] then
            handleTextInput(element.asInstanceOf[UI.TextInput], event, layout, allowNewline = element.isInstanceOf[UI.Textarea])
        else ()
        end if
    end builtInHandle

    /** Toggle boolean input (Checkbox/Radio) checked state via SignalRef. */
    private def handleBooleanToggle(bi: UI.BooleanInput)(using Frame, AllowUnsafe): Unit < Sync =
        bi.checked match
            case Present(sig: Signal[?]) =>
                sig match
                    case ref: SignalRef[?] =>
                        // unsafe: asInstanceOf for union type resolution
                        val typedRef = ref.asInstanceOf[SignalRef[Boolean]]
                        val cur      = Sync.Unsafe.evalOrThrow(typedRef.get)
                        val next     = !cur
                        for
                            _ <- typedRef.set(next)
                            _ <- fireOnChangeBool(bi.onChange, next)
                        yield ()
                        end for
                    case _ => ()
            case _ => ()
    end handleBooleanToggle

    /** Fire onChange callback with the new value. */
    private def fireOnChange(onChange: Maybe[String => Unit < Async], value: String)(using Frame): Unit < Sync =
        if onChange.isDefined then
            Fiber.initUnscoped(onChange.get(value)).unit
        else ()

    /** Fire onChange callback with a boolean value. */
    private def fireOnChangeBool(onChange: Maybe[Boolean => Unit < Async], value: Boolean)(using Frame): Unit < Sync =
        if onChange.isDefined then
            Fiber.initUnscoped(onChange.get(value)).unit
        else ()

    /** Open href URL in system browser for Anchor elements. */
    private def handleHrefActivation(element: UI.Element)(using Frame, AllowUnsafe): Unit < Sync =
        element match
            case anchor: UI.Anchor if anchor.href.isDefined =>
                val url = anchor.href.get match
                    case s: String      => s
                    case sig: Signal[?] =>
                        // unsafe: asInstanceOf for union type resolution
                        Sync.Unsafe.evalOrThrow(sig.asInstanceOf[Signal[String]].current)
                openInBrowser(url)
            case _ => ()
    end handleHrefActivation

    /** Open a URL in the system browser. */
    private def openInBrowser(url: String)(using Frame): Unit < Sync =
        Fiber.initUnscoped {
            Sync.defer {
                val os = java.lang.System.getProperty("os.name", "").toLowerCase
                val cmd =
                    if os.contains("mac") then Array("open", url)
                    else if os.contains("win") then Array("cmd", "/c", "start", url)
                    else Array("xdg-open", url) // Linux and others
                val _ = Runtime.getRuntime.exec(cmd)
            }
        }.unit
    end openInBrowser

    private def handleTextInput(
        ti: UI.TextInput,
        event: InputEvent,
        layout: TuiLayout,
        allowNewline: Boolean
    )(using Frame, AllowUnsafe): Unit < Sync =
        ti.value match
            case Present(ref: SignalRef[?]) =>
                // unsafe: asInstanceOf for union type resolution
                val r   = ref.asInstanceOf[SignalRef[String]]
                val cur = Sync.Unsafe.evalOrThrow(r.get)
                val pos = effectiveCursorPos(cur.length)
                event match
                    case InputEvent.Key(k, false, false, false) if k.charValue.isDefined =>
                        val ch     = k.charValue.get
                        val newVal = cur.substring(0, pos) + ch + cur.substring(pos)
                        _cursorPos = pos + 1
                        for
                            _ <- r.set(newVal)
                            _ <- fireOnInput(ti.onInput, newVal)
                        yield ()
                        end for
                    case InputEvent.Key(UI.Keyboard.Backspace, false, false, false) =>
                        if pos > 0 then
                            val newVal = cur.substring(0, pos - 1) + cur.substring(pos)
                            _cursorPos = pos - 1
                            for
                                _ <- r.set(newVal)
                                _ <- fireOnInput(ti.onInput, newVal)
                            yield ()
                            end for
                        else ()
                    case InputEvent.Key(UI.Keyboard.Delete, false, false, false) =>
                        if pos < cur.length then
                            val newVal = cur.substring(0, pos) + cur.substring(pos + 1)
                            // cursorPos stays the same
                            for
                                _ <- r.set(newVal)
                                _ <- fireOnInput(ti.onInput, newVal)
                            yield ()
                            end for
                        else ()
                    case InputEvent.Key(UI.Keyboard.ArrowLeft, false, false, false) =>
                        if pos > 0 then _cursorPos = pos - 1
                        ()
                    case InputEvent.Key(UI.Keyboard.ArrowRight, false, false, false) =>
                        if pos < cur.length then _cursorPos = pos + 1
                        ()
                    case InputEvent.Key(UI.Keyboard.ArrowUp, false, false, false) if allowNewline =>
                        moveCursorVertically(cur, pos, -1, layout)
                        ()
                    case InputEvent.Key(UI.Keyboard.ArrowDown, false, false, false) if allowNewline =>
                        moveCursorVertically(cur, pos, 1, layout)
                        ()
                    case InputEvent.Key(UI.Keyboard.Home, false, false, false) =>
                        _cursorPos = 0
                        ()
                    case InputEvent.Key(UI.Keyboard.End, false, false, false) =>
                        _cursorPos = -1 // -1 = end of text
                        ()
                    case InputEvent.Key(UI.Keyboard.Enter, false, false, false) =>
                        if allowNewline then
                            val newVal = cur.substring(0, pos) + "\n" + cur.substring(pos)
                            _cursorPos = pos + 1
                            for
                                _ <- r.set(newVal)
                                _ <- fireOnInput(ti.onInput, newVal)
                            yield ()
                            end for
                        else
                            // Enter on non-textarea text input: walk parent chain for Form → fire onSubmit
                            fireSubmitFromParent(layout)
                    case _ => ()
                end match
            case _ => ()
    end handleTextInput

    /** Move cursor up or down by one wrapped line, preserving column position. */
    private def moveCursorVertically(text: String, pos: Int, direction: Int, layout: TuiLayout): Unit =
        val idx = focusedIndex
        if idx < 0 then return // unsafe: early return
        val lf = layout.lFlags(idx)
        val contentW = layout.w(idx) - layout.padL(idx) - layout.padR(idx) -
            (if TuiLayout.hasBorderL(lf) then 1 else 0) - (if TuiLayout.hasBorderR(lf) then 1 else 0)
        if contentW <= 0 then return // unsafe: early return
        // Collect wrapped line start/end positions
        var lineCount = 0
        // unsafe: fixed-size arrays for line ranges
        val starts = new Array[Int](256)
        val ends   = new Array[Int](256)
        val _ = TuiText.forEachLine(text, contentW, wrap = true) { (start, end) =>
            if lineCount < 256 then
                starts(lineCount) = start
                ends(lineCount) = end
                lineCount += 1
            end if
        }
        // Find which line the cursor is on
        var cursorLine = lineCount - 1
        var cursorCol  = 0
        // unsafe: while for line scanning
        var li = 0
        while li < lineCount do
            val s = starts(li)
            val e = ends(li)
            if pos >= s && (pos < e || (li == lineCount - 1 && pos == e)) then
                cursorLine = li
                cursorCol = pos - s
                li = lineCount // break
            end if
            li += 1
        end while
        val targetLine = cursorLine + direction
        if targetLine >= 0 && targetLine < lineCount then
            val targetLen = ends(targetLine) - starts(targetLine)
            _cursorPos = starts(targetLine) + math.min(cursorCol, targetLen)
        end if
    end moveCursorVertically

    /** Fire onInput callback with the new value. */
    private def fireOnInput(onInput: Maybe[String => Unit < Async], value: String)(using Frame): Unit < Sync =
        if onInput.isDefined then
            Fiber.initUnscoped(onInput.get(value)).unit
        else ()

    /** Handle paste event: insert full text at cursor position in focused text input. */
    private def handlePaste(text: String, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
        if current < 0 || current >= count then ()
        else
            val idx  = indices(current)
            val elem = layout.element(idx)
            if elem.isEmpty then ()
            else

                val element = elem.get
                if !element.isInstanceOf[UI.TextInput] then ()
                else
                    val textInp = element.asInstanceOf[UI.TextInput]
                    textInp.value match
                        case Present(ref: SignalRef[?]) =>
                            // unsafe: asInstanceOf for union type resolution
                            val r      = ref.asInstanceOf[SignalRef[String]]
                            val cur    = Sync.Unsafe.evalOrThrow(r.get)
                            val pos    = effectiveCursorPos(cur.length)
                            val newVal = cur.substring(0, pos) + text + cur.substring(pos)
                            _cursorPos = pos + text.length
                            for
                                _ <- r.set(newVal)
                                _ <- fireOnInput(textInp.onInput, newVal)
                            yield ()
                            end for
                        case _ => ()
                    end match
                end if
            end if
    end handlePaste

    /** Walk parent chain from focused element looking for Form, fire its onSubmit. */
    private def fireSubmitFromParent(layout: TuiLayout)(using Frame): Unit < Sync =
        if current < 0 || current >= count then ()
        else
            val startIdx = indices(current)
            val formIdx  = findFormAncestor(startIdx, layout)
            if formIdx >= 0 then
                val elem = layout.element(formIdx)
                if elem.isDefined then
                    fireAction(elem.get.asInstanceOf[UI.Form].onSubmit)
                else ()
                end if
            else ()
            end if
    end fireSubmitFromParent

    /** Walk parent chain looking for Form. */
    private def findFormAncestor(idx: Int, layout: TuiLayout): Int =
        @tailrec def walk(i: Int): Int =
            if i < 0 then -1
            else
                val elem = layout.element(i)
                if elem.isDefined then
                    if elem.get.isInstanceOf[UI.Form] then i
                    else walk(layout.parent(i))
                else walk(layout.parent(i))
                end if
        walk(idx)
    end findFormAncestor

end TuiFocus
