package kyo.internal.tui2.pipeline

import kyo.*

/** All persistent state across frames. Owned by the backend session.
  *
  * Reactive state (SignalRef.Unsafe): changes trigger re-render. Frame-local state (var): overwritten each frame, not reactive.
  */
class ScreenState(val theme: ResolvedTheme)(using AllowUnsafe):
    // ---- Reactive: changes trigger re-render ----
    val focusedId: SignalRef.Unsafe[Maybe[WidgetKey]] = SignalRef.Unsafe.init(Absent)
    val hoveredId: SignalRef.Unsafe[Maybe[WidgetKey]] = SignalRef.Unsafe.init(Absent)
    val activeId: SignalRef.Unsafe[Maybe[WidgetKey]]  = SignalRef.Unsafe.init(Absent)

    // ---- Widget state cache (contains SignalRefs, but cache itself is not reactive) ----
    val widgetState: WidgetStateCache = new WidgetStateCache

    // ---- Frame-local: rebuilt or overwritten each frame ----
    var focusableIds: Chunk[WidgetKey]  = Chunk.empty
    var prevLayout: Maybe[LayoutResult] = Absent
    var prevGrid: Maybe[CellGrid]       = Absent
end ScreenState
