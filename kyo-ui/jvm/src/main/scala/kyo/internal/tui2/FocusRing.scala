package kyo.internal.tui2

import kyo.*
import kyo.Maybe.*
import kyo.internal.tui2.widget.ValueResolver
import scala.annotation.tailrec

/** Focus management using parallel arrays instead of IdentityHashMap.
  *
  * Scans the UI tree for focusable elements each frame, migrates per-element state (cursor position, scroll offsets) from the previous
  * frame, and provides focus cycling (next/prev) and per-element state accessors.
  *
  * Tracks: enclosing Form (for Form.onSubmit), tabIndex (for focus order), and Foreach keys (for state migration).
  */
final private[kyo] class FocusRing:

    private val maxFocusables = 64

    // Current frame
    private val elems          = new Array[UI.Element](maxFocusables)
    private val cursorPosArr   = new Array[Int](maxFocusables)
    private val scrollXArr     = new Array[Int](maxFocusables)
    private val scrollYArr     = new Array[Int](maxFocusables)
    private val selStartArr    = new Array[Int](maxFocusables) // -1 = no selection
    private val selEndArr      = new Array[Int](maxFocusables)
    private val formArr        = new Array[UI.Form](maxFocusables)
    private val tabIndexArr    = new Array[Int](maxFocusables)
    private val keyArr         = new Array[String](maxFocusables)
    private var count: Int     = 0
    private var focusIdx: Int  = -1
    private var focusableCount = 0

    // Previous frame (for state migration)
    private val prevElems      = new Array[UI.Element](maxFocusables)
    private val prevCursorPos  = new Array[Int](maxFocusables)
    private val prevScrollX    = new Array[Int](maxFocusables)
    private val prevScrollY    = new Array[Int](maxFocusables)
    private val prevSelStart   = new Array[Int](maxFocusables)
    private val prevSelEnd     = new Array[Int](maxFocusables)
    private val prevKeyArr     = new Array[String](maxFocusables)
    private var prevCount: Int = 0

    // Pre-allocated temp arrays for tabIndex permutation sort
    private val permBuf     = new Array[Int](maxFocusables)
    private val tmpElems    = new Array[UI.Element](maxFocusables)
    private val tmpForm     = new Array[UI.Form](maxFocusables)
    private val tmpCursor   = new Array[Int](maxFocusables)
    private val tmpScrollX  = new Array[Int](maxFocusables)
    private val tmpScrollY  = new Array[Int](maxFocusables)
    private val tmpSelStart = new Array[Int](maxFocusables)
    private val tmpSelEnd   = new Array[Int](maxFocusables)
    private val tmpTabIdx   = new Array[Int](maxFocusables)
    private val tmpKey      = new Array[String](maxFocusables)

    // Cursor screen position (set during render, read after for terminal cursor placement)
    var cursorScreenX: Int = -1
    var cursorScreenY: Int = -1

    /** Scan the UI tree for focusable elements and migrate state from previous frame. */
    def scan(ui: UI, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        // 1. Copy current → prev
        java.lang.System.arraycopy(elems, 0, prevElems, 0, count)
        java.lang.System.arraycopy(cursorPosArr, 0, prevCursorPos, 0, count)
        java.lang.System.arraycopy(scrollXArr, 0, prevScrollX, 0, count)
        java.lang.System.arraycopy(scrollYArr, 0, prevScrollY, 0, count)
        java.lang.System.arraycopy(selStartArr, 0, prevSelStart, 0, count)
        java.lang.System.arraycopy(selEndArr, 0, prevSelEnd, 0, count)
        java.lang.System.arraycopy(keyArr, 0, prevKeyArr, 0, count)
        val prevFocused    = focused
        val prevFocusedIdx = focusIdx
        prevCount = count
        count = 0

        // 2. Walk tree, collect focusables
        scanTree(ui, Absent, "", ctx)

        // 3. Migrate state from prev
        migrateState()

        // 4. Sort by tabIndex
        sortByTabIndex()

        // 5. Restore focus index
        focusIdx = prevFocused match
            case Present(elem) =>
                val idx = findIndex(elem)
                if idx >= 0 then idx
                // Fallback: match by key from previous frame (handles Signal.map recreated elements)
                else if prevFocusedIdx >= 0 && prevFocusedIdx < prevCount then
                    val prevKey = prevKeyArr(prevFocusedIdx)
                    if prevKey.nonEmpty then findByKey(prevKey) else -1
                else -1
                end if
            case _ => if count > 0 then 0 else -1

        // Reset cursor screen position
        cursorScreenX = -1
        cursorScreenY = -1
    end scan

    private def scanTree(ui: UI, formCtx: Maybe[UI.Form], currentKey: String, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        ui match
            case form: UI.Form =>
                scanChildren(form, Present(form), currentKey, ctx)
            case elem: UI.Focusable =>
                if count < maxFocusables then
                    elems(count) = elem
                    formArr(count) = formCtx.getOrElse(null.asInstanceOf[UI.Form])
                    cursorPosArr(count) = 0
                    scrollXArr(count) = 0
                    scrollYArr(count) = 0
                    selStartArr(count) = -1
                    selEndArr(count) = -1
                    keyArr(count) = currentKey
                    val ti = elem.attrs.tabIndex match
                        case Present(n) => n
                        case _          => 0
                    tabIndexArr(count) = ti
                    count += 1
                end if
                scanChildren(elem, formCtx, currentKey, ctx)
            case fe: UI.Foreach[?] =>
                val seq = ctx.resolve(fe.signal)
                @tailrec def scanItems(i: Int): Unit =
                    if i < seq.size then
                        val item    = seq(i)
                        val child   = ValueResolver.foreachApply(fe, i, item)
                        val itemKey = ValueResolver.foreachKey(fe, item)
                        scanTree(child, formCtx, itemKey, ctx)
                        scanItems(i + 1)
                scanItems(0)
            case elem: UI.Element =>
                scanChildren(elem, formCtx, currentKey, ctx)
            case UI.Fragment(children) =>
                scanSpan(children, 0, formCtx, currentKey, ctx)
            case UI.Reactive(signal) =>
                // Generate a positional key prefix for elements inside reactive nodes.
                // Signal.map re-evaluates on each scan, creating new element objects.
                // Without keys, identity matching fails and focus/state is lost.
                val reactiveKey =
                    if currentKey.nonEmpty then currentKey
                    else s"r${java.lang.System.identityHashCode(signal)}"
                val startCount = count
                scanTree(ctx.resolve(signal), formCtx, reactiveKey, ctx)
                // Append position suffix to make keys unique within the reactive
                @tailrec def assignPositions(i: Int): Unit =
                    if i < count then
                        keyArr(i) = s"${keyArr(i)}:${i - startCount}"
                        assignPositions(i + 1)
                assignPositions(startCount)
            case _ => ()

    private def scanChildren(elem: UI.Element, formCtx: Maybe[UI.Form], currentKey: String, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        scanSpan(elem.children, 0, formCtx, currentKey, ctx)

    @tailrec private def scanSpan(
        children: Span[UI],
        i: Int,
        formCtx: Maybe[UI.Form],
        currentKey: String,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        if i < children.size then
            scanTree(children(i), formCtx, currentKey, ctx)
            scanSpan(children, i + 1, formCtx, currentKey, ctx)

    private def migrateState(): Unit =
        @tailrec def outer(i: Int): Unit =
            if i < count then
                @tailrec def inner(j: Int): Unit =
                    if j < prevCount then
                        val matched =
                            if keyArr(i).nonEmpty && prevKeyArr(j).nonEmpty then
                                keyArr(i) == prevKeyArr(j)
                            else
                                prevElems(j) eq elems(i)
                        if matched then
                            cursorPosArr(i) = prevCursorPos(j)
                            scrollXArr(i) = prevScrollX(j)
                            scrollYArr(i) = prevScrollY(j)
                            selStartArr(i) = prevSelStart(j)
                            selEndArr(i) = prevSelEnd(j)
                        else inner(j + 1)
                        end if
                inner(0)
                outer(i + 1)
        outer(0)
    end migrateState

    private def sortByTabIndex(): Unit =
        @tailrec def initPerm(i: Int): Unit =
            if i < count then
                permBuf(i) = i; initPerm(i + 1)
        initPerm(0)

        @tailrec def sortLoop(i: Int): Unit =
            if i < count then
                val pi = permBuf(i)
                val ki = sortKey(pi)
                @tailrec def insertLoop(j: Int): Unit =
                    if j > 0 && sortKey(permBuf(j - 1)) > ki then
                        permBuf(j) = permBuf(j - 1)
                        insertLoop(j - 1)
                    else
                        permBuf(j) = pi
                insertLoop(i)
                sortLoop(i + 1)
        sortLoop(1)

        java.lang.System.arraycopy(elems, 0, tmpElems, 0, count)
        java.lang.System.arraycopy(formArr, 0, tmpForm, 0, count)
        java.lang.System.arraycopy(cursorPosArr, 0, tmpCursor, 0, count)
        java.lang.System.arraycopy(scrollXArr, 0, tmpScrollX, 0, count)
        java.lang.System.arraycopy(scrollYArr, 0, tmpScrollY, 0, count)
        java.lang.System.arraycopy(selStartArr, 0, tmpSelStart, 0, count)
        java.lang.System.arraycopy(selEndArr, 0, tmpSelEnd, 0, count)
        java.lang.System.arraycopy(tabIndexArr, 0, tmpTabIdx, 0, count)
        java.lang.System.arraycopy(keyArr, 0, tmpKey, 0, count)

        @tailrec def applyPerm(i: Int): Unit =
            if i < count then
                val src = permBuf(i)
                elems(i) = tmpElems(src)
                formArr(i) = tmpForm(src)
                cursorPosArr(i) = tmpCursor(src)
                scrollXArr(i) = tmpScrollX(src)
                scrollYArr(i) = tmpScrollY(src)
                selStartArr(i) = tmpSelStart(src)
                selEndArr(i) = tmpSelEnd(src)
                tabIndexArr(i) = tmpTabIdx(src)
                keyArr(i) = tmpKey(src)
                applyPerm(i + 1)
        applyPerm(0)

        @tailrec def countFocusable(i: Int, acc: Int): Int =
            if i >= count then acc
            else if tabIndexArr(i) < 0 then acc
            else countFocusable(i + 1, acc + 1)
        focusableCount = countFocusable(0, 0)
    end sortByTabIndex

    private def sortKey(idx: Int): Int =
        val ti = tabIndexArr(idx)
        if ti < 0 then Int.MaxValue
        else if ti > 0 then ti
        else maxFocusables + idx
    end sortKey

    private def findIndex(elem: UI.Element): Int =
        @tailrec def loop(i: Int): Int =
            if i >= count then -1
            else if elems(i) eq elem then i
            else loop(i + 1)
        loop(0)
    end findIndex

    private def findByKey(key: String): Int =
        @tailrec def loop(i: Int): Int =
            if i >= count then -1
            else if keyArr(i) == key then i
            else loop(i + 1)
        loop(0)
    end findByKey

    // ---- Focus accessors ----

    def focusCount: Int = count

    def focusedIndex: Int = focusIdx

    def focused: Maybe[UI.Element] =
        Maybe.when(focusIdx >= 0 && focusIdx < count)(elems(focusIdx))

    def isFocused(elem: UI.Element): Boolean =
        focused match
            case Present(f) => f eq elem
            case _          => false

    def elementAt(i: Int): UI.Element =
        if i >= 0 && i < count then elems(i) else null

    /** Get the Form enclosing the currently focused element (if any). */
    def formForFocused: Maybe[UI.Form] =
        if focusIdx >= 0 && focusIdx < count then Maybe(formArr(focusIdx))
        else Absent

    // ---- Focus cycling ----

    def next(): Unit =
        if focusableCount > 0 then focusIdx = (focusIdx + 1) % focusableCount

    def prev(): Unit =
        if focusableCount > 0 then focusIdx = if focusIdx <= 0 then focusableCount - 1 else focusIdx - 1

    def focusOn(elem: UI.Element): Unit =
        val idx = findIndex(elem)
        if idx >= 0 then focusIdx = idx

    // ---- Per-element state accessors ----

    def cursorPos(elem: UI.Element): Int =
        val i = findIndex(elem)
        if i >= 0 then cursorPosArr(i) else 0

    def setCursorPos(elem: UI.Element, pos: Int): Unit =
        val i = findIndex(elem)
        if i >= 0 then cursorPosArr(i) = pos

    def scrollX(elem: UI.Element): Int =
        val i = findIndex(elem)
        if i >= 0 then scrollXArr(i) else 0

    def setScrollX(elem: UI.Element, v: Int): Unit =
        val i = findIndex(elem)
        if i >= 0 then scrollXArr(i) = v

    def scrollY(elem: UI.Element): Int =
        val i = findIndex(elem)
        if i >= 0 then scrollYArr(i) else 0

    def setScrollY(elem: UI.Element, v: Int): Unit =
        val i = findIndex(elem)
        if i >= 0 then scrollYArr(i) = v

    /** Iterate all focusable elements in the ring. */
    def forEachFocusable(f: UI.Element => Unit): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < count then
                f(elems(i))
                loop(i + 1)
        loop(0)
    end forEachFocusable

    // ---- Selection state ----

    def selStart(elem: UI.Element): Int =
        val i = findIndex(elem)
        if i >= 0 then selStartArr(i) else -1

    def selEnd(elem: UI.Element): Int =
        val i = findIndex(elem)
        if i >= 0 then selEndArr(i) else -1

    def setSelection(elem: UI.Element, start: Int, end: Int): Unit =
        val i = findIndex(elem)
        if i >= 0 then
            selStartArr(i) = start
            selEndArr(i) = end
    end setSelection

    def clearSelection(elem: UI.Element): Unit =
        val i = findIndex(elem)
        if i >= 0 then
            selStartArr(i) = -1
            selEndArr(i) = -1
    end clearSelection

    def hasSelection(elem: UI.Element): Boolean =
        val i = findIndex(elem)
        i >= 0 && selStartArr(i) >= 0 && selStartArr(i) != selEndArr(i)

    // ---- Diagnostics ----

    /** Dump all elements in the focus ring with their state. */
    def dump(using Frame, AllowUnsafe): String =
        val sb = new StringBuilder
        sb.append(s"FocusRing(count=$count, focusIdx=$focusIdx, focusableCount=$focusableCount)\n")
        @tailrec def loop(i: Int): Unit =
            if i < count then
                val elem    = elems(i)
                val typ     = elem.getClass.getSimpleName
                val id      = elem.attrs.identifier.getOrElse("-")
                val focused = if i == focusIdx then " [FOCUSED]" else ""
                val cursor  = cursorPosArr(i)
                val tabIdx  = tabIndexArr(i)
                val hasRef = elem match
                    case ti: UI.TextInput =>
                        ti.value match
                            case Maybe.Present(_: Signal.SignalRef[?]) => true
                            case _                                     => false
                    case _ => false
                val value = elem match
                    case ti: UI.TextInput =>
                        ti.value match
                            case Maybe.Present(s: String)               => s"\"$s\""
                            case Maybe.Present(sr: Signal.SignalRef[?]) => s"ref=${sr.asInstanceOf[Signal.SignalRef[String]].unsafe.get()}"
                            case _                                      => "absent"
                    case _ => "n/a"
                sb.append(s"  [$i] $typ(id=$id, tab=$tabIdx, cursor=$cursor, hasRef=$hasRef, value=$value)$focused\n")
                loop(i + 1)
        loop(0)
        sb.toString
    end dump

end FocusRing
