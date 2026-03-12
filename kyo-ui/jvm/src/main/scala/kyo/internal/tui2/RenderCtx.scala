package kyo.internal.tui2

import kyo.*
import kyo.Maybe.*

/** Mutable context passed through the tree walk.
  *
  * Holds pre-allocated ResolvedStyle, layout scratch arrays, inherited style state, and references to all subsystems (screen, focus,
  * signals, overlays). One instance per backend, reused across frames.
  */
final private[kyo] class RenderCtx(
    val screen: Screen,
    val canvas: Canvas,
    val focus: FocusRing,
    val theme: ResolvedTheme,
    val signals: SignalCollector,
    val overlays: OverlayCollector
):

    // ---- Inherited style (single object + pre-allocated save stack) ----
    val inherited: InheritedStyle                     = new InheritedStyle
    private val inheritedStack: Array[InheritedStyle] = Array.fill(32)(new InheritedStyle)
    private var inheritedDepth: Int                   = 0

    inline def saveInherited(): Unit =
        inheritedStack(inheritedDepth).copyFrom(inherited)
        inheritedDepth += 1

    inline def restoreInherited(): Unit =
        inheritedDepth -= 1
        inherited.copyFrom(inheritedStack(inheritedDepth))

    def cellStyle: CellStyle = inherited.cellStyle

    // ---- Interaction targets ----
    var hoverTarget: Maybe[UI.Element]  = Absent
    var activeTarget: Maybe[UI.Element] = Absent

    // ---- Element ID registry (for Label.forId) ----
    val identifiers: IdentifierRegistry = new IdentifierRegistry

    // ---- Image cache (for Img.src) ----
    val imageCache: ImageCache = new ImageCache

    // ---- FileInput selected paths ----
    val fileInputPaths = new java.util.IdentityHashMap[UI.FileInput, String]()

    // ---- Picker (Select) internal value storage (for selects without bound SignalRef) ----
    val pickerValues = new java.util.IdentityHashMap[UI.PickerInput, String]()

    // ---- Select dropdown expansion state ----
    // Tracks whether the focused Select is expanded. Identity-free — avoids
    // the reactive-node identity problem (new element instances each frame).
    var dropdownOpen: Boolean  = false
    var dropdownHighlight: Int = 0
    // Deferred dropdown render info (set during render, drawn after overlays with clip reset)
    var dropdownX: Int                        = 0
    var dropdownY: Int                        = 0
    var dropdownW: Int                        = 0
    var dropdownOptionCount: Int              = 0
    private val dropdownTexts: Array[String]  = new Array[String](32)
    private val dropdownValues: Array[String] = new Array[String](32)
    var dropdownStyle: CellStyle              = CellStyle.Empty
    var hasDropdown: Boolean                  = false

    def scheduleDropdown(
        sel: UI.Select,
        cx: Int,
        cy: Int,
        cw: Int,
        texts: Array[String],
        values: Array[String],
        count: Int,
        highlight: Int,
        style: CellStyle
    ): Unit =
        dropdownX = cx
        dropdownY = cy
        dropdownW = cw
        dropdownOptionCount = math.min(count, dropdownTexts.length)
        var i = 0
        while i < dropdownOptionCount do
            dropdownTexts(i) = texts(i)
            dropdownValues(i) = values(i)
            i += 1
        end while
        dropdownHighlight = highlight
        dropdownStyle = style
        hasDropdown = true
    end scheduleDropdown

    /** Paint the deferred dropdown. Called after overlays with clip reset. */
    def renderDropdown(): Unit =
        if !hasDropdown || dropdownOptionCount == 0 then return
        val x     = dropdownX
        val y     = dropdownY + 2                 // Skip content row (+1) and separator (+1), first option row
        val w     = dropdownW
        val count = dropdownOptionCount
        val maxY  = screen.height
        val maxH  = math.min(count, maxY - y - 1) // -1 for bottom border
        if maxH <= 0 then return
        val style       = dropdownStyle
        val hlStyle     = style.swapColors
        val borderStyle = CellStyle(style.fg, style.bg, false, false, false, false, false)
        // Connect to select: replace bottom corners with tee junctions
        canvas.drawChar(x, y - 1, '\u251c', borderStyle)                      // ├
        canvas.hline(x + 1, y - 1, math.max(0, w - 2), '\u2500', borderStyle) // ─
        canvas.drawChar(x + w - 1, y - 1, '\u2524', borderStyle)              // ┤
        // Option rows
        var row = 0
        while row < maxH do
            val text     = dropdownTexts(row)
            val isHL     = row == dropdownHighlight
            val rowStyle = if isHL then hlStyle else style
            canvas.drawChar(x, y + row, '\u2502', borderStyle) // │
            canvas.hline(x + 1, y + row, math.max(0, w - 2), ' ', rowStyle)
            discard(canvas.drawString(x + 1, y + row, math.max(0, w - 2), text, 0, rowStyle))
            canvas.drawChar(x + w - 1, y + row, '\u2502', borderStyle) // │
            row += 1
        end while
        // Bottom border
        canvas.drawChar(x, y + maxH, '\u2514', borderStyle)                      // └
        canvas.hline(x + 1, y + maxH, math.max(0, w - 2), '\u2500', borderStyle) // ─
        canvas.drawChar(x + w - 1, y + maxH, '\u2518', borderStyle)              // ┘
    end renderDropdown

    // ---- List context (for Ul/Ol marker rendering) ----
    var listKind: Int  = 0 // 0=none, 1=ul, 2=ol
    var listIndex: Int = 0

    // ---- Terminal reference (for FileInput suspend/resume) ----
    var terminal: Maybe[Terminal] = Absent

    // ---- Pre-allocated per-element style resolution ----
    val rs: ResolvedStyle        = new ResolvedStyle
    val measureRs: ResolvedStyle = new ResolvedStyle

    // ---- Layout scratch arrays (owned here, not global) ----
    var layoutIntBuf: Array[Int]    = new Array[Int](512)
    var layoutDblBuf: Array[Double] = new Array[Double](128)

    // ---- Scroll state for overflow:scroll containers (decoupled from FocusRing) ----
    val scrollYMap: java.util.IdentityHashMap[UI.Element, java.lang.Integer] = new java.util.IdentityHashMap()
    private val scrollableElems: Array[UI.Element]                           = new Array[UI.Element](64)
    private var scrollableCount: Int                                         = 0

    def registerScrollable(elem: UI.Element): Unit =
        if scrollableCount < scrollableElems.length then
            scrollableElems(scrollableCount) = elem
            scrollableCount += 1

    def getScrollY(elem: UI.Element): Int =
        val v = scrollYMap.get(elem)
        if v != null then v.intValue else 0

    def setScrollY(elem: UI.Element, v: Int): Unit =
        discard(scrollYMap.put(elem, java.lang.Integer.valueOf(v)))

    def findScrollableAt(mx: Int, my: Int): Maybe[UI.Element] =
        var best: UI.Element = null
        var bestArea         = Int.MaxValue
        var i                = 0
        while i < scrollableCount do
            val packed = getPosition(scrollableElems(i))
            if packed != -1L then
                val x = posX(packed); val y = posY(packed)
                val w = posW(packed); val h = posH(packed)
                if mx >= x && mx < x + w && my >= y && my < y + h then
                    val area = w * h
                    if area < bestArea then
                        best = scrollableElems(i)
                        bestArea = area
                end if
            end if
            i += 1
        end while
        if best != null then Present(best) else Absent
    end findScrollableAt

    // ---- Element position cache (populated during rendering, used by hitTest) ----
    val elementPositions: java.util.IdentityHashMap[UI.Element, Long] = new java.util.IdentityHashMap()

    /** Record an element's bounding box. Packs (x, y, w, h) as shorts into a Long. */
    def recordPosition(elem: UI.Element, x: Int, y: Int, w: Int, h: Int): Unit =
        val packed = (x.toLong & 0xffff) | ((y.toLong & 0xffff) << 16) |
            ((w.toLong & 0xffff) << 32) | ((h.toLong & 0xffff) << 48)
        discard(elementPositions.put(elem, packed))
    end recordPosition

    // ---- Content position cache (content rect inside border+padding, used by mouse click→cursor) ----
    val contentPositions: java.util.IdentityHashMap[UI.Element, Long] = new java.util.IdentityHashMap()

    def recordContentPosition(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int): Unit =
        val packed = (cx.toLong & 0xffff) | ((cy.toLong & 0xffff) << 16) |
            ((cw.toLong & 0xffff) << 32) | ((ch.toLong & 0xffff) << 48)
        discard(contentPositions.put(elem, packed))
    end recordContentPosition

    def getContentPosition(elem: UI.Element): Long =
        if contentPositions.containsKey(elem) then contentPositions.get(elem)
        else -1L

    /** Look up an element's cached position. Returns (x, y, w, h) or null if not cached. */
    def getPosition(elem: UI.Element): Long =
        if elementPositions.containsKey(elem) then elementPositions.get(elem)
        else -1L

    /** Unpack position components from a packed Long. */
    inline def posX(packed: Long): Int = (packed & 0xffff).toShort.toInt
    inline def posY(packed: Long): Int = ((packed >> 16) & 0xffff).toShort.toInt
    inline def posW(packed: Long): Int = ((packed >> 32) & 0xffff).toShort.toInt
    inline def posH(packed: Long): Int = ((packed >> 48) & 0xffff).toShort.toInt

    // ---- Per-frame signal result cache ----
    // Ensures scan, render, layout, and hitTest all see the same element instances
    // for reactive (Signal.map) nodes within a single frame.
    private val signalCache: java.util.IdentityHashMap[Signal[?], Any] = new java.util.IdentityHashMap()

    /** Reset state for a new frame. */
    def beginFrame(): Unit =
        signals.reset()
        overlays.reset()
        identifiers.reset()
        inherited.reset()
        inheritedDepth = 0
        hoverTarget = Absent
        // Note: activeTarget is NOT cleared — it persists across frames for drag/release
        elementPositions.clear()
        contentPositions.clear()
        signalCache.clear()
        scrollableCount = 0 // scrollYMap is NOT cleared — it persists between frames
        hasDropdown = false // Dropdown render data is per-frame; expandedSelect persists
    end beginFrame

    /** Read the current value of a signal, tracking it for change detection. Results are cached per frame so that scan, render, and hitTest
      * all see the same element instances for reactive nodes.
      */
    def resolve[A](signal: Signal[A])(using Frame, AllowUnsafe): A =
        signals.add(signal)
        if signalCache.containsKey(signal) then signalCache.get(signal).asInstanceOf[A]
        else
            val result = Sync.Unsafe.evalOrThrow(signal.current)
            discard(signalCache.put(signal, result))
            result
        end if
    end resolve

end RenderCtx
